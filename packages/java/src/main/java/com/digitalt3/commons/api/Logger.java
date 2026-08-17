package com.digitalt3.commons.api;

import java.util.Map;

/**
 * Public structured logger contract with synchronous flush and lifecycle support.
 */
public interface Logger extends AutoCloseable {
    void debug(String message, Map<String, Object> context);
    void info(String message, Map<String, Object> context);
    void warn(String message, Map<String, Object> context);
    void error(String message, Throwable error, Map<String, Object> context);
    void flush();

    // PUBLIC_INTERFACE
    /**
     * Close the logger, release its configured transport, and prevent future use.
     *
     * <p>Implementations must make this operation idempotent.</p>
     */
    @Override
    void close();
}
