package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.LogTransport;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Kafka REST Proxy and Azure Event Hubs HTTPS transports.
 *
 * @since 0.1.0
 */
public final class KafkaTransport implements LogTransport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI endpoint;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final HttpClient client;
    private final Mode mode;

    public enum Mode {
        KAFKA_REST,
        EVENT_HUB
    }

    public static KafkaTransport kafkaRest(
        String topic,
        String restEndpoint,
        long timeoutMillis,
        Map<String, String> headers
    ) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException(
                "exporter.kafka.topic must be configured for the kafka exporter"
            );
        }
        if (restEndpoint == null || restEndpoint.isBlank()) {
            throw new IllegalArgumentException(
                "exporter.kafka.rest_endpoint must be configured for the kafka exporter"
            );
        }
        String base = restEndpoint.trim().replaceAll("/$", "");
        String endpoint = base + "/topics/" + topic.trim();
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Content-Type", "application/vnd.kafka.json.v2+json");
        requestHeaders.put(
            "Accept",
            "application/vnd.kafka.v2+json, application/vnd.kafka+json, application/json"
        );
        if (headers != null) {
            requestHeaders.putAll(headers);
        }
        return new KafkaTransport(endpoint, timeoutMillis, requestHeaders, Mode.KAFKA_REST);
    }

    public static KafkaTransport eventHub(
        String endpoint,
        long timeoutMillis,
        Map<String, String> headers
    ) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(
                "exporter.eventhub.endpoint must be configured for the eventhub exporter"
            );
        }
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("Content-Type", "application/json");
        if (headers != null) {
            requestHeaders.putAll(headers);
        }
        return new KafkaTransport(endpoint.trim(), timeoutMillis, requestHeaders, Mode.EVENT_HUB);
    }

    private KafkaTransport(
        String endpoint,
        long timeoutMillis,
        Map<String, String> headers,
        Mode mode
    ) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Kafka/Event Hub timeout must be greater than zero");
        }
        this.endpoint = URI.create(endpoint);
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.headers = Map.copyOf(headers);
        this.mode = mode;
        this.client = HttpClient.newBuilder().connectTimeout(this.timeout).build();
    }

    @Override
    public synchronized void write(LogEvent logEvent) {
        Objects.requireNonNull(logEvent, "logEvent must not be null");
        Map<String, Object> canonical = logEvent.toMap();
        Object payload;
        if (mode == Mode.KAFKA_REST) {
            payload = Map.of("records", List.of(Map.of("value", canonical)));
        } else {
            payload = canonical;
        }
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            HttpResponse<Void> response = client.send(
                builder.build(),
                HttpResponse.BodyHandlers.discarding()
            );
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new KafkaTransportError(
                    mode == Mode.KAFKA_REST ? "Kafka" : "Event Hub",
                    status
                );
            }
        } catch (KafkaTransportError error) {
            throw error;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new KafkaTransportError(
                mode == Mode.KAFKA_REST ? "Kafka" : "Event Hub",
                exception
            );
        }
    }

    @Override
    public void flush() {
        // Synchronous transport — nothing buffered.
    }

    @Override
    public void shutdown() {
        // HttpClient does not require explicit shutdown for this usage.
    }
}
