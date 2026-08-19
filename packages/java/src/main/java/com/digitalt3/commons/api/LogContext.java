package com.digitalt3.commons.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Execution-scoped trace, correlation, and tenant metadata for DT3 log events.
 *
 * <p>Use {@link #open()} in a try-with-resources block to attach the context
 * to events created by the current thread. Context is thread-local; use
 * {@link #wrap(Runnable)} or {@link #wrap(java.util.concurrent.Callable)} to
 * explicitly transfer a snapshot into executor work.</p>
 *
 * @since 0.1.0
 */
public final class LogContext {

    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";
    private static final String CORRELATION_ID_HEADER = "x-correlation-id";
    private static final String TENANT_ID_HEADER = "x-tenant-id";
    private static final String TENANT_REGION_HEADER = "x-tenant-region";
    private static final String TENANT_ENVIRONMENT_HEADER = "x-tenant-environment";

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
     * Create an immutable context from canonical event fields.
     *
     * @param values canonical context fields
     * @return an immutable context definition
     */
    public static LogContext of(Map<String, ?> values) {
        Objects.requireNonNull(values, "context values must not be null");
        Map<String, Object> copiedValues = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copiedValues.put(entry.getKey(), entry.getValue());
            }
        }
        return new LogContext(copiedValues);
    }

    // PUBLIC_INTERFACE
    /**
     * Return a snapshot of the context's canonical values.
     *
     * @return a defensive copy of this context's values
     */
    public Map<String, Object> values() {
        return new LinkedHashMap<>(values);
    }

    // PUBLIC_INTERFACE
    /**
     * Activate this context for the current thread.
     *
     * <p>Nested scopes inherit unspecified fields and restore the prior context
     * when closed. Context is not automatically transferred to executor work.</p>
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

    // PUBLIC_INTERFACE
    /**
     * Inject this context into a mutable HTTP header map.
     *
     * @param headers header mapping updated in place
     */
    public void inject(Map<String, String> headers) {
        inject(values, headers);
    }

    // PUBLIC_INTERFACE
    /**
     * Inject canonical trace, correlation, and tenant fields into HTTP headers.
     *
     * @param context canonical context fields to serialize
     * @param headers header mapping updated in place
     */
    public static void inject(Map<String, ?> context, Map<String, String> headers) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(headers, "headers must not be null");

        Object traceId = context.get("trace.id");
        Object spanId = context.get("span.id");
        Object traceFlags = context.get("trace.flags");
        if (traceFlags == null) {
            traceFlags = "01";
        }
        if (traceId instanceof String trace
            && spanId instanceof String span
            && traceFlags instanceof String flags
            && isValidTraceId(trace)
            && isValidSpanId(span)
            && isTwoHexCharacters(flags)) {
            headers.put(TRACEPARENT, "00-" + trace.toLowerCase(Locale.ROOT)
                + "-" + span.toLowerCase(Locale.ROOT)
                + "-" + flags.toLowerCase(Locale.ROOT));
        }

        putHeaderIfText(context, headers, "tracestate", TRACESTATE);
        putHeaderIfText(context, headers, "correlation.id", CORRELATION_ID_HEADER);
        putHeaderIfText(context, headers, "tenant.id", TENANT_ID_HEADER);
        putHeaderIfText(context, headers, "tenant.region", TENANT_REGION_HEADER);
        putHeaderIfText(context, headers, "tenant.environment", TENANT_ENVIRONMENT_HEADER);
    }

    // PUBLIC_INTERFACE
    /**
     * Extract W3C trace, correlation, and tenant metadata from HTTP headers.
     *
     * <p>Header names are matched case-insensitively. Missing or malformed
     * traceparent values are ignored without discarding independently valid
     * correlation or tenant headers.</p>
     *
     * @param headers incoming HTTP headers
     * @return a context suitable for {@link #open()}
     */
    public static LogContext extract(Map<String, String> headers) {
        Objects.requireNonNull(headers, "headers must not be null");
        Map<String, Object> extracted = new LinkedHashMap<>();
        parseTraceparent(headerValue(headers, TRACEPARENT), extracted);

        String tracestate = headerValue(headers, TRACESTATE);
        if (tracestate != null) {
            extracted.put("tracestate", tracestate);
        }

        copyHeader(headers, extracted, CORRELATION_ID_HEADER, "correlation.id");
        copyHeader(headers, extracted, TENANT_ID_HEADER, "tenant.id");
        copyHeader(headers, extracted, TENANT_REGION_HEADER, "tenant.region");
        copyHeader(headers, extracted, TENANT_ENVIRONMENT_HEADER, "tenant.environment");
        return new LogContext(extracted);
    }

    // PUBLIC_INTERFACE
    /**
     * Return a copy of the current thread's active canonical event context.
     *
     * @return active canonical context, or an empty map when no scope is active
     */
    public static Map<String, Object> activeValues() {
        return new LinkedHashMap<>(ACTIVE_CONTEXT.get());
    }

    /**
     * Store a generated correlation ID in the active context only when an
     * active scope does not already provide one.
     *
     * <p>This lets logger-generated IDs identify the entire execution scope
     * while preserving explicit or extracted correlation IDs.</p>
     *
     * @param correlationId generated correlation identifier to persist
     * @return the effective correlation ID, or {@code null} when no scope is active
     */
    public static String establishCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            return null;
        }

        Map<String, Object> activeContext = ACTIVE_CONTEXT.get();
        if (activeContext.isEmpty()) {
            return null;
        }

        Object existing = activeContext.get("correlation.id");
        if (existing instanceof String existingText && !existingText.isBlank()) {
            return existingText;
        }

        Map<String, Object> updatedContext = new LinkedHashMap<>(activeContext);
        updatedContext.put("correlation.id", correlationId);
        ACTIVE_CONTEXT.set(Collections.unmodifiableMap(updatedContext));
        return correlationId;
    }

    // PUBLIC_INTERFACE
    /**
     * Wrap work so it runs with a snapshot of the current execution context.
     *
     * <p>The worker's previous context is restored after execution, preventing
     * context leakage when executor threads are reused.</p>
     *
     * @param work work to run with the captured context
     * @return a context-propagating runnable
     */
    public static Runnable wrap(Runnable work) {
        Objects.requireNonNull(work, "work must not be null");
        Map<String, Object> snapshot = activeValues();
        return () -> {
            try (Scope ignored = LogContext.of(snapshot).open()) {
                work.run();
            }
        };
    }

    // PUBLIC_INTERFACE
    /**
     * Wrap callable work so it runs with a snapshot of the current execution context.
     *
     * @param work work to run with the captured context
     * @param <T> callable result type
     * @return a context-propagating callable
     */
    public static <T> java.util.concurrent.Callable<T> wrap(java.util.concurrent.Callable<T> work) {
        Objects.requireNonNull(work, "work must not be null");
        Map<String, Object> snapshot = activeValues();
        return () -> {
            try (Scope ignored = LogContext.of(snapshot).open()) {
                return work.call();
            }
        };
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
     * Builder for canonical trace, correlation, and tenant event fields.
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
         * Set the W3C trace flags represented by two hexadecimal characters.
         *
         * @param traceFlags trace flags, or {@code null} to omit them
         * @return this builder
         */
        public Builder traceFlags(String traceFlags) {
            return putIfPresent("trace.flags", traceFlags);
        }

        // PUBLIC_INTERFACE
        /**
         * Set the W3C tracestate value.
         *
         * @param tracestate tracestate value, or {@code null} to omit it
         * @return this builder
         */
        public Builder tracestate(String tracestate) {
            return putIfPresent("tracestate", tracestate);
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
         * Set the canonical {@code tenant.id} field.
         *
         * @param tenantId tenant identifier, or {@code null} to omit it
         * @return this builder
         */
        public Builder tenantId(String tenantId) {
            return putIfPresent("tenant.id", tenantId);
        }

        // PUBLIC_INTERFACE
        /**
         * Set the canonical {@code tenant.region} field.
         *
         * @param tenantRegion tenant region, or {@code null} to omit it
         * @return this builder
         */
        public Builder tenantRegion(String tenantRegion) {
            return putIfPresent("tenant.region", tenantRegion);
        }

        // PUBLIC_INTERFACE
        /**
         * Set the canonical {@code tenant.environment} field.
         *
         * @param tenantEnvironment tenant environment, or {@code null} to omit it
         * @return this builder
         */
        public Builder tenantEnvironment(String tenantEnvironment) {
            return putIfPresent("tenant.environment", tenantEnvironment);
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

    private static void putHeaderIfText(
        Map<String, ?> context,
        Map<String, String> headers,
        String contextField,
        String headerName
    ) {
        Object value = context.get(contextField);
        if (value instanceof String text && !text.isBlank()) {
            headers.put(headerName, text);
        }
    }

    private static void parseTraceparent(String traceparent, Map<String, Object> values) {
        if (traceparent == null) {
            return;
        }

        String[] parts = traceparent.split("-", -1);
        if (parts.length != 4
            || !isTwoHexCharacters(parts[0])
            || !isValidTraceId(parts[1])
            || !isValidSpanId(parts[2])
            || !isTwoHexCharacters(parts[3])) {
            return;
        }

        values.put("trace.id", parts[1].toLowerCase(Locale.ROOT));
        values.put("span.id", parts[2].toLowerCase(Locale.ROOT));
        values.put("trace.flags", parts[3].toLowerCase(Locale.ROOT));
    }

    private static boolean isValidTraceId(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{32}") && !value.matches("0{32}");
    }

    private static boolean isValidSpanId(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{16}") && !value.matches("0{16}");
    }

    private static boolean isTwoHexCharacters(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{2}");
    }

    private static String headerValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null
                && entry.getKey().equalsIgnoreCase(name)
                && entry.getValue() != null
                && !entry.getValue().isBlank()) {
                return entry.getValue().trim();
            }
        }
        return null;
    }

    private static void copyHeader(
        Map<String, String> headers,
        Map<String, Object> values,
        String headerName,
        String contextField
    ) {
        String value = headerValue(headers, headerName);
        if (value != null) {
            values.put(contextField, value);
        }
    }
}
