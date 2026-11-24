package io.github.linwancen.plugin.show.cache;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 有界并发 + 有界 FIFO 等待队列的任务调度器。
 * <p>
 * 职责：
 * 1. 严格限制同时处于活跃执行态的任务数量（<= maxConcurrent），避免压垮底层执行器。
 * 2. 超出并发上限的任务不再静默丢弃，而是进入有界队列等待槽位释放后自动续跑。
 * 3. 所有外部回调（runner / onDrop）均在锁外执行，杜绝持锁重入或死锁风险。
 */
final class BoundedAsyncDispatcher<T> {

    private final int maxConcurrent;
    private final int maxQueueSize;
    private final Object lock = new Object();
    private final Deque<T> queue = new ArrayDeque<>();
    private int activeCount;

    private final @NotNull Predicate<T> isExpired;
    private final @NotNull BiConsumer<T, Runnable> runner;
    private final @NotNull Consumer<T> onDrop;

    BoundedAsyncDispatcher(int maxConcurrent,
                           int maxQueueSize,
                           @NotNull Predicate<T> isExpired,
                           @NotNull BiConsumer<T, Runnable> runner,
                           @NotNull Consumer<T> onDrop) {
        if (maxConcurrent <= 0) {
            throw new IllegalArgumentException("maxConcurrent must be positive: " + maxConcurrent);
        }
        if (maxQueueSize < 0) {
            throw new IllegalArgumentException("maxQueueSize must be non-negative: " + maxQueueSize);
        }
        this.maxConcurrent = maxConcurrent;
        this.maxQueueSize = maxQueueSize;
        this.isExpired = isExpired;
        this.runner = runner;
        this.onDrop = onDrop;
    }

    /**
     * 提交任务：若有空闲槽位立即执行，否则进入等待队列；队列满时淘汰最旧任务。
     *
     * @return true 表示任务已进入执行或队列；false 表示任务已过期或队列容量为 0 被拒绝
     */
    boolean submit(@NotNull T task) {
        if (isExpired.test(task)) {
            onDrop.accept(task);
            return false;
        }
        boolean runNow = false;
        @Nullable T evicted = null;
        synchronized (lock) {
            if (activeCount < maxConcurrent) {
                activeCount++;
                runNow = true;
            } else if (maxQueueSize == 0) {
                evicted = task;
            } else {
                if (queue.size() >= maxQueueSize) {
                    evicted = queue.pollFirst();
                }
                queue.addLast(task);
            }
        }
        if (evicted != null) {
            onDrop.accept(evicted);
            if (evicted == task) {
                return false;
            }
        }
        if (runNow) {
            dispatch(task);
        }
        return true;
    }

    /**
     * 清理队列中所有满足条件的任务（例如项目关闭时清理该项目的排队任务）。
     */
    void purge(@NotNull Predicate<T> shouldDrop) {
        @Nullable List<T> dropped = null;
        synchronized (lock) {
            Iterator<T> it = queue.iterator();
            while (it.hasNext()) {
                T task = it.next();
                if (shouldDrop.test(task)) {
                    it.remove();
                    if (dropped == null) {
                        dropped = new ArrayList<>();
                    }
                    dropped.add(task);
                }
            }
        }
        if (dropped != null) {
            for (T task : dropped) {
                onDrop.accept(task);
            }
        }
    }

    int getActiveCount() {
        synchronized (lock) {
            return activeCount;
        }
    }

    int getQueueSize() {
        synchronized (lock) {
            return queue.size();
        }
    }

    private void dispatch(@NotNull T initialTask) {
        T current = initialTask;
        while (current != null) {
            if (isExpired.test(current)) {
                onDrop.accept(current);
                current = takeNextOrRelease();
                continue;
            }
            AtomicBoolean released = new AtomicBoolean(false);
            Runnable releaseCallback = () -> {
                if (released.compareAndSet(false, true)) {
                    T next = takeNextOrRelease();
                    if (next != null) {
                        dispatch(next);
                    }
                }
            };
            try {
                runner.accept(current, releaseCallback);
                return;
            } catch (Throwable ignored) {
                // runner 自身同步失败时，保证清理当前任务并释放槽位续跑下一个
                onDrop.accept(current);
                if (released.compareAndSet(false, true)) {
                    current = takeNextOrRelease();
                } else {
                    return;
                }
            }
        }
    }

    private @Nullable T takeNextOrRelease() {
        @Nullable List<T> expired = null;
        @Nullable T next = null;
        synchronized (lock) {
            while (!queue.isEmpty()) {
                T candidate = queue.pollFirst();
                if (isExpired.test(candidate)) {
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    expired.add(candidate);
                } else {
                    next = candidate;
                    break;
                }
            }
            if (next == null) {
                activeCount = Math.max(0, activeCount - 1);
            }
        }
        if (expired != null) {
            for (T task : expired) {
                onDrop.accept(task);
            }
        }
        return next;
    }
}
