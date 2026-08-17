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

    // PUBLIC_INTERFACE
    /**
     * Validate a log event using the selected validation mode.
     *
     * <p>STRICT mode throws a dedicated validation exception when the event is
     * invalid, LENIENT mode returns diagnostics, and OFF mode skips validation.
     * Implementations that predate selectable validation may preserve their
     * existing default through {@link #validate(LogEvent)}.</p>
     *
     * @param event the log event to validate
     * @param mode selected validation behavior
     * @return a structured validation result
     */
    default ValidationResult validate(LogEvent event, ValidationMode mode) {
        return validate(event);
    }

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
