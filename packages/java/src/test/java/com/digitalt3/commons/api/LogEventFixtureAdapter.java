package com.digitalt3.commons.api;

import java.util.Map;

/**
 * Test-only adapter for translating untyped shared JSON fixtures into typed
 * {@link LogEvent} instances while preserving malformed values for validation.
 */
final class LogEventFixtureAdapter {
    private LogEventFixtureAdapter() {
    }

    static LogEvent fromFixture(Map<String, Object> event) {
        LogEvent.Builder builder = LogEvent.builder()
            .timestamp(stringValue(event, "timestamp"))
            .severity(stringValue(event, "severity"))
            .message(stringValue(event, "message"))
            .eventName(stringValue(event, "event.name"))
            .schemaVersion(stringValue(event, "schema.version"))
            .sdkName(stringValue(event, "sdk.name"))
            .sdkVersion(stringValue(event, "sdk.version"))
            .serviceName(stringValue(event, "service.name"))
            .serviceVersion(stringValue(event, "service.version"))
            .deploymentEnvironment(stringValue(event, "deployment.environment"));

        if (event.containsKey("duration.ms")) {
            builder.rawDurationMs(event.get("duration.ms"));
        }
        if (event.containsKey("error.retryable")) {
            builder.rawErrorRetryable(event.get("error.retryable"));
        }
        if (event.containsKey("attributes")) {
            builder.rawAttributes(event.get("attributes"));
        }

        return builder.build();
    }

    private static String stringValue(Map<String, Object> event, String key) {
        Object value = event.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
