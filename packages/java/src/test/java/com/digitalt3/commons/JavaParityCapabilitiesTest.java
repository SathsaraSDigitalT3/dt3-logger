package com.digitalt3.commons;

import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.sdk.LogEventValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Focused regression coverage for Java SDK parity capabilities.
 */
public class JavaParityCapabilitiesTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";

    @Test
    public void fatalCreatesCanonicalFatalEvent() {
        JsonNode event = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());
            logger.fatal("Unrecoverable failure", Map.of("event.name", "FATAL_FAILURE"));
        }).get(0);

        assertEquals("FATAL", event.path("severity").asText());
        assertEquals("Unrecoverable failure", event.path("message").asText());
        assertEquals("FATAL_FAILURE", event.path("event.name").asText());
    }

    @Test
    public void directEventUsesTheNormalPipelineAndPreservesCanonicalFields() {
        SdkConfig config = baseConfig();
        config.setMaskingTrackMaskedFields(true);
        LogEvent input = LogEvent.builder()
            .timestamp("2026-08-19T07:13:03Z")
            .severity("INFO")
            .message("Direct event")
            .eventName("DIRECT_EVENT")
            .correlationId("direct-correlation")
            .tenantId("tenant-a")
            .attributes(Map.of("password", "secret", "safe", "visible"))
            .build();

        JsonNode event = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(config);
            logger.event(input);
        }).get(0);

        assertEquals("INFO", event.path("severity").asText());
        assertEquals("Direct event", event.path("message").asText());
        assertEquals("DIRECT_EVENT", event.path("event.name").asText());
        assertEquals("direct-correlation", event.path("correlation.id").asText());
        assertEquals("tenant-a", event.path("tenant.id").asText());
        assertEquals("[REDACTED]", event.path("password").asText());
        assertEquals("visible", event.path("safe").asText());
        assertTrue(event.has("dt3.security.masked_fields"));
        assertEquals("secret", input.getAttributes().get("password"));
    }

    @Test
    public void directEventHonorsStrictLenientAndOffValidationModes() {
        LogEvent invalidEvent = LogEvent.builder()
            .timestamp("not-a-valid-rfc3339-timestamp")
            .severity("INFO")
            .message("Invalid timestamp")
            .eventName("INVALID_DIRECT_EVENT")
            .build();

        SdkConfig strictConfig = baseConfig();
        strictConfig.setValidationMode(ValidationMode.STRICT);
        assertThrows(
            LogEventValidationException.class,
            () -> LoggerFactory.createLogger(strictConfig).event(invalidEvent)
        );

        SdkConfig lenientConfig = baseConfig();
        lenientConfig.setValidationMode(ValidationMode.LENIENT);
        JsonNode lenientEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(lenientConfig);
            logger.event(invalidEvent);
        }).get(0);
        assertTrue(lenientEvent.has("dt3.validation.errors"));

        SdkConfig offConfig = baseConfig();
        offConfig.setValidationMode(ValidationMode.OFF);
        JsonNode offEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(offConfig);
            logger.event(invalidEvent);
        }).get(0);
        assertFalse(offEvent.has("dt3.validation.errors"));
    }

    @Test
    public void injectAndExtractRoundTripTraceCorrelationAndTenantHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        LogContext context = LogContext.builder()
            .traceId(TRACE_ID)
            .spanId(SPAN_ID)
            .traceFlags("01")
            .tracestate("vendor=value")
            .correlationId("request-123")
            .tenantId("tenant-a")
            .tenantRegion("eu-west-1")
            .tenantEnvironment("production")
            .build();

        context.inject(headers);

        assertEquals("00-" + TRACE_ID + "-" + SPAN_ID + "-01", headers.get("traceparent"));
        assertEquals("vendor=value", headers.get("tracestate"));
        assertEquals("request-123", headers.get("x-correlation-id"));
        assertEquals("tenant-a", headers.get("x-tenant-id"));
        assertEquals("eu-west-1", headers.get("x-tenant-region"));
        assertEquals("production", headers.get("x-tenant-environment"));

        LogContext extracted = LogContext.extract(Map.of(
            "TraceParent", headers.get("traceparent"),
            "TraceState", headers.get("tracestate"),
            "X-Correlation-Id", headers.get("x-correlation-id"),
            "X-Tenant-Id", headers.get("x-tenant-id"),
            "X-Tenant-Region", headers.get("x-tenant-region"),
            "X-Tenant-Environment", headers.get("x-tenant-environment")
        ));

        assertEquals(TRACE_ID, extracted.values().get("trace.id"));
        assertEquals(SPAN_ID, extracted.values().get("span.id"));
        assertEquals("01", extracted.values().get("trace.flags"));
        assertEquals("vendor=value", extracted.values().get("tracestate"));
        assertEquals("request-123", extracted.values().get("correlation.id"));
        assertEquals("tenant-a", extracted.values().get("tenant.id"));
        assertEquals("eu-west-1", extracted.values().get("tenant.region"));
        assertEquals("production", extracted.values().get("tenant.environment"));
    }

    @Test
    public void malformedTraceparentDoesNotDiscardCorrelationOrTenantHeaders() {
        LogContext extracted = LogContext.extract(Map.of(
            "traceparent", "malformed",
            "x-correlation-id", "request-123",
            "x-tenant-id", "tenant-a"
        ));

        assertFalse(extracted.values().containsKey("trace.id"));
        assertFalse(extracted.values().containsKey("span.id"));
        assertEquals("request-123", extracted.values().get("correlation.id"));
        assertEquals("tenant-a", extracted.values().get("tenant.id"));
    }

    @Test
    public void extractedContextEnrichesEventsOnlyWithinItsScope() {
        LogContext extracted = LogContext.extract(Map.of(
            "traceparent", "00-" + TRACE_ID + "-" + SPAN_ID + "-01",
            "x-tenant-id", "tenant-a",
            "x-tenant-region", "us-east-1",
            "x-tenant-environment", "test"
        ));

        List<JsonNode> events = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());
            try (LogContext.Scope ignored = logger.withContext(extracted)) {
                logger.info("Inbound request", Map.of("event.name", "INBOUND_REQUEST"));
            }
            logger.info("Outside request", Map.of("event.name", "OUTSIDE_REQUEST"));
        });

        assertEquals(TRACE_ID, events.get(0).path("trace.id").asText());
        assertEquals("tenant-a", events.get(0).path("tenant.id").asText());
        assertEquals("us-east-1", events.get(0).path("tenant.region").asText());
        assertEquals("test", events.get(0).path("tenant.environment").asText());
        assertFalse(events.get(1).has("trace.id"));
        assertFalse(events.get(1).has("tenant.id"));
    }

    @Test
    public void automaticCorrelationGenerationCanBeEnabledDisabledAndPreserved() {
        SdkConfig enabledConfig = baseConfig();
        enabledConfig.setAutoGenerateCorrelationId(true);
        List<JsonNode> generatedEvents = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(enabledConfig);
            logger.info("Generated one", Map.of("event.name", "GENERATED_ONE"));
            logger.info("Generated two", Map.of("event.name", "GENERATED_TWO"));
        });

        assertTrue(generatedEvents.get(0).has("correlation.id"));
        assertTrue(generatedEvents.get(1).has("correlation.id"));
        assertNotEquals(
            generatedEvents.get(0).path("correlation.id").asText(),
            generatedEvents.get(1).path("correlation.id").asText()
        );

        SdkConfig disabledConfig = baseConfig();
        disabledConfig.setAutoGenerateCorrelationId(false);
        JsonNode disabledEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(disabledConfig);
            logger.info("No generated ID", Map.of("event.name", "NO_GENERATED_ID"));
        }).get(0);
        assertFalse(disabledEvent.has("correlation.id"));

        JsonNode existingEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(enabledConfig);
            logger.info(
                "Existing ID",
                Map.of("event.name", "EXISTING_CORRELATION", "correlation.id", "incoming-id")
            );
        }).get(0);
        assertEquals("incoming-id", existingEvent.path("correlation.id").asText());
    }

    @Test
    public void generatedCorrelationIdIsStableWithinAScopedContext() {
        SdkConfig config = baseConfig();
        config.setAutoGenerateCorrelationId(true);

        List<JsonNode> events = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(config);
            try (LogContext.Scope ignored = logger.withContext(LogContext.of(Map.of("tenant.id", "tenant-a")))) {
                logger.info("First", Map.of("event.name", "SCOPED_FIRST"));
                logger.info("Second", Map.of("event.name", "SCOPED_SECOND"));
            }
        });

        assertEquals(
            events.get(0).path("correlation.id").asText(),
            events.get(1).path("correlation.id").asText()
        );
    }

    @Test
    public void explicitCorrelationIdEstablishesAndPreservesTheActiveScope() {
        SdkConfig config = baseConfig();
        config.setAutoGenerateCorrelationId(true);

        List<JsonNode> events = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(config);
            try (LogContext.Scope ignored = logger.withContext(LogContext.of(Map.of("tenant.id", "tenant-a")))) {
                logger.info(
                    "Explicit correlation",
                    Map.of("event.name", "EXPLICIT_CORRELATION", "correlation.id", "incoming-id")
                );
                logger.info("Inherited correlation", Map.of("event.name", "INHERITED_CORRELATION"));
            }
        });

        assertEquals("incoming-id", events.get(0).path("correlation.id").asText());
        assertEquals("incoming-id", events.get(1).path("correlation.id").asText());
    }

    @Test
    public void separateScopesReceiveSeparateGeneratedCorrelationIds() {
        SdkConfig config = baseConfig();
        config.setAutoGenerateCorrelationId(true);

        List<JsonNode> events = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(config);
            try (LogContext.Scope ignored = logger.withContext(LogContext.of(Map.of("tenant.id", "tenant-a")))) {
                logger.info("First scope", Map.of("event.name", "FIRST_SCOPE"));
            }
            try (LogContext.Scope ignored = logger.withContext(LogContext.of(Map.of("tenant.id", "tenant-b")))) {
                logger.info("Second scope", Map.of("event.name", "SECOND_SCOPE"));
            }
        });

        assertNotEquals(
            events.get(0).path("correlation.id").asText(),
            events.get(1).path("correlation.id").asText()
        );
    }

    @Test
    public void directEventReassertsConfiguredLoggerOwnedMetadata() {
        SdkConfig config = baseConfig();
        config.setSdkName("configured-sdk");
        config.setSdkVersion("9.8.7");
        config.setSchemaVersion("2.0.0");
        config.setServiceName("configured-service");
        config.setServiceVersion("3.2.1");
        config.setDeploymentEnvironment("production");

        LogEvent input = LogEvent.builder()
            .timestamp("2026-08-19T07:13:03Z")
            .severity("INFO")
            .message("Metadata conflict")
            .eventName("METADATA_CONFLICT")
            .attributes(Map.of(
                "sdk.name", "caller-sdk",
                "sdk.version", "0.0.1",
                "schema.version", "0.0.1",
                "service.name", "caller-service",
                "service.version", "0.0.1",
                "deployment.environment", "development"
            ))
            .build();

        JsonNode event = captureEvents(() -> LoggerFactory.createLogger(config).event(input)).get(0);

        assertEquals("configured-sdk", event.path("sdk.name").asText());
        assertEquals("9.8.7", event.path("sdk.version").asText());
        assertEquals("2.0.0", event.path("schema.version").asText());
        assertEquals("configured-service", event.path("service.name").asText());
        assertEquals("3.2.1", event.path("service.version").asText());
        assertEquals("production", event.path("deployment.environment").asText());
    }

    @Test
    public void wrappedExecutorWorkReceivesSnapshotAndDoesNotLeakIt() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Map<String, Object>> propagated;
            try (LogContext.Scope ignored = LogContext.builder()
                .traceId(TRACE_ID)
                .correlationId("executor-request")
                .build()
                .open()) {
                propagated = executor.submit(LogContext.wrap(LogContext::activeValues));
            }

            assertEquals(TRACE_ID, propagated.get().get("trace.id"));
            assertEquals("executor-request", propagated.get().get("correlation.id"));
            assertTrue(executor.submit(LogContext::activeValues).get().isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    private SdkConfig baseConfig() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("parity-test-service");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        return config;
    }

    private List<JsonNode> captureEvents(Runnable operation) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            operation.run();
        } finally {
            System.setOut(original);
        }

        return captured.toString(StandardCharsets.UTF_8).trim().lines()
            .filter(line -> !line.isBlank())
            .map(this::parseJson)
            .toList();
    }

    private JsonNode parseJson(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (IOException exception) {
            throw new AssertionError("Expected a JSON event", exception);
        }
    }
}
