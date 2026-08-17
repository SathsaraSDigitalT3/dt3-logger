import { LogContext } from './types';

export interface Logger {
  debug(message: string, context?: Record<string, unknown>): void;
  info(message: string, context?: Record<string, unknown>): void;
  warn(message: string, context?: Record<string, unknown>): void;
  error(message: string, error?: Error, context?: Record<string, unknown>): void;

  // PUBLIC_INTERFACE
  /**
   * Run a synchronous or asynchronous callback with execution-scoped log context.
   *
   * Context propagates through promises and async/await. Nested calls inherit
   * unspecified values and restore the preceding scope after completion.
   *
   * @param context - Trace and correlation identifiers for the execution scope.
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
