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
 * Synchronously initiate export of final DT3 structured log events to an HTTP endpoint.
 *
 * Node's built-in HTTP client is asynchronous; this transport performs no buffering
 * and exposes failures through the request error callback. Logger-level `fail_open`
 * controls whether those failures are propagated.
 */
export declare class HttpTransport {
    private readonly endpoint;
    private readonly timeoutMs;
    private readonly headers;
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
     * Export one final DT3 event as an application/json payload.
     *
     * @param event - The already-masked and validation-processed canonical log event.
     * @throws HttpTransportError if the request fails, times out, or returns a non-2xx response.
     */
    export(event: LogEvent): void;
    /**
     * Flush output written by this transport.
     *
     * Requests are sent immediately and no event buffer is maintained.
     */
    flush(): void;
    private static areValidHeaders;
}
