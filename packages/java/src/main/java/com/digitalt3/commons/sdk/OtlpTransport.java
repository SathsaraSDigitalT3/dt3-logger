package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.LogTransport;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Synchronously exports final DT3 structured events through OTLP/HTTP JSON.
 *
 * <p>This transport accepts events after logger masking and validation. It sends
 * a standards-shaped OTLP Logs request, accepts the complete 2xx status range,
 * and reports only sanitized transport errors.</p>
 *
 * @since 0.1.0
 */
public final class OtlpTransport implements LogTransport {

    private static final String OTLP_JSON_CONTENT_TYPE = "application/json";
    private static final long DEFAULT_TIMEOUT_MILLIS = 10_000L;

    private final URI endpoint;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final HttpClient client;

    /**
     * Create a synchronous OTLP/HTTP JSON transport.
     *
     * @param endpoint OTLP Logs HTTP or HTTPS endpoint, commonly ending in {@code /v1/logs}
     * @param timeoutMillis request timeout in milliseconds
     * @param headers optional request headers
     * @throws IllegalArgumentException if configuration is invalid
     */
    public OtlpTransport(String endpoint, long timeoutMillis, Map<String, String> headers) {
        this.endpoint = parseEndpoint(endpoint);
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("otlp.timeout must be greater than zero");
        }

        this.timeout = Duration.ofMillis(timeoutMillis);
        this.headers = validateHeaders(headers);
        this.client = HttpClient.newBuilder()
            .connectTimeout(this.timeout)
            .build();
    }

    /**
     * Create a synchronous OTLP/HTTP JSON transport with the canonical ten-second timeout.
     *
     * @param endpoint OTLP Logs HTTP or HTTPS endpoint
     * @param headers optional request headers
     */
    public OtlpTransport(String endpoint, Map<String, String> headers) {
        this(endpoint, DEFAULT_TIMEOUT_MILLIS, headers);
    }

    // PUBLIC_INTERFACE
    /**
     * Map and synchronously export one final canonical structured event.
     *
     * @param logEvent already-masked and validation-processed DT3 event
     * @throws OtlpTransportError when delivery fails, times out, or receives a non-2xx response
     */
    @Override
    public synchronized void write(LogEvent logEvent) {
        Objects.requireNonNull(logEvent, "logEvent must not be null");
        writeEventMap(logEvent.toMap());
    }

    /**
     * Map and synchronously export a final event map created by the logger pipeline.
     *
     * @param finalEvent already-masked and validation-processed canonical event
     * @throws OtlpTransportError when delivery fails, times out, or receives a non-2xx response
     */
    synchronized void writeEventMap(Map<String, Object> finalEvent) {
        Objects.requireNonNull(finalEvent, "finalEvent must not be null");
        String payload = StdoutLogger.toJson(toOtlpPayload(finalEvent));

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(timeout)
            .header("Content-Type", OTLP_JSON_CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(payload));

        for (Map.Entry<String, String> header : headers.entrySet()) {
            if (!"content-type".equalsIgnoreCase(header.getKey())) {
                requestBuilder.header(header.getKey(), header.getValue());
            }
        }

        try {
            HttpResponse<Void> response = client.send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.discarding()
            );
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new OtlpTransportError(status);
            }
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new OtlpTransportError(
                "OTLP export request timed out",
                exception,
                Dt3ErrorCode.TRANSPORT_TIMEOUT,
                true
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OtlpTransportError(
                "OTLP export request interrupted",
                exception,
                Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
                true
            );
        } catch (IOException exception) {
            throw new OtlpTransportError(
                "OTLP export request failed",
                exception,
                Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
                true
            );
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Flush the synchronous OTLP transport.
     *
     * <p>Every request completes before {@link #write(LogEvent)} returns, so
     * no buffered events remain to flush.</p>
     */
    @Override
    public void flush() {
        // No-op: HttpClient.send completes synchronously.
    }

    // PUBLIC_INTERFACE
    /**
     * Shut down this OTLP transport.
     *
     * <p>The Java 17 HTTP client exposes no explicit shutdown operation.</p>
     */
    @Override
    public void shutdown() {
        // No-op: HttpClient has no explicit shutdown operation in Java 17.
    }

    /**
     * Map a final DT3 event to one OTLP Logs JSON export request.
     *
     * @param event final canonical DT3 event
     * @return an OTLP/HTTP JSON payload containing one log record
     */
    static Map<String, Object> toOtlpPayload(Map<String, Object> event) {
        Map<String, Object> eventData = new LinkedHashMap<>(event);
        String severityText = String.valueOf(eventData.getOrDefault("severity", "INFO")).toUpperCase();
        Map<String, Object> logRecord = new LinkedHashMap<>();
        logRecord.put("timeUnixNano", String.valueOf(timestampToNanoseconds(eventData.get("timestamp"))));
        logRecord.put("severityNumber", severityNumber(severityText));
        logRecord.put("severityText", severityText);
        logRecord.put("body", Map.of("stringValue", String.valueOf(eventData.getOrDefault("message", ""))));

        List<Map<String, Object>> logAttributes = logAttributes(eventData);
        if (!logAttributes.isEmpty()) {
            logRecord.put("attributes", logAttributes);
        }

        List<Map<String, Object>> scopeAttributes = new ArrayList<>();
        if (eventData.containsKey("sdk.name")) {
            scopeAttributes.add(attribute("dt3.sdk.name", eventData.get("sdk.name")));
        }
        if (eventData.containsKey("sdk.version")) {
            scopeAttributes.add(attribute("dt3.sdk.version", eventData.get("sdk.version")));
        }

        Map<String, Object> scopeLog = new LinkedHashMap<>();
        scopeLog.put("logRecords", List.of(logRecord));
        if (!scopeAttributes.isEmpty()) {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("name", "dt3.logger");
            scope.put("attributes", scopeAttributes);
            scopeLog.put("scope", scope);
        }

        Map<String, Object> resourceLog = new LinkedHashMap<>();
        resourceLog.put("resource", Map.of("attributes", resourceAttributes(eventData)));
        resourceLog.put("scopeLogs", List.of(scopeLog));

        return Map.of("resourceLogs", List.of(resourceLog));
    }

    private static List<Map<String, Object>> resourceAttributes(Map<String, Object> event) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (String key : List.of(
            "service.name",
            "service.version",
            "deployment.environment",
            "tenant.id",
            "tenant.name"
        )) {
            if (event.containsKey(key)) {
                attributes.add(attribute(key, event.get(key)));
            }
        }
        return attributes;
    }

    private static List<Map<String, Object>> logAttributes(Map<String, Object> event) {
        List<Map<String, Object>> attributes = new ArrayList<>();
        for (Map.Entry<String, Object> entry : event.entrySet()) {
            if (!isOtlpReservedField(entry.getKey())) {
                attributes.add(attribute(entry.getKey(), entry.getValue()));
            }
        }
        return attributes;
    }

    private static boolean isOtlpReservedField(String key) {
        return List.of(
            "timestamp",
            "severity",
            "message",
            "service.name",
            "service.version",
            "deployment.environment",
            "tenant.id",
            "tenant.name",
            "sdk.name",
            "sdk.version"
        ).contains(key);
    }

    private static Map<String, Object> attribute(String key, Object value) {
        return Map.of("key", String.valueOf(key), "value", anyValue(value));
    }

    private static Map<String, Object> anyValue(Object value) {
        if (value instanceof Boolean booleanValue) {
            return Map.of("boolValue", booleanValue);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Map.of("intValue", String.valueOf(value));
        }
        if (value instanceof Number numberValue) {
            return Map.of("doubleValue", numberValue);
        }
        if (value instanceof String stringValue) {
            return Map.of("stringValue", stringValue);
        }
        if (value == null) {
            return Map.of("stringValue", "null");
        }
        if (value instanceof Map<?, ?> mapValue) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                values.add(attribute(String.valueOf(entry.getKey()), entry.getValue()));
            }
            return Map.of("kvlistValue", Map.of("values", values));
        }
        if (value instanceof Iterable<?> iterableValue) {
            List<Map<String, Object>> values = new ArrayList<>();
            for (Object item : iterableValue) {
                values.add(anyValue(item));
            }
            return Map.of("arrayValue", Map.of("values", values));
        }
        if (value.getClass().isArray()) {
            List<Map<String, Object>> values = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                values.add(anyValue(java.lang.reflect.Array.get(value, index)));
            }
            return Map.of("arrayValue", Map.of("values", values));
        }
        return Map.of("stringValue", String.valueOf(value));
    }

    private static int severityNumber(String severityText) {
        return switch (severityText) {
            case "TRACE" -> 1;
            case "DEBUG" -> 5;
            case "INFO" -> 9;
            case "WARN", "WARNING" -> 13;
            case "ERROR" -> 17;
            case "FATAL" -> 21;
            default -> 9;
        };
    }

    private static long timestampToNanoseconds(Object timestamp) {
        if (!(timestamp instanceof String timestampText)) {
            return Instant.now().toEpochMilli() * 1_000_000L;
        }

        try {
            Instant parsedTimestamp = Instant.parse(timestampText);
            return parsedTimestamp.getEpochSecond() * 1_000_000_000L + parsedTimestamp.getNano();
        } catch (DateTimeParseException exception) {
            return Instant.now().toEpochMilli() * 1_000_000L;
        }
    }

    private URI parseEndpoint(String configuredEndpoint) {
        if (configuredEndpoint == null || configuredEndpoint.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "otlp.endpoint must be configured for the OTLP exporter"
            );
        }

        try {
            URI parsed = new URI(configuredEndpoint);
            String scheme = parsed.getScheme();
            if (
                scheme == null
                || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || parsed.getHost() == null
            ) {
                throw new IllegalArgumentException(
                    "otlp.endpoint must be a valid HTTP or HTTPS URL"
                );
            }
            return parsed;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException(
                "otlp.endpoint must be a valid HTTP or HTTPS URL",
                exception
            );
        }
    }

    private Map<String, String> validateHeaders(Map<String, String> configuredHeaders) {
        Map<String, String> validated = new LinkedHashMap<>();
        if (configuredHeaders == null) {
            return validated;
        }

        for (Map.Entry<String, String> header : configuredHeaders.entrySet()) {
            String name = header.getKey();
            String value = header.getValue();
            if (
                name == null
                || name.isBlank()
                || value == null
                || name.indexOf('\r') >= 0
                || name.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.indexOf('\n') >= 0
            ) {
                throw new IllegalArgumentException(
                    "otlp.headers must be a mapping of non-blank string header names to string values"
                );
            }
            validated.put(name, value);
        }

        return Map.copyOf(validated);
    }
}
