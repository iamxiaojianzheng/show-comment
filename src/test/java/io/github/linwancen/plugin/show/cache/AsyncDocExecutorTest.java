package io.github.linwancen.plugin.show.cache;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 验证异步受控调度器 {@link AsyncDocExecutor} 的项目销毁拒绝、有界排队接纳与清理机制。
 */
class AsyncDocExecutorTest {

    private Project createMockProject(boolean isDisposed) {
        return (Project) Proxy.newProxyInstance(
                Project.class.getClassLoader(),
                new Class<?>[]{Project.class},
                (proxy, method, args) -> {
                    if ("isDisposed".equals(method.getName())) {
                        return isDisposed;
                    }
                    return null;
                }
        );
    }

    @Test
    void testDisposedProjectRejection() {
        Project mockProject = createMockProject(true);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        boolean submitted = AsyncDocExecutor.submitCancellableTask(
                mockProject,
                () -> "doc",
                result -> {},
                () -> cancelled.set(true)
        );

        Assertions.assertFalse(submitted, "Disposed project must be rejected immediately");
        Assertions.assertTrue(cancelled.get(), "Disposed project must trigger cleanup");
    }

    @Test
    void testQueueAcceptanceAndPurgeWhenConcurrencySaturated() throws Exception {
        Field dispatcherField = AsyncDocExecutor.class.getDeclaredField("DISPATCHER");
        dispatcherField.setAccessible(true);
        BoundedAsyncDispatcher<?> dispatcher = (BoundedAsyncDispatcher<?>) dispatcherField.get(null);

        Field activeCountField = BoundedAsyncDispatcher.class.getDeclaredField("activeCount");
        activeCountField.setAccessible(true);
        Field maxConcurrentField = BoundedAsyncDispatcher.class.getDeclaredField("maxConcurrent");
        maxConcurrentField.setAccessible(true);

        int maxConcurrent = maxConcurrentField.getInt(dispatcher);
        int oldActive = activeCountField.getInt(dispatcher);
        activeCountField.setInt(dispatcher, maxConcurrent);
        try {
            Project mockProject = createMockProject(false);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            boolean submitted = AsyncDocExecutor.submitCancellableTask(
                    mockProject,
                    () -> "doc",
                    result -> {},
                    () -> cancelled.set(true)
            );

            Assertions.assertTrue(submitted, "Task must be queued rather than dropped when concurrency is saturated");
            Assertions.assertFalse(cancelled.get(), "Queued task must not trigger cleanup prematurely");
            Assertions.assertEquals(1, dispatcher.getQueueSize());

            AsyncDocExecutor.purgeDisposed(mockProject);
            Assertions.assertTrue(cancelled.get(), "Purged task must trigger cleanup callback");
            Assertions.assertEquals(0, dispatcher.getQueueSize());
        } finally {
            activeCountField.setInt(dispatcher, oldActive);
        }
    }
}
