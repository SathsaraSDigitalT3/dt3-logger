package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.sdk.Dt3ErrorCode;
import com.digitalt3.commons.sdk.Dt3ErrorPhase;
import com.digitalt3.commons.sdk.Dt3SdkException;
import com.digitalt3.commons.sdk.HttpTransportError;
import com.digitalt3.commons.sdk.LogEventValidationException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for synchronous Java HTTP export behavior using an in-process server.
 */
public class HttpTransportTest {

    @Test
    public void httpExporterPostsFinalJsonWithConfiguredHeaders() throws IOException {
        CapturingServer server = new CapturingServer(201, 0);
        try {
            Logger logger = LoggerFactory.createLogger(httpConfig(server.endpoint(), true, 1000));
            logger.info(
                "HTTP event",
                Map.of("event.name", "HTTP_EVENT", "request.id", "request-1")
            );

            assertEquals("POST", server.method.get());
            assertEquals("application/json", server.contentType.get());
            assertEquals("tenant-a", server.tenantHeader.get());
            assertTrue(server.body.get().contains("\"event.name\":\"HTTP_EVENT\""));
            assertTrue(server.body.get().contains("\"request.id\":\"request-1\""));
        } finally {
            server.close();
        }
    }

    @Test
    public void httpExporterTreatsAll2xxResponsesAsSuccess() throws IOException {
        CapturingServer server = new CapturingServer(204, 0);
        try {
            Logger logger = LoggerFactory.createLogger(httpConfig(server.endpoint(), false, 1000));

            logger.info("No content", Map.of("event.name", "NO_CONTENT_EVENT"));

            assertEquals("POST", server.method.get());
            assertTrue(server.body.get().contains("\"event.name\":\"NO_CONTENT_EVENT\""));
        } finally {
            server.close();
        }
    }

    @Test
    public void non2xxResponseIsTransportFailureWithoutPayloadLeakage() throws IOException {
        CapturingServer server = new CapturingServer(500, 0);
        try {
            Logger logger = LoggerFactory.createLogger(httpConfig(server.endpoint(), false, 1000));
            String secret = "never-expose-this-event-content";

            HttpTransportError error = assertThrows(
                HttpTransportError.class,
                () -> logger.info(secret, Map.of("event.name", "SERVER_FAILURE", "token", secret))
            );

            assertEquals("HTTP export failed with status 500", error.getMessage());
            assertFalse(error.getMessage().contains(secret));
            assertTrue(server.body.get().contains("[REDACTED]"));
            assertTrue(server.body.get().contains(secret));
        } finally {
            server.close();
        }
    }

    @Test
    public void connectionFailureProducesSanitizedTransportError() {
        Logger logger = LoggerFactory.createLogger(httpConfig("http://127.0.0.1:1/events", false, 200));
        String secret = "connection-secret";

        HttpTransportError error = assertThrows(
            HttpTransportError.class,
            () -> logger.info(secret, Map.of("event.name", "CONNECTION_FAILURE", "password", secret))
        );

        assertEquals("HTTP export request failed", error.getMessage());
        assertFalse(error.getMessage().contains(secret));
    }

    @Test
    public void timeoutProducesSanitizedTransportError() throws IOException {
        CapturingServer server = new CapturingServer(200, 250);
        try {
            Logger logger = LoggerFactory.createLogger(httpConfig(server.endpoint(), false, 50));
            String secret = "timeout-secret";

            HttpTransportError error = assertThrows(
                HttpTransportError.class,
                () -> logger.info(secret, Map.of("event.name", "TIMEOUT_EVENT", "password", secret))
            );

            assertEquals("HTTP export request timed out", error.getMessage());
            assertFalse(error.getMessage().contains(secret));
        } finally {
            server.close();
        }
    }

    @Test
    public void failOpenTrueSwallowsHttpTransportFailure() throws IOException {
        CapturingServer server = new CapturingServer(503, 0);
        try {
            Logger logger = LoggerFactory.createLogger(httpConfig(server.endpoint(), true, 1000));

            logger.info("Fail open", Map.of("event.name", "FAIL_OPEN_EVENT"));

            assertEquals("POST", server.method.get());
        } finally {
            server.close();
        }
    }

    @Test
    public void maskingOccursBeforeHttpExport() throws IOException {
        CapturingServer server = new CapturingServer(200, 0);
        try {
            SdkConfig config = httpConfig(server.endpoint(), false, 1000);
            config.setMaskingTrackMaskedFields(true);
            Logger logger = LoggerFactory.createLogger(config);

            logger.info(
                "Masked export",
                Map.of("event.name", "MASKED_HTTP_EVENT", "password", "top-secret")
            );

            assertTrue(server.body.get().contains("\"password\":\"[REDACTED]\""));
            assertTrue(server.body.get().contains("\"dt3.security.masked_fields\""));
            assertFalse(server.body.get().contains("top-secret"));
        } finally {
            server.close();
        }
    }

    @Test
    public void strictValidationPreventsHttpExportWhileLenientExportsDiagnostics() throws IOException {
        // `attributes` is caller-controlled, unlike logger-owned event metadata.
        CapturingServer strictServer = new CapturingServer(200, 0);
        try {
            SdkConfig strictConfig = httpConfig(strictServer.endpoint(), false, 1000);
            strictConfig.setValidationMode(ValidationMode.STRICT);
            Logger strictLogger = LoggerFactory.createLogger(strictConfig);

            assertThrows(
                LogEventValidationException.class,
                () -> strictLogger.info(
                    "Invalid type override",
                    Map.of("attributes", "not-an-object")
                )
            );
            assertTrue(strictServer.body.get() == null);
        } finally {
            strictServer.close();
        }

        CapturingServer lenientServer = new CapturingServer(200, 0);
        try {
            SdkConfig lenientConfig = httpConfig(lenientServer.endpoint(), false, 1000);
            lenientConfig.setValidationMode(ValidationMode.LENIENT);
            Logger lenientLogger = LoggerFactory.createLogger(lenientConfig);

            lenientLogger.info(
                "Invalid type override",
                Map.of("attributes", "not-an-object")
            );

            assertTrue(lenientServer.body.get().contains("\"dt3.validation.errors\""));
            assertTrue(lenientServer.body.get().contains("\"field\":\"attributes\""));
            assertTrue(lenientServer.body.get().contains("\"rule\":\"type\""));
            assertTrue(lenientServer.body.get().contains("\"attributes\":\"not-an-object\""));
        } finally {
            lenientServer.close();
        }
    }

    @Test
    public void httpExporterRequiresEndpoint() {
        SdkConfig config = baseConfig();
        config.setExporter("http");

        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> LoggerFactory.createLogger(config)
        );

        assertEquals(
            "exporter.http.endpoint must be configured for the HTTP exporter",
            error.getMessage()
        );
    }

    @Test
    public void httpTimeoutApiMapsCanonicalTimeoutValueInMilliseconds() {
        SdkConfig config = baseConfig();

        config.setHttpTimeout(1_250);

        assertEquals(1_250, config.getHttpTimeout());
    }

    @Test
    public void canonicalDotKeysOverrideLegacyHttpAliasesAndKeepMillisecondUnits() {
        SdkConfig config = SdkConfig.fromMap(Map.of(
            "exporter.http.endpoint", "http://canonical.example/events",
            "http.endpoint", "http://legacy.example/events",
            "exporter.http.timeout", 1_250,
            "http.timeout", 50,
            "exporter.http.headers", Map.of("X-Source", "canonical"),
            "http.headers", Map.of("X-Source", "legacy"),
            "fail_open", false
        ));

        assertEquals("http://canonical.example/events", config.getHttpEndpoint());
        assertEquals(1_250, config.getHttpTimeout());
        assertEquals("canonical", config.getHttpHeaders().get("X-Source"));
        assertFalse(config.isFailOpen());
    }

    @Test
    public void genericHttpHeadersRejectCarriageReturnAndLineFeedInjection() {
        SdkConfig newlineName = httpConfig("http://127.0.0.1:1/events", false, 1_000);
        newlineName.setHttpHeaders(Map.of("X-Injected\r\nHeader", "value"));

        assertThrows(IllegalArgumentException.class, () -> LoggerFactory.createLogger(newlineName));

        SdkConfig newlineValue = httpConfig("http://127.0.0.1:1/events", false, 1_000);
        newlineValue.setHttpHeaders(Map.of("X-Valid", "value\r\nInjected: true"));

        assertThrows(IllegalArgumentException.class, () -> LoggerFactory.createLogger(newlineValue));
    }

    @Test
    public void loggerCloseIsIdempotentAndPreventsSubsequentFlushOrExport() {
        Logger logger = LoggerFactory.createLogger(baseConfig());

        logger.close();
        logger.close();

        Dt3SdkException exportException = assertThrows(
            Dt3SdkException.class,
            () -> logger.info("Closed logger", Map.of("event.name", "CLOSED_LOGGER_EVENT"))
        );
        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, exportException.getCode());
        assertEquals(Dt3ErrorPhase.LIFECYCLE, exportException.getPhase());
        assertFalse(exportException.isRetryable());

        Dt3SdkException flushException = assertThrows(Dt3SdkException.class, logger::flush);
        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, flushException.getCode());
        assertEquals(Dt3ErrorPhase.LIFECYCLE, flushException.getPhase());
        assertFalse(flushException.isRetryable());
    }

    private SdkConfig httpConfig(String endpoint, boolean failOpen, long timeout) {
        SdkConfig config = baseConfig();
        config.setExporter("http");
        config.setHttpEndpoint(endpoint);
        config.setHttpTimeout(timeout);
        config.setHttpHeaders(Map.of("X-Tenant", "tenant-a", "Content-Type", "text/plain"));
        config.setFailOpen(failOpen);
        return config;
    }

    private SdkConfig baseConfig() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("http-test-service");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        return config;
    }

    private static final class CapturingServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> method = new AtomicReference<>();
        private final AtomicReference<String> contentType = new AtomicReference<>();
        private final AtomicReference<String> tenantHeader = new AtomicReference<>();
        private final AtomicReference<String> body = new AtomicReference<>();

        private CapturingServer(int status, long responseDelayMillis) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/events", exchange -> handle(exchange, status, responseDelayMillis));
            server.start();
        }

        private String endpoint() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/events";
        }

        private void handle(HttpExchange exchange, int status, long responseDelayMillis) throws IOException {
            method.set(exchange.getRequestMethod());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            tenantHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }

            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
