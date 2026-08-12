package com.digitalt3.commons;

import org.junit.Test;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class LoggerTest {

    @Test
    public void testLoggerCompleteFlow() {

        System.out.println("\n==============================");
        System.out.println("DT3 JAVA LOGGER TEST");
        System.out.println("==============================");

        SdkConfig config = new SdkConfig();
        config.setServiceName("test-app");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        config.setExporter("stdout");
        config.setMaskingEnabled(true);

        Logger logger = LoggerFactory.createLogger(config);

        assertNotNull(logger);

        // ---------------- INFO ----------------
        System.out.println("\n--- INFO TEST ---");

        logger.info(
                "Application started",
                Map.of("event.name", "APPLICATION_START")
        );

        // ---------------- DEBUG ----------------
        System.out.println("\n--- DEBUG TEST ---");

        logger.debug(
                "Debug message",
                Map.of("event.name", "DEBUG_TEST")
        );

        // ---------------- WARN ----------------
        System.out.println("\n--- WARN TEST ---");

        logger.warn(
                "Something looks suspicious",
                Map.of("event.name", "WARNING_TEST")
        );

        // ---------------- ERROR ----------------
        System.out.println("\n--- ERROR TEST ---");

        Exception exception = new RuntimeException("Something went wrong");

        logger.error(
                "Operation failed",
                exception,
                Map.of("event.name", "ERROR_TEST")
        );

        // ---------------- CONTEXT ----------------
        System.out.println("\n--- CONTEXT TEST ---");

        logger.info(
                "User logged in",
                Map.of(
                        "event.name", "USER_LOGIN",
                        "user.id", "12345",
                        "action", "login"
                )
        );

        // ---------------- TENANT ----------------
        System.out.println("\n--- TENANT TEST ---");

        logger.info(
                "Tenant operation",
                Map.of(
                        "event.name", "TENANT_OPERATION",
                        "tenant.id", "tenant-123",
                        "tenant.region", "India",
                        "tenant.environment", "test"
                )
        );

        // ---------------- MASKING ----------------
        System.out.println("\n--- MASKING TEST ---");

        Map<String, Object> sensitiveData = new HashMap<>();

        sensitiveData.put("event.name", "USER_LOGIN");
        sensitiveData.put("username", "testuser");
        sensitiveData.put("password", "secret123");
        sensitiveData.put("token", "abc123");
        sensitiveData.put("email", "test@example.com");
        sensitiveData.put("api_key", "key123");

        Map<String, Object> nested = new HashMap<>();
        nested.put("password", "nested-secret");
        nested.put("email", "nested@example.com");

        sensitiveData.put("nested", nested);

        logger.info("User login", sensitiveData);

        // ---------------- FLUSH ----------------
        System.out.println("\n--- FLUSH TEST ---");

        logger.flush();

        System.out.println("Flush completed");

        System.out.println("\n==============================");
        System.out.println("TEST COMPLETED");
        System.out.println("==============================");
    }
}