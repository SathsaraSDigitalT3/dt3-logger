import { Headers, LogEvent } from '../api/types';
/**
 * Raised when an OTLP export cannot be initiated successfully.
 */
export declare class OtlpTransportError extends Error {
    /**
     * Create an OTLP export error with a sanitized failure description.
     *
     * @param message - Safe transport failure description that never includes event data.
     */
    constructor(message: string);
}
/**
 * Synchronously initiates OTLP/HTTP JSON export for final DT3 structured log events.
 *
 * Node's HTTP client sends requests asynchronously. This transport does not buffer
 * events, and logger-level `fail_open` controls handling of synchronous initiation
 * failures without recursively logging transport errors.
 */
export declare class OtlpTransport {
    private static readonly severityNumbers;
    private readonly endpoint;
    private readonly timeoutMs;
    private readonly headers;
    private readonly inFlight;
    private closed;
    /**
     * Create an OTLP/HTTP JSON transport.
     *
     * @param endpoint - OTLP Logs endpoint, conventionally ending in `/v1/logs`.
     * @param timeoutMs - Maximum request duration in milliseconds.
     * @param headers - Optional request headers merged with the OTLP JSON content type.
     * @throws Error if endpoint, timeout, or headers are invalid.
     */
    constructor(endpoint: string, timeoutMs?: number, headers?: Headers);
    /**
     * Export one final DT3 event using the OTLP Logs JSON request format.
     *
     * @param event - Already-masked and validation-processed canonical log event.
     * @throws OtlpTransportError if the request cannot be initiated.
     */
    export(event: LogEvent): void;
    /**
     * Wait for OTLP requests that were in flight when this method was called.
     *
     * @returns A promise resolving after all captured requests settle, rejecting
     * with the first sanitized delivery failure.
     */
    flush(): Promise<void>;
    /**
     * Enter the terminal transport state.
     *
     * The OTLP transport has no persistent resource to release, but closing is
     * idempotent and prevents new exports and flushes.
     */
    close(): void;
    /**
     * Map a final DT3 event into a standards-shaped OTLP Logs JSON export body.
     *
     * @param event - Final canonical DT3 log event.
     * @returns An OTLP Logs JSON request containing one log record.
     */
    static toOtlpPayload(event: LogEvent): Record<string, unknown>;
    private static resourceAttributes;
    private static logAttributes;
    private static attribute;
    private static anyValue;
    private static timestampToNanoseconds;
    private static areValidHeaders;
}
