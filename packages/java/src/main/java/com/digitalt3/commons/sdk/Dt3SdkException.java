package com.digitalt3.commons.sdk;

import java.util.Objects;

/**
 * Typed DT3 SDK failure with canonical classification metadata.
 *
 * <p>SDK internals use this base type whenever the error code, retryability, or
 * originating pipeline phase is known at the throw site. This keeps centralized
 * error handling independent of human-readable exception messages.</p>
 *
 * @since 0.1.0
 */
public class Dt3SdkException extends RuntimeException {
    private final Dt3ErrorCode code;
    private final boolean retryable;
    private final Dt3ErrorPhase phase;

    /**
     * Create a typed SDK failure.
     *
     * @param message sanitized failure description
     * @param code stable error classification
     * @param retryable whether retrying can reasonably succeed
     * @param phase pipeline phase that raised the error
     */
    public Dt3SdkException(
        String message,
        Dt3ErrorCode code,
        boolean retryable,
        Dt3ErrorPhase phase
    ) {
        this(message, null, code, retryable, phase);
    }

    /**
     * Create a typed SDK failure with an underlying cause.
     *
     * @param message sanitized failure description
     * @param cause underlying failure, if available
     * @param code stable error classification
     * @param retryable whether retrying can reasonably succeed
     * @param phase pipeline phase that raised the error
     */
    public Dt3SdkException(
        String message,
        Throwable cause,
        Dt3ErrorCode code,
        boolean retryable,
        Dt3ErrorPhase phase
    ) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.retryable = retryable;
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
    }

    // PUBLIC_INTERFACE
    /**
     * Return this failure's canonical DT3 error code.
     *
     * @return stable error classification
     */
    public Dt3ErrorCode getCode() {
        return code;
    }

    // PUBLIC_INTERFACE
    /**
     * Return whether retrying the failed operation can reasonably succeed.
     *
     * @return {@code true} for transient errors
     */
    public boolean isRetryable() {
        return retryable;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the pipeline phase that raised this failure.
     *
     * @return failure phase
     */
    public Dt3ErrorPhase getPhase() {
        return phase;
    }
}
