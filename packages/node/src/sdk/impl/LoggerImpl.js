"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.LoggerImpl = void 0;
const types_1 = require("../../api/types");
const masking_1 = require("../masking");
const validation_1 = require("../validation");
/**
 * Concrete DT3 logger that builds structured events and exports them to stdout.
 */
class LoggerImpl {
    config;
    exporter;
    validationMode;
    maskingEngine;
    validator;
    /**
     * Create a logger from SDK configuration.
     *
     * @param config - Service metadata, exporter, masking, and validation configuration.
     */
    constructor(config) {
        this.config = config;
        this.exporter = typeof config.exporter === 'string' ? config.exporter : 'stdout';
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
    }
    resolveValidationMode(value) {
        const normalizedMode = typeof value === 'string' ? value.toUpperCase() : types_1.ValidationMode.LENIENT;
        if (!Object.values(types_1.ValidationMode).includes(normalizedMode)) {
            throw new Error('validation.mode must be one of STRICT, LENIENT, or OFF');
        }
        return normalizedMode;
    }
    log(level, message, context, error) {
        const eventName = typeof context?.['event.name'] === 'string' ? context['event.name'] : 'GENERIC_EVENT';
        const logEvent = {
            timestamp: new Date().toISOString(),
            severity: level,
            message,
            'event.name': eventName,
            'schema.version': typeof this.config['schema.version'] === 'string' ? this.config['schema.version'] : '1.0.0',
            'sdk.name': typeof this.config['sdk.name'] === 'string' ? this.config['sdk.name'] : '@digitalt3/commons',
            'sdk.version': typeof this.config['sdk.version'] === 'string' ? this.config['sdk.version'] : '0.1.0',
            ...context,
        };
        // Required service metadata is copied only when supplied. This lets schema
        // validation accurately report missing fields instead of hiding them behind
        // synthetic "unknown" values.
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
        // The repository contract requires masking before validation so validation
        // handling cannot expose caller-supplied sensitive values.
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
        if (this.exporter === 'stdout') {
            console.log(JSON.stringify(maskedEvent));
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
     * Flush pending log events. The stdout exporter has no buffered events.
     */
    flush() {
        // No-op for stdout.
    }
}
exports.LoggerImpl = LoggerImpl;
