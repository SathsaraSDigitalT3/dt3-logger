package com.digitalt3.commons.api.events;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.Logger;

import java.util.Map;
import java.util.Objects;

/**
 * Typed emitter that routes domain/AI builder outputs through {@link Logger#event(LogEvent)}.
 *
 * @since 0.1.0
 */
public final class EventEmitter {

    private final Logger logger;

    /**
     * Create an emitter bound to a logger.
     *
     * @param logger destination logger
     */
    public EventEmitter(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger must not be null");
    }

    /**
     * Emit a canonical log event through the logger pipeline.
     *
     * @param event event to emit
     */
    public void emit(LogEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        logger.event(event);
    }

    /**
     * Emit a domain attribute map as a canonical event.
     *
     * <p>The map should include at least {@code event.name}. Missing severity and
     * message default to {@code INFO} and the event name respectively.</p>
     *
     * @param fields canonical or domain fields
     */
    public void emit(Map<String, Object> fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        Object eventName = fields.get("event.name");
        String name = eventName instanceof String text ? text : "GENERIC_EVENT";
        Object severity = fields.get("severity");
        Object message = fields.get("message");

        LogEvent.Builder builder = LogEvent.builder()
            .severity(severity instanceof String text ? text : "INFO")
            .message(message instanceof String text ? text : name)
            .eventName(name)
            .attributes(new java.util.LinkedHashMap<>(fields));

        Object duration = fields.get("duration.ms");
        if (duration instanceof Number number) {
            builder.durationMs(number.doubleValue());
        }
        Object eventId = fields.get("event.id");
        if (eventId instanceof String text) {
            builder.eventId(text);
        }
        Object operationId = fields.get("operation.id");
        if (operationId instanceof String text) {
            builder.operationId(text);
        }
        Object componentName = fields.get("component.name");
        if (componentName instanceof String text) {
            builder.componentName(text);
        }
        Object correlationId = fields.get("correlation.id");
        if (correlationId instanceof String text) {
            builder.correlationId(text);
        }
        Object tenantId = fields.get("tenant.id");
        if (tenantId instanceof String text) {
            builder.tenantId(text);
        }

        logger.event(builder.build());
    }

    /**
     * Emit a typed AI request event.
     *
     * @param kaviaAttributes AI request attributes
     */
    public void emitAiRequest(Map<String, Object> kaviaAttributes) {
        emit(AiEvents.request(kaviaAttributes));
    }

    /**
     * Emit a typed AI response event.
     *
     * @param kaviaAttributes AI response attributes
     */
    public void emitAiResponse(Map<String, Object> kaviaAttributes) {
        emit(AiEvents.response(kaviaAttributes));
    }
}
