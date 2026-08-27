package com.digitalt3.commons.api;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Lightweight tracer that creates W3C-compatible spans via scoped {@link LogContext}.
 *
 * @since 0.1.0
 */
public interface Tracer {

    /**
     * Start a new span as a child of the currently active span when present.
     *
     * @param name span name used for completion events
     * @return an open span that must be ended or closed
     */
    Span startSpan(String name);

    /**
     * Start a new span with optional attributes applied to the span context.
     *
     * @param name span name used for completion events
     * @param attributes optional attributes attached to span completion events
     * @return an open span that must be ended or closed
     */
    Span startSpan(String name, Map<String, Object> attributes);

    /**
     * Run work under an active span and end the span when the callback returns.
     *
     * @param name span name
     * @param work callback receiving the active span
     * @param <T> result type
     * @return the callback result
     */
    <T> T withSpan(String name, Function<Span, T> work);

    /**
     * Run work under an active span and end the span when the callback returns.
     *
     * @param name span name
     * @param work callback receiving the active span
     */
    void withSpan(String name, Consumer<Span> work);
}
