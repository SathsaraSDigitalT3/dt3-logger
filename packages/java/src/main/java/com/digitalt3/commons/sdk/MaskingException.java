package com.digitalt3.commons.sdk;

/**
 * Indicates that sensitive-data masking failed during SDK event enrichment.
 *
 * <p>This exception provides a typed boundary for masking failures so callers
 * can classify them without inspecting exception messages.</p>
 *
 * @since 0.1.0
 */
public final class MaskingException extends RuntimeException {

    /**
     * Creates a masking failure with a safe diagnostic message.
     *
     * @param message safe description of the masking failure
     */
    public MaskingException(String message) {
        super(message);
    }

    /**
     * Creates a masking failure with its underlying cause.
     *
     * @param message safe description of the masking failure
     * @param cause underlying masking failure
     */
    public MaskingException(String message, Throwable cause) {
        super(message, cause);
    }
}
