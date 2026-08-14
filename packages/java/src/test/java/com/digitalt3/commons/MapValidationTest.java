package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.ValidationMode;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Verifies canonical map-based schema validation through the public Java Logger API.
 */
public class MapValidationTest {

    @Test
    public void validCanonicalEventPassesWithoutDiagnostics() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("event.name", "USER_LOGIN");
        context.put("trace.id", "0123456789abcdef0123456789abcdef");
        context.put("span.id", "0123456789abcdef");
        context.put("correlation.id", "correlation-1");
        context.put("tenant.id", "tenant-1");
        context.put("tenant.region", "us-east-1");
        context.put("tenant.environment", "test");
        context.put("user.id", "user-1");
        context.put("session.id", "session-1");
        context.put("duration.ms", 42.5);
        context.put("error.type", "IllegalStateException");
        context.put("error.message", "retry later");
        context.put("error.stack", "stack trace");
        context.put("error.code", "E_RETRY");
        context.put("error.retryable", true);
        context.put("attributes", Map.of("request.id", "request-1"));

        String output = emit(ValidationMode.LENIENT, context);

        assertTrue(output.contains("\"event.name\":\"USER_LOGIN\""));
        assertFalse(output.contains("\"dt3.validation.errors\""));
    }

    @Test
    public void presentNullRequiredPropertyIsRejectedAsStructuredTypeError() {
        // "severity" is intentionally excluded from this test: the logger method always
        // controls severity and overwrites any caller-supplied null value before validation.
        // That canonical behavior is verified separately in loggerMethodSeverityCannotBeOverriddenByCallerContext.
        //
        // All other required fields can be null-overridden via caller context and therefore
        // generate a genuine "type" diagnostic without triggering a "required" diagnostic.
        for (String field : requiredFields()) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put(field, null);

            String output = emit(ValidationMode.LENIENT, context);

            assertTrue("Expected diagnostics for " + field, output.contains("\"dt3.validation.errors\""));
            assertStructuredError(output, field, "type", "Value has an invalid type");
            assertFalse(
                "A present-null value must not also be diagnosed as missing: " + field,
                output.contains("\"rule\":\"required\"")
            );
            assertTrue(
                "Invalid type values must be redacted for " + field,
                output.contains("\"" + field + "\":\"[REDACTED]\"")
            );
        }
    }

    @Test
    public void loggerMethodSeverityCannotBeOverriddenByCallerContext() {
        // Regression test: severity is always set by the logger method and cannot be
        // overridden by a null or non-null caller-supplied value in the context map.
        // logger.info() with Map.of("severity", null) must produce severity = INFO (no error).
        Map<String, Object> nullSeverityContext = new LinkedHashMap<>();
        nullSeverityContext.put("event.name", "SEVERITY_NULL_TEST");
        nullSeverityContext.put("severity", null);

        String nullOutput = emit(ValidationMode.LENIENT, nullSeverityContext);

        // No validation error expected: severity is always "INFO" from the logger method
        assertFalse(
            "severity=null from caller must not produce validation diagnostics (logger overrides it)",
            nullOutput.contains("\"dt3.validation.errors\"")
        );
        assertTrue(
            "Severity must remain INFO regardless of caller null",
            nullOutput.contains("\"severity\":\"INFO\"")
        );

        // logger.info() with Map.of("severity", "ERROR") must also produce INFO severity
        Map<String, Object> errorSeverityContext = new LinkedHashMap<>();
        errorSeverityContext.put("event.name", "SEVERITY_OVERRIDE_TEST");
        errorSeverityContext.put("severity", "ERROR");

        String errorOutput = emit(ValidationMode.LENIENT, errorSeverityContext);

        assertFalse(
            "severity=ERROR from caller must not produce validation diagnostics (logger overrides to INFO)",
            errorOutput.contains("\"dt3.validation.errors\"")
        );
        assertTrue(
            "Severity must remain INFO regardless of caller-supplied ERROR",
            errorOutput.contains("\"severity\":\"INFO\"")
        );
    }

    @Test
    public void missingRequiredPropertyIsReportedAsStructuredError() {
        SdkConfig config = validConfig(ValidationMode.LENIENT);
        config.setDeploymentEnvironment(null);
        Logger logger = LoggerFactory.createLogger(config);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        captureStdout(stdout, () -> {
            logger.info("Validation test", Map.of("event.name", "VALID_EVENT"));
            logger.flush();
        });

        String output = stdout.toString(StandardCharsets.UTF_8).trim();

        assertTrue(output.contains("\"dt3.validation.errors\""));
        assertStructuredError(
            output,
            "deployment.environment",
            "required",
            "Required property is missing"
        );
    }

    @Test
    public void lenientModeValidatesCanonicalFieldTypes() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sdk.name", 1);
        context.put("service.version", 2);
        context.put("deployment.environment", 3);
        context.put("correlation.id", 4);
        context.put("tenant.id", 5);
        context.put("tenant.region", 6);
        context.put("tenant.environment", 7);
        context.put("user.id", 8);
        context.put("session.id", 9);
        context.put("error.type", 10);
        context.put("error.message", 11);
        context.put("error.stack", 12);
        context.put("error.code", 13);
        context.put("attributes", java.util.List.of("not-an-object"));

        String output = emit(ValidationMode.LENIENT, context);

        assertTrue(output.contains("\"dt3.validation.errors\""));
        for (String field : context.keySet()) {
            assertStructuredError(output, field, "type", "Value has an invalid type");
            assertTrue(
                "Invalid type values must be redacted for " + field,
                output.contains("\"" + field + "\":\"[REDACTED]\"")
            );
        }
    }

    @Test
    public void attributesArrayScalarAndNullAreRejectedInLenientMode() {
        for (Object invalidAttributes : new Object[] {java.util.List.of("array"), "scalar", 1, null}) {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("attributes", invalidAttributes);

            String output = emit(ValidationMode.LENIENT, context);

            assertTrue(output.contains("\"dt3.validation.errors\""));
            assertStructuredError(output, "attributes", "type", "Value has an invalid type");
            assertTrue(output.contains("\"attributes\":\"[REDACTED]\""));
        }
    }

    @Test
    public void strictModeThrowsAndDoesNotWriteInvalidEventToStdout() {
        SdkConfig config = validConfig(ValidationMode.STRICT);
        Logger logger = LoggerFactory.createLogger(config);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        captureStdout(stdout, () -> assertThrows(
            IllegalArgumentException.class,
            () -> logger.info("Validation test", Map.of("sdk.name", 123))
        ));

        assertEquals("", stdout.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    public void strictValidationExceptionIsNotSwallowed() {
        SdkConfig config = validConfig(ValidationMode.STRICT);
        Logger logger = LoggerFactory.createLogger(config);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> logger.info("Validation test", Map.of("attributes", "secret-invalid-value"))
        );

        assertTrue(exception.getMessage().contains("Value has an invalid type"));
        assertFalse(exception.getMessage().contains("secret-invalid-value"));
    }

    @Test
    public void lenientDiagnosticsDoNotExposeCallerValuesAndMaskingRunsFirst() {
        SdkConfig config = validConfig(ValidationMode.LENIENT);
        config.setMaskingFields(java.util.List.of("sdk.name"));
        Logger logger = LoggerFactory.createLogger(config);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        captureStdout(stdout, () -> logger.info(
            "Validation test",
            Map.of("sdk.name", "caller-secret")
        ));

        String output = stdout.toString(StandardCharsets.UTF_8).trim();
        assertFalse(output.contains("caller-secret"));
        assertFalse(output.contains("\"dt3.validation.errors\""));
        assertTrue(output.contains("\"sdk.name\":\"[REDACTED]\""));
    }

    @Test
    public void lenientDiagnosticsDoNotExposeInvalidCallerValues() {
        String secret = "super-secret-invalid-attributes";
        String output = emit(ValidationMode.LENIENT, Map.of("attributes", secret));

        assertTrue(output.contains("\"dt3.validation.errors\""));
        assertStructuredError(output, "attributes", "type", "Value has an invalid type");
        assertFalse(output.contains(secret));
        assertTrue(output.contains("\"attributes\":\"[REDACTED]\""));
    }

    @Test
    public void offModeSkipsValidationAndDoesNotAttachErrors() {
        String output = emit(
            ValidationMode.OFF,
            Map.of(
                "sdk.name", 1,
                "attributes", java.util.List.of("invalid")
            )
        );

        assertTrue(output.contains("\"sdk.name\":1"));
        assertTrue(output.contains("\"attributes\":[\"invalid\"]"));
        assertFalse(output.contains("\"dt3.validation.errors\""));
    }

    @Test
    public void unknownTopLevelPropertiesRemainAccepted() {
        String output = emit(
            ValidationMode.LENIENT,
            Map.of(
                "event.name", "UNKNOWN_PROPERTY_EVENT",
                "application.unknown", Map.of("nested", true)
            )
        );

        assertTrue(output.contains("\"application.unknown\":{\"nested\":true}"));
        assertFalse(output.contains("\"dt3.validation.errors\""));
    }

    @Test
    public void nullValidationModeIsRejectedRatherThanDisablingValidation() {
        SdkConfig config = validConfig(null);
        Logger logger = LoggerFactory.createLogger(config);

        assertThrows(
            IllegalArgumentException.class,
            () -> logger.info("Validation test", Map.of("event.name", "USER_LOGIN"))
        );
    }

    private void assertStructuredError(String output, String field, String rule, String message) {
        assertTrue(
            "Expected field for " + field,
            output.contains("\"field\":\"" + field + "\"")
        );
        assertTrue(
            "Expected message for " + field,
            output.contains("\"message\":\"" + message + "\"")
        );
        assertTrue(
            "Expected rule for " + field,
            output.contains("\"rule\":\"" + rule + "\"")
        );
    }

    private String emit(ValidationMode validationMode, Map<String, Object> context) {
        SdkConfig config = validConfig(validationMode);
        Logger logger = LoggerFactory.createLogger(config);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        captureStdout(stdout, () -> {
            logger.info("Validation test", context);
            logger.flush();
        });

        return stdout.toString(StandardCharsets.UTF_8).trim();
    }

    private SdkConfig validConfig(ValidationMode validationMode) {
        SdkConfig config = new SdkConfig();
        config.setServiceName("validation-test");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        config.setValidationMode(validationMode);
        return config;
    }

    private void captureStdout(ByteArrayOutputStream stdout, Runnable action) {
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(originalOut);
        }
    }

    private String[] requiredFields() {
        // "severity" is intentionally excluded: the logger method always controls severity
        // and overwrites any caller-supplied null before validation runs. This means a
        // caller-supplied null severity never reaches the validator, so testing it here
        // would test the wrong invariant. The logger severity override is tested in
        // loggerMethodSeverityCannotBeOverriddenByCallerContext().
        return new String[] {
            "timestamp",
            "message",
            "event.name",
            "schema.version",
            "sdk.name",
            "sdk.version",
            "service.name",
            "service.version",
            "deployment.environment"
        };
    }
}
