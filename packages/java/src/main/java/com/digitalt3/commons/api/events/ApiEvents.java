package com.digitalt3.commons.api.events;

import com.digitalt3.commons.api.LogEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed builders for API/HTTP domain events.
 *
 * @since 0.1.0
 */
public final class ApiEvents {

    private ApiEvents() {
    }

    /**
     * Build an {@code INCOMING_HTTP} event.
     *
     * @param method HTTP method
     * @param route matched route template
     * @param statusCode response status code
     * @param durationMs request duration in milliseconds
     * @return canonical log event
     */
    public static LogEvent incomingHttp(
        String method,
        String route,
        Integer statusCode,
        Double durationMs
    ) {
        return incomingHttp(method, route, statusCode, durationMs, null);
    }

    /**
     * Build an {@code INCOMING_HTTP} event with additional attributes.
     *
     * @param method HTTP method
     * @param route matched route template
     * @param statusCode response status code
     * @param durationMs request duration in milliseconds
     * @param attributes optional additional attributes
     * @return canonical log event
     */
    public static LogEvent incomingHttp(
        String method,
        String route,
        Integer statusCode,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        return build("INCOMING_HTTP", method, route, null, statusCode, durationMs, attributes);
    }

    /**
     * Build an {@code OUTGOING_HTTP} event.
     *
     * @param method HTTP method
     * @param target request target
     * @param statusCode response status code
     * @param durationMs request duration in milliseconds
     * @return canonical log event
     */
    public static LogEvent outgoingHttp(
        String method,
        String target,
        Integer statusCode,
        Double durationMs
    ) {
        return outgoingHttp(method, target, statusCode, durationMs, null);
    }

    /**
     * Build an {@code OUTGOING_HTTP} event with additional attributes.
     *
     * @param method HTTP method
     * @param target request target
     * @param statusCode response status code
     * @param durationMs request duration in milliseconds
     * @param attributes optional additional attributes
     * @return canonical log event
     */
    public static LogEvent outgoingHttp(
        String method,
        String target,
        Integer statusCode,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        return build("OUTGOING_HTTP", method, null, target, statusCode, durationMs, attributes);
    }

    /**
     * Build a flat attribute map for an API event without constructing a {@link LogEvent}.
     *
     * @param eventName {@code INCOMING_HTTP} or {@code OUTGOING_HTTP}
     * @param fields well-known and additional fields
     * @return mutable attribute map including {@code event.name}
     */
    public static Map<String, Object> asMap(String eventName, Map<String, Object> fields) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event.name", eventName);
        if (fields != null) {
            map.putAll(fields);
        }
        return map;
    }

    private static LogEvent build(
        String eventName,
        String method,
        String route,
        String target,
        Integer statusCode,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (method != null) {
            attrs.put("http.request.method", method);
        }
        if (route != null) {
            attrs.put("http.route", route);
        }
        if (target != null) {
            attrs.put("http.target", target);
        }
        if (statusCode != null) {
            attrs.put("http.response.status_code", statusCode);
        }
        if (attributes != null) {
            attrs.putAll(attributes);
        }
        return LogEvent.builder()
            .severity("INFO")
            .message(eventName)
            .eventName(eventName)
            .durationMs(durationMs)
            .attributes(attrs)
            .build();
    }
}
