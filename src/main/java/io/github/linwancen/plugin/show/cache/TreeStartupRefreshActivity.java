package io.github.linwancen.plugin.show.cache;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import io.github.linwancen.plugin.show.ext.listener.FileLoadInitListener;
import io.github.linwancen.plugin.show.lang.base.BaseLangDoc;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 项目冷启动后置活动：确保所有语言处理器在项目就绪时完成预加载，
 * 并在进入 SmartMode 后主动执行一次外部注释加载与树视图自愈刷新，
 * 弥补启动期无 DumbMode 切换或 ProjectView 异步恢复展开早于语言加载的时序竞态。
 */
public class TreeStartupRefreshActivity implements StartupActivity.DumbAware {

    private static final Logger LOG = LoggerFactory.getLogger(TreeStartupRefreshActivity.class);

    @Override
    public void runActivity(@NotNull Project project) {
        if (project.isDisposed()) {
            return;
        }
        try {
            BaseLangDoc.ensureLoaded();
            DumbService.getInstance(project).runWhenSmart(() -> FileLoadInitListener.onSmartReady(project));
        } catch (Throwable e) {
            LOG.info("TreeStartupRefreshActivity catch Throwable but log to record.", e);
        }
    }
}
