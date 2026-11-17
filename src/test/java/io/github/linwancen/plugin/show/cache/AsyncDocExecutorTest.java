package io.github.linwancen.plugin.show.cache;

import com.intellij.openapi.project.Project;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 验证异步受控调度器 {@link AsyncDocExecutor} 的并发门禁、取消清理与状态隔离机制。
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
    void testSemaphoreBoundaryThrottle() throws Exception {
        Field semaphoreField = AsyncDocExecutor.class.getDeclaredField("SEMAPHORE");
        semaphoreField.setAccessible(true);
        Semaphore semaphore = (Semaphore) semaphoreField.get(null);

        // 占满全部许可
        int permits = semaphore.availablePermits();
        semaphore.acquire(permits);
        try {
            Project mockProject = createMockProject(false);

            AtomicBoolean cancelled = new AtomicBoolean(false);
            boolean submitted = AsyncDocExecutor.submitCancellableTask(
                    mockProject,
                    () -> "doc",
                    result -> {},
                    () -> cancelled.set(true)
            );

            Assertions.assertFalse(submitted, "Task must be rejected when semaphore is saturated");
            Assertions.assertTrue(cancelled.get(), "Saturated semaphore must trigger cleanup callback");
        } finally {
            semaphore.release(permits);
        }

        Assertions.assertEquals(permits, semaphore.availablePermits(), "Permits must be fully restored");
    }
}
