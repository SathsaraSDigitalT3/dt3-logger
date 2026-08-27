package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.sdk.Dt3ErrorCode;
import com.digitalt3.commons.sdk.Dt3ErrorPhase;
import com.digitalt3.commons.sdk.Dt3SdkException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for Java logger batching behavior.
 *
 * <p>The file exporter provides deterministic assertions over batch delivery
 * while preserving coverage through the public logger API.</p>
 */
public class BatchingTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void batchingDisabledDeliversEventsImmediately() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, false, 3, 10_000));

        logger.info("Immediate", Map.of("event.name", "IMMEDIATE_EVENT"));

        assertEquals(List.of("IMMEDIATE_EVENT"), eventNames(logFile));
        logger.close();
    }

    @Test
    public void underSizedBatchWaitsUntilManualFlush() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true, 3, 10_000));

        logger.info("Buffered", Map.of("event.name", "BUFFERED_EVENT"));

        assertFalse(Files.exists(logFile));

        logger.flush();

        assertEquals(List.of("BUFFERED_EVENT"), eventNames(logFile));
        logger.close();
    }

    @Test
    public void reachingBatchSizeFlushesEventsInInsertionOrder() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true, 3, 10_000));

        logger.info("First", Map.of("event.name", "FIRST_EVENT"));
        logger.info("Second", Map.of("event.name", "SECOND_EVENT"));
        assertFalse(Files.exists(logFile));

        logger.info("Third", Map.of("event.name", "THIRD_EVENT"));

        assertEquals(
            List.of("FIRST_EVENT", "SECOND_EVENT", "THIRD_EVENT"),
            eventNames(logFile)
        );
        logger.close();
    }

    @Test
    public void intervalFlushesPendingUnderSizedBatch() throws Exception {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true, 3, 25));

        try {
            logger.info("Timed", Map.of("event.name", "TIMED_EVENT"));

            waitForEvents(logFile, 1, 1_000);

            assertEquals(List.of("TIMED_EVENT"), eventNames(logFile));
        } finally {
            logger.close();
        }
    }

    @Test
    public void closeFlushesRemainingEventsAndPreventsFurtherOutput() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true, 3, 10_000));

        logger.info("Closing", Map.of("event.name", "CLOSE_EVENT"));
        logger.close();
        logger.close();

        assertEquals(List.of("CLOSE_EVENT"), eventNames(logFile));
        Dt3SdkException exception = assertThrows(
            Dt3SdkException.class,
            () -> logger.info("Closed", Map.of("event.name", "AFTER_CLOSE_EVENT"))
        );
        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, exception.getCode());
        assertEquals(Dt3ErrorPhase.LIFECYCLE, exception.getPhase());
        assertFalse(exception.isRetryable());
    }

    @Test
    public void batchingConfigurationAcceptsCanonicalValuesAndRejectsInvalidValues() {
        SdkConfig config = SdkConfig.fromMap(Map.of(
            "batching.enabled", true,
            "batching.max_size", 25,
            "batching.flush_interval_ms", 125
        ));

        assertTrue(config.isBatchingEnabled());
        assertEquals(25, config.getBatchingMaxSize());
        assertEquals(125, config.getBatchingFlushIntervalMs());

        assertThrows(
            IllegalArgumentException.class,
            () -> SdkConfig.fromMap(Map.of("batching.enabled", "true"))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SdkConfig.fromMap(Map.of("batching.max_size", 0))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> SdkConfig.fromMap(Map.of("batching.flush_interval_ms", 0))
        );
    }

    @Test
    public void diagnosticsStackConfigurationIsParsedFromCanonicalKey() {
        SdkConfig config = SdkConfig.fromMap(Map.of(
            "error.diagnostics.enabled", true,
            "error.diagnostics.include_stack", true
        ));

        assertTrue(config.isErrorDiagnosticsEnabled());
        assertTrue(config.isErrorDiagnosticsIncludeStack());

        assertThrows(
            IllegalArgumentException.class,
            () -> SdkConfig.fromMap(Map.of("error.diagnostics.include_stack", "true"))
        );
    }

    @Test
    public void batchDeliveryFailureHonorsFailOpenSetting() throws IOException {
        Path failOpenDestination = Files.createTempDirectory("dt3-batching-fail-open");
        Logger failOpenLogger = LoggerFactory.createLogger(
            fileConfig(failOpenDestination, true, 1, 10_000)
        );

        failOpenLogger.info("Fail open", Map.of("event.name", "FAIL_OPEN_EVENT"));
        failOpenLogger.close();
        assertTrue(Files.isDirectory(failOpenDestination));

        Path failClosedDestination = Files.createTempDirectory("dt3-batching-fail-closed");
        Logger failClosedLogger = LoggerFactory.createLogger(
            fileConfig(failClosedDestination, true, 1, 10_000)
        );
        SdkConfig failClosedConfig = fileConfig(failClosedDestination, true, 1, 10_000);
        failClosedConfig.setFailOpen(false);
        failClosedLogger.close();
        failClosedLogger = LoggerFactory.createLogger(failClosedConfig);

        Logger finalFailClosedLogger = failClosedLogger;
        Dt3SdkException exception = assertThrows(
            Dt3SdkException.class,
            () -> finalFailClosedLogger.info(
                "Fail closed",
                Map.of("event.name", "FAIL_CLOSED_EVENT")
            )
        );
        assertEquals(Dt3ErrorCode.FILE_WRITE_FAILED, exception.getCode());
        assertEquals(Dt3ErrorPhase.DELIVERY, exception.getPhase());
        assertFalse(exception.isRetryable());
        assertTrue(exception.getCause() instanceof IOException);
        failClosedLogger.close();
    }

    private SdkConfig fileConfig(
        Path filePath,
        boolean batchingEnabled,
        int maxSize,
        long flushIntervalMs
    ) {
        SdkConfig config = new SdkConfig();
        config.setServiceName("batching-test-service");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        config.setExporter("file");
        config.setFilePath(filePath.toString());
        config.setBatchingEnabled(batchingEnabled);
        config.setBatchingMaxSize(maxSize);
        config.setBatchingFlushIntervalMs(flushIntervalMs);
        return config;
    }

    private List<String> eventNames(Path logFile) throws IOException {
        return Files.readAllLines(logFile)
            .stream()
            .map(this::parseJsonLine)
            .map(event -> event.path("event.name").asText())
            .toList();
    }

    private JsonNode parseJsonLine(String line) {
        try {
            return OBJECT_MAPPER.readTree(line);
        } catch (IOException exception) {
            throw new AssertionError("Expected a valid JSON Lines record", exception);
        }
    }

    private Path temporaryLogFile() throws IOException {
        return Files.createTempDirectory("dt3-batching").resolve("events.jsonl");
    }

    private void waitForEvents(Path logFile, int expectedCount, long timeoutMillis)
        throws IOException, InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;

        while (System.nanoTime() < deadline) {
            if (Files.exists(logFile) && Files.readAllLines(logFile).size() >= expectedCount) {
                return;
            }
            Thread.sleep(10);
        }

        throw new AssertionError(
            "Expected " + expectedCount + " event(s) to be delivered within " + timeoutMillis + "ms"
        );
    }
}
