package com.digitalt3.commons.api.events;

import com.digitalt3.commons.api.LogEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed builders for messaging and worker domain events.
 *
 * @since 0.1.0
 */
public final class MessagingEvents {

    private MessagingEvents() {
    }

    public static LogEvent jobReceived(String system, String destination, String messageId) {
        return build("WORKER_JOB_RECEIVED", system, destination, "receive", messageId, null, null);
    }

    public static LogEvent jobStarted(String system, String destination, String messageId) {
        return build("WORKER_JOB_STARTED", system, destination, "process", messageId, null, null);
    }

    public static LogEvent jobCompleted(
        String system,
        String destination,
        String messageId,
        Double durationMs
    ) {
        return build("WORKER_JOB_COMPLETED", system, destination, "process", messageId, durationMs, null);
    }

    public static LogEvent jobFailed(
        String system,
        String destination,
        String messageId,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        return build("WORKER_JOB_FAILED", system, destination, "process", messageId, durationMs, attributes);
    }

    public static LogEvent jobRetried(String system, String destination, String messageId) {
        return build("WORKER_JOB_RETRIED", system, destination, "process", messageId, null, null);
    }

    public static LogEvent publish(String system, String destination, String messageId) {
        return build("MESSAGING_PUBLISH", system, destination, "publish", messageId, null, null);
    }

    public static LogEvent receive(String system, String destination, String messageId) {
        return build("MESSAGING_RECEIVE", system, destination, "receive", messageId, null, null);
    }

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
        String system,
        String destination,
        String operation,
        String messageId,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (system != null) {
            attrs.put("messaging.system", system);
        }
        if (destination != null) {
            attrs.put("messaging.destination", destination);
        }
        if (operation != null) {
            attrs.put("messaging.operation", operation);
        }
        if (messageId != null) {
            attrs.put("messaging.message.id", messageId);
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
