package com.digitalt3.commons.api;

import com.digitalt3.commons.sdk.StdoutLogger;

import java.util.Objects;

/**
 * Creates the supported DT3 Commons Java logger implementation.
 *
 * <p>The SDK supports synchronous structured JSON export to stdout,
 * append-only JSON Lines export to a configured file, synchronous HTTP POST
 * export to a configured endpoint, and synchronous OTLP/HTTP JSON Logs
 * export to a configured OTLP endpoint.</p>
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
     * @param config service metadata, masking configuration, and stdout, file, HTTP, or OTLP exporter settings
     * @return a synchronous structured logger for the configured exporter
     */
    public static Logger createLogger(SdkConfig config) {
        return new StdoutLogger(Objects.requireNonNull(config, "config must not be null"));
    }
}
