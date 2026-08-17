import { ValidationErrorDetail, ValidationMode, ValidationResult } from '../api/types';
/**
 * Raised when a log event fails canonical schema validation in STRICT mode.
 */
export declare class ValidationError extends Error {
    /**
     * Create an error describing sanitized schema-rule failures.
     *
     * @param errors - Schema-rule errors that do not contain caller-supplied values.
     */
    constructor(errors: ValidationErrorDetail[]);
    private static formatErrors;
}
/**
 * Validate structured log events against the build-derived canonical schema.
 */
export declare class LogEventValidator {
    private readonly validator;
    /**
     * Load and compile the canonical DT3 log-event validation schema artifact.
     */
    constructor();
    /**
     * Validate an event without mutating the supplied value.
     *
     * @param event - Structured log event to validate.
     * @param mode - Repository-defined validation mode.
     * @returns The validation outcome with structured, sanitized schema-rule diagnostics.
     * @throws Error if the validation mode is not repository-defined.
     */
    validate(event: Readonly<Record<string, unknown>>, mode?: ValidationMode | string): ValidationResult;
    private errorSortKey;
    private formatError;
    private toFieldPath;
    private messageForRule;
}
