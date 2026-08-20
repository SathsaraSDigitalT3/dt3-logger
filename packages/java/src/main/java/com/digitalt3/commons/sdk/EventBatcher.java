package com.digitalt3.commons.sdk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Thread-safe buffer for finalized canonical events.
 *
 * <p>The batcher delegates delivery to the logger's existing single-event
 * transport pipeline so batching does not alter the transport payload contract.</p>
 */
final class EventBatcher implements AutoCloseable {
    private final Consumer<Map<String, Object>> deliver;
    private final int maxSize;
    private final long flushIntervalMs;
    private final ScheduledExecutorService scheduler;
    private final List<Map<String, Object>> events = new ArrayList<>();

    private ScheduledFuture<?> scheduledFlush;
    private boolean closed;

    /**
     * Create a final-event batching buffer.
     *
     * @param deliver synchronous final-event delivery callback
     * @param maxSize positive count that causes an immediate flush
     * @param flushIntervalMs positive maximum pending interval in milliseconds
     */
    EventBatcher(
        Consumer<Map<String, Object>> deliver,
        int maxSize,
        long flushIntervalMs
    ) {
        this.deliver = Objects.requireNonNull(deliver, "deliver must not be null");
        if (maxSize <= 0) {
            throw new IllegalArgumentException("batching.max_size must be a positive integer");
        }
        if (flushIntervalMs <= 0) {
            throw new IllegalArgumentException(
                "batching.flush_interval_ms must be a positive integer in milliseconds"
            );
        }

        this.maxSize = maxSize;
        this.flushIntervalMs = flushIntervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new BatcherThreadFactory());
    }

    /**
     * Buffer a final event and synchronously flush at the configured capacity.
     *
     * @param event final masked and validation-processed canonical event
     */
    synchronized void add(Map<String, Object> event) {
        if (closed) {
            throw new IllegalStateException("Batcher is closed");
        }

        events.add(new java.util.LinkedHashMap<>(event));
        scheduleFlushLocked();
        if (events.size() >= maxSize) {
            flushLocked();
        }
    }

    /**
     * Synchronously deliver all pending events in insertion order.
     */
    synchronized void flush() {
        flushLocked();
    }

    /**
     * Flush pending events and prevent future buffering.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        cancelScheduledFlushLocked();
        flushLocked();
        scheduler.shutdownNow();
    }

    private void scheduleFlushLocked() {
        if (scheduledFlush == null && !events.isEmpty() && !closed) {
            scheduledFlush = scheduler.schedule(
                this::flushFromTimer,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
            );
        }
    }

    private void flushFromTimer() {
        synchronized (this) {
            scheduledFlush = null;
            if (closed || events.isEmpty()) {
                return;
            }

            try {
                flushLocked();
            } catch (RuntimeException ignored) {
                // The logger delivery callback applies fail-open/fail-closed
                // semantics. A timer thread cannot surface a synchronous error.
            }
        }
    }

    private void flushLocked() {
        if (events.isEmpty()) {
            return;
        }

        cancelScheduledFlushLocked();
        List<Map<String, Object>> pendingEvents = new ArrayList<>(events);
        events.clear();

        for (Map<String, Object> event : pendingEvents) {
            try {
                deliver.accept(event);
            } catch (RuntimeException exception) {
                // The failed event and the remaining events in this batch have
                // already been removed from the buffer. Keep the batcher active
                // so fail-open logging can accept and attempt later events.
                throw exception;
            }
        }
    }

    private void cancelScheduledFlushLocked() {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
            scheduledFlush = null;
        }
    }

    private static final class BatcherThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "dt3-event-batcher");
            thread.setDaemon(true);
            return thread;
        }
    }
}
