import { Logger } from '../../api/Logger';
import { LogContext } from '../../api/types';
/**
 * Concrete DT3 logger that builds, validates, and exports structured events.
 */
export declare class LoggerImpl implements Logger {
    private readonly config;
    private readonly exporter;
    private readonly failOpen;
    private readonly validationMode;
    private readonly maskingEngine;
    private readonly validator;
    private readonly fileTransport?;
    private readonly httpTransport?;
    private readonly otlpTransport?;
    private closed;
    /**
     * Create a logger from SDK configuration.
     *
     * @param config - Service metadata, exporter, masking, and validation configuration.
     */
    constructor(config: Record<string, unknown>);
    private resolveValidationMode;
    private resolveHttpTimeout;
    private resolveHeaders;
    private ensureOpen;
    private handleDeliveryFailure;
    private log;
    /**
     * Export a DEBUG log event.
     *
     * @param message - Human-readable event message.
     * @param context - Optional structured event context.
     */
    debug(message: string, context?: Record<string, unknown>): void;
    /**
     * Run a callback with trace and correlation context attached to all logs
     * created in the callback's execution scope.
     *
     * @param context - Convenience trace and correlation identifiers.
     * @param callback - Synchronous or asynchronous work to run in the scope.
     * @returns The callback result.
     */
    withContext<T>(context: LogContext, callback: () => T): T;
    /**
     * Export an INFO log event.
     *
     * @param message - Human-readable event message.
     * @param context - Optional structured event context.
     */
    info(message: string, context?: Record<string, unknown>): void;
    /**
     * Export a WARN log event.
     *
     * @param message - Human-readable event message.
     * @param context - Optional structured event context.
     */
    warn(message: string, context?: Record<string, unknown>): void;
    /**
     * Export an ERROR log event with optional error details.
     *
     * @param message - Human-readable event message.
     * @param error - Optional error to include in structured event fields.
     * @param context - Optional structured event context.
     */
    error(message: string, error?: Error, context?: Record<string, unknown>): void;
    /**
     * Settle delivery work initiated before the flush boundary.
     *
     * @returns A promise that rejects only when a delivery failure is configured
     * to fail closed.
     */
    flush(): Promise<void>;
    /**
     * Close the logger and its active transport.
     *
     * Closing is idempotent. Subsequent logging and flush calls fail with a
     * documented terminal-state error.
     */
    close(): void;
}
