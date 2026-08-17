export interface Logger {
  debug(message: string, context?: Record<string, unknown>): void;
  info(message: string, context?: Record<string, unknown>): void;
  warn(message: string, context?: Record<string, unknown>): void;
  error(message: string, error?: Error, context?: Record<string, unknown>): void;

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
