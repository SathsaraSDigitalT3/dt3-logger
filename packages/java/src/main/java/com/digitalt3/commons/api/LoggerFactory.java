package com.digitalt3.commons.api;

import com.digitalt3.commons.sdk.StdoutLogger;

import java.util.Objects;

/**
 * Creates the supported DT3 Commons Java logger implementation.
 *
 * <p>The current SDK baseline supports synchronous structured JSON export to
 * stdout only. Other exporters, validation modes, and batching are intentionally
 * not implemented in this phase.</p>
 *
 * @since 0.1.0
 */
public final class LoggerFactory {

    private LoggerFactory() {
        // Factory class; no instances.
    }

    // PUBLIC_INTERFACE
    /**
     * Create a logger using the supplied SDK configuration.
     *
     * @param config service metadata and supported masking configuration
     * @return a synchronous structured stdout logger
     */
    public static Logger createLogger(SdkConfig config) {
        return new StdoutLogger(Objects.requireNonNull(config, "config must not be null"));
    }
}
