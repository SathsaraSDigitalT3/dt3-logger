"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.OtlpTransport = exports.OtlpTransportError = void 0;
const node_http_1 = require("node:http");
const node_https_1 = require("node:https");
/**
 * Raised when an OTLP export cannot be initiated successfully.
 */
class OtlpTransportError extends Error {
    /**
     * Create an OTLP export error with a sanitized failure description.
     *
     * @param message - Safe transport failure description that never includes event data.
     */
    constructor(message) {
        super(message);
        this.name = 'OtlpTransportError';
    }
}
exports.OtlpTransportError = OtlpTransportError;
/**
 * Synchronously initiates OTLP/HTTP JSON export for final DT3 structured log events.
 *
 * Node's HTTP client sends requests asynchronously. This transport does not buffer
 * events, and logger-level `fail_open` controls handling of synchronous initiation
 * failures without recursively logging transport errors.
 */
class OtlpTransport {
    static severityNumbers = {
        TRACE: 1,
        DEBUG: 5,
        INFO: 9,
        WARN: 13,
        WARNING: 13,
        ERROR: 17,
        FATAL: 21,
    };
    endpoint;
    timeoutMs;
    headers;
    inFlight = new Set();
    closed = false;
    /**
     * Create an OTLP/HTTP JSON transport.
     *
     * @param endpoint - OTLP Logs endpoint, conventionally ending in `/v1/logs`.
     * @param timeoutMs - Maximum request duration in milliseconds.
     * @param headers - Optional request headers merged with the OTLP JSON content type.
     * @throws Error if endpoint, timeout, or headers are invalid.
     */
    constructor(endpoint, timeoutMs = 10000, headers) {
        if (typeof endpoint !== 'string' || endpoint.trim().length === 0) {
            throw new Error('otlp.endpoint must be configured for the OTLP exporter');
        }
        if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
            throw new Error('otlp.timeout must be greater than zero');
        }
        if (headers !== undefined && !OtlpTransport.areValidHeaders(headers)) {
            throw new Error('otlp.headers must be a mapping of string header names to string values');
        }
        try {
            this.endpoint = new URL(endpoint);
        }
        catch {
            throw new Error('otlp.endpoint must be a valid HTTP or HTTPS URL');
        }
        if (this.endpoint.protocol !== 'http:' && this.endpoint.protocol !== 'https:') {
            throw new Error('otlp.endpoint must use the HTTP or HTTPS protocol');
        }
        this.timeoutMs = timeoutMs;
        this.headers = { ...(headers ?? {}) };
    }
    /**
     * Export one final DT3 event using the OTLP Logs JSON request format.
     *
     * @param event - Already-masked and validation-processed canonical log event.
     * @throws OtlpTransportError if the request cannot be initiated.
     */
    export(event) {
        if (this.closed) {
            throw new OtlpTransportError('OTLP transport is closed');
        }
        const payload = JSON.stringify(OtlpTransport.toOtlpPayload(event));
        const headers = Object.fromEntries(Object.entries(this.headers).filter(([name]) => name.toLowerCase() !== 'content-type'));
        headers['Content-Type'] = 'application/json';
        const requestFactory = this.endpoint.protocol === 'https:' ? node_https_1.request : node_http_1.request;
        let resolveDelivery;
        let rejectDelivery;
        const delivery = new Promise((resolve, reject) => {
            resolveDelivery = resolve;
            rejectDelivery = reject;
        });
        this.inFlight.add(delivery);
        void delivery.finally(() => this.inFlight.delete(delivery)).catch(() => undefined);
        try {
            const outgoingRequest = requestFactory(this.endpoint, {
                method: 'POST',
                headers,
                timeout: this.timeoutMs,
            }, (response) => {
                // Drain the response so the connection is not retained unnecessarily.
                response.resume();
                if (response.statusCode === undefined || response.statusCode < 200 || response.statusCode >= 300) {
                    rejectDelivery(new OtlpTransportError(`OTLP export failed with status ${response.statusCode ?? 'unknown'}`));
                    return;
                }
                resolveDelivery();
            });
            outgoingRequest.once('timeout', () => {
                outgoingRequest.destroy(new OtlpTransportError('OTLP export request timed out'));
            });
            outgoingRequest.once('error', (error) => {
                // Transport errors intentionally omit the event payload to avoid exposing data.
                rejectDelivery(error instanceof OtlpTransportError
                    ? error
                    : new OtlpTransportError('OTLP export request failed'));
            });
            outgoingRequest.write(payload, 'utf8');
            outgoingRequest.end();
        }
        catch {
            rejectDelivery(new OtlpTransportError('OTLP export request could not be initiated'));
        }
    }
    /**
     * Wait for OTLP requests that were in flight when this method was called.
     *
     * @returns A promise resolving after all captured requests settle, rejecting
     * with the first sanitized delivery failure.
     */
    async flush() {
        if (this.closed) {
            throw new OtlpTransportError('OTLP transport is closed');
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
     * The OTLP transport has no persistent resource to release, but closing is
     * idempotent and prevents new exports and flushes.
     */
    close() {
        this.closed = true;
    }
    /**
     * Map a final DT3 event into a standards-shaped OTLP Logs JSON export body.
     *
     * @param event - Final canonical DT3 log event.
     * @returns An OTLP Logs JSON request containing one log record.
     */
    static toOtlpPayload(event) {
        const eventData = { ...event };
        const severityText = String(eventData.severity ?? 'INFO').toUpperCase();
        const logRecord = {
            timeUnixNano: String(OtlpTransport.timestampToNanoseconds(eventData.timestamp)),
            severityNumber: OtlpTransport.severityNumbers[severityText] ?? 9,
            severityText,
            body: { stringValue: String(eventData.message ?? '') },
        };
        const logAttributes = OtlpTransport.logAttributes(eventData);
        if (logAttributes.length > 0) {
            logRecord.attributes = logAttributes;
        }
        const scopeAttributes = [];
        if ('sdk.name' in eventData) {
            scopeAttributes.push(OtlpTransport.attribute('dt3.sdk.name', eventData['sdk.name']));
        }
        if ('sdk.version' in eventData) {
            scopeAttributes.push(OtlpTransport.attribute('dt3.sdk.version', eventData['sdk.version']));
        }
        const scopeLog = { logRecords: [logRecord] };
        if (scopeAttributes.length > 0) {
            scopeLog.scope = { name: 'dt3.logger', attributes: scopeAttributes };
        }
        return {
            resourceLogs: [
                {
                    resource: { attributes: OtlpTransport.resourceAttributes(eventData) },
                    scopeLogs: [scopeLog],
                },
            ],
        };
    }
    static resourceAttributes(event) {
        const attributes = [];
        for (const key of [
            'service.name',
            'service.version',
            'deployment.environment',
            'tenant.id',
            'tenant.name',
        ]) {
            if (key in event) {
                attributes.push(OtlpTransport.attribute(key, event[key]));
            }
        }
        return attributes;
    }
    static logAttributes(event) {
        const excluded = new Set([
            'timestamp',
            'severity',
            'message',
            'service.name',
            'service.version',
            'deployment.environment',
            'tenant.id',
            'tenant.name',
            'sdk.name',
            'sdk.version',
        ]);
        return Object.entries(event)
            .filter(([key]) => !excluded.has(key))
            .map(([key, value]) => OtlpTransport.attribute(key, value));
    }
    static attribute(key, value) {
        return { key, value: OtlpTransport.anyValue(value) };
    }
    static anyValue(value) {
        if (typeof value === 'boolean') {
            return { boolValue: value };
        }
        if (typeof value === 'number') {
            return Number.isInteger(value) ? { intValue: String(value) } : { doubleValue: value };
        }
        if (typeof value === 'string') {
            return { stringValue: value };
        }
        if (value === null || value === undefined) {
            return { stringValue: 'null' };
        }
        if (Array.isArray(value)) {
            return { arrayValue: { values: value.map((item) => OtlpTransport.anyValue(item)) } };
        }
        if (typeof value === 'object') {
            return {
                kvlistValue: {
                    values: Object.entries(value).map(([key, item]) => OtlpTransport.attribute(key, item)),
                },
            };
        }
        return { stringValue: String(value) };
    }
    static timestampToNanoseconds(value) {
        const nowNanoseconds = () => BigInt(Date.now()) * 1000000n;
        if (typeof value !== 'string') {
            return nowNanoseconds();
        }
        const timestamp = Date.parse(value);
        return Number.isNaN(timestamp) ? nowNanoseconds() : BigInt(timestamp) * 1000000n;
    }
    static areValidHeaders(headers) {
        return Object.entries(headers).every(([name, value]) => typeof name === 'string' &&
            name.trim().length > 0 &&
            !/[\r\n]/.test(name) &&
            typeof value === 'string' &&
            !/[\r\n]/.test(value));
    }
}
exports.OtlpTransport = OtlpTransport;
