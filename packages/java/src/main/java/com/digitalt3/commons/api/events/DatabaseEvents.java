package com.digitalt3.commons.api.events;

import com.digitalt3.commons.api.LogEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed builders for database domain events.
 *
 * @since 0.1.0
 */
public final class DatabaseEvents {

    private DatabaseEvents() {
    }

    public static LogEvent queryStarted(String system, String operation, String database) {
        return queryStarted(system, operation, database, null, null);
    }

    public static LogEvent queryStarted(
        String system,
        String operation,
        String database,
        String table,
        Map<String, Object> attributes
    ) {
        return build("DB_QUERY_STARTED", system, operation, database, table, null, attributes);
    }

    public static LogEvent queryCompleted(
        String system,
        String operation,
        String database,
        Double durationMs
    ) {
        return queryCompleted(system, operation, database, null, durationMs, null);
    }

    public static LogEvent queryCompleted(
        String system,
        String operation,
        String database,
        String table,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        return build("DB_QUERY_COMPLETED", system, operation, database, table, durationMs, attributes);
    }

    public static LogEvent queryFailed(
        String system,
        String operation,
        String database,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        return build("DB_QUERY_FAILED", system, operation, database, null, durationMs, attributes);
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
        String operation,
        String database,
        String table,
        Double durationMs,
        Map<String, Object> attributes
    ) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (system != null) {
            attrs.put("db.system", system);
        }
        if (operation != null) {
            attrs.put("db.operation", operation);
        }
        if (database != null) {
            attrs.put("db.name", database);
        }
        if (table != null) {
            attrs.put("db.sql.table", table);
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
