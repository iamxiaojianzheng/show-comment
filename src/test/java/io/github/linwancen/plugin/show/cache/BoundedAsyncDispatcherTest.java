package io.github.linwancen.plugin.show.cache;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证 {@link BoundedAsyncDispatcher} 的有界并发、FIFO 排队、溢出淘汰、清理及异常自愈契约。
 */
class BoundedAsyncDispatcherTest {

    @Test
    void testBoundedConcurrencyCompletesAllBurstTasks() throws Exception {
        int totalTasks = 50;
        int maxConcurrent = 8;
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrent);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger dropped = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(totalTasks);

        BoundedAsyncDispatcher<Integer> dispatcher = new BoundedAsyncDispatcher<>(
                maxConcurrent,
                1024,
                task -> false,
                (task, release) -> pool.submit(() -> {
                    int current = inFlight.incrementAndGet();
                    peakInFlight.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(5L);
                        completed.incrementAndGet();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inFlight.decrementAndGet();
                        latch.countDown();
                        release.run();
                        // 重复调用 release 必须是幂等的，不能导致 activeCount 错乱
                        release.run();
                    }
                }),
                task -> dropped.incrementAndGet()
        );

        for (int i = 0; i < totalTasks; i++) {
            Assertions.assertTrue(dispatcher.submit(i));
        }

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS), "所有 50 个任务应在超时前全部执行完成");
        pool.shutdown();
        Assertions.assertEquals(totalTasks, completed.get());
        Assertions.assertEquals(0, dropped.get());
        Assertions.assertTrue(peakInFlight.get() <= maxConcurrent,
                "峰值并发不应超过上限 " + maxConcurrent + "，实际为 " + peakInFlight.get());
        Assertions.assertEquals(0, dispatcher.getActiveCount());
        Assertions.assertEquals(0, dispatcher.getQueueSize());
    }

    @Test
    void testQueueOverflowEvictsOldestAndRunsNewest() {
        List<Runnable> releases = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        BoundedAsyncDispatcher<String> dispatcher = new BoundedAsyncDispatcher<>(
                1,
                2,
                task -> false,
                (task, release) -> {
                    executed.add(task);
                    releases.add(release);
                },
                dropped::add
        );

        dispatcher.submit("t1"); // 立即执行
        dispatcher.submit("t2"); // 队列 [t2]
        dispatcher.submit("t3"); // 队列 [t2, t3]
        dispatcher.submit("t4"); // 淘汰最旧 t2，队列 [t3, t4]

        Assertions.assertEquals(Collections.singletonList("t2"), dropped);
        Assertions.assertEquals(2, dispatcher.getQueueSize());

        // 依次释放所有槽位
        for (int i = 0; i < releases.size(); i++) {
            releases.get(i).run();
        }
        Assertions.assertEquals(List.of("t1", "t3", "t4"), executed);
        Assertions.assertEquals(0, dispatcher.getActiveCount());
        Assertions.assertEquals(0, dispatcher.getQueueSize());
    }

    @Test
    void testPurgeAndRunnerFailureDoNotLeakSlots() {
        List<Runnable> releases = new ArrayList<>();
        List<String> executed = new ArrayList<>();
        List<String> dropped = new ArrayList<>();

        BoundedAsyncDispatcher<String> dispatcher = new BoundedAsyncDispatcher<>(
                1,
                10,
                task -> false,
                (task, release) -> {
                    if ("boom".equals(task)) {
                        throw new IllegalStateException("simulated submit error");
                    }
                    executed.add(task);
                    releases.add(release);
                },
                dropped::add
        );

        dispatcher.submit("t1");
        dispatcher.submit("drop-me");
        dispatcher.submit("boom");
        dispatcher.submit("t2");

        dispatcher.purge(s -> s.startsWith("drop-"));
        Assertions.assertEquals(Collections.singletonList("drop-me"), dropped);

        // 释放 t1 -> 自动触发 boom（同步异常被捕获并回调 onDrop）-> 自动续跑 t2
        releases.get(0).run();
        Assertions.assertEquals(List.of("drop-me", "boom"), dropped);
        Assertions.assertEquals(List.of("t1", "t2"), executed);

        releases.get(1).run();
        Assertions.assertEquals(0, dispatcher.getActiveCount());
        Assertions.assertEquals(0, dispatcher.getQueueSize());
    }
}
