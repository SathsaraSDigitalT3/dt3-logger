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
    private final Consumer<RuntimeException> timerFailureHandler;
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
     * @param timerFailureHandler receives failures raised by the timer thread
     * @param maxSize positive count that causes an immediate flush
     * @param flushIntervalMs positive maximum pending interval in milliseconds
     */
    EventBatcher(
        Consumer<Map<String, Object>> deliver,
        Consumer<RuntimeException> timerFailureHandler,
        int maxSize,
        long flushIntervalMs
    ) {
        this.deliver = Objects.requireNonNull(deliver, "deliver must not be null");
        this.timerFailureHandler = Objects.requireNonNull(
            timerFailureHandler,
            "timerFailureHandler must not be null"
        );
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
            throw new Dt3SdkException(
                "Batcher is closed",
                Dt3ErrorCode.LIFECYCLE_CLOSED,
                false,
                Dt3ErrorPhase.LIFECYCLE
            );
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
            } catch (RuntimeException exception) {
                abortLocked();
                timerFailureHandler.accept(toBatchAbortedFailure(exception));
            }
        }
    }

    /**
     * Prevent further buffering after a scheduled delivery failure.
     *
     * <p>Events are cleared because a failed timer flush has already removed its
     * delivery batch from the buffer. Closing the batcher prevents future events
     * from being accepted into an unusable delivery pipeline.</p>
     */
    private void abortLocked() {
        closed = true;
        cancelScheduledFlushLocked();
        events.clear();
        scheduler.shutdownNow();
    }

    private void flushLocked() {
        if (events.isEmpty()) {
            return;
        }

        cancelScheduledFlushLocked();
        List<Map<String, Object>> pendingEvents = new ArrayList<>(events);
        events.clear();

        for (Map<String, Object> event : pendingEvents) {
            deliver.accept(event);
        }
    }

    private RuntimeException toBatchAbortedFailure(RuntimeException exception) {
        return new Dt3SdkException(
            "Scheduled batch delivery aborted",
            exception,
            Dt3ErrorCode.BATCH_ABORTED,
            false,
            Dt3ErrorPhase.BATCHING
        );
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
