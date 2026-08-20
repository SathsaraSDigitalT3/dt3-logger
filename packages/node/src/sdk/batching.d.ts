import { LogEvent } from '../api/types';
/**
 * Callback used by EventBatcher to deliver one final canonical event.
 */
export type EventDelivery = (event: LogEvent) => void;
/**
 * Buffer final canonical events and deliver them in insertion order.
 *
 * The batcher intentionally calls the existing single-event delivery pipeline
 * for each buffered event. This preserves the current transport payload
 * contract while providing logger-level in-memory batching.
 */
export declare class EventBatcher {
    private readonly deliver;
    private readonly maxSize;
    private readonly flushIntervalMs;
    private events;
    private timer?;
    private closed;
    private aborted;
    /**
     * Create a batching buffer.
     *
     * @param deliver - Synchronous callback that delivers one final event.
     * @param maxSize - Number of events that triggers an immediate flush.
     * @param flushIntervalMs - Maximum time a pending event remains buffered.
     * @throws Error when either batching limit is not a positive integer.
     */
    constructor(deliver: EventDelivery, maxSize: number, flushIntervalMs: number);
    /**
     * Buffer one final event and synchronously flush when the size threshold is reached.
     *
     * @param event - Final masked and validation-processed canonical event.
     * @throws Error when the batcher is closed or fail-closed delivery fails.
     */
    add(event: LogEvent): void;
    /**
     * Deliver all buffered events in insertion order.
     *
     * A delivery error aborts the batcher. The failed event and unattempted
     * suffix are discarded so cleanup cannot implicitly retry or duplicate them.
     */
    flush(): void;
    /**
     * Flush remaining events and prevent further buffering.
     *
     * Closing is idempotent. It deliberately does not replay events after a
     * delivery failure, matching the Python batcher's terminal behavior.
     */
    close(): void;
    private scheduleFlush;
    private takeEvents;
    private abort;
    private cancelTimer;
}
