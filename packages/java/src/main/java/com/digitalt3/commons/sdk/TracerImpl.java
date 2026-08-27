package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.Span;
import com.digitalt3.commons.api.Tracer;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Tracer implementation that activates W3C trace/span IDs via {@link LogContext}.
 *
 * @since 0.1.0
 */
public final class TracerImpl implements Tracer {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Logger logger;
    private final boolean spanEventsEnabled;

    /**
     * Create a tracer bound to a logger.
     *
     * @param logger logger used for span events
     * @param spanEventsEnabled whether ending a span emits a completion LogEvent
     */
    public TracerImpl(Logger logger, boolean spanEventsEnabled) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
        this.spanEventsEnabled = spanEventsEnabled;
    }

    @Override
    public Span startSpan(String name) {
        return startSpan(name, null);
    }

    @Override
    public Span startSpan(String name, Map<String, Object> attributes) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("span name must not be blank");
        }

        Map<String, Object> active = LogContext.activeValues();
        String parentSpanId = textOrNull(active.get("span.id"));
        String traceId = textOrNull(active.get("trace.id"));
        if (traceId == null) {
            traceId = generateTraceId();
        }
        String spanId = generateSpanId();

        LogContext.Builder contextBuilder = LogContext.builder()
            .traceId(traceId)
            .spanId(spanId);
        if (parentSpanId != null) {
            contextBuilder.parentSpanId(parentSpanId);
        }

        LogContext.Scope scope = contextBuilder.build().open();
        return new SpanImpl(
            name,
            traceId,
            spanId,
            parentSpanId,
            attributes == null ? Map.of() : new LinkedHashMap<>(attributes),
            scope
        );
    }

    @Override
    public <T> T withSpan(String name, Function<Span, T> work) {
        Objects.requireNonNull(work, "work must not be null");
        try (Span span = startSpan(name)) {
            return work.apply(span);
        }
    }

    @Override
    public void withSpan(String name, Consumer<Span> work) {
        Objects.requireNonNull(work, "work must not be null");
        try (Span span = startSpan(name)) {
            work.accept(span);
        }
    }

    static String generateTraceId() {
        return randomHex(32);
    }

    static String generateSpanId() {
        return randomHex(16);
    }

    private static String randomHex(int length) {
        byte[] bytes = new byte[length / 2];
        String hex;
        do {
            RANDOM.nextBytes(bytes);
            hex = toHex(bytes);
        } while (hex.chars().allMatch(ch -> ch == '0'));
        return hex;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value));
        }
        return builder.toString();
    }

    private static String textOrNull(Object value) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        return null;
    }

    private final class SpanImpl implements Span {
        private final String name;
        private final String traceId;
        private final String spanId;
        private final String parentSpanId;
        private final Map<String, Object> attributes;
        private final LogContext.Scope scope;
        private final long startedAtNanos;
        private boolean ended;

        private SpanImpl(
            String name,
            String traceId,
            String spanId,
            String parentSpanId,
            Map<String, Object> attributes,
            LogContext.Scope scope
        ) {
            this.name = name;
            this.traceId = traceId;
            this.spanId = spanId;
            this.parentSpanId = parentSpanId;
            this.attributes = attributes;
            this.scope = scope;
            this.startedAtNanos = System.nanoTime();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getTraceId() {
            return traceId;
        }

        @Override
        public String getSpanId() {
            return spanId;
        }

        @Override
        public String getParentSpanId() {
            return parentSpanId;
        }

        @Override
        public void addEvent(String eventName, Map<String, Object> eventAttributes) {
            Objects.requireNonNull(eventName, "eventName must not be null");
            Map<String, Object> context = new LinkedHashMap<>();
            if (eventAttributes != null) {
                context.putAll(eventAttributes);
            }
            context.put("event.name", toEventName(eventName));
            logger.info(eventName, context);
        }

        @Override
        public void end() {
            if (ended) {
                return;
            }
            ended = true;

            long durationMs = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
            try {
                if (spanEventsEnabled) {
                    Map<String, Object> context = new LinkedHashMap<>(attributes);
                    context.put("event.name", toEventName(name));
                    context.put("duration.ms", durationMs);
                    context.put("trace.id", traceId);
                    context.put("span.id", spanId);
                    if (parentSpanId != null) {
                        context.put("parent.span.id", parentSpanId);
                    }
                    logger.info(name, context);
                }
            } finally {
                scope.close();
            }
        }

        @Override
        public void close() {
            end();
        }

        private static String toEventName(String value) {
            String normalized = value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            if (normalized.isEmpty() || !normalized.matches("^[A-Z][A-Z0-9_]*$")) {
                return "SPAN_EVENT";
            }
            return normalized;
        }
    }
}
