package com.digitalt3.commons.sdk;

/**
 * Raised when a synchronous OTLP/HTTP export cannot complete successfully.
 *
 * <p>Messages contain only sanitized transport metadata. Classification data is
 * explicit so callers never need to parse messages or response bodies.</p>
 *
 * @since 0.1.0
 */
public final class OtlpTransportError extends Dt3SdkException {
    private final Integer statusCode;

    /**
     * Create a sanitized OTLP transport failure.
     *
     * @param message safe description of the transport failure
     * @param cause underlying transport exception, when available
     * @param code canonical transport classification
     * @param retryable whether delivery may succeed when retried
     */
    public OtlpTransportError(
        String message,
        Throwable cause,
        Dt3ErrorCode code,
        boolean retryable
    ) {
        this(message, cause, code, retryable, null);
    }

    /**
     * Create a sanitized OTLP response rejection.
     *
     * @param statusCode HTTP response status
     */
    public OtlpTransportError(int statusCode) {
        this(
            "OTLP export failed with status " + statusCode,
            null,
            Dt3ErrorCode.TRANSPORT_REJECTED,
            statusCode >= 500,
            statusCode
        );
    }

    /**
     * Retained source-compatible constructor for unavailable transport failures.
     *
     * @param message safe description of the transport failure
     * @param cause underlying transport exception, when available
     */
    public OtlpTransportError(String message, Throwable cause) {
        this(message, cause, Dt3ErrorCode.TRANSPORT_UNAVAILABLE, true);
    }

    /**
     * Retained source-compatible constructor for unavailable transport failures.
     *
     * @param message safe description of the transport failure
     */
    public OtlpTransportError(String message) {
        this(message, null, legacyCode(message), legacyRetryable(message));
    }

    private static Dt3ErrorCode legacyCode(String message) {
        return message != null && message.matches(".*status (4|5)\\d\\d.*")
            ? Dt3ErrorCode.TRANSPORT_REJECTED
            : Dt3ErrorCode.TRANSPORT_UNAVAILABLE;
    }

    private static boolean legacyRetryable(String message) {
        return message == null || !message.matches(".*status (4|5)\\d\\d.*");
    }

    private OtlpTransportError(
        String message,
        Throwable cause,
        Dt3ErrorCode code,
        boolean retryable,
        Integer statusCode
    ) {
        super(message, cause, code, retryable, Dt3ErrorPhase.DELIVERY);
        this.statusCode = statusCode;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the rejected OTLP HTTP response status when one was received.
     *
     * @return status code, or {@code null} when no response was available
     */
    public Integer getStatusCode() {
        return statusCode;
    }
}
