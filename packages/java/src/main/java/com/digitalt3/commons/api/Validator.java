package com.digitalt3.commons.api;

import java.util.List;

/**
 * Schema validator for log events.
 *
 * @since 0.1.0
 */
public interface Validator {

    /**
     * Validate a log event against the canonical schema.
     *
     * @param event The log event to validate
     * @return Validation result
     */
    ValidationResult validate(LogEvent event);

    /**
     * Validation result.
     */
    record ValidationResult(boolean valid, List<String> errors, ValidationMode mode) {}
}
