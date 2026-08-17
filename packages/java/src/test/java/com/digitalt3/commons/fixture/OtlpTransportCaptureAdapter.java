package com.digitalt3.commons.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-only OTLP/HTTP endpoint that captures one transport request for shared
 * cross-language fixture assertions.
 *
 * <p>This adapter is deliberately isolated from production transport code and
 * existing transport tests. It accepts the OTLP/HTTP JSON request shape emitted
 * by the Java SDK and exposes a parsed payload after a successful response.</p>
 */
 public final class OtlpTransportCaptureAdapter implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final long CAPTURE_TIMEOUT_SECONDS = 1L;

    private final HttpServer server;
    private final CountDownLatch requestCaptured = new CountDownLatch(1);
    private volatile CapturedRequest capturedRequest;
    private volatile IOException captureFailure;

    private OtlpTransportCaptureAdapter() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/logs", this::captureRequest);
        server.start();
    }

    /**
     * Start a local endpoint that acknowledges OTLP transport requests.
     *
     * @return running test-only capture adapter
     * @throws IOException if the local HTTP server cannot be started
     */
    public static OtlpTransportCaptureAdapter start() throws IOException {
        return new OtlpTransportCaptureAdapter();
    }

    /**
     * Return the OTLP Logs endpoint configured on the fixture logger.
     *
     * @return local HTTP endpoint ending in {@code /v1/logs}
     */
    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/logs";
    }

    /**
     * Wait for and return the request emitted by the transport under test.
     *
     * @return captured request metadata and parsed OTLP JSON payload
     */
    public CapturedRequest awaitRequest() {
        try {
            if (!requestCaptured.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the OTLP fixture transport request");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(
                "Interrupted while waiting for the OTLP fixture transport request",
                exception
            );
        }

        if (captureFailure != null) {
            throw new AssertionError("Unable to parse captured OTLP fixture payload", captureFailure);
        }
        if (capturedRequest == null) {
            throw new AssertionError("Expected the OTLP fixture transport request to be captured");
        }
        return capturedRequest;
    }

    private void captureRequest(HttpExchange exchange) throws IOException {
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedRequest = new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                OBJECT_MAPPER.readTree(body)
            );
            exchange.sendResponseHeaders(200, -1);
        } catch (IOException exception) {
            captureFailure = exception;
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
            requestCaptured.countDown();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /**
     * Immutable OTLP request data used by fixture assertions.
     *
     * @param method HTTP method used by the transport
     * @param path request path used by the transport
     * @param contentType request content type
     * @param payload parsed OTLP/HTTP JSON payload
     */
    public record CapturedRequest(String method, String path, String contentType, JsonNode payload) {
    }
}
