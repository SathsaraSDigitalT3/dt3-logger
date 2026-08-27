import { LogEvent } from './types';

/**
 * Pluggable export destination for processed canonical log events.
 *
 * Built-in transports and custom application sinks share this contract so the
 * logger can fan out without knowing the destination implementation.
 */
export interface EventSink {
  /**
   * Deliver one already-masked and validated canonical log event.
   *
   * @param event - Final pipeline event ready for export.
   */
  export(event: LogEvent): void;

  /**
   * Flush any buffered sink state.
   */
  flush(): void | Promise<void>;

  /**
   * Release sink resources. Must be idempotent.
   */
  close(): void | Promise<void>;
}
