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
export class EventBatcher {
  private readonly deliver: EventDelivery;
  private readonly maxSize: number;
  private readonly flushIntervalMs: number;
  private events: LogEvent[] = [];
  private timer?: ReturnType<typeof setTimeout>;
  private closed = false;
  private aborted = false;

  /**
   * Create a batching buffer.
   *
   * @param deliver - Synchronous callback that delivers one final event.
   * @param maxSize - Number of events that triggers an immediate flush.
   * @param flushIntervalMs - Maximum time a pending event remains buffered.
   * @throws Error when either batching limit is not a positive integer.
   */
  constructor(deliver: EventDelivery, maxSize: number, flushIntervalMs: number) {
    if (!Number.isInteger(maxSize) || maxSize <= 0) {
      throw new Error('batching.max_size must be a positive integer');
    }
    if (!Number.isInteger(flushIntervalMs) || flushIntervalMs <= 0) {
      throw new Error('batching.flush_interval_ms must be a positive integer in milliseconds');
    }

    this.deliver = deliver;
    this.maxSize = maxSize;
    this.flushIntervalMs = flushIntervalMs;
  }

  /**
   * Buffer one final event and synchronously flush when the size threshold is reached.
   *
   * @param event - Final masked and validation-processed canonical event.
   * @throws Error when the batcher is closed or fail-closed delivery fails.
   */
  public add(event: LogEvent): void {
    if (this.closed) {
      throw new Error('Batcher is closed');
    }
    if (this.aborted) {
      return;
    }

    this.events.push({ ...event });
    this.scheduleFlush();

    if (this.events.length >= this.maxSize) {
      this.flush();
    }
  }

  /**
   * Deliver all buffered events in insertion order.
   *
   * A delivery error aborts the batcher. The failed event and unattempted
   * suffix are discarded so cleanup cannot implicitly retry or duplicate them.
   */
  public flush(): void {
    if (this.aborted) {
      return;
    }

    const pendingEvents = this.takeEvents();
    for (const event of pendingEvents) {
      try {
        this.deliver(event);
      } catch (error) {
        this.abort();
        throw error;
      }
    }
  }

  /**
   * Flush remaining events and prevent further buffering.
   *
   * Closing is idempotent. It deliberately does not replay events after a
   * delivery failure, matching the Python batcher's terminal behavior.
   */
  public close(): void {
    if (this.closed) {
      return;
    }

    this.closed = true;
    this.cancelTimer();

    if (this.aborted) {
      this.events = [];
      return;
    }

    const pendingEvents = this.takeEvents();
    for (const event of pendingEvents) {
      this.deliver(event);
    }
  }

  private scheduleFlush(): void {
    if (this.timer !== undefined || this.events.length === 0 || this.closed) {
      return;
    }

    this.timer = setTimeout(() => {
      this.timer = undefined;
      if (this.closed || this.events.length === 0) {
        return;
      }

      try {
        this.flush();
      } catch {
        // A fail-closed error has already aborted the batcher. Timer callbacks
        // cannot surface synchronous exceptions to the original logger call.
      }
    }, this.flushIntervalMs);
  }

  private takeEvents(): LogEvent[] {
    if (this.events.length === 0) {
      return [];
    }

    this.cancelTimer();
    const pendingEvents = this.events;
    this.events = [];
    return pendingEvents;
  }

  private abort(): void {
    this.aborted = true;
    this.events = [];
    this.cancelTimer();
  }

  private cancelTimer(): void {
    if (this.timer !== undefined) {
      clearTimeout(this.timer);
      this.timer = undefined;
    }
  }
}
