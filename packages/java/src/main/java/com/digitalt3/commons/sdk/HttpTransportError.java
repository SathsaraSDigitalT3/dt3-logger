package com.digitalt3.commons.sdk;

/**
 * Raised when a synchronous HTTP event export cannot complete successfully.
 *
 * <p>Messages intentionally contain only transport metadata and never event
 * payloads, response bodies, or exception details that might expose log data.</p>
 *
 * @since 0.1.0
 */
public final class HttpTransportError extends RuntimeException {

    /**
     * Create a sanitized HTTP transport failure.
     *
     * @param message safe description of the transport failure
     * @param cause underlying transport exception, when available
     */
    public HttpTransportError(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Create a sanitized HTTP transport failure without an underlying cause.
     *
     * @param message safe description of the transport failure
     */
    public HttpTransportError(String message) {
        super(message);
    }
}
