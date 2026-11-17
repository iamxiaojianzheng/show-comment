package io.github.linwancen.plugin.show.cache;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.CancellablePromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * 集中管理插件后台文档/注释计算任务的协作式非阻塞执行器。
 * <p>
 * 契约：
 * 1. 协作式中断：改用 {@link ReadAction#nonBlocking(Callable)}，当 EDT 请求写锁或写意图锁
 *    （Write Intent Action）时，底层任务自动让出读锁并协作式中断，彻底根除 EDT 锁饥饿（Freeze）。
 * 2. 有界并发屏障：通过受控信号量限制同时活跃的 nonBlocking 任务数量，杜绝平台
 *    "Too many non blocking read actions submitted at once" 警告。
 * 3. 智能模式与生命周期对齐：任务自动绑定在 smart 模式运行，在项目销毁或应用退出时自动失效。
 */
public final class AsyncDocExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncDocExecutor.class);

    /**
     * 允许同时处于执行态的非阻塞读任务并发上限，严格控制在平台阈值之下。
     */
    private static final int MAX_CONCURRENT_TASKS = 8;
    private static final Semaphore SEMAPHORE = new Semaphore(MAX_CONCURRENT_TASKS, true);

    private AsyncDocExecutor() {}

    /**
     * 提交协作式可取消的文档计算任务。
     *
     * @param project         关联的项目上下文
     * @param compute         在后台非阻塞读锁中执行的计算逻辑
     * @param onResultOnEdt   计算成功后在 EDT 回调消费结果
     * @param onCancelOrError 任务由于限流未提交、执行中被取消或异常中断时的清理回调
     * @param <T>             计算结果类型
     * @return true 表示成功提交，false 表示门禁满载未提交并触发了清理
     */
    public static <T> boolean submitCancellableTask(
            @NotNull Project project,
            @NotNull Callable<T> compute,
            @NotNull Consumer<T> onResultOnEdt,
            @NotNull Runnable onCancelOrError) {

        if (project.isDisposed()) {
            onCancelOrError.run();
            return false;
        }

        if (!SEMAPHORE.tryAcquire()) {
            // 门禁满载：快速放弃本次调度，依赖下一次重绘或防抖窗口自愈，避免阻塞线程池与触发平台告警
            onCancelOrError.run();
            return false;
        }

        boolean submitted = false;
        try {
            CancellablePromise<T> promise = ReadAction.nonBlocking(compute)
                    .inSmartMode(project)
                    .expireWhen(project::isDisposed)
                    .finishOnUiThread(ModalityState.any(), onResultOnEdt::accept)
                    .submit(AppExecutorUtil.getAppExecutorService());

            submitted = true;

            promise.onError(e -> {
                // 任务由于写操作打断（ProcessCanceledException）或其他异常结束，触发状态清理以便后续重试
                onCancelOrError.run();
            });

            promise.onProcessed(ignored -> {
                SEMAPHORE.release();
            });
            return true;
        } catch (Throwable e) {
            LOG.info("AsyncDocExecutor submit failed, cleaning up.", e);
            onCancelOrError.run();
            return false;
        } finally {
            if (!submitted) {
                SEMAPHORE.release();
            }
        }
    }
}
