package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end verification of the Java SDK's currently supported stdout logging baseline.
 */
public class JavaSdkEndToEndTest {

    @Test
    public void loggerEmitsStructuredMaskedJsonForAllSupportedOperations() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("test-service");
        config.setServiceVersion("1.2.3");
        config.setDeploymentEnvironment("test");
        config.setSchemaVersion("1.0.0");
        config.setMaskingTrackMaskedFields(true);
        config.setMaskingFields(List.of("internalId"));

        Logger logger = LoggerFactory.createLogger(config);

        Map<String, Object> sensitiveContext = new LinkedHashMap<>();
        sensitiveContext.put("event.name", "USER_LOGIN");
        sensitiveContext.put("user.id", "12345");
        sensitiveContext.put("tenant.id", "tenant-123");
        sensitiveContext.put("tenant.region", "India");
        sensitiveContext.put("tenant.environment", "test");
        sensitiveContext.put("action", "login");
        sensitiveContext.put("password", "secret123");
        sensitiveContext.put("Password", "case-sensitive-secret");
        sensitiveContext.put("internalId", "customer-1");
        sensitiveContext.put(
            "nested",
            Map.of("email", "nested@example.com", "safe", "visible")
        );
        sensitiveContext.put(
            "users",
            List.of(Map.of("TOKEN", "array-token"), Map.of("name", "Ada"))
        );

        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));

        try {
            logger.debug("Debug message", Map.of("event.name", "DEBUG_EVENT"));
            logger.info("User logged in", sensitiveContext);
            logger.warn("Warning message", Map.of("event.name", "WARN_EVENT"));
            logger.error(
                "Operation failed",
                new IllegalStateException("Something went wrong"),
                Map.of("event.name", "ERROR_EVENT")
            );
            logger.flush();
        } finally {
            System.setOut(originalOut);
        }

        String[] lines = stdout.toString(StandardCharsets.UTF_8).trim().split("\\R");
        assertEquals("Expected one JSON event per logging method.", 4, lines.length);

        String debug = lines[0];
        assertTrue(debug.startsWith("{"));
        assertTrue(debug.endsWith("}"));
        assertTrue(debug.contains("\"severity\":\"DEBUG\""));
        assertTrue(debug.contains("\"event.name\":\"DEBUG_EVENT\""));
        assertRequiredMetadata(debug);

        String info = lines[1];
        assertTrue(info.contains("\"severity\":\"INFO\""));
        assertTrue(info.contains("\"message\":\"User logged in\""));
        assertTrue(info.contains("\"event.name\":\"USER_LOGIN\""));
        assertTrue(info.contains("\"user.id\":\"12345\""));
        assertTrue(info.contains("\"tenant.id\":\"tenant-123\""));
        assertTrue(info.contains("\"tenant.region\":\"India\""));
        assertTrue(info.contains("\"tenant.environment\":\"test\""));
        assertTrue(info.contains("\"action\":\"login\""));
        assertTrue(info.contains("\"password\":\"[REDACTED]\""));
        assertTrue(info.contains("\"Password\":\"[REDACTED]\""));
        assertTrue(info.contains("\"internalId\":\"[REDACTED]\""));
        assertTrue(info.contains("\"email\":\"[REDACTED]\""));
        assertTrue(info.contains("\"TOKEN\":\"[REDACTED]\""));
        assertTrue(info.contains("\"safe\":\"visible\""));
        assertTrue(info.contains("\"name\":\"Ada\""));
        assertTrue(info.contains("\"dt3.security.masked_fields\""));
        assertTrue(info.contains("\"password\""));
        assertTrue(info.contains("\"Password\""));
        assertTrue(info.contains("\"internalId\""));
        assertTrue(info.contains("\"nested.email\""));
        assertTrue(info.contains("\"users[0].TOKEN\""));
        assertFalse(info.contains("secret123"));
        assertFalse(info.contains("nested@example.com"));
        assertFalse(info.contains("array-token"));

        String warn = lines[2];
        assertTrue(warn.contains("\"severity\":\"WARN\""));
        assertTrue(warn.contains("\"event.name\":\"WARN_EVENT\""));

        String error = lines[3];
        assertTrue(error.contains("\"severity\":\"ERROR\""));
        assertTrue(error.contains("\"event.name\":\"ERROR_EVENT\""));
        assertTrue(error.contains("\"error.type\":\"IllegalStateException\""));
        assertTrue(error.contains("\"error.message\":\"Something went wrong\""));
        assertTrue(error.contains("\"error.stack\""));

        assertEquals("secret123", sensitiveContext.get("password"));
        assertEquals(
            "nested@example.com",
            ((Map<?, ?>) sensitiveContext.get("nested")).get("email")
        );
        assertEquals(
            "array-token",
            ((Map<?, ?>) ((List<?>) sensitiveContext.get("users")).get(0)).get("TOKEN")
        );
    }

    private void assertRequiredMetadata(String event) {
        assertTrue(event.contains("\"timestamp\":"));
        assertTrue(event.contains("\"schema.version\":\"1.0.0\""));
        assertTrue(event.contains("\"sdk.name\":\"dt3-commons-java\""));
        assertTrue(event.contains("\"sdk.version\":\"0.1.0\""));
        assertTrue(event.contains("\"service.name\":\"test-service\""));
        assertTrue(event.contains("\"service.version\":\"1.2.3\""));
        assertTrue(event.contains("\"deployment.environment\":\"test\""));
    }
}
