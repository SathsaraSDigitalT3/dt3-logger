package com.digitalt3.commons;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.api.Validator;
import com.digitalt3.commons.sdk.LogEventValidator;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the supported public {@link Validator} contract for {@link LogEvent}.
 */
public class ValidatorContractTest {

    @Test
    public void publicValidatorReturnsSuccessfulStructuredResultForCanonicalEvent() {
        Validator validator = new LogEventValidator();

        Validator.ValidationResult result = validator.validate(validEvent());

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
        assertEquals(ValidationMode.LENIENT, result.mode());
    }

    @Test
    public void publicValidatorReturnsSanitizedStructuredDetailsForInvalidEvent() {
        Validator validator = new LogEventValidator();
        LogEvent invalidEvent = validEvent();
        invalidEvent.setSeverity("INVALID");

        Validator.ValidationResult result = validator.validate(invalidEvent);

        assertFalse(result.valid());
        assertFalse(result.errors().isEmpty());
        assertEquals(ValidationMode.LENIENT, result.mode());

        Validator.ValidationErrorDetail severityError = result.errors().stream()
            .filter(error -> "severity".equals(error.field()))
            .findFirst()
            .orElse(null);

        assertTrue("Expected a severity enum diagnostic", severityError != null);
        assertEquals("enum", severityError.rule());
        assertEquals("Value is not an allowed value", severityError.message());
        assertFalse(severityError.message().contains("INVALID"));
    }

    @Test
    public void loggerSelectedSeverityIsNotPartOfTheCallerEventContract() {
        LogEvent event = validEvent();
        event.setSeverity("DEBUG");

        Validator.ValidationResult result = new LogEventValidator().validate(event);

        assertTrue(result.valid());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    public void publicValidatorReportsRequiredFieldsWithCanonicalShape() {
        Validator validator = new LogEventValidator();
        LogEvent invalidEvent = validEvent();
        invalidEvent.setDeploymentEnvironment(null);

        Validator.ValidationResult result = validator.validate(invalidEvent);

        Validator.ValidationErrorDetail requiredError = result.errors().stream()
            .filter(error -> "deployment.environment".equals(error.field()))
            .findFirst()
            .orElse(null);

        assertTrue("Expected a deployment.environment required diagnostic", requiredError != null);
        assertEquals("required", requiredError.rule());
        assertEquals("Required property is missing", requiredError.message());
    }

    @Test
    public void publicValidatorRejectsNullEvent() {
        Validator validator = new LogEventValidator();

        assertThrows(NullPointerException.class, () -> validator.validate(null));
    }

    private LogEvent validEvent() {
        return LogEvent.builder()
            .timestamp("2026-07-03T10:00:00.000Z")
            .severity("INFO")
            .message("Test event")
            .eventName("TEST_EVENT")
            .schemaVersion("1.0.0")
            .sdkName("dt3-commons-java")
            .sdkVersion("0.1.0")
            .serviceName("test-service")
            .serviceVersion("1.0.0")
            .deploymentEnvironment("test")
            .build();
    }
}
