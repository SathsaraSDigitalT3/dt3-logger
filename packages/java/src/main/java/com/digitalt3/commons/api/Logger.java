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

    // PUBLIC_INTERFACE
    /**
     * Activate trace and correlation metadata for logs created on the current thread.
     *
     * <p>Use the returned scope with try-with-resources. Nested scopes inherit
     * unspecified values and restore the preceding scope when closed. Explicit
     * context supplied to individual logging calls overrides active scoped
     * values, while logger-owned fields retain their precedence.</p>
     *
     * @param context canonical execution-scoped context to activate
     * @return a scope that restores the previous context when closed
     */
    default LogContext.Scope withContext(LogContext context) {
        return java.util.Objects.requireNonNull(context, "context must not be null").open();
    }

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
