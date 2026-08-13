import Ajv2020, { ErrorObject, ValidateFunction } from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import { ValidationErrorDetail, ValidationMode, ValidationResult } from '../api/types';

/**
 * Raised when a log event fails canonical schema validation in STRICT mode.
 */
export class ValidationError extends Error {
  /**
   * Create an error describing sanitized schema-rule failures.
   *
   * @param errors - Schema-rule errors that do not contain caller-supplied values.
   */
  constructor(errors: ValidationErrorDetail[]) {
    super(`Log event failed schema validation: ${ValidationError.formatErrors(errors)}`);
    this.name = 'ValidationError';
  }

  private static formatErrors(errors: ValidationErrorDetail[]): string {
    return errors.map(({ field, message, rule }) => `${field}: ${message} (${rule})`).join('; ');
  }
}

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
} as const;

/**
 * Validate structured log events against the canonical repository schema.
 */
export class LogEventValidator {
  private readonly validator: ValidateFunction;

  /**
   * Load and compile the canonical DT3 log-event validation schema.
   */
  constructor() {
    // The repository's canonical schema explicitly targets JSON Schema Draft
    // 2020-12, so use Ajv's matching dialect-specific entry point.
    const ajv = new Ajv2020({ allErrors: true, strict: false });
    addFormats(ajv);
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
  public validate(event: Readonly<Record<string, unknown>>, mode: ValidationMode | string = ValidationMode.LENIENT): ValidationResult {
    const normalizedMode = String(mode).toUpperCase() as ValidationMode;

    if (!Object.values(ValidationMode).includes(normalizedMode)) {
      throw new Error('validation.mode must be one of STRICT, LENIENT, or OFF');
    }

    if (normalizedMode === ValidationMode.OFF) {
      return { valid: true, errors: [], mode: normalizedMode };
    }

    const valid = this.validator({ ...event }) as boolean;
    const errors = valid
      ? []
      : [...(this.validator.errors ?? [])]
          .sort((left, right) => this.errorSortKey(left).localeCompare(this.errorSortKey(right)))
          .map((error) => this.formatError(error));

    return { valid, errors, mode: normalizedMode };
  }

  private errorSortKey(error: ErrorObject): string {
    return `${error.instancePath}:${error.keyword}`;
  }

  private formatError(error: ErrorObject): ValidationErrorDetail {
    if (error.keyword === 'required') {
      const missingProperty = (error.params as { missingProperty?: string }).missingProperty ?? 'unknown';
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

  private toFieldPath(instancePath: string): string {
    if (!instancePath) {
      return '$';
    }

    return instancePath
      .slice(1)
      .split('/')
      .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
      .join('.');
  }

  private messageForRule(rule: string): string {
    const messages: Record<string, string> = {
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
