package com.digitalt3.commons.sdk;

/**
 * Raised when a synchronous HTTP event export cannot complete successfully.
 *
 * <p>Messages contain only sanitized transport metadata. Classification data is
 * explicit so callers never need to parse messages or response bodies.</p>
 *
 * @since 0.1.0
 */
public final class HttpTransportError extends Dt3SdkException {
    private final Integer statusCode;

    /**
     * Create a sanitized HTTP transport failure.
     *
     * @param message safe description of the transport failure
     * @param cause underlying transport exception, when available
     * @param code canonical transport classification
     * @param retryable whether delivery may succeed when retried
     */
    public HttpTransportError(
        String message,
        Throwable cause,
        Dt3ErrorCode code,
        boolean retryable
    ) {
        this(message, cause, code, retryable, null);
    }

    /**
     * Create a sanitized HTTP response rejection.
     *
     * @param statusCode HTTP response status
     */
    public HttpTransportError(int statusCode) {
        this(
            "HTTP export failed with status " + statusCode,
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
    public HttpTransportError(String message, Throwable cause) {
        this(message, cause, Dt3ErrorCode.TRANSPORT_UNAVAILABLE, true);
    }

    /**
     * Retained source-compatible constructor for unavailable transport failures.
     *
     * @param message safe description of the transport failure
     */
    public HttpTransportError(String message) {
        this(message, null, legacyCode(message), true);
    }

    private static Dt3ErrorCode legacyCode(String message) {
        return message != null && message.toLowerCase().contains("timed out")
            ? Dt3ErrorCode.TRANSPORT_TIMEOUT
            : Dt3ErrorCode.TRANSPORT_UNAVAILABLE;
    }

    private HttpTransportError(
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
     * Return the rejected HTTP response status when one was received.
     *
     * @return status code, or {@code null} when no response was available
     */
    public Integer getStatusCode() {
        return statusCode;
    }
}
