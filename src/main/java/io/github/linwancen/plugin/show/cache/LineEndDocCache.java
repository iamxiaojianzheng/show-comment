package io.github.linwancen.plugin.show.cache;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.LineExtensionInfo;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.linwancen.plugin.show.LineEnd;
import io.github.linwancen.plugin.show.bean.LineInfo;
import io.github.linwancen.plugin.show.settings.AppSettingsState;
import io.github.linwancen.plugin.show.settings.GlobalSettingsState;
import io.github.linwancen.plugin.show.settings.ProjectSettingsState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Doc cache for the line-end painter when the lineEndCache option is off.
 * <p>
 * 架构契约：
 * 1. EDT 零锁/零分配快路径：采用按 {@link VirtualFile} 分桶的两级缓存（file -> lineNumber -> Entry），
 *    在文件修改戳（融合 Document 内存修改戳与 VirtualFile 磁盘修改戳）和配置版本号未变化时，
 *    直接从内存返回缓存结果，无需构造 {@link LineInfo} 或切片行文本。
 * 2. 协作式后台计算：未命中或过期时通过 {@link AsyncDocExecutor} 提交可取消的后台 SmartMode 任务，
 *    完成后经防抖合并 + 尾随刷新机制重绘编辑器。
 * 3. 精准内存回收：文件关闭时通过 {@link #evictFile(Project, VirtualFile)} O(1) 释放该文件的所有行缓存。
 */
public final class LineEndDocCache implements Disposable {

    private static final Logger LOG = LoggerFactory.getLogger(LineEndDocCache.class);

    private static final AtomicBoolean SERVICE_MISSING_LOGGED = new AtomicBoolean();

    private static final long PENDING_TIMEOUT_MS = 30_000L;
    private static final long SWEEP_INTERVAL_MS = 60_000L;
    private static final long ENTRY_HARD_TTL_MS = 30 * 60_000L;
    private static final long PENDING_HARD_TTL_MS = 60_000L;
    private static final long NOT_READY_RETRY_DELAY_MS = 300L;
    private static final long REPAINT_DELAY_MS = 50L;

    private static final class Entry {
        @Nullable
        final LineExtensionInfo ext;
        @Nullable
        final Collection<LineExtensionInfo> extList;
        final boolean notReady;
        final boolean retried;
        final long time;
        final long fileModStamp;
        final long settingsModCount;
        volatile long lastAccess;

        Entry(@Nullable LineExtensionInfo ext, boolean notReady, boolean retried, long time,
              long fileModStamp, long settingsModCount) {
            this.ext = ext;
            this.extList = ext == null ? null : Collections.singleton(ext);
            this.notReady = notReady;
            this.retried = retried;
            this.time = time;
            this.fileModStamp = fileModStamp;
            this.settingsModCount = settingsModCount;
            this.lastAccess = time;
        }
    }

    private final @NotNull Project project;
    private final @NotNull AppSettingsState appSettings;
    private final @NotNull GlobalSettingsState globalSettings;
    private final @NotNull ProjectSettingsState projectSettings;

    /**
     * 两级缓存：file -> (lineNumber -> Entry)，支持零字符串拼接命中与文件关闭 O(1) 精准释放。
     */
    private final Map<VirtualFile, Map<Integer, Entry>> docs = new ConcurrentHashMap<>();
    /**
     * file -> (lineNumber -> compute start time)，防止同一行重复并发调度。
     */
    private final Map<VirtualFile, Map<Integer, Long>> pending = new ConcurrentHashMap<>();
    private final Set<VirtualFile> repaintQueued = ConcurrentHashMap.newKeySet();
    private final Set<VirtualFile> repaintAgain = ConcurrentHashMap.newKeySet();
    private long nextSweep;

    public LineEndDocCache(@NotNull Project project) {
        this.project = project;
        this.appSettings = AppSettingsState.getInstance();
        this.globalSettings = GlobalSettingsState.getInstance();
        this.projectSettings = ProjectSettingsState.getInstance(project);
    }

    /**
     * EDT 零分配快速入口：先基于 file + lineNumber + 双修改戳查内存缓存，
     * 仅在未命中或过期时才按需构造 {@link LineInfo} 并调度后台计算。
     */
    public static @Nullable Collection<LineExtensionInfo> getLineExtensions(
            @NotNull Project project, @NotNull VirtualFile file, int lineNumber) {
        if (project.isDisposed() || !file.isValid()) {
            return null;
        }
        @Nullable LineEndDocCache cache = getService(project);
        if (cache == null) {
            return null;
        }
        return cache.renderFast(file, lineNumber);
    }

    /**
     * 兼容保留入口（已持有 LineInfo 的调用方）。
     */
    public static @Nullable Collection<LineExtensionInfo> getLineExtensions(@NotNull LineInfo info) {
        @NotNull Project project = info.project;
        if (project.isDisposed() || !info.file.isValid()) {
            return null;
        }
        @Nullable LineEndDocCache cache = getService(project);
        if (cache == null) {
            return null;
        }
        return cache.render(info);
    }

    /**
     * 文件关闭时释放该文件对应的行末缓存与待办标记。
     */
    public static void evictFile(@NotNull Project project, @NotNull VirtualFile file) {
        if (project.isDisposed()) {
            return;
        }
        @Nullable LineEndDocCache cache = project.getService(LineEndDocCache.class);
        if (cache != null) {
            cache.docs.remove(file);
            cache.pending.remove(file);
            cache.repaintQueued.remove(file);
            cache.repaintAgain.remove(file);
        }
    }

    private static @Nullable LineEndDocCache getService(@NotNull Project project) {
        @Nullable LineEndDocCache cache = project.getService(LineEndDocCache.class);
        if (cache == null) {
            if (SERVICE_MISSING_LOGGED.compareAndSet(false, true)) {
                LOG.warn("LineEndDocCache is not registered as a projectService in plugin.xml.");
            }
        }
        return cache;
    }

    private @Nullable Collection<LineExtensionInfo> renderFast(@NotNull VirtualFile file, int lineNumber) {
        long now = System.currentTimeMillis();
        sweep(now);
        long currentFileStamp = CacheStampResolver.fileStamp(file);
        long currentSettingsStamp = currentSettingsModCount();

        @Nullable Map<Integer, Entry> lineMap = docs.get(file);
        @Nullable Entry entry = lineMap == null ? null : lineMap.get(lineNumber);
        if (entry != null) {
            entry.lastAccess = now;
            if (!entry.notReady
                    && entry.fileModStamp == currentFileStamp
                    && entry.settingsModCount == currentSettingsStamp) {
                return entry.extList;
            }
        }

        // 未命中或已过期：按需构造 LineInfo 进行后台计算
        @Nullable LineInfo info = LineInfo.of(file, project, lineNumber);
        if (info == null) {
            return null;
        }
        schedule(info, currentFileStamp, currentSettingsStamp, now);
        // 刷新期间保留旧值避免闪烁（仅当未换文件且行存在旧值时）
        return entry == null ? null : entry.extList;
    }

    private @Nullable Collection<LineExtensionInfo> render(@NotNull LineInfo info) {
        long now = System.currentTimeMillis();
        sweep(now);
        long currentFileStamp = CacheStampResolver.fileStamp(info.file);
        long currentSettingsStamp = currentSettingsModCount();

        @Nullable Map<Integer, Entry> lineMap = docs.get(info.file);
        @Nullable Entry entry = lineMap == null ? null : lineMap.get(info.lineNumber);
        if (entry != null) {
            entry.lastAccess = now;
            if (!entry.notReady
                    && entry.fileModStamp == currentFileStamp
                    && entry.settingsModCount == currentSettingsStamp) {
                return entry.extList;
            }
        }
        schedule(info, currentFileStamp, currentSettingsStamp, now);
        return entry == null ? null : entry.extList;
    }

    private long currentSettingsModCount() {
        return appSettings.getModificationCount()
                + globalSettings.getModificationCount()
                + projectSettings.getModificationCount();
    }

    private static final class LineComputeResult {
        @Nullable
        final LineExtensionInfo result;
        final boolean notReady;
        final long fileModStamp;
        final long settingsModCount;

        LineComputeResult(@Nullable LineExtensionInfo result, boolean notReady,
                          long fileModStamp, long settingsModCount) {
            this.result = result;
            this.notReady = notReady;
            this.fileModStamp = fileModStamp;
            this.settingsModCount = settingsModCount;
        }
    }

    private void schedule(@NotNull LineInfo info, long fileModStamp, long settingsModCount, long now) {
        @NotNull Map<Integer, Long> filePending = pending.computeIfAbsent(info.file, k -> new ConcurrentHashMap<>());
        @Nullable Long since = filePending.get(info.lineNumber);
        if (since != null && now - since < PENDING_TIMEOUT_MS) {
            return;
        }
        filePending.put(info.lineNumber, now);
        AsyncDocExecutor.submitCancellableTask(
                info.project,
                () -> doCompute(info, fileModStamp, settingsModCount),
                res -> finish(info.project, info.file, info.lineNumber, res.result, res.notReady,
                        res.fileModStamp, res.settingsModCount, now),
                () -> clearPending(info.file, info.lineNumber, now)
        );
    }

    private void clearPending(@NotNull VirtualFile file, int lineNumber, long token) {
        @Nullable Map<Integer, Long> filePending = pending.get(file);
        if (filePending != null) {
            filePending.remove(lineNumber, token);
        }
    }

    private static @NotNull LineComputeResult doCompute(@NotNull LineInfo info,
                                                        long expectedFileStamp,
                                                        long expectedSettingsStamp) {
        PsiDocCache.consumeNotReadyFlag();
        long fileModStamp = CacheStampResolver.fileStamp(info.file);
        @Nullable LineExtensionInfo result = computeSafe(info);
        boolean notReady = PsiDocCache.consumeNotReadyFlag();
        // 使用最新读取的修改戳，确保与渲染侧 CacheStampResolver.fileStamp 口径完全一致
        long effectiveStamp = Math.max(expectedFileStamp, fileModStamp);
        return new LineComputeResult(result, notReady, effectiveStamp, expectedSettingsStamp);
    }

    private static @Nullable LineExtensionInfo computeSafe(@NotNull LineInfo info) {
        @NotNull Project project = info.project;
        if (project.isDisposed() || !info.file.isValid() || DumbService.isDumb(project)) {
            return null;
        }
        try {
            return LineEnd.lineExt(info);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (Throwable e) {
            LOG.info("LineEndDocCache lineExt catch Throwable but log to record.", e);
            return null;
        }
    }

    private void finish(@NotNull Project project, @NotNull VirtualFile file, int lineNumber,
                        @Nullable LineExtensionInfo result, boolean notReady,
                        long fileModStamp, long settingsModCount, long token) {
        boolean retry;
        boolean changed;
        @NotNull Map<Integer, Entry> lineMap = docs.computeIfAbsent(file, k -> new ConcurrentHashMap<>());
        synchronized (lineMap) {
            @Nullable Entry old = lineMap.get(lineNumber);
            @Nullable LineExtensionInfo oldExt = old == null ? null : old.ext;
            @Nullable LineExtensionInfo effectiveExt = (notReady && oldExt != null) ? oldExt : result;
            changed = !sameExt(oldExt, effectiveExt);
            if (!changed && oldExt != null) {
                effectiveExt = oldExt;
            }
            boolean retriedBefore = old != null && old.retried;
            retry = notReady && !retriedBefore;
            lineMap.put(lineNumber, new Entry(effectiveExt, notReady, retry || (notReady && retriedBefore),
                    System.currentTimeMillis(), fileModStamp, settingsModCount));
        }
        clearPending(file, lineNumber, token);
        if (project.isDisposed()) {
            return;
        }
        if (retry) {
            scheduleRepaint(project, file, NOT_READY_RETRY_DELAY_MS);
            return;
        }
        if (!changed) {
            return;
        }
        scheduleRepaint(project, file, REPAINT_DELAY_MS);
    }

    private static boolean sameExt(@Nullable LineExtensionInfo a, @Nullable LineExtensionInfo b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(a.getText(), b.getText())
                && Objects.equals(a.getColor(), b.getColor())
                && Objects.equals(a.getBgColor(), b.getBgColor())
                && Objects.equals(a.getEffectType(), b.getEffectType())
                && Objects.equals(a.getEffectColor(), b.getEffectColor())
                && a.getFontType() == b.getFontType();
    }

    private void scheduleRepaint(@NotNull Project project, @NotNull VirtualFile file, long delayMs) {
        if (!repaintQueued.add(file)) {
            repaintAgain.add(file);
            return;
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    boolean again = repaintAgain.remove(file);
                    repaintQueued.remove(file);
                    if (!project.isDisposed()) {
                        repaintEditors(project, file);
                    }
                    if (again && !project.isDisposed()) {
                        scheduleRepaint(project, file, delayMs);
                    }
                }), delayMs, TimeUnit.MILLISECONDS);
    }

    private static void repaintEditors(@NotNull Project project, @NotNull VirtualFile file) {
        try {
            // 零锁与零 I/O 契约：仅当文档已在内存中缓存时才重绘对应编辑器，严禁在 EDT 上同步从磁盘加载 Document
            @Nullable Document document = FileDocumentManager.getInstance().getCachedDocument(file);
            if (document == null) {
                return;
            }
            for (Editor editor : EditorFactory.getInstance().getEditors(document, project)) {
                editor.getContentComponent().repaint();
            }
        } catch (Throwable e) {
            LOG.info("LineEndDocCache repaintEditors catch Throwable but log to record.", e);
        }
    }

    private void sweep(long now) {
        if (now < nextSweep) {
            return;
        }
        nextSweep = now + SWEEP_INTERVAL_MS;
        Iterator<Map.Entry<VirtualFile, Map<Integer, Entry>>> docIt = docs.entrySet().iterator();
        while (docIt.hasNext()) {
            Map.Entry<VirtualFile, Map<Integer, Entry>> fileEntry = docIt.next();
            VirtualFile file = fileEntry.getKey();
            Map<Integer, Entry> lineMap = fileEntry.getValue();
            if (!file.isValid()) {
                docIt.remove();
                continue;
            }
            lineMap.entrySet().removeIf(e -> now - e.getValue().lastAccess > ENTRY_HARD_TTL_MS);
            if (lineMap.isEmpty()) {
                docIt.remove();
            }
        }
        Iterator<Map.Entry<VirtualFile, Map<Integer, Long>>> pendingIt = pending.entrySet().iterator();
        while (pendingIt.hasNext()) {
            Map.Entry<VirtualFile, Map<Integer, Long>> fileEntry = pendingIt.next();
            Map<Integer, Long> linePending = fileEntry.getValue();
            linePending.entrySet().removeIf(e -> now - e.getValue() > PENDING_HARD_TTL_MS);
            if (linePending.isEmpty()) {
                pendingIt.remove();
            }
        }
    }

    @Override
    public void dispose() {
        AsyncDocExecutor.purgeDisposed(project);
        docs.clear();
        pending.clear();
        repaintQueued.clear();
        repaintAgain.clear();
    }
}
