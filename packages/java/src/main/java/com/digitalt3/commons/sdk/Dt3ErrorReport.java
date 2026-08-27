package com.digitalt3.commons.sdk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable sanitized description of an SDK-internal handled failure.
 *
 * @since 0.1.0
 */
public final class Dt3ErrorReport {
    private final Dt3ErrorCode code;
    private final Dt3ErrorPhase phase;
    private final String message;
    private final boolean retryable;
    private final String errorType;
    private final long occurrences;
    private final boolean includePhaseInFields;

    Dt3ErrorReport(
        Dt3ErrorCode code,
        Dt3ErrorPhase phase,
        String message,
        boolean retryable,
        String errorType,
        long occurrences
    ) {
        this(code, phase, message, retryable, errorType, occurrences, false);
    }

    Dt3ErrorReport(
        Dt3ErrorCode code,
        Dt3ErrorPhase phase,
        String message,
        boolean retryable,
        String errorType,
        long occurrences,
        boolean includePhaseInFields
    ) {
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.message = message == null ? "" : message;
        this.retryable = retryable;
        this.errorType = Objects.requireNonNull(errorType, "errorType must not be null");
        this.occurrences = occurrences;
        this.includePhaseInFields = includePhaseInFields;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the stable error classification.
     *
     * @return canonical SDK error code
     */
    public Dt3ErrorCode getCode() {
        return code;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the SDK pipeline phase in which the error occurred.
     *
     * @return failure phase
     */
    public Dt3ErrorPhase getPhase() {
        return phase;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the sanitized error message.
     *
     * @return safe diagnostic message
     */
    public String getMessage() {
        return message;
    }

    // PUBLIC_INTERFACE
    /**
     * Return whether retrying the failed operation can reasonably succeed.
     *
     * @return {@code true} for transient failures
     */
    public boolean isRetryable() {
        return retryable;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the cumulative number of handled failures with this code.
     *
     * @return one-based cumulative error occurrence count
     */
    public long getOccurrences() {
        return occurrences;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the handled exception's runtime type name.
     *
     * @return sanitized exception type name
     */
    public String getErrorType() {
        return errorType;
    }

    // PUBLIC_INTERFACE
    /**
     * Convert the report to canonical error event fields.
     *
     * @return stable error fields suitable for a structured event
     */
    public Map<String, Object> toFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("error.type", errorType);
        fields.put("error.code", code.getValue());
        fields.put("error.retryable", retryable);
        if (includePhaseInFields) {
            fields.put("dt3.error.phase", phase.getValue());
        }
        return Map.copyOf(fields);
    }
}
