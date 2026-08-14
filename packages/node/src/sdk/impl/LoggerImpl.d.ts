import { Logger } from '../../api/Logger';
/**
 * Concrete DT3 logger that builds structured events and exports them to stdout.
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
    /**
     * Create a logger from SDK configuration.
     *
     * @param config - Service metadata, exporter, masking, and validation configuration.
     */
    constructor(config: Record<string, unknown>);
    private resolveValidationMode;
    private resolveHttpHeaders;
    private resolveOtlpHeaders;
    private log;
    /**
     * Export a DEBUG log event.
     *
     * @param message - Human-readable event message.
     * @param context - Optional structured event context.
     */
    debug(message: string, context?: Record<string, unknown>): void;
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
     * Flush pending log events. OTLP delivery settles asynchronously, while the
     * remaining exporters preserve their existing synchronous flush behavior.
     */
    flush(): Promise<void>;
}
