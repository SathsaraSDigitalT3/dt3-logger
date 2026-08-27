package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.fixture.OtlpTransportCaptureAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Applies the isolated shared STEP-05 OTLP transport fixtures to the Java SDK.
 *
 * <p>Transport capture is provided by a test-only local HTTP endpoint so this
 * adapter exercises the existing Java OTLP exporter without modifying its
 * implementation or the STEP-01 through STEP-04 test suites.</p>
 */
public class CrossLanguageOtlpFixtureAdapterTest {

    private static final Path FIXTURE_DIRECTORY =
        Path.of("..", "..", "tests", "cross-language", "fixtures");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void javaOtlpTransportMatchesSharedCrossLanguageFixtures() throws IOException {
        for (String fixtureName : List.of(
            "otlp-valid-canonical-event.json"
        )) {
            Map<String, Object> fixture = readFixture(fixtureName);
            Map<String, Object> event = objectMap(fixture.get("event"));
            Map<String, Object> expected = objectMap(fixture.get("expected"));

            try (OtlpTransportCaptureAdapter capture = OtlpTransportCaptureAdapter.start()) {
                Logger logger = LoggerFactory.createLogger(otlpConfig(capture.endpoint(), event));
                logger.info(stringValue(event, "message"), event);

                OtlpTransportCaptureAdapter.CapturedRequest request = capture.awaitRequest();
                JsonNode logRecord = logRecord(request.payload());

                assertEquals("Fixture method mismatch: " + fixtureName, "POST", request.method());
                assertEquals("Fixture path mismatch: " + fixtureName, "/v1/logs", request.path());
                assertEquals(
                    "Fixture content type mismatch: " + fixtureName,
                    "application/json",
                    request.contentType()
                );
                assertEquals(
                    "Fixture severity mismatch: " + fixtureName,
                    expected.get("severityText"),
                    logRecord.path("severityText").asText()
                );
                assertEquals(
                    "Fixture body mismatch: " + fixtureName,
                    expected.get("body"),
                    logRecord.path("body").path("stringValue").asText()
                );
                assertTrue(
                    "Fixture timestamp must map to OTLP nanoseconds: " + fixtureName,
                    logRecord.path("timeUnixNano").asText().matches("\\d+")
                );

                for (Map<String, Object> expectedAttribute : objectList(expected.get("logAttributes"))) {
                    assertAttribute(
                        logRecord.path("attributes"),
                        stringValue(expectedAttribute, "key"),
                        stringValue(expectedAttribute, "valueType"),
                        stringValue(expectedAttribute, "value")
                    );
                }

                for (Map<String, Object> expectedAttribute : objectList(expected.get("resourceAttributes"))) {
                    assertAttribute(
                        resourceAttributes(request.payload()),
                        stringValue(expectedAttribute, "key"),
                        stringValue(expectedAttribute, "valueType"),
                        stringValue(expectedAttribute, "value")
                    );
                }
            }
        }
    }

    private SdkConfig otlpConfig(String endpoint, Map<String, Object> event) {
        SdkConfig config = new SdkConfig();
        config.setExporter("otlp");
        config.setOtlpEndpoint(endpoint);
        config.setOtlpTimeout(1_000L);
        config.setServiceName(stringValue(event, "service.name"));
        config.setServiceVersion(stringValue(event, "service.version"));
        config.setDeploymentEnvironment(stringValue(event, "deployment.environment"));
        config.setSchemaVersion(stringValue(event, "schema.version"));
        return config;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFixture(String fixtureName) throws IOException {
        String content = Files.readString(FIXTURE_DIRECTORY.resolve(fixtureName));
        return OBJECT_MAPPER.readValue(content, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private String stringValue(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private JsonNode logRecord(JsonNode payload) {
        return payload.path("resourceLogs").get(0).path("scopeLogs").get(0).path("logRecords").get(0);
    }

    private JsonNode resourceAttributes(JsonNode payload) {
        return payload.path("resourceLogs").get(0).path("resource").path("attributes");
    }

    private void assertAttribute(
        JsonNode attributes,
        String key,
        String valueType,
        String expectedValue
    ) {
        for (JsonNode attribute : attributes) {
            if (key.equals(attribute.path("key").asText())) {
                assertEquals(
                    "Unexpected OTLP value for attribute " + key,
                    expectedValue,
                    attribute.path("value").path(valueType).asText()
                );
                return;
            }
        }
        throw new AssertionError("Expected OTLP attribute " + key);
    }
}
