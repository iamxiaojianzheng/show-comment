package io.github.linwancen.plugin.show.ext.listener;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import io.github.linwancen.plugin.show.cache.RenderDocCache;
import io.github.linwancen.plugin.show.cache.TreeRefreshScheduler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 项目级监听器：负责在项目打开进入 Smart 模式或退出 Dumb 模式时，
 * 触发外部注释加载（{@link FileLoader#loadAll}）与树视图渲染缓存自愈。
 */
public class FileLoadInitListener implements DumbService.DumbModeListener, ProjectManagerListener {

    private static final Logger LOG = LoggerFactory.getLogger(FileLoadInitListener.class);
    private static final Map<Project, Boolean> PROJECT_LOAD_MAP = new ConcurrentHashMap<>();

    private final @NotNull Project project;

    public FileLoadInitListener(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void projectOpened(@NotNull Project openedProject) {
        if (openedProject != this.project || project.isDisposed()) {
            return;
        }
        try {
            // 兜底：若项目打开时索引已就绪（未经历 DumbMode 切换），确保冷启动也会加载外部注释并自愈刷新树视图
            DumbService.getInstance(project).runWhenSmart(() -> onSmartReady(project));
        } catch (Throwable e) {
            LOG.info("FileLoadInitListener projectOpened catch Throwable but log to record.", e);
        }
    }

    @Override
    public void exitDumbMode() {
        try {
            onSmartReady(project);
        } catch (Throwable e) {
            LOG.info("FileLoadInitListener exitDumbMode catch Throwable but log to record.", e);
        }
    }

    public static void onSmartReady(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        @Nullable RenderDocCache renderCache = project.getService(RenderDocCache.class);
        if (renderCache != null) {
            renderCache.onExitDumbMode(project);
        }
        PROJECT_LOAD_MAP.computeIfAbsent(project, k -> {
            FileLoader.loadAll(project);
            return Boolean.TRUE;
        });
    }

    @Override
    public void projectClosed(@NotNull Project closedProject) {
        if (closedProject != this.project) {
            return;
        }
        PROJECT_LOAD_MAP.remove(closedProject);
        TreeRefreshScheduler.clear(closedProject);
    }
}
