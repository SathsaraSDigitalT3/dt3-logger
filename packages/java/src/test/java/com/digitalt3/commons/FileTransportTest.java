package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.ValidationMode;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Focused tests for synchronous append-only Java file exporter behavior.
 */
public class FileTransportTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void fileExporterWritesCanonicalStructuredEvent() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true));

        logger.info("File transport event", Map.of("event.name", "FILE_EVENT", "request.id", "req-1"));
        logger.flush();

        List<JsonNode> events = readJsonLines(logFile);
        assertEquals(1, events.size());

        JsonNode event = events.get(0);
        assertEquals("INFO", event.path("severity").asText());
        assertEquals("File transport event", event.path("message").asText());
        assertEquals("FILE_EVENT", event.path("event.name").asText());
        assertEquals("req-1", event.path("request.id").asText());
        assertEquals("1.0.0", event.path("schema.version").asText());
        assertEquals("file-test-service", event.path("service.name").asText());
    }

    @Test
    public void fileExporterAppendsSeparateJsonLines() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true));

        logger.info("First", Map.of("event.name", "FIRST_EVENT"));
        logger.warn("Second", Map.of("event.name", "SECOND_EVENT"));

        List<JsonNode> events = readJsonLines(logFile);
        assertEquals(2, events.size());
        assertEquals("FIRST_EVENT", events.get(0).path("event.name").asText());
        assertEquals("SECOND_EVENT", events.get(1).path("event.name").asText());
    }

    @Test
    public void fileExporterEscapesControlCharactersWithoutSplittingJsonLines() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true));
        String message = "First line\nSecond line\rThird line\tTabbed";

        logger.info(message, Map.of("event.name", "CONTROL_CHARACTER_EVENT"));

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size());

        List<JsonNode> events = readJsonLines(logFile);
        assertEquals(1, events.size());
        assertEquals(message, events.get(0).path("message").asText());
        assertEquals("CONTROL_CHARACTER_EVENT", events.get(0).path("event.name").asText());
    }

    @Test
    public void fileExporterCreatesNestedDestinationDirectories() throws IOException {
        Path rootDirectory = Files.createTempDirectory("dt3-file-transport");
        Path logFile = rootDirectory.resolve("nested").resolve("output").resolve("events.jsonl");
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true));

        logger.info("Nested destination", Map.of("event.name", "NESTED_DIRECTORY_EVENT"));

        assertTrue(Files.isDirectory(logFile.getParent()));
        List<JsonNode> events = readJsonLines(logFile);
        assertEquals(1, events.size());
        assertEquals("NESTED_DIRECTORY_EVENT", events.get(0).path("event.name").asText());
    }

    @Test
    public void fileExporterRequiresConfiguredPath() {
        SdkConfig config = baseConfig();
        config.setExporter("file");

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> LoggerFactory.createLogger(config)
        );

        assertEquals(
            "exporter.file.path must be configured for the file exporter",
            exception.getMessage()
        );
    }

    @Test
    public void maskingOccursBeforeEventReachesFileTransport() throws IOException {
        Path logFile = temporaryLogFile();
        SdkConfig config = fileConfig(logFile, true);
        config.setMaskingTrackMaskedFields(true);
        Logger logger = LoggerFactory.createLogger(config);

        logger.info(
            "Masked event",
            Map.of("event.name", "MASKED_EVENT", "password", "top-secret")
        );

        JsonNode event = readJsonLines(logFile).get(0);
        assertEquals("[REDACTED]", event.path("password").asText());
        assertTrue(event.has("dt3.security.masked_fields"));
        assertFalse(event.toString().contains("top-secret"));
    }

    @Test
    public void strictValidationPreventsInvalidEventFromBeingWritten() throws IOException {
        // Validation is tested with a genuinely invalid caller-supplied value (sdk.name as
        // an integer), which is not a field that the logger method overrides. This ensures
        // the STRICT validation behavior is exercised independently of logger severity.
        Path logFile = temporaryLogFile();
        SdkConfig config = fileConfig(logFile, true);
        config.setValidationMode(ValidationMode.STRICT);
        Logger logger = LoggerFactory.createLogger(config);

        assertThrows(
            IllegalArgumentException.class,
            () -> logger.info("Invalid type override", Map.of("sdk.name", 123))
        );

        assertFalse(Files.exists(logFile));
    }

    @Test
    public void lenientValidationWritesDiagnostics() throws IOException {
        // Validation is tested with a genuinely invalid caller-supplied value (sdk.name as
        // an integer), which the logger does not override. This ensures the LENIENT
        // validation diagnostic behavior is exercised independently of logger severity.
        Path logFile = temporaryLogFile();
        SdkConfig config = fileConfig(logFile, true);
        config.setValidationMode(ValidationMode.LENIENT);
        Logger logger = LoggerFactory.createLogger(config);

        logger.info("Invalid type override", Map.of("sdk.name", 123));

        JsonNode event = readJsonLines(logFile).get(0);
        JsonNode errorsNode = event.path("dt3.validation.errors");
        // Guard: assert that validation diagnostics exist before accessing array elements
        assertFalse("Expected dt3.validation.errors to be present in the exported event",
            errorsNode.isMissingNode());
        assertTrue("Expected at least one validation diagnostic", errorsNode.size() > 0);

        JsonNode validationError = errorsNode.get(0);
        assertNotNull("Expected the first validation diagnostic to be non-null", validationError);
        assertEquals("sdk.name", validationError.path("field").asText());
        assertEquals("type", validationError.path("rule").asText());
    }

    /**
     * Regression test: the logger method controls severity; caller context must NOT
     * override it. Calling logger.info(...) with Map.of("severity", "ERROR") must
     * produce severity = INFO in the exported event.
     */
    @Test
    public void loggerMethodSeverityCannotBeOverriddenByCallerContext() throws IOException {
        Path logFile = temporaryLogFile();
        Logger logger = LoggerFactory.createLogger(fileConfig(logFile, true));

        // Caller supplies "severity" = "ERROR" in context; should be ignored.
        logger.info("Severity override attempt", Map.of(
            "event.name", "SEVERITY_OVERRIDE_EVENT",
            "severity", "ERROR"
        ));
        // Caller supplies "severity" = "WARN" in context for warn(); should remain WARN.
        logger.warn("Warn severity override attempt", Map.of(
            "event.name", "WARN_OVERRIDE_EVENT",
            "severity", "ERROR"
        ));
        // Caller supplies "severity" = "INFO" in context for debug(); should remain DEBUG.
        logger.debug("Debug severity override attempt", Map.of(
            "event.name", "DEBUG_OVERRIDE_EVENT",
            "severity", "INFO"
        ));

        List<JsonNode> events = readJsonLines(logFile);
        assertEquals(3, events.size());

        // logger.info() must remain INFO regardless of caller context
        assertEquals("INFO", events.get(0).path("severity").asText());
        assertEquals("SEVERITY_OVERRIDE_EVENT", events.get(0).path("event.name").asText());

        // logger.warn() must remain WARN regardless of caller context
        assertEquals("WARN", events.get(1).path("severity").asText());
        assertEquals("WARN_OVERRIDE_EVENT", events.get(1).path("event.name").asText());

        // logger.debug() must remain DEBUG regardless of caller context
        assertEquals("DEBUG", events.get(2).path("severity").asText());
        assertEquals("DEBUG_OVERRIDE_EVENT", events.get(2).path("event.name").asText());
    }

    @Test
    public void failOpenTrueSwallowsFileTransportFailures() throws IOException {
        Path directoryDestination = Files.createTempDirectory("dt3-file-transport-directory");
        SdkConfig config = fileConfig(directoryDestination, true);
        Logger logger = LoggerFactory.createLogger(config);

        logger.info("Transport failure", Map.of("event.name", "FAIL_OPEN_EVENT"));

        assertTrue(Files.isDirectory(directoryDestination));
    }

    @Test
    public void failOpenFalsePropagatesFileTransportFailures() throws IOException {
        Path directoryDestination = Files.createTempDirectory("dt3-file-transport-directory");
        SdkConfig config = fileConfig(directoryDestination, false);
        Logger logger = LoggerFactory.createLogger(config);

        assertThrows(
            IllegalStateException.class,
            () -> logger.info("Transport failure", Map.of("event.name", "FAIL_CLOSED_EVENT"))
        );
    }

    private List<JsonNode> readJsonLines(Path logFile) throws IOException {
        return Files.readAllLines(logFile)
            .stream()
            .map(this::parseJsonLine)
            .toList();
    }

    private JsonNode parseJsonLine(String line) {
        try {
            return OBJECT_MAPPER.readTree(line);
        } catch (IOException exception) {
            throw new AssertionError("Expected a valid JSON Lines record", exception);
        }
    }

    private SdkConfig fileConfig(Path filePath, boolean failOpen) {
        SdkConfig config = baseConfig();
        config.setExporter("file");
        config.setFilePath(filePath.toString());
        config.setFailOpen(failOpen);
        return config;
    }

    private SdkConfig baseConfig() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("file-test-service");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        return config;
    }

    private Path temporaryLogFile() throws IOException {
        Path directory = Files.createTempDirectory("dt3-file-transport");
        return directory.resolve("events.jsonl");
    }
}
