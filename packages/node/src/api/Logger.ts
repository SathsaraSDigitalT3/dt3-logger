import { Timer, TimerContext } from './Timer';
import { LogContext, LogEvent } from './types';

export interface Logger {
  debug(message: string, context?: Record<string, unknown>): void;
  info(message: string, context?: Record<string, unknown>): void;
  warn(message: string, context?: Record<string, unknown>): void;
  error(message: string, error?: Error, context?: Record<string, unknown>): void;

  // PUBLIC_INTERFACE
  /**
   * Export a FATAL log event through the normal processing pipeline.
   *
   * @param message - Human-readable event message.
   * @param context - Optional structured event context.
   */
  fatal(message: string, context?: Record<string, unknown>): void;

  // PUBLIC_INTERFACE
  /**
   * Process a canonical log event through enrichment, masking, validation,
   * batching, and the configured exporter.
   *
   * @param event - Canonical event fields. The object is never mutated.
   * @throws TypeError when the event or its message is invalid.
   * @throws Error when the severity is unsupported or the logger is closed.
   */
  event(event: LogEvent): void;

  // PUBLIC_INTERFACE
  /**
   * Create an unstarted timer that emits an INFO completion event through this logger.
   *
   * @param name - Non-blank canonical event name for the completion event.
   * @param context - Optional event metadata merged with active scoped context on completion.
   * @returns A new unstarted timer.
   * @throws Error when this logger is closed or the name is invalid.
   */
  createTimer(name: string, context?: TimerContext): Timer;

  // PUBLIC_INTERFACE
  /**
   * Run a synchronous or asynchronous callback with execution-scoped log context.
   *
   * Context propagates through promises and async/await. Nested calls inherit
   * unspecified values and restore the preceding scope after completion.
   *
   * @param context - Trace, correlation, and tenant identifiers for the execution scope.
   * @param callback - Work to execute while the supplied context is active.
   * @returns The callback result.
   */
  withContext<T>(context: LogContext, callback: () => T): T;

  /**
   * Wait for delivery work that was in progress when flushing began.
   *
   * @returns A promise that resolves after pending exporter work settles, or
   * rejects when delivery errors are configured to fail closed.
   */
  flush(): Promise<void>;

  /**
   * Close the logger and prevent future logging or flush operations.
   *
   * This operation is idempotent.
   */
  close(): void;
}
