package com.digitalt3.commons.api;

import com.digitalt3.commons.sdk.LogEventValidator;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Applies the shared cross-language JSON validation fixtures to the Java SDK.
 */
public class CrossLanguageFixtureAdapterTest {

    private static final Path FIXTURE_DIRECTORY =
        Path.of("..", "..", "tests", "cross-language", "fixtures");

    @Test
    public void javaValidatorMatchesSharedCrossLanguageFixtures() throws IOException {
        for (String fixtureName : List.of(
            "validation-valid-canonical-event.json",
            "validation-missing-required-field.json",
            "validation-invalid-field-rules.json"
        )) {
            Map<String, Object> fixture = readFixture(fixtureName);
            Map<String, Object> expected = objectMap(fixture.get("expected"));

            Validator.ValidationResult result = new LogEventValidator().validate(
                LogEventFixtureAdapter.fromFixture(objectMap(fixture.get("event"))),
                ValidationMode.LENIENT
            );

            assertEquals(
                "Fixture validity mismatch: " + fixtureName,
                expected.get("valid"),
                result.valid()
            );

            Set<String> actualErrors = result.errors().stream()
                .map(error -> error.field() + ":" + error.rule())
                .collect(Collectors.toSet());

            for (Map<String, Object> expectedError : objectList(expected.get("errors"))) {
                String expectedDiagnostic = expectedError.get("field") + ":" + expectedError.get("rule");
                assertTrue(
                    "Missing expected diagnostic '" + expectedDiagnostic
                        + "' for fixture " + fixtureName
                        + "; actual diagnostics: " + actualErrors,
                    actualErrors.contains(expectedDiagnostic)
                );
            }

            assertTrue(
                "Every Java diagnostic must include sanitized message text",
                result.errors().stream()
                    .allMatch(error -> error.message() != null && !error.message().isEmpty())
            );
        }
    }

    @Test
    public void invalidTimestampStringProducesCanonicalFormatDiagnostic() {
        LogEvent event = LogEvent.builder()
            .timestamp("not-a-date-time")
            .severity("INFO")
            .message("Timestamp validation regression")
            .eventName("TIMESTAMP_VALIDATION")
            .schemaVersion("1.0.0")
            .sdkName("dt3-java")
            .sdkVersion("0.1.0")
            .serviceName("fixture-service")
            .serviceVersion("1.0.0")
            .deploymentEnvironment("test")
            .build();

        Validator.ValidationResult result = new LogEventValidator().validate(
            event,
            ValidationMode.LENIENT
        );

        assertTrue("An invalid timestamp must fail validation", !result.valid());
        assertTrue(
            "Invalid timestamp strings must produce timestamp:format",
            result.errors().stream().anyMatch(error ->
                "timestamp".equals(error.field()) && "format".equals(error.rule())
            )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFixture(String fixtureName) throws IOException {
        String content = Files.readString(FIXTURE_DIRECTORY.resolve(fixtureName));
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> objectList(Object value) {
        return new ArrayList<>((List<Map<String, Object>>) value);
    }

}
