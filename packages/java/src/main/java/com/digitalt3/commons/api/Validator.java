package com.digitalt3.commons.api;

import java.util.List;

/**
 * Schema validator for log events.
 *
 * @since 0.1.0
 */
public interface Validator {

    // PUBLIC_INTERFACE
    /**
     * Validate a log event against the canonical schema.
     *
     * @param event the log event to validate
     * @return a structured validation result
     */
    ValidationResult validate(LogEvent event);

    /**
     * A sanitized schema-validation diagnostic matching the canonical
     * validation-result contract.
     *
     * @param field affected event field, or {@code $} for the root object
     * @param message human-readable explanation that excludes caller values
     * @param rule violated JSON Schema keyword
     */
    record ValidationErrorDetail(String field, String message, String rule) {
    }

    /**
     * Result of validating a log event.
     *
     * @param valid whether the event conforms to the canonical schema
     * @param errors sanitized structured schema-validation diagnostics
     * @param mode validation mode associated with this result
     */
    record ValidationResult(
        boolean valid,
        List<ValidationErrorDetail> errors,
        ValidationMode mode
    ) {
    }
}
