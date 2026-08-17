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
 * Export final DT3 structured events to a generic HTTP endpoint.
 *
 * Requests are settled asynchronously and captured by `flush`, while `export`
 * remains synchronous so existing logger method signatures are preserved.
 */
class HttpTransport {
    endpoint;
    timeoutMs;
    headers;
    inFlight = new Set();
    closed = false;
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
            throw new Error('exporter.http.timeout must be greater than zero');
        }
        if (headers !== undefined && !HttpTransport.areValidHeaders(headers)) {
            throw new Error('exporter.http.headers must be a mapping of safe string header names to string values');
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
     * Start export of one final DT3 event as an application/json payload.
     *
     * @param event - The already-masked and validation-processed canonical log event.
     * @throws HttpTransportError if the transport is closed or initialization fails.
     */
    export(event) {
        if (this.closed) {
            throw new HttpTransportError('HTTP transport is closed');
        }
        const payload = JSON.stringify(event);
        const headers = Object.fromEntries(Object.entries(this.headers).filter(([name]) => name.toLowerCase() !== 'content-type'));
        headers['Content-Type'] = 'application/json';
        let resolveDelivery;
        let rejectDelivery;
        const delivery = new Promise((resolve, reject) => {
            resolveDelivery = resolve;
            rejectDelivery = reject;
        });
        this.inFlight.add(delivery);
        void delivery.finally(() => this.inFlight.delete(delivery)).catch(() => undefined);
        try {
            const requestFactory = this.endpoint.protocol === 'https:' ? node_https_1.request : node_http_1.request;
            const outgoingRequest = requestFactory(this.endpoint, {
                method: 'POST',
                headers,
                timeout: this.timeoutMs,
            }, (response) => {
                response.resume();
                if (response.statusCode === undefined || response.statusCode < 200 || response.statusCode >= 300) {
                    rejectDelivery(new HttpTransportError(`HTTP export failed with status ${response.statusCode ?? 'unknown'}`));
                    return;
                }
                resolveDelivery();
            });
            outgoingRequest.once('timeout', () => {
                outgoingRequest.destroy(new HttpTransportError('HTTP export request timed out'));
            });
            outgoingRequest.once('error', (error) => {
                rejectDelivery(error instanceof HttpTransportError
                    ? error
                    : new HttpTransportError('HTTP export request failed'));
            });
            outgoingRequest.write(payload, 'utf8');
            outgoingRequest.end();
        }
        catch {
            rejectDelivery(new HttpTransportError('HTTP export request could not be initiated'));
        }
    }
    /**
     * Settle requests started before this flush boundary.
     *
     * @returns A promise that resolves when captured requests succeed or rejects
     * with the first sanitized delivery failure.
     * @throws HttpTransportError if the transport is closed.
     */
    async flush() {
        if (this.closed) {
            throw new HttpTransportError('HTTP transport is closed');
        }
        const pending = [...this.inFlight];
        if (pending.length === 0) {
            return;
        }
        const results = await Promise.allSettled(pending);
        const failure = results.find((result) => result.status === 'rejected');
        if (failure) {
            throw failure.reason;
        }
    }
    /**
     * Enter the terminal transport state.
     *
     * The generic HTTP transport owns no persistent sockets, so close only
     * prevents future exports and flush operations.
     */
    close() {
        this.closed = true;
    }
    static areValidHeaders(headers) {
        return Object.entries(headers).every(([name, value]) => typeof name === 'string' &&
            name.trim().length > 0 &&
            !/[\r\n]/.test(name) &&
            typeof value === 'string' &&
            !/[\r\n]/.test(value));
    }
}
exports.HttpTransport = HttpTransport;
