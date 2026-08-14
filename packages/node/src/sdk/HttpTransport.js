"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.HttpTransport = exports.HttpTransportError = void 0;
const node_http_1 = require("node:http");
const node_https_1 = require("node:https");
/**
 * Raised when an HTTP export cannot complete successfully.
 */
class HttpTransportError extends Error {
    /**
     * Create an HTTP export error.
     *
     * @param message - A sanitized description of the transport failure.
     */
    constructor(message) {
        super(message);
        this.name = 'HttpTransportError';
    }
}
exports.HttpTransportError = HttpTransportError;
/**
 * Synchronously initiate export of final DT3 structured log events to an HTTP endpoint.
 *
 * Node's built-in HTTP client is asynchronous; this transport performs no buffering
 * and exposes failures through the request error callback. Logger-level `fail_open`
 * controls whether those failures are propagated.
 */
class HttpTransport {
    endpoint;
    timeoutMs;
    headers;
    /**
     * Create an HTTP transport.
     *
     * @param endpoint - Destination URL that receives JSON log events.
     * @param timeoutMs - Maximum request duration in milliseconds.
     * @param headers - Optional request headers merged with the JSON content type.
     * @throws Error if the endpoint, timeout, or headers are invalid.
     */
    constructor(endpoint, timeoutMs = 5000, headers) {
        if (typeof endpoint !== 'string' || endpoint.trim().length === 0) {
            throw new Error('exporter.http.endpoint must be configured for the HTTP exporter');
        }
        if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
            throw new Error('exporter.http.timeout_ms must be greater than zero');
        }
        if (headers !== undefined && !HttpTransport.areValidHeaders(headers)) {
            throw new Error('exporter.http.headers must be a mapping of string header names to string values');
        }
        try {
            this.endpoint = new URL(endpoint);
        }
        catch {
            throw new Error('exporter.http.endpoint must be a valid HTTP or HTTPS URL');
        }
        if (this.endpoint.protocol !== 'http:' && this.endpoint.protocol !== 'https:') {
            throw new Error('exporter.http.endpoint must use the HTTP or HTTPS protocol');
        }
        this.timeoutMs = timeoutMs;
        this.headers = { ...(headers ?? {}) };
    }
    /**
     * Export one final DT3 event as an application/json payload.
     *
     * @param event - The already-masked and validation-processed canonical log event.
     * @throws HttpTransportError if the request fails, times out, or returns a non-2xx response.
     */
    export(event) {
        const payload = JSON.stringify(event);
        const headers = Object.fromEntries(Object.entries(this.headers).filter(([name]) => name.toLowerCase() !== 'content-type'));
        headers['Content-Type'] = 'application/json';
        const requestFactory = this.endpoint.protocol === 'https:' ? node_https_1.request : node_http_1.request;
        const outgoingRequest = requestFactory(this.endpoint, {
            method: 'POST',
            headers,
            timeout: this.timeoutMs,
        }, (response) => {
            // Drain the response so the connection is not retained unnecessarily.
            response.resume();
            if (response.statusCode === undefined || response.statusCode < 200 || response.statusCode >= 300) {
                outgoingRequest.destroy(new HttpTransportError(`HTTP export failed with status ${response.statusCode ?? 'unknown'}`));
            }
        });
        outgoingRequest.once('timeout', () => {
            outgoingRequest.destroy(new HttpTransportError('HTTP export request timed out'));
        });
        outgoingRequest.once('error', () => {
            // Logger-level failure handling owns propagation and must not recursively log.
        });
        outgoingRequest.write(payload, 'utf8');
        outgoingRequest.end();
    }
    /**
     * Flush output written by this transport.
     *
     * Requests are sent immediately and no event buffer is maintained.
     */
    flush() {
        // No-op: HTTP requests are initiated as soon as export is called.
    }
    static areValidHeaders(headers) {
        return Object.entries(headers).every(([name, value]) => typeof name === 'string' && typeof value === 'string');
    }
}
exports.HttpTransport = HttpTransport;
