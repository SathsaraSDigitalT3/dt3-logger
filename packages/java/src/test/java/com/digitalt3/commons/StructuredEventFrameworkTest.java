package com.digitalt3.commons;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.LogTransport;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.Span;
import com.digitalt3.commons.api.Tracer;
import com.digitalt3.commons.api.events.AiEvents;
import com.digitalt3.commons.api.events.ApiEvents;
import com.digitalt3.commons.api.events.DatabaseEvents;
import com.digitalt3.commons.api.events.EventEmitter;
import com.digitalt3.commons.api.events.EventEnvelope;
import com.digitalt3.commons.api.events.MessagingEvents;
import com.digitalt3.commons.sdk.RecursiveMaskingEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Coverage for structured event framework gap-close capabilities.
 */
public class StructuredEventFrameworkTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void logEventIdentityFieldsRoundTripThroughToMapAndBuilder() {
        LogEvent event = LogEvent.builder()
            .eventId("evt-1")
            .operationId("op-1")
            .componentName("checkout")
            .parentSpanId("fedcba9876543210")
            .eventName("ORDER_CREATED")
            .severity("INFO")
            .message("created")
            .build();

        assertEquals("evt-1", event.getEventId());
        assertEquals("op-1", event.getOperationId());
        assertEquals("checkout", event.getComponentName());
        assertEquals("fedcba9876543210", event.getParentSpanId());

        Map<String, Object> map = event.toMap();
        assertEquals("evt-1", map.get("event.id"));
        assertEquals("op-1", map.get("operation.id"));
        assertEquals("checkout", map.get("component.name"));
        assertEquals("fedcba9876543210", map.get("parent.span.id"));
    }

    @Test
    public void loggerAutoGeneratesEventIdAndAppliesComponentName() {
        SdkConfig config = baseConfig();
        config.setComponentName("payments");

        JsonNode event = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(config);
            logger.info("hello", Map.of("event.name", "PAYMENT_STARTED"));
        }).get(0);

        assertTrue(event.hasNonNull("event.id"));
        assertFalse(event.path("event.id").asText().isBlank());
        assertEquals("payments", event.path("component.name").asText());
        assertEquals("1.1.0", event.path("schema.version").asText());
    }

    @Test
    public void multiSinkFanoutDeliversToRegisteredSinksAndIsolatesFailures() {
        List<Map<String, Object>> captured = new ArrayList<>();
        AtomicInteger failingWrites = new AtomicInteger();

        LogTransport failing = new LogTransport() {
            @Override
            public void write(LogEvent logEvent) {
                failingWrites.incrementAndGet();
                throw new RuntimeException("sink failed");
            }

            @Override
            public void flush() {
            }

            @Override
            public void shutdown() {
            }
        };

        LogTransport capturing = new LogTransport() {
            @Override
            public void write(LogEvent logEvent) {
                captured.add(logEvent.toMap());
            }

            @Override
            public void flush() {
            }

            @Override
            public void shutdown() {
            }
        };

        SdkConfig config = baseConfig();
        config.setFailOpen(true);

        captureEvents(() -> {
            try (Logger logger = LoggerFactory.createLogger(config)) {
                logger.registerSink(failing);
                logger.registerSink(capturing);
                logger.info("multi-sink", Map.of("event.name", "MULTI_SINK_EVENT"));
            }
        });

        assertEquals(1, failingWrites.get());
        assertEquals(1, captured.size());
        assertEquals("MULTI_SINK_EVENT", captured.get(0).get("event.name"));
        assertNotNull(captured.get(0).get("event.id"));
    }

    @Test
    public void exportersListCreatesStdoutPlusRegisteredFanout() {
        List<Map<String, Object>> captured = new ArrayList<>();
        LogTransport capturing = new LogTransport() {
            @Override
            public void write(LogEvent logEvent) {
                captured.add(logEvent.toMap());
            }

            @Override
            public void flush() {
            }

            @Override
            public void shutdown() {
            }
        };

        SdkConfig config = baseConfig();
        config.setExporters(List.of("stdout"));

        JsonNode stdoutEvent = captureEvents(() -> {
            try (Logger logger = LoggerFactory.createLogger(config)) {
                logger.registerSink(capturing);
                logger.info("fanout", Map.of("event.name", "EXPORTERS_EVENT"));
            }
        }).get(0);

        assertEquals("EXPORTERS_EVENT", stdoutEvent.path("event.name").asText());
        assertEquals(1, captured.size());
        assertEquals("EXPORTERS_EVENT", captured.get(0).get("event.name"));
    }

    @Test
    public void eventEmitterEmitsTypedDomainAndAiEvents() {
        JsonNode apiEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());
            EventEmitter emitter = new EventEmitter(logger);
            emitter.emit(ApiEvents.incomingHttp("GET", "/orders/{id}", 200, 12.5));
        }).get(0);

        assertEquals("INCOMING_HTTP", apiEvent.path("event.name").asText());
        assertEquals("GET", apiEvent.path("http.request.method").asText());
        assertEquals("/orders/{id}", apiEvent.path("http.route").asText());
        assertEquals(200, apiEvent.path("http.response.status_code").asInt());
        assertEquals(12.5, apiEvent.path("duration.ms").asDouble(), 0.001);

        JsonNode dbEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());
            new EventEmitter(logger).emit(
                DatabaseEvents.queryCompleted("postgresql", "SELECT", "orders", 3.0)
            );
        }).get(0);
        assertEquals("DB_QUERY_COMPLETED", dbEvent.path("event.name").asText());
        assertEquals("postgresql", dbEvent.path("db.system").asText());

        JsonNode messagingEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());
            new EventEmitter(logger).emit(
                MessagingEvents.jobCompleted("kafka", "orders", "msg-1", 8.0)
            );
        }).get(0);
        assertEquals("WORKER_JOB_COMPLETED", messagingEvent.path("event.name").asText());

        JsonNode aiEvent = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());
            new EventEmitter(logger).emit(AiEvents.promptSubmitted(Map.of(
                "kavia.provider", "openai",
                "kavia.model", "gpt-4",
                "kavia.prompt", "secret prompt",
                "kavia.request.id", "req-1"
            )));
        }).get(0);
        assertEquals("AI_PROMPT_SUBMITTED", aiEvent.path("event.name").asText());
        assertEquals("[REDACTED]", aiEvent.path("kavia.prompt").asText());
        assertEquals("openai", aiEvent.path("kavia.provider").asText());
    }

    @Test
    public void eventEnvelopeWrapsLogEvent() {
        LogEvent event = LogEvent.builder()
            .eventName("MESSAGING_PUBLISH")
            .schemaVersion("1.1.0")
            .timestamp("2026-08-27T00:00:00Z")
            .serviceName("orders")
            .correlationId("corr-1")
            .tenantId("tenant-a")
            .severity("INFO")
            .message("publish")
            .build();

        Map<String, Object> envelope = EventEnvelope.wrap(event);
        assertEquals("MESSAGING_PUBLISH", envelope.get("event_type"));
        assertEquals("1.1.0", envelope.get("event_version"));
        assertEquals("2026-08-27T00:00:00Z", envelope.get("timestamp"));
        assertEquals("orders", envelope.get("source"));
        assertEquals("corr-1", envelope.get("correlation_id"));
        assertEquals("tenant-a", envelope.get("tenant_id"));
        assertTrue(envelope.get("payload") instanceof Map);
    }

    @Test
    public void maskingEngineRedactsPromptAndResponseFields() {
        RecursiveMaskingEngine engine = new RecursiveMaskingEngine();
        Map<String, Object> masked = engine.mask(Map.of(
            "prompt", "user secret",
            "response", "model secret",
            "kavia.prompt", "ai secret",
            "kavia.response", "ai reply",
            "safe", "visible"
        ));

        assertEquals("[REDACTED]", masked.get("prompt"));
        assertEquals("[REDACTED]", masked.get("response"));
        assertEquals("[REDACTED]", masked.get("kavia.prompt"));
        assertEquals("[REDACTED]", masked.get("kavia.response"));
        assertEquals("visible", masked.get("safe"));
    }

    @Test
    public void tracerCreatesW3cIdsAndEmitsSpanCompletionEvent() {
        SdkConfig config = baseConfig();
        config.setTracingSpanEventsEnabled(true);

        List<JsonNode> events = captureEvents(() -> {
            try (Logger logger = LoggerFactory.createLogger(config)) {
                Tracer tracer = logger.createTracer();
                try (Span span = tracer.startSpan("checkout")) {
                    assertEquals(32, span.getTraceId().length());
                    assertEquals(16, span.getSpanId().length());
                    assertTrue(span.getTraceId().matches("^[a-f0-9]{32}$"));
                    assertTrue(span.getSpanId().matches("^[a-f0-9]{16}$"));
                    logger.info("inside", Map.of("event.name", "INSIDE_SPAN"));
                }
            }
        });

        assertEquals(2, events.size());
        JsonNode inside = events.get(0);
        JsonNode completion = events.get(1);

        assertEquals("INSIDE_SPAN", inside.path("event.name").asText());
        assertEquals(inside.path("trace.id").asText(), completion.path("trace.id").asText());
        assertEquals(inside.path("span.id").asText(), completion.path("span.id").asText());
        assertEquals("CHECKOUT", completion.path("event.name").asText());
        assertTrue(completion.has("duration.ms"));
    }

    @Test
    public void nestedSpanSetsParentSpanId() {
        SdkConfig config = baseConfig();
        config.setTracingSpanEventsEnabled(false);

        try (Logger logger = LoggerFactory.createLogger(config)) {
            Tracer tracer = logger.createTracer();
            tracer.withSpan("parent", parent -> {
                assertNotNull(parent.getSpanId());
                tracer.withSpan("child", child -> {
                    assertEquals(parent.getTraceId(), child.getTraceId());
                    assertEquals(parent.getSpanId(), child.getParentSpanId());
                });
            });
        }
    }

    @Test
    public void autoGeneratesTraceAndSpanIds() {
        JsonNode event = captureEvents(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());
            logger.info("traced", Map.of("event.name", "AUTO_TRACE"));
        }).get(0);

        assertTrue(event.path("trace.id").asText().matches("^[a-f0-9]{32}$"));
        assertTrue(event.path("span.id").asText().matches("^[a-f0-9]{16}$"));
    }

    @Test
    public void aiRequestAndResponseBuildersShareRequestId() {
        LogEvent request = AiEvents.request(Map.of(
            "kavia.request.id", "req-7",
            "kavia.prompt", "hello",
            "kavia.model", "gpt-4o"
        ));
        LogEvent response = AiEvents.response(Map.of(
            "kavia.request.id", "req-7",
            "kavia.tokens.total", 4,
            "kavia.finish_reason", "stop"
        ));
        assertEquals("AI_PROMPT_SUBMITTED", request.getEventName());
        assertEquals("AI_RESPONSE_RECEIVED", response.getEventName());
        assertEquals("req-7", request.getAttributes().get("kavia.request.id"));
        assertEquals("req-7", response.getAttributes().get("kavia.request.id"));
    }

    private SdkConfig baseConfig() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("structured-event-test");
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
