import { Headers, LogEvent } from '../api/types';
/**
 * Raised when an HTTP export cannot complete successfully.
 */
export declare class HttpTransportError extends Error {
    /**
     * Create an HTTP export error.
     *
     * @param message - A sanitized description of the transport failure.
     */
    constructor(message: string);
}
/**
 * Export final DT3 structured events to a generic HTTP endpoint.
 *
 * Requests are settled asynchronously and captured by `flush`, while `export`
 * remains synchronous so existing logger method signatures are preserved.
 */
export declare class HttpTransport {
    private readonly endpoint;
    private readonly timeoutMs;
    private readonly headers;
    private readonly inFlight;
    private closed;
    /**
     * Create an HTTP transport.
     *
     * @param endpoint - Destination URL that receives JSON log events.
     * @param timeoutMs - Maximum request duration in milliseconds.
     * @param headers - Optional request headers merged with the JSON content type.
     * @throws Error if the endpoint, timeout, or headers are invalid.
     */
    constructor(endpoint: string, timeoutMs?: number, headers?: Headers);
    /**
     * Start export of one final DT3 event as an application/json payload.
     *
     * @param event - The already-masked and validation-processed canonical log event.
     * @throws HttpTransportError if the transport is closed or initialization fails.
     */
    export(event: LogEvent): void;
    /**
     * Settle requests started before this flush boundary.
     *
     * @returns A promise that resolves when captured requests succeed or rejects
     * with the first sanitized delivery failure.
     * @throws HttpTransportError if the transport is closed.
     */
    flush(): Promise<void>;
    /**
     * Enter the terminal transport state.
     *
     * The generic HTTP transport owns no persistent sockets, so close only
     * prevents future exports and flush operations.
     */
    close(): void;
    private static areValidHeaders;
}
