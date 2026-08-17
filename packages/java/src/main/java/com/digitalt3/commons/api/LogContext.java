package com.digitalt3.commons.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Execution-scoped trace and correlation metadata for DT3 log events.
 *
 * <p>Use {@link #open()} in a try-with-resources block to attach the context
 * to all events created by the current thread until the scope exits. Nested
 * scopes inherit unspecified values and restore their parent scope on close.</p>
 *
 * @since 0.1.0
 */
public final class LogContext {

    private static final ThreadLocal<Map<String, Object>> ACTIVE_CONTEXT =
        ThreadLocal.withInitial(Map::of);

    private final Map<String, Object> values;

    private LogContext(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    // PUBLIC_INTERFACE
    /**
     * Create a builder for execution-scoped canonical logging context.
     *
     * @return a new context builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // PUBLIC_INTERFACE
    /**
     * Activate this context for the current thread.
     *
     * <p>The returned scope must be closed, preferably through
     * try-with-resources, to restore the prior context even when work throws an
     * exception. Context is thread-local and is intentionally not propagated to
     * executor tasks or other threads automatically.</p>
     *
     * @return a scope that restores the previously active context when closed
     */
    public Scope open() {
        Map<String, Object> previousContext = ACTIVE_CONTEXT.get();
        Map<String, Object> mergedContext = new LinkedHashMap<>(previousContext);
        mergedContext.putAll(values);
        ACTIVE_CONTEXT.set(Collections.unmodifiableMap(mergedContext));
        return new Scope(previousContext);
    }

    /**
     * Return a copy of the current thread's active canonical event context.
     *
     * <p>This pipeline helper never exposes the mutable ThreadLocal value.</p>
     *
     * @return active canonical context, or an empty map when no scope is active
     */
    public static Map<String, Object> activeValues() {
        return new LinkedHashMap<>(ACTIVE_CONTEXT.get());
    }

    /**
     * A scope which restores the preceding active context when closed.
     */
    public static final class Scope implements AutoCloseable {
        private final Map<String, Object> previousContext;
        private boolean closed;

        private Scope(Map<String, Object> previousContext) {
            this.previousContext = previousContext;
        }

        // PUBLIC_INTERFACE
        /**
         * Restore the context that was active before this scope opened.
         *
         * <p>This operation is idempotent.</p>
         */
        @Override
        public void close() {
            if (closed) {
                return;
            }

            closed = true;
            if (previousContext.isEmpty()) {
                ACTIVE_CONTEXT.remove();
            } else {
                ACTIVE_CONTEXT.set(previousContext);
            }
        }
    }

    /**
     * Builder for canonical trace and correlation event fields.
     */
    public static final class Builder {
        private final Map<String, Object> values = new LinkedHashMap<>();

        private Builder() {
            // Construct through LogContext.builder().
        }

        // PUBLIC_INTERFACE
        /**
         * Set the canonical {@code trace.id} field.
         *
         * @param traceId trace identifier, or {@code null} to omit it
         * @return this builder
         */
        public Builder traceId(String traceId) {
            return putIfPresent("trace.id", traceId);
        }

        // PUBLIC_INTERFACE
        /**
         * Set the canonical {@code span.id} field.
         *
         * @param spanId span identifier, or {@code null} to omit it
         * @return this builder
         */
        public Builder spanId(String spanId) {
            return putIfPresent("span.id", spanId);
        }

        // PUBLIC_INTERFACE
        /**
         * Set the canonical {@code parent.span.id} field.
         *
         * @param parentSpanId parent span identifier, or {@code null} to omit it
         * @return this builder
         */
        public Builder parentSpanId(String parentSpanId) {
            return putIfPresent("parent.span.id", parentSpanId);
        }

        // PUBLIC_INTERFACE
        /**
         * Set the canonical {@code correlation.id} field.
         *
         * @param correlationId correlation identifier, or {@code null} to omit it
         * @return this builder
         */
        public Builder correlationId(String correlationId) {
            return putIfPresent("correlation.id", correlationId);
        }

        // PUBLIC_INTERFACE
        /**
         * Build the immutable context definition.
         *
         * @return a context that may be activated for the current execution scope
         */
        public LogContext build() {
            return new LogContext(values);
        }

        private Builder putIfPresent(String key, String value) {
            Objects.requireNonNull(key, "context key must not be null");
            if (value != null) {
                values.put(key, value);
            }
            return this;
        }
    }
}
