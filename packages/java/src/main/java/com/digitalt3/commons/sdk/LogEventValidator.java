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
        LogEvent nonNullEvent = Objects.requireNonNull(event, "event must not be null");
        List<ValidationErrorDetail> errors = mapEventValidator.validate(nonNullEvent.toMap());

        return new ValidationResult(
            errors.isEmpty(),
            errors,
            ValidationMode.LENIENT
        );
    }
}
