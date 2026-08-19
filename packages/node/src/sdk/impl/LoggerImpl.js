"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LoggerImpl = void 0;
const types_1 = require("../../api/types");
const batching_1 = require("../batching");
const FileTransport_1 = require("../FileTransport");
const HttpTransport_1 = require("../HttpTransport");
const context_1 = require("../context");
const masking_1 = require("../masking");
const OtlpTransport_1 = require("../OtlpTransport");
const Timer_1 = require("../Timer");
const validation_1 = require("../validation");
/**
 * Concrete DT3 logger that builds, validates, batches, and exports structured events.
 */
class LoggerImpl {
    config;
    exporter;
    failOpen;
    validationMode;
    maskingEngine;
    validator;
    fileTransport;
    httpTransport;
    otlpTransport;
    batcher;
    closed = false;
    /**
     * Create a logger from SDK configuration.
     *
     * @param config - Service metadata, exporter, masking, validation, and batching configuration.
     */
    constructor(config) {
        this.config = config;
        this.exporter = typeof config.exporter === 'string' ? config.exporter : 'stdout';
        this.failOpen = this.requireBoolean(config.fail_open ?? true, 'fail_open');
        this.fileTransport =
            this.exporter === 'file'
                ? new FileTransport_1.FileTransport(typeof config['exporter.file.path'] === 'string' ? config['exporter.file.path'] : '')
                : undefined;
        this.httpTransport =
            this.exporter === 'http'
                ? new HttpTransport_1.HttpTransport(typeof config['exporter.http.endpoint'] === 'string'
                    ? config['exporter.http.endpoint']
                    : '', this.resolveHttpTimeout(config), this.resolveHeaders(config['exporter.http.headers'], 'exporter.http.headers'))
                : undefined;
        this.otlpTransport =
            this.exporter === 'otlp'
                ? new OtlpTransport_1.OtlpTransport(typeof config['otlp.endpoint'] === 'string' ? config['otlp.endpoint'] : '', typeof config['otlp.timeout'] === 'number' ? config['otlp.timeout'] : 10000, this.resolveHeaders(config['otlp.headers'], 'otlp.headers'))
                : undefined;
        this.validationMode = this.resolveValidationMode(config['validation.mode']);
        this.maskingEngine = new masking_1.MaskingEngine({
            sensitiveFields: Array.isArray(config['masking.fields'])
                ? config['masking.fields'].filter((field) => typeof field === 'string')
                : undefined,
            replacementValue: typeof config['masking.replacement_value'] === 'string'
                ? config['masking.replacement_value']
                : undefined,
            trackMaskedFields: config['masking.track_masked_fields'] === true,
            enabled: config['masking.enabled'] !== false,
        });
        this.validator = new validation_1.LogEventValidator();
        if (this.requireBoolean(config['batching.enabled'] ?? false, 'batching.enabled')) {
            const maxSize = this.resolveBatchingNumber(config['batching.max_size'], 'batching.max_size', 100);
            const flushIntervalMs = this.resolveBatchingNumber(config['batching.flush_interval_ms'], 'batching.flush_interval_ms', 5000);
            this.batcher = new batching_1.EventBatcher((event) => this.exportWithPolicy(event), maxSize, flushIntervalMs);
        }
    }
    resolveBatchingNumber(value, key, defaultValue) {
        if (value === undefined) {
            return defaultValue;
        }
        if (typeof value !== 'number') {
            throw new Error(`${key} must be a number`);
        }
        return value;
    }
    resolveValidationMode(value) {
        const normalizedMode = typeof value === 'string' ? value.toUpperCase() : types_1.ValidationMode.LENIENT;
        if (!Object.values(types_1.ValidationMode).includes(normalizedMode)) {
            throw new Error('validation.mode must be one of STRICT, LENIENT, or OFF');
        }
        return normalizedMode;
    }
    resolveHttpTimeout(config) {
        const canonicalTimeout = config['exporter.http.timeout'];
        const legacyTimeout = config['exporter.http.timeout_ms'];
        if (canonicalTimeout !== undefined) {
            if (typeof canonicalTimeout !== 'number') {
                throw new Error('exporter.http.timeout must be a number of milliseconds');
            }
            return canonicalTimeout;
        }
        if (legacyTimeout !== undefined) {
            if (typeof legacyTimeout !== 'number') {
                throw new Error('exporter.http.timeout_ms must be a number of milliseconds');
            }
            return legacyTimeout;
        }
        return 5000;
    }
    resolveHeaders(value, key) {
        if (value === undefined) {
            return undefined;
        }
        if (value === null || Array.isArray(value) || typeof value !== 'object') {
            throw new Error(`${key} must be a mapping of safe string header names to string values`);
        }
        const headers = Object.entries(value);
        if (headers.some(([name, headerValue]) => name.trim().length === 0 ||
            /[\r\n]/.test(name) ||
            typeof headerValue !== 'string' ||
            /[\r\n]/.test(headerValue))) {
            throw new Error(`${key} must be a mapping of safe string header names to string values`);
        }
        return Object.fromEntries(headers);
    }
    ensureOpen() {
        if (this.closed) {
            throw new Error('Logger is closed');
        }
    }
    /**
     * Verify that the logger remains usable by a timer lifecycle operation.
     *
     * This intentionally delegates to the existing logger lifecycle gate so
     * timers fail with the same documented closed-logger error as log methods.
     */
    ensureTimerLoggerOpen() {
        this.ensureOpen();
    }
    requireBoolean(value, key) {
        if (typeof value !== 'boolean') {
            throw new Error(`${key} must be a boolean`);
        }
        return value;
    }
    handleDeliveryFailure(error) {
        if (!this.failOpen) {
            throw error;
        }
    }
    export(event) {
        if (this.exporter === 'stdout') {
            console.log(JSON.stringify(event));
        }
        else if (this.exporter === 'file') {
            this.fileTransport?.export(event);
        }
        else if (this.exporter === 'http') {
            this.httpTransport?.export(event);
        }
        else if (this.exporter === 'otlp') {
            this.otlpTransport?.export(event);
        }
    }
    exportWithPolicy(event) {
        try {
            this.export(event);
        }
        catch (deliveryError) {
            this.handleDeliveryFailure(deliveryError);
        }
    }
    log(level, message, context, error) {
        this.ensureOpen();
        // Explicit event context intentionally overrides values inherited from the
        // active execution scope. Logger-owned fields are asserted below.
        const callerContext = { ...(0, context_1.getActiveLogContext)(), ...context };
        const eventName = typeof callerContext['event.name'] === 'string' ? callerContext['event.name'] : 'GENERIC_EVENT';
        const logEvent = { ...callerContext };
        // The logger owns these fields; setting them after context prevents caller
        // input from overriding method-selected severity or logger metadata.
        Object.assign(logEvent, {
            timestamp: new Date().toISOString(),
            severity: level,
            message,
            'event.name': eventName,
            'schema.version': typeof this.config['schema.version'] === 'string' ? this.config['schema.version'] : '1.0.0',
            'sdk.name': typeof this.config['sdk.name'] === 'string' ? this.config['sdk.name'] : '@digitalt3/commons',
            'sdk.version': typeof this.config['sdk.version'] === 'string' ? this.config['sdk.version'] : '0.1.0',
        });
        for (const field of ['service.name', 'service.version', 'deployment.environment']) {
            if (typeof this.config[field] === 'string') {
                logEvent[field] = this.config[field];
            }
        }
        if (error) {
            logEvent['error.type'] = error.name;
            logEvent['error.message'] = error.message;
            logEvent['error.stack'] = error.stack;
        }
        const maskedResult = this.maskingEngine.mask(logEvent);
        const maskedEvent = maskedResult.data;
        if (maskedResult.maskedFields.length > 0) {
            maskedEvent['dt3.security.masked_fields'] = maskedResult.maskedFields;
        }
        const validationResult = this.validator.validate(maskedEvent, this.validationMode);
        if (!validationResult.valid) {
            if (validationResult.mode === types_1.ValidationMode.STRICT) {
                throw new validation_1.ValidationError(validationResult.errors);
            }
            if (validationResult.mode === types_1.ValidationMode.LENIENT) {
                maskedEvent['dt3.validation.errors'] = validationResult.errors;
            }
        }
        if (this.batcher) {
            this.batcher.add(maskedEvent);
        }
        else {
            this.exportWithPolicy(maskedEvent);
        }
    }
    // PUBLIC_INTERFACE
    /**
     * Export a DEBUG log event.
     *
     * @param message - Human-readable event message.
     * @param context - Optional structured event context.
     */
    debug(message, context) {
        this.log('DEBUG', message, context);
    }
    // PUBLIC_INTERFACE
    /**
     * Run a callback with trace and correlation context attached to all logs
     * created in the callback's execution scope.
     *
     * @param context - Convenience trace and correlation identifiers.
     * @param callback - Synchronous or asynchronous work to run in the scope.
     * @returns The callback result.
     */
    withContext(context, callback) {
        return (0, context_1.withLogContext)(context, callback);
    }
    // PUBLIC_INTERFACE
    /**
     * Export an INFO log event.
     *
     * @param message - Human-readable event message.
     * @param context - Optional structured event context.
     */
    info(message, context) {
        this.log('INFO', message, context);
    }
    // PUBLIC_INTERFACE
    /**
     * Export a WARN log event.
     *
     * @param message - Human-readable event message.
     * @param context - Optional structured event context.
     */
    warn(message, context) {
        this.log('WARN', message, context);
    }
    // PUBLIC_INTERFACE
    /**
     * Export an ERROR log event with optional error details.
     *
     * @param message - Human-readable event message.
     * @param error - Optional error to include in structured event fields.
     * @param context - Optional structured event context.
     */
    error(message, error, context) {
        this.log('ERROR', message, context, error);
    }
    // PUBLIC_INTERFACE
    /**
     * Create an unstarted timer that emits a canonical INFO completion event.
     *
     * Completion delegates to info, preserving active context, masking,
     * validation, batching, and the configured transport/exporter behavior.
     *
     * @param name - Non-blank canonical event name for the completion event.
     * @param context - Optional event metadata for the completion event.
     * @returns A new unstarted timer.
     * @throws Error when the logger is closed or the timer name is invalid.
     */
    createTimer(name, context) {
        this.ensureOpen();
        return new Timer_1.TimerImpl(this, name, context);
    }
    // PUBLIC_INTERFACE
    /**
     * Flush buffered events and settle delivery work initiated before the flush boundary.
     *
     * @returns A promise that rejects only when a delivery failure is configured
     * to fail closed.
     */
    async flush() {
        this.ensureOpen();
        try {
            this.batcher?.flush();
            this.fileTransport?.flush();
            await this.httpTransport?.flush();
            await this.otlpTransport?.flush();
        }
        catch (error) {
            this.handleDeliveryFailure(error);
        }
    }
    // PUBLIC_INTERFACE
    /**
     * Flush remaining events, close the active transport, and prevent future logging.
     *
     * Closing is idempotent. Subsequent logging and flush calls fail with a
     * documented terminal-state error.
     */
    close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            this.batcher?.close();
            this.fileTransport?.flush();
        }
        catch (error) {
            this.handleDeliveryFailure(error);
        }
        this.httpTransport?.close();
        this.otlpTransport?.close();
    }
}
exports.LoggerImpl = LoggerImpl;
