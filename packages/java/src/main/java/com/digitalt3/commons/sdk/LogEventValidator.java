package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.api.Validator;

import java.util.List;
import java.util.Objects;

/**
 * Public canonical-schema validator for {@link LogEvent} instances.
 *
 * <p>This adapter implements the supported {@link Validator} contract from
 * {@code specs/validation.yaml}. It validates the event's flat map through the
 * same canonical schema machinery used by {@link StdoutLogger}, while remaining
 * independent from logger validation-mode branching.</p>
 *
 * @since 0.1.0
 */
public final class LogEventValidator implements Validator {

    private final MapEventValidator mapEventValidator;

    /**
     * Create a validator backed by the packaged canonical log-event schema.
     */
    public LogEventValidator() {
        this.mapEventValidator = new MapEventValidator();
    }

    // PUBLIC_INTERFACE
    /**
     * Validate an event against the canonical log-event schema.
     *
     * @param event the event to validate; must not be {@code null}
     * @return a LENIENT-mode structured result containing every sanitized violation
     * @throws NullPointerException if {@code event} is {@code null}
     */
    @Override
    public ValidationResult validate(LogEvent event) {
        return validate(event, ValidationMode.LENIENT);
    }

    // PUBLIC_INTERFACE
    /**
     * Validate an event with the requested canonical validation behavior.
     *
     * @param event the event to validate; must not be {@code null}
     * @param mode validation mode to apply; must not be {@code null}
     * @return a structured result in the requested mode
     * @throws LogEventValidationException when STRICT validation rejects the event
     * @throws NullPointerException if {@code event} or {@code mode} is {@code null}
     */
    @Override
    public ValidationResult validate(LogEvent event, ValidationMode mode) {
        LogEvent nonNullEvent = Objects.requireNonNull(event, "event must not be null");
        ValidationMode resolvedMode = Objects.requireNonNull(mode, "mode must not be null");
        if (resolvedMode == ValidationMode.OFF) {
            return new ValidationResult(true, List.of(), ValidationMode.OFF);
        }

        List<ValidationErrorDetail> errors = mapEventValidator.validate(nonNullEvent.toMap());
        if (resolvedMode == ValidationMode.STRICT && !errors.isEmpty()) {
            throw new LogEventValidationException(
                "Log event validation failed: " + MapEventValidator.formatErrors(errors)
            );
        }

        return new ValidationResult(
            errors.isEmpty(),
            errors,
            resolvedMode
        );
    }
}
