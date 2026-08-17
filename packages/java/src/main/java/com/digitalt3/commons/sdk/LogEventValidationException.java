package com.digitalt3.commons.sdk;

/**
 * Raised when STRICT validation rejects a masked structured log event.
 *
 * <p>This exception is intentionally distinct from transport failures so
 * {@code fail_open} cannot convert a validation rejection into a successful
 * logging operation.</p>
 *
 * @since 0.1.0
 */
public final class LogEventValidationException extends IllegalArgumentException {

    /**
     * Create a sanitized strict-validation exception.
     *
     * @param message sanitized validation diagnostic summary
     */
    public LogEventValidationException(String message) {
        super(message);
    }
}
