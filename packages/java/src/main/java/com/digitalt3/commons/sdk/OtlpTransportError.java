package com.digitalt3.commons.sdk;

/**
 * Raised when a synchronous OTLP/HTTP export cannot complete successfully.
 *
 * <p>Messages contain only safe transport metadata and never event payloads,
 * response bodies, or exception details that could expose logged data.</p>
 *
 * @since 0.1.0
 */
public final class OtlpTransportError extends RuntimeException {

    /**
     * Create a sanitized OTLP transport failure.
     *
     * @param message safe description of the transport failure
     * @param cause underlying transport exception, when available
     */
    public OtlpTransportError(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create a sanitized OTLP transport failure without an underlying cause.
     *
     * @param message safe description of the transport failure
     */
    public OtlpTransportError(String message) {
        super(message);
    }
}
