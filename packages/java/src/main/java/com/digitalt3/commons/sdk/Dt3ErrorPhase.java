package com.digitalt3.commons.sdk;

/**
 * Processing stages in which the DT3 Java SDK can handle a failure.
 *
 * @since 0.1.0
 */
public enum Dt3ErrorPhase {
    /** SDK construction or configuration parsing. */
    CONFIGURATION("configuration"),
    /** Event construction and contextual enrichment. */
    ENRICHMENT("enrichment"),
    /** Recursive sensitive-data masking. */
    MASKING("masking"),
    /** Canonical event validation. */
    VALIDATION("validation"),
    /** Batch capacity, scheduled flush, or explicit flush processing. */
    BATCHING("batching"),
    /** Event serialization or delivery through a configured exporter. */
    DELIVERY("delivery"),
    /** Logger, timer, or transport lifecycle operations. */
    LIFECYCLE("lifecycle");

    private final String value;

    Dt3ErrorPhase(String value) {
        this.value = value;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the canonical cross-SDK wire value for this error phase.
     *
     * @return lower-case phase wire value
     */
    public String getValue() {
        return value;
    }
}
