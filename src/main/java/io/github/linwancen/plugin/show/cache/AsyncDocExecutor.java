package io.github.linwancen.plugin.show.cache;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.CancellablePromise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 集中管理插件后台文档/注释计算任务的协作式非阻塞执行器。
 * <p>
 * 契约：
 * 1. 协作式中断：使用 {@link ReadAction#nonBlocking(Callable)}，当 EDT 请求写锁或写意图锁
 *    （Write Intent Action）时，底层任务自动让出读锁并协作式中断，杜绝 EDT 锁饥饿（Freeze）。
 * 2. 有界并发 + 有界排队：同时向平台提交的 nonBlocking 任务严格不超过 {@link #MAX_CONCURRENT_TASKS}，
 *    杜绝平台 "Too many non blocking read actions submitted at once" 警告；超出并发上限的任务进入
 *    有界等待队列（{@link #MAX_QUEUED_TASKS}），槽位释放后自动续跑，彻底解决启动期大批量树节点被静默丢弃的问题。
 * 3. 智能模式与生命周期对齐：任务自动绑定在 smart 模式运行，在项目销毁时自动失效并清理排队任务。
 */
public final class AsyncDocExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncDocExecutor.class);

    /**
     * 允许同时处于平台执行态的非阻塞读任务并发上限，严格控制在平台阈值之下。
     */
    private static final int MAX_CONCURRENT_TASKS = 8;

    /**
     * 有界等待队列上限：足以容纳启动期展开的大量树节点与可见行，同时防止内存无界增长。
     */
    private static final int MAX_QUEUED_TASKS = 1024;

    private static final class QueuedTask<T> {
        final @NotNull Project project;
        final @NotNull Callable<T> compute;
        final @NotNull Consumer<T> onResultOnEdt;
        final @NotNull Runnable onCancelOrError;

        QueuedTask(@NotNull Project project,
                   @NotNull Callable<T> compute,
                   @NotNull Consumer<T> onResultOnEdt,
                   @NotNull Runnable onCancelOrError) {
            this.project = project;
            this.compute = compute;
            this.onResultOnEdt = onResultOnEdt;
            this.onCancelOrError = onCancelOrError;
        }
    }

    private static final BoundedAsyncDispatcher<QueuedTask<?>> DISPATCHER = new BoundedAsyncDispatcher<>(
            MAX_CONCURRENT_TASKS,
            MAX_QUEUED_TASKS,
            task -> task.project.isDisposed(),
            AsyncDocExecutor::executeTask,
            task -> runQuietly(task.onCancelOrError)
    );

    private AsyncDocExecutor() {}

    /**
     * 提交协作式可取消的文档计算任务。
     *
     * @param project         关联的项目上下文
     * @param compute         在后台非阻塞读锁中执行的计算逻辑
     * @param onResultOnEdt   计算成功后在 EDT 回调消费结果
     * @param onCancelOrError 任务被淘汰、取消或异常中断时的清理回调（保证最多执行一次）
     * @param <T>             计算结果类型
     * @return true 表示任务已受理（执行或排队），false 表示项目已销毁或被拒绝
     */
    public static <T> boolean submitCancellableTask(
            @NotNull Project project,
            @NotNull Callable<T> compute,
            @NotNull Consumer<T> onResultOnEdt,
            @NotNull Runnable onCancelOrError) {
        if (project.isDisposed()) {
            runQuietly(onCancelOrError);
            return false;
        }
        return DISPATCHER.submit(new QueuedTask<>(project, compute, onResultOnEdt, onCancelOrError));
    }

    /**
     * 项目关闭时清理仍在等待队列中的该项目任务，防止持有已关闭项目的引用。
     */
    public static void purgeDisposed(@NotNull Project project) {
        DISPATCHER.purge(task -> task.project == project || task.project.isDisposed());
    }

    @SuppressWarnings("unchecked")
    private static <T> void executeTask(@NotNull QueuedTask<?> rawTask, @NotNull Runnable releaseSlot) {
        QueuedTask<T> task = (QueuedTask<T>) rawTask;
        if (task.project.isDisposed()) {
            runQuietly(task.onCancelOrError);
            releaseSlot.run();
            return;
        }

        AtomicBoolean settled = new AtomicBoolean(false);
        boolean submitted = false;
        try {
            CancellablePromise<T> promise = ReadAction.nonBlocking(task.compute)
                    .inSmartMode(task.project)
                    .expireWhen(task.project::isDisposed)
                    .finishOnUiThread(ModalityState.any(), result -> {
                        if (settled.compareAndSet(false, true)) {
                            task.onResultOnEdt.accept(result);
                        }
                    })
                    .submit(AppExecutorUtil.getAppExecutorService());

            submitted = true;

            promise.onError(e -> {
                if (settled.compareAndSet(false, true)) {
                    runQuietly(task.onCancelOrError);
                }
            });

            promise.onProcessed(ignored -> {
                // 部分 IDEA 版本中 promise 被 cancel/expire 时可能不回调 onError，此处兜底清理 pending
                if (promise.isCancelled() && settled.compareAndSet(false, true)) {
                    runQuietly(task.onCancelOrError);
                }
                releaseSlot.run();
            });
        } catch (Throwable e) {
            LOG.info("AsyncDocExecutor submit failed, cleaning up.", e);
            if (settled.compareAndSet(false, true)) {
                runQuietly(task.onCancelOrError);
            }
        } finally {
            if (!submitted) {
                releaseSlot.run();
            }
        }
    }

    private static void runQuietly(@NotNull Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            LOG.info("AsyncDocExecutor callback catch Throwable but log to record.", e);
        }
    }
}
