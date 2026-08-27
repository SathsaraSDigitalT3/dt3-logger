package com.digitalt3.commons.sdk;

/**
 * Stable classifications for failures handled by the DT3 Java SDK.
 *
 * @since 0.1.0
 */
public enum Dt3ErrorCode {
    /** Invalid SDK or exporter configuration. */
    CONFIGURATION_INVALID("DT3_CONFIG_INVALID"),
    /** An unsupported exporter was selected. */
    EXPORTER_UNSUPPORTED("DT3_EXPORTER_UNSUPPORTED"),
    /** A masked event failed canonical validation. */
    VALIDATION_FAILED("DT3_VALIDATION_FAILED"),
    /** Recursive event masking could not complete. */
    MASKING_FAILED("DT3_MASKING_FAILED"),
    /** Event JSON serialization could not complete. */
    SERIALIZATION_FAILED("DT3_SERIALIZATION_FAILED"),
    /** A transport request exceeded its configured timeout. */
    TRANSPORT_TIMEOUT("DT3_TRANSPORT_TIMEOUT"),
    /** A transport destination was unavailable. */
    TRANSPORT_UNAVAILABLE("DT3_TRANSPORT_UNAVAILABLE"),
    /** A transport returned a non-success response. */
    TRANSPORT_REJECTED("DT3_TRANSPORT_REJECTED"),
    /** A closed transport was used. */
    TRANSPORT_CLOSED("DT3_TRANSPORT_CLOSED"),
    /** File export could not create or append to its destination. */
    FILE_WRITE_FAILED("DT3_FILE_WRITE_FAILED"),
    /** A batch exceeded its configured capacity. */
    BATCH_OVERFLOW("DT3_BATCH_OVERFLOW"),
    /** A batch delivery was aborted or discarded. */
    BATCH_ABORTED("DT3_BATCH_ABORTED"),
    /** A logger, timer, or batcher was used after its lifecycle ended. */
    LIFECYCLE_CLOSED("DT3_LIFECYCLE_CLOSED"),
    /** A failure did not match a more specific SDK classification. */
    UNKNOWN("DT3_UNKNOWN");

    private final String value;

    Dt3ErrorCode(String value) {
        this.value = value;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the stable cross-SDK wire value for this error code.
     *
     * @return canonical {@code DT3_*} error code value
     */
    public String getValue() {
        return value;
    }
}
