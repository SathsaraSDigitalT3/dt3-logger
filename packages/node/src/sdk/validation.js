"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.LogEventValidator = exports.ValidationError = void 0;
const _2020_1 = __importDefault(require("ajv/dist/2020"));
const ajv_formats_1 = __importDefault(require("ajv-formats"));
const types_1 = require("../api/types");
/**
 * Raised when a log event fails canonical schema validation in STRICT mode.
 */
class ValidationError extends Error {
    /**
     * Create an error describing sanitized schema-rule failures.
     *
     * @param errors - Schema-rule errors that do not contain caller-supplied values.
     */
    constructor(errors) {
        super(`Log event failed schema validation: ${ValidationError.formatErrors(errors)}`);
        this.name = 'ValidationError';
    }
    static formatErrors(errors) {
        return errors.map(({ field, message, rule }) => `${field}: ${message} (${rule})`).join('; ');
    }
}
exports.ValidationError = ValidationError;
/**
 * Canonical repository JSON Schema for structured log events.
 *
 * This is kept in TypeScript only to make it available to the published SDK at
 * runtime; it mirrors, rather than replaces, schemas/log-event.schema.json.
 */
const LOG_EVENT_SCHEMA = {
    $schema: 'https://json-schema.org/draft/2020-12/schema',
    $id: 'https://digitalt3.com/schemas/log-event/1.0.0',
    type: 'object',
    required: [
        'timestamp',
        'severity',
        'message',
        'event.name',
        'schema.version',
        'sdk.name',
        'sdk.version',
        'service.name',
        'service.version',
        'deployment.environment',
    ],
    properties: {
        timestamp: { type: 'string', format: 'date-time' },
        severity: { type: 'string', enum: ['DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL'] },
        message: { type: 'string', minLength: 1 },
        'event.name': { type: 'string', pattern: '^[A-Z][A-Z0-9_]*$' },
        'schema.version': { type: 'string', pattern: '^\\d+\\.\\d+\\.\\d+$' },
        'sdk.name': { type: 'string' },
        'sdk.version': { type: 'string', pattern: '^\\d+\\.\\d+\\.\\d+$' },
        'service.name': { type: 'string', minLength: 1 },
        'service.version': { type: 'string' },
        'deployment.environment': { type: 'string' },
        'trace.id': { type: 'string', pattern: '^[a-f0-9]{32}$' },
        'span.id': { type: 'string', pattern: '^[a-f0-9]{16}$' },
        'parent.span.id': { type: 'string', pattern: '^[a-f0-9]{16}$' },
        'correlation.id': { type: 'string' },
        'tenant.id': { type: 'string' },
        'tenant.region': { type: 'string' },
        'tenant.environment': { type: 'string' },
        'user.id': { type: 'string' },
        'session.id': { type: 'string' },
        'duration.ms': { type: 'number', minimum: 0 },
        'error.type': { type: 'string' },
        'error.message': { type: 'string' },
        'error.stack': { type: 'string' },
        'error.code': { type: 'string' },
        'error.retryable': { type: 'boolean' },
        attributes: { type: 'object', additionalProperties: true },
        'dt3.validation.errors': {
            type: 'array',
            items: {
                type: 'object',
                properties: {
                    field: { type: 'string' },
                    message: { type: 'string' },
                    rule: { type: 'string' },
                },
            },
        },
        'dt3.security.masked_fields': { type: 'array', items: { type: 'string' } },
    },
    additionalProperties: true,
};
/**
 * Validate structured log events against the canonical repository schema.
 */
class LogEventValidator {
    validator;
    /**
     * Load and compile the canonical DT3 log-event validation schema.
     */
    constructor() {
        // The repository's canonical schema explicitly targets JSON Schema Draft
        // 2020-12, so use Ajv's matching dialect-specific entry point.
        const ajv = new _2020_1.default({ allErrors: true, strict: false });
        (0, ajv_formats_1.default)(ajv);
        this.validator = ajv.compile(LOG_EVENT_SCHEMA);
    }
    // PUBLIC_INTERFACE
    /**
     * Validate an event without mutating the supplied value.
     *
     * @param event - Structured log event to validate.
     * @param mode - Repository-defined validation mode.
     * @returns The validation outcome with structured, sanitized schema-rule diagnostics.
     * @throws Error if the validation mode is not repository-defined.
     */
    validate(event, mode = types_1.ValidationMode.LENIENT) {
        const normalizedMode = String(mode).toUpperCase();
        if (!Object.values(types_1.ValidationMode).includes(normalizedMode)) {
            throw new Error('validation.mode must be one of STRICT, LENIENT, or OFF');
        }
        if (normalizedMode === types_1.ValidationMode.OFF) {
            return { valid: true, errors: [], mode: normalizedMode };
        }
        const valid = this.validator({ ...event });
        const errors = valid
            ? []
            : [...(this.validator.errors ?? [])]
                .sort((left, right) => this.errorSortKey(left).localeCompare(this.errorSortKey(right)))
                .map((error) => this.formatError(error));
        return { valid, errors, mode: normalizedMode };
    }
    errorSortKey(error) {
        return `${error.instancePath}:${error.keyword}`;
    }
    formatError(error) {
        if (error.keyword === 'required') {
            const missingProperty = error.params.missingProperty ?? 'unknown';
            return {
                field: missingProperty,
                message: 'Required property is missing.',
                rule: 'required',
            };
        }
        return {
            field: this.toFieldPath(error.instancePath),
            message: this.messageForRule(error.keyword),
            rule: error.keyword,
        };
    }
    toFieldPath(instancePath) {
        if (!instancePath) {
            return '$';
        }
        return instancePath
            .slice(1)
            .split('/')
            .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
            .join('.');
    }
    messageForRule(rule) {
        const messages = {
            enum: 'Value must be one of the allowed options.',
            format: 'Value does not match the required format.',
            minLength: 'Value is shorter than the minimum permitted length.',
            minimum: 'Value is below the minimum permitted value.',
            pattern: 'Value does not match the required pattern.',
            type: 'Value has an invalid type.',
        };
        return messages[rule] ?? `Value violates the '${rule}' schema rule.`;
    }
}
exports.LogEventValidator = LogEventValidator;
