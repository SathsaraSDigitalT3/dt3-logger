import Ajv2020, { ErrorObject, ValidateFunction } from 'ajv/dist/2020';
import addFormats from 'ajv-formats';

import { ValidationErrorDetail, ValidationMode, ValidationResult } from '../api/types';
import logEventSchema from './log-event.schema.json';

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
 * Validate structured log events against the build-derived canonical schema.
 */
export class LogEventValidator {
  private readonly validator: ValidateFunction;

  /**
   * Load and compile the canonical DT3 log-event validation schema artifact.
   */
  constructor() {
    const ajv = new Ajv2020({ allErrors: true, strict: false });
    addFormats(ajv);
    this.validator = ajv.compile(logEventSchema);
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
  public validate(
    event: Readonly<Record<string, unknown>>,
    mode: ValidationMode | string = ValidationMode.LENIENT,
  ): ValidationResult {
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
