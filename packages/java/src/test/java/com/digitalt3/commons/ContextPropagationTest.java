package com.digitalt3.commons;

import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Focused verification for execution-scoped Java SDK context propagation.
 */
public class ContextPropagationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TRACE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TRACE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void contextIsAttachedToMultipleEventsAndClearedAfterScope() {
        List<JsonNode> events = captureStdout(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());

            try (LogContext.Scope ignored = logger.withContext(
                LogContext.builder()
                    .traceId(TRACE_A)
                    .spanId("1111111111111111")
                    .parentSpanId("2222222222222222")
                    .correlationId("request-1")
                    .build()
            )) {
                logger.info("Started", Map.of("event.name", "REQUEST_STARTED"));
                logger.info("Completed", Map.of("event.name", "REQUEST_COMPLETED"));
            }

            logger.info("Outside", Map.of("event.name", "OUTSIDE"));
        });

        for (int index = 0; index < 2; index++) {
            assertEquals(TRACE_A, events.get(index).path("trace.id").asText());
            assertEquals("1111111111111111", events.get(index).path("span.id").asText());
            assertEquals("2222222222222222", events.get(index).path("parent.span.id").asText());
            assertEquals("request-1", events.get(index).path("correlation.id").asText());
        }
        assertFalse(events.get(2).has("trace.id"));
        assertFalse(events.get(2).has("span.id"));
        assertFalse(events.get(2).has("parent.span.id"));
        assertFalse(events.get(2).has("correlation.id"));
    }

    @Test
    public void nestedScopeInheritsParentAndRestoresItAfterClose() {
        List<JsonNode> events = captureStdout(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());

            try (LogContext.Scope outer = logger.withContext(
                LogContext.builder().traceId(TRACE_A).correlationId("outer").build()
            )) {
                logger.info("Outer before", Map.of("event.name", "OUTER_BEFORE"));
                try (LogContext.Scope inner = logger.withContext(
                    LogContext.builder().spanId("1111111111111111").correlationId("inner").build()
                )) {
                    logger.info("Inner", Map.of("event.name", "INNER"));
                }
                logger.info("Outer after", Map.of("event.name", "OUTER_AFTER"));
            }
        });

        assertEquals(TRACE_A, events.get(0).path("trace.id").asText());
        assertEquals("outer", events.get(0).path("correlation.id").asText());
        assertFalse(events.get(0).has("span.id"));

        assertEquals(TRACE_A, events.get(1).path("trace.id").asText());
        assertEquals("1111111111111111", events.get(1).path("span.id").asText());
        assertEquals("inner", events.get(1).path("correlation.id").asText());

        assertEquals(TRACE_A, events.get(2).path("trace.id").asText());
        assertEquals("outer", events.get(2).path("correlation.id").asText());
        assertFalse(events.get(2).has("span.id"));
    }

    @Test
    public void contextIsRestoredWhenScopedWorkThrows() {
        Logger logger = LoggerFactory.createLogger(baseConfig());

        assertThrows(IllegalStateException.class, () -> {
            try (LogContext.Scope ignored = logger.withContext(
                LogContext.builder().traceId(TRACE_A).build()
            )) {
                throw new IllegalStateException("Expected failure");
            }
        });

        assertTrue(LogContext.activeValues().isEmpty());
    }

    @Test
    public void explicitEventContextOverridesScopeWhileLoggerOwnedFieldsWin() {
        List<JsonNode> events = captureStdout(() -> {
            Logger logger = LoggerFactory.createLogger(baseConfig());

            try (LogContext.Scope ignored = logger.withContext(
                LogContext.builder().traceId(TRACE_A).correlationId("scope").build()
            )) {
                logger.warn("Override", Map.of(
                    "event.name", "EXPLICIT_CONTEXT",
                    "trace.id", TRACE_B,
                    "correlation.id", "event",
                    "severity", "ERROR",
                    "service.name", "caller-service"
                ));
            }
        });

        JsonNode event = events.get(0);
        assertEquals(TRACE_B, event.path("trace.id").asText());
        assertEquals("event", event.path("correlation.id").asText());
        assertEquals("WARN", event.path("severity").asText());
        assertEquals("context-test-service", event.path("service.name").asText());
    }

    @Test
    public void independentThreadsDoNotLeakContext() throws InterruptedException {
        Logger logger = LoggerFactory.createLogger(baseConfig());
        AtomicReference<Map<String, Object>> firstThreadContext = new AtomicReference<>();
        AtomicReference<Map<String, Object>> secondThreadContext = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        Thread first = new Thread(() -> {
            try (LogContext.Scope ignored = logger.withContext(
                LogContext.builder().traceId(TRACE_A).correlationId("thread-a").build()
            )) {
                await(start);
                firstThreadContext.set(LogContext.activeValues());
            }
        });
        Thread second = new Thread(() -> {
            try (LogContext.Scope ignored = logger.withContext(
                LogContext.builder().traceId(TRACE_B).correlationId("thread-b").build()
            )) {
                await(start);
                secondThreadContext.set(LogContext.activeValues());
            }
        });

        first.start();
        second.start();
        start.countDown();
        first.join();
        second.join();

        assertEquals(TRACE_A, firstThreadContext.get().get("trace.id"));
        assertEquals("thread-a", firstThreadContext.get().get("correlation.id"));
        assertEquals(TRACE_B, secondThreadContext.get().get("trace.id"));
        assertEquals("thread-b", secondThreadContext.get().get("correlation.id"));
        assertTrue(LogContext.activeValues().isEmpty());
    }

    @Test
    public void contextReachesFileHttpAndOtlpTransports() throws IOException {
        Path directory = Files.createTempDirectory("dt3-context");
        Path file = directory.resolve("events.jsonl");
        Logger fileLogger = LoggerFactory.createLogger(fileConfig(file));

        try (LogContext.Scope ignored = fileLogger.withContext(
            LogContext.builder().traceId(TRACE_A).correlationId("file-request").build()
        )) {
            fileLogger.info("File", Map.of("event.name", "FILE_CONTEXT"));
        }
        fileLogger.close();

        JsonNode fileEvent = OBJECT_MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(TRACE_A, fileEvent.path("trace.id").asText());
        assertEquals("file-request", fileEvent.path("correlation.id").asText());

        CapturingServer server = new CapturingServer();
        try {
            Logger httpLogger = LoggerFactory.createLogger(httpConfig(server.endpoint("/events")));
            try (LogContext.Scope ignored = httpLogger.withContext(
                LogContext.builder().traceId(TRACE_A).build()
            )) {
                httpLogger.info("HTTP", Map.of("event.name", "HTTP_CONTEXT"));
            }
            JsonNode httpEvent = OBJECT_MAPPER.readTree(server.awaitBody());
            assertEquals(TRACE_A, httpEvent.path("trace.id").asText());

            server.reset();
            Logger otlpLogger = LoggerFactory.createLogger(otlpConfig(server.endpoint("/v1/logs")));
            try (LogContext.Scope ignored = otlpLogger.withContext(
                LogContext.builder().traceId(TRACE_B).build()
            )) {
                otlpLogger.info("OTLP", Map.of("event.name", "OTLP_CONTEXT"));
            }
            JsonNode otlpPayload = OBJECT_MAPPER.readTree(server.awaitBody());
            JsonNode attributes = otlpPayload.path("resourceLogs").get(0)
                .path("scopeLogs").get(0).path("logRecords").get(0).path("attributes");
            assertEquals(TRACE_B, attributeValue(attributes, "trace.id"));
        } finally {
            server.close();
        }
    }

    private List<JsonNode> captureStdout(Runnable operation) {
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

    private JsonNode parseJson(String content) {
        try {
            return OBJECT_MAPPER.readTree(content);
        } catch (IOException exception) {
            throw new AssertionError("Expected valid JSON", exception);
        }
    }

    private SdkConfig baseConfig() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("context-test-service");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        return config;
    }

    private SdkConfig fileConfig(Path path) {
        SdkConfig config = baseConfig();
        config.setExporter("file");
        config.setFilePath(path.toString());
        return config;
    }

    private SdkConfig httpConfig(String endpoint) {
        SdkConfig config = baseConfig();
        config.setExporter("http");
        config.setHttpEndpoint(endpoint);
        config.setFailOpen(false);
        return config;
    }

    private SdkConfig otlpConfig(String endpoint) {
        SdkConfig config = baseConfig();
        config.setExporter("otlp");
        config.setOtlpEndpoint(endpoint);
        config.setFailOpen(false);
        return config;
    }

    private String attributeValue(JsonNode attributes, String key) {
        for (JsonNode attribute : attributes) {
            if (key.equals(attribute.path("key").asText())) {
                return attribute.path("value").path("stringValue").asText();
            }
        }
        throw new AssertionError("Expected OTLP attribute " + key);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test threads");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", exception);
        }
    }

    private static final class CapturingServer implements AutoCloseable {
        private final HttpServer server;
        private volatile CountDownLatch requestReceived = new CountDownLatch(1);
        private final AtomicReference<String> body = new AtomicReference<>();

        private CapturingServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/events", this::handle);
            server.createContext("/v1/logs", this::handle);
            server.start();
        }

        private String endpoint(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        private void handle(HttpExchange exchange) throws IOException {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            requestReceived.countDown();
        }

        private String awaitBody() {
            try {
                if (!requestReceived.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for export");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for export", exception);
            }
            return body.get();
        }

        private void reset() {
            body.set(null);
            requestReceived = new CountDownLatch(1);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
