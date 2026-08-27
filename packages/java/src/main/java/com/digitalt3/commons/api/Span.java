package com.digitalt3.commons.api;

import java.util.Map;

/**
 * A W3C-compatible span activated through {@link LogContext}.
 *
 * @since 0.1.0
 */
public interface Span extends AutoCloseable {

    /**
     * Return the span name.
     *
     * @return span name
     */
    String getName();

    /**
     * Return the 32-character lowercase hex trace identifier.
     *
     * @return W3C trace ID
     */
    String getTraceId();

    /**
     * Return the 16-character lowercase hex span identifier.
     *
     * @return W3C span ID
     */
    String getSpanId();

    /**
     * Return the parent span identifier when this span is nested.
     *
     * @return parent span ID, or {@code null} for a root span
     */
    String getParentSpanId();

    /**
     * Attach a named event under the active span context via the owning logger.
     *
     * @param name event name
     * @param attributes optional event attributes
     */
    void addEvent(String name, Map<String, Object> attributes);

    /**
     * End this span, restore prior context, and optionally emit a completion event.
     */
    void end();

    /**
     * End this span. Idempotent.
     */
    @Override
    void close();
}
