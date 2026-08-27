package com.digitalt3.commons.api.events;

import com.digitalt3.commons.api.LogEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Messaging wrapper around a canonical {@link LogEvent} using the event-envelope shape.
 *
 * @since 0.1.0
 */
public final class EventEnvelope {

    private EventEnvelope() {
    }

    /**
     * Wrap a canonical log event as an async messaging envelope.
     *
     * @param event canonical log event
     * @return envelope map with snake_case keys per {@code schemas/event-envelope.schema.json}
     */
    public static Map<String, Object> wrap(LogEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Map<String, Object> payload = event.toMap();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(
            "event_type",
            event.getEventName() != null ? event.getEventName() : payload.get("event.name")
        );
        envelope.put(
            "event_version",
            event.getSchemaVersion() != null ? event.getSchemaVersion() : "1.1.0"
        );
        envelope.put(
            "timestamp",
            event.getTimestamp() != null ? event.getTimestamp() : Instant.now().toString()
        );
        if (event.getServiceName() != null) {
            envelope.put("source", event.getServiceName());
        }
        if (event.getCorrelationId() != null) {
            envelope.put("correlation_id", event.getCorrelationId());
        }
        if (event.getTenantId() != null) {
            envelope.put("tenant_id", event.getTenantId());
        }
        envelope.put("payload", payload);
        return envelope;
    }
}
