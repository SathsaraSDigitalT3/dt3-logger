package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.sdk.OtlpTransportError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for synchronous OTLP/HTTP JSON Java export behavior.
 */
public class OtlpTransportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void otlpExporterPostsMappedPayloadWithConfiguredHeaders() throws IOException {
        CapturingServer server = new CapturingServer(201, 0);
        try {
            Logger logger = LoggerFactory.createLogger(otlpConfig(server.endpoint(), false, 1_000));
            logger.info(
                "OTLP event",
                Map.of(
                    "event.name", "OTLP_EVENT",
                    "count", 3,
                    "enabled", true,
                    "details", Map.of("region", "west"),
                    "labels", List.of("one", "two")
                )
            );

            CapturingServer.CompletedRequest request = server.request();
            JsonNode payload = payload(request);
            JsonNode logRecord = logRecord(payload);
            assertEquals("POST", request.method());
            assertEquals("/v1/logs", request.path());
            assertEquals("application/json", request.contentType());
            assertEquals("tenant-a", request.tenantHeader());
            assertEquals("INFO", logRecord.path("severityText").asText());
            assertEquals(9, logRecord.path("severityNumber").asInt());
            assertEquals("OTLP event", logRecord.path("body").path("stringValue").asText());
            assertTrue(logRecord.path("timeUnixNano").asText().matches("\\d+"));
            assertAttribute(logRecord.path("attributes"), "event.name", "stringValue", "OTLP_EVENT");
            assertAttribute(logRecord.path("attributes"), "count", "intValue", "3");
            assertAttribute(logRecord.path("attributes"), "enabled", "boolValue", "true");
            assertTrue(findAttribute(logRecord.path("attributes"), "details").path("value").has("kvlistValue"));
            assertTrue(findAttribute(logRecord.path("attributes"), "labels").path("value").has("arrayValue"));
            assertAttribute(
                resourceAttributes(payload),
                "service.name",
                "stringValue",
                "otlp-test-service"
            );
            assertAttribute(scopeAttributes(payload), "dt3.sdk.name", "stringValue", "dt3-commons-java");
        } finally {
            server.close();
        }
    }

    @Test
    public void otlpExporterAcceptsComplete2xxRangeAndPreventsContentTypeOverride() throws IOException {
        CapturingServer server = new CapturingServer(204, 0);
        try {
            SdkConfig config = otlpConfig(server.endpoint(), false, 1_000);
            config.setOtlpHeaders(Map.of(
                "X-Tenant", "tenant-a",
                "Content-Type", "text/plain"
            ));
            Logger logger = LoggerFactory.createLogger(config);

            logger.info("No content", Map.of("event.name", "NO_CONTENT_EVENT"));

            CapturingServer.CompletedRequest request = server.request();
            JsonNode payload = payload(request);
            assertEquals("POST", request.method());
            assertEquals("application/json", request.contentType());
            assertEquals(
                "NO_CONTENT_EVENT",
                attributeValue(logRecord(payload).path("attributes"), "event.name")
            );
        } finally {
            server.close();
        }
    }

    @Test
    public void non2xxResponseIsSanitizedFailClosedTransportFailure() throws IOException {
        CapturingServer server = new CapturingServer(500, 0);
        try {
            Logger logger = LoggerFactory.createLogger(otlpConfig(server.endpoint(), false, 1_000));
            String secret = "never-expose-this-event-content";

            OtlpTransportError error = assertThrows(
                OtlpTransportError.class,
                () -> logger.info(secret, Map.of("event.name", "SERVER_FAILURE", "password", secret))
            );

            assertEquals("OTLP export failed with status 500", error.getMessage());
            assertFalse(error.getMessage().contains(secret));
            assertFalse(error.getMessage().contains("server-response-body"));
        } finally {
            server.close();
        }
    }

    @Test
    public void connectionAndTimeoutFailuresAreSanitized() throws IOException {
        Logger unavailableLogger = LoggerFactory.createLogger(
            otlpConfig("http://127.0.0.1:1/v1/logs", false, 200)
        );
        String secret = "connection-secret";

        OtlpTransportError connectionError = assertThrows(
            OtlpTransportError.class,
            () -> unavailableLogger.info(secret, Map.of("event.name", "CONNECTION_FAILURE", "password", secret))
        );
        assertEquals("OTLP export request failed", connectionError.getMessage());
        assertFalse(connectionError.getMessage().contains(secret));

        CapturingServer delayedServer = new CapturingServer(200, 250);
        try {
            Logger timeoutLogger = LoggerFactory.createLogger(
                otlpConfig(delayedServer.endpoint(), false, 50)
            );

            OtlpTransportError timeoutError = assertThrows(
                OtlpTransportError.class,
                () -> timeoutLogger.info(secret, Map.of("event.name", "TIMEOUT_FAILURE", "password", secret))
            );
            assertEquals("OTLP export request timed out", timeoutError.getMessage());
            assertFalse(timeoutError.getMessage().contains(secret));
        } finally {
            delayedServer.close();
        }
    }

    @Test
    public void failOpenSwallowsOtlpFailuresWhileFailClosedPropagatesThem() throws IOException {
        CapturingServer failingServer = new CapturingServer(503, 0);
        try {
            Logger failOpenLogger = LoggerFactory.createLogger(
                otlpConfig(failingServer.endpoint(), true, 1_000)
            );

            failOpenLogger.info("Fail open", Map.of("event.name", "FAIL_OPEN_EVENT"));
            CapturingServer.CompletedRequest request = failingServer.request();
            assertEquals("POST", request.method());

            Logger failClosedLogger = LoggerFactory.createLogger(
                otlpConfig(failingServer.endpoint(), false, 1_000)
            );

            OtlpTransportError error = assertThrows(
                OtlpTransportError.class,
                () -> failClosedLogger.info(
                    "Fail closed",
                    Map.of("event.name", "FAIL_CLOSED_EVENT")
                )
            );
            assertEquals("OTLP export failed with status 503", error.getMessage());

            List<CapturingServer.CompletedRequest> requests = failingServer.requests(2);
            assertEquals(2, requests.size());
            assertEquals(
                "FAIL_CLOSED_EVENT",
                attributeValue(logRecord(payload(requests.get(1))).path("attributes"), "event.name")
            );
        } finally {
            failingServer.close();
        }
    }

    @Test
    public void maskingAndValidationAreProcessedBeforeOtlpExport() throws IOException {
        // --- Masking: password field is redacted before OTLP export ---
        CapturingServer maskingServer = new CapturingServer(200, 0);
        try {
            SdkConfig config = otlpConfig(maskingServer.endpoint(), false, 1_000);
            config.setMaskingTrackMaskedFields(true);
            Logger logger = LoggerFactory.createLogger(config);

            logger.info(
                "Masked export",
                Map.of("event.name", "MASKED_OTLP_EVENT", "password", "top-secret")
            );

            CapturingServer.CompletedRequest request = maskingServer.request();
            JsonNode attributes = logRecord(payload(request)).path("attributes");
            assertEquals("[REDACTED]", attributeValue(attributes, "password"));
            assertTrue(findAttribute(attributes, "dt3.security.masked_fields").path("value").has("arrayValue"));
            assertFalse(request.body().contains("top-secret"));
        } finally {
            maskingServer.close();
        }

        // --- STRICT validation: caller-controlled invalid attributes prevent export ---
        CapturingServer strictServer = new CapturingServer(200, 0);
        try {
            SdkConfig strictConfig = otlpConfig(strictServer.endpoint(), false, 1_000);
            strictConfig.setValidationMode(ValidationMode.STRICT);
            Logger strictLogger = LoggerFactory.createLogger(strictConfig);

            assertThrows(
                com.digitalt3.commons.sdk.LogEventValidationException.class,
                () -> strictLogger.info("Invalid type override", Map.of("attributes", "not-an-object"))
            );
            assertTrue(strictServer.completedRequests.isEmpty());
        } finally {
            strictServer.close();
        }

        // --- LENIENT validation: caller-controlled invalid values export unchanged with diagnostics ---
        CapturingServer lenientServer = new CapturingServer(200, 0);
        try {
            SdkConfig lenientConfig = otlpConfig(lenientServer.endpoint(), false, 1_000);
            lenientConfig.setValidationMode(ValidationMode.LENIENT);
            Logger lenientLogger = LoggerFactory.createLogger(lenientConfig);

            lenientLogger.info("Invalid type override", Map.of("attributes", "not-an-object"));

            CapturingServer.CompletedRequest request = lenientServer.request();
            JsonNode diagnosticAttribute = findAttribute(
                logRecord(payload(request)).path("attributes"),
                "dt3.validation.errors"
            );
            JsonNode diagnostics = diagnosticAttribute.path("value").path("arrayValue").path("values");
            assertTrue(diagnostics.isArray());
            assertEquals(1, diagnostics.size());

            JsonNode diagnosticFields = diagnostics.get(0).path("kvlistValue").path("values");
            assertAttribute(diagnosticFields, "field", "stringValue", "attributes");
            assertAttribute(diagnosticFields, "message", "stringValue", "Value has an invalid type");
            assertAttribute(diagnosticFields, "rule", "stringValue", "type");
            assertAttribute(
                logRecord(payload(request)).path("attributes"),
                "attributes",
                "stringValue",
                "not-an-object"
            );
        } finally {
            lenientServer.close();
        }
    }

    @Test
    public void otlpConfigurationRequiresValidEndpointAndTimeout() {
        SdkConfig missingEndpoint = baseConfig();
        missingEndpoint.setExporter("otlp");

        IllegalArgumentException endpointError = assertThrows(
            IllegalArgumentException.class,
            () -> LoggerFactory.createLogger(missingEndpoint)
        );
        assertEquals(
            "otlp.endpoint must be configured for the OTLP exporter",
            endpointError.getMessage()
        );

        SdkConfig invalidTimeout = otlpConfig("http://127.0.0.1:4318/v1/logs", false, 1_000);
        invalidTimeout.setOtlpTimeout(0);

        IllegalArgumentException timeoutError = assertThrows(
            IllegalArgumentException.class,
            () -> LoggerFactory.createLogger(invalidTimeout)
        );
        assertEquals("otlp.timeout must be greater than zero", timeoutError.getMessage());
    }

    @Test
    public void otlpPayloadEscapesControlCharactersAndPreservesTimestampMapping() throws IOException {
        CapturingServer server = new CapturingServer(200, 0);
        try {
            Logger logger = LoggerFactory.createLogger(otlpConfig(server.endpoint(), false, 1_000));
            String message = "First line\nSecond line\rThird line\tTabbed";

            logger.info(message, Map.of("event.name", "CONTROL_CHARACTER_EVENT"));

            CapturingServer.CompletedRequest request = server.request();
            JsonNode logRecord = logRecord(payload(request));
            assertEquals(message, logRecord.path("body").path("stringValue").asText());
            assertTrue(logRecord.path("timeUnixNano").asText().matches("\\d+"));
        } finally {
            server.close();
        }
    }

    /**
     * Regression test: the OTLP transport exports severityText and severityNumber
     * consistently based on the logger method, not caller-supplied context.
     * logger.info(..., Map.of("severity", "ERROR")) must produce INFO severity in the OTLP payload.
     */
    @Test
    public void otlpSeverityIsControlledByLoggerMethodNotCallerContext() throws IOException {
        CapturingServer server = new CapturingServer(200, 0);
        try {
            Logger logger = LoggerFactory.createLogger(otlpConfig(server.endpoint(), false, 1_000));

            // Caller supplies "severity" = "ERROR" — must be ignored; logger.info() produces INFO.
            logger.info(
                "Severity override attempt",
                Map.of("event.name", "SEVERITY_OVERRIDE_EVENT", "severity", "ERROR")
            );

            CapturingServer.CompletedRequest request = server.request();
            JsonNode logRecord = logRecord(payload(request));
            // severityText must reflect the logger method (INFO), not the caller context (ERROR)
            assertEquals("INFO", logRecord.path("severityText").asText());
            assertEquals(9, logRecord.path("severityNumber").asInt());
        } finally {
            server.close();
        }
    }

    private SdkConfig otlpConfig(String endpoint, boolean failOpen, long timeout) {
        SdkConfig config = baseConfig();
        config.setExporter("otlp");
        config.setOtlpEndpoint(endpoint);
        config.setOtlpTimeout(timeout);
        config.setOtlpHeaders(Map.of("X-Tenant", "tenant-a", "Content-Type", "text/plain"));
        config.setFailOpen(failOpen);
        return config;
    }

    private SdkConfig baseConfig() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("otlp-test-service");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        return config;
    }

    private JsonNode payload(CapturingServer.CompletedRequest request) {
        try {
            return OBJECT_MAPPER.readTree(request.body());
        } catch (IOException exception) {
            throw new AssertionError("Expected a valid OTLP JSON payload", exception);
        }
    }

    private JsonNode logRecord(JsonNode payload) {
        return payload.path("resourceLogs").get(0).path("scopeLogs").get(0).path("logRecords").get(0);
    }

    private JsonNode resourceAttributes(JsonNode payload) {
        return payload.path("resourceLogs").get(0).path("resource").path("attributes");
    }

    private JsonNode scopeAttributes(JsonNode payload) {
        return payload.path("resourceLogs").get(0).path("scopeLogs").get(0).path("scope").path("attributes");
    }

    private void assertAttribute(
        JsonNode attributes,
        String key,
        String valueType,
        String expectedValue
    ) {
        assertEquals(expectedValue, findAttribute(attributes, key).path("value").path(valueType).asText());
    }

    private String attributeValue(JsonNode attributes, String key) {
        JsonNode value = findAttribute(attributes, key).path("value");
        if (value.has("stringValue")) {
            return value.path("stringValue").asText();
        }
        if (value.has("intValue")) {
            return value.path("intValue").asText();
        }
        if (value.has("boolValue")) {
            return String.valueOf(value.path("boolValue").asBoolean());
        }
        throw new AssertionError("Expected a scalar OTLP value for attribute " + key);
    }

    private JsonNode findAttribute(JsonNode attributes, String key) {
        for (JsonNode attribute : attributes) {
            if (key.equals(attribute.path("key").asText())) {
                return attribute;
            }
        }
        throw new AssertionError("Expected OTLP attribute " + key);
    }

    private static final class CapturingServer implements AutoCloseable {
        private final HttpServer server;
        private final List<CompletedRequest> completedRequests = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final AtomicReference<CountDownLatch> responseCompleted =
            new AtomicReference<>(new CountDownLatch(1));

        private CapturingServer(int status, long responseDelayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/logs", exchange -> handle(exchange, status, responseDelayMillis));
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs";
        }

        private CompletedRequest request() {
            try {
                if (!responseCompleted.get().await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for the OTLP response to complete");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the OTLP response to complete", exception);
            }
            List<CompletedRequest> requests = List.copyOf(completedRequests);
            if (requests.isEmpty()) {
                throw new AssertionError("Expected a completed OTLP request to be captured");
            }
            return requests.get(0);
        }

        private List<CompletedRequest> requests(int expectedCount) {
            CountDownLatch completionLatch = responseCompleted.updateAndGet(currentLatch -> {
                int remainingRequests = Math.max(0, expectedCount - completedRequests.size());
                return remainingRequests == currentLatch.getCount()
                    ? currentLatch
                    : new CountDownLatch(remainingRequests);
            });
            try {
                if (!completionLatch.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for OTLP requests to complete");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for OTLP requests to complete", exception);
            }
            return List.copyOf(completedRequests);
        }

        private void handle(HttpExchange exchange, int status, long responseDelayMillis) throws IOException {
            CompletedRequest request = new CompletedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                exchange.getRequestHeaders().getFirst("X-Tenant"),
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)
            );

            try {
                if (responseDelayMillis > 0) {
                    try {
                        Thread.sleep(responseDelayMillis);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }

                exchange.getResponseHeaders().set("X-Response-Detail", "server-response-body");
                exchange.sendResponseHeaders(status, -1);
            } finally {
                try {
                    exchange.close();
                } finally {
                    completedRequests.add(request);
                    responseCompleted.get().countDown();
                }
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private record CompletedRequest(
            String method,
            String path,
            String contentType,
            String tenantHeader,
            String body
        ) {
        }
    }
}
