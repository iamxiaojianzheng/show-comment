package io.github.linwancen.plugin.show.cache;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ProjectView 树视图防抖合并 + 尾随刷新调度器。
 * <p>
 * 将突发的多节点异步完成事件合并为单次 {@link ProjectView#refresh()}，
 * 并在防抖延迟窗口内若收到后续刷新请求时追加一次尾随刷新，避免边界漏刷。
 */
public final class TreeRefreshScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(TreeRefreshScheduler.class);

    private static final long REFRESH_DELAY_MS = 200L;

    /**
     * 已排队的项目集合（防抖窗口内只调度一次定时任务）。
     */
    private static final Set<Project> REFRESH_QUEUED = ConcurrentHashMap.newKeySet();

    /**
     * 在防抖延迟期间收到新刷新请求的项目集合（本轮刷新后追加一次尾随刷新）。
     */
    private static final Set<Project> REFRESH_AGAIN = ConcurrentHashMap.newKeySet();

    private TreeRefreshScheduler() {}

    public static void scheduleRefresh(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        if (!REFRESH_QUEUED.add(project)) {
            REFRESH_AGAIN.add(project);
            return;
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(() ->
                ApplicationManager.getApplication().invokeLater(() -> {
                    boolean again = REFRESH_AGAIN.remove(project);
                    REFRESH_QUEUED.remove(project);
                    if (project.isDisposed()) {
                        return;
                    }
                    try {
                        ProjectView.getInstance(project).refresh();
                    } catch (Throwable e) {
                        LOG.info("TreeRefreshScheduler refresh catch Throwable but log to record.", e);
                    }
                    if (again && !project.isDisposed()) {
                        scheduleRefresh(project);
                    }
                }), REFRESH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 项目关闭时清理状态引用，避免残留。
     */
    public static void clear(@NotNull Project project) {
        REFRESH_QUEUED.remove(project);
        REFRESH_AGAIN.remove(project);
    }
}
