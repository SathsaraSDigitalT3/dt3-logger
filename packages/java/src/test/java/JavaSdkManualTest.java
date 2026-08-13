package com.digitalt3.commons;

import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;

import java.util.HashMap;
import java.util.Map;

public class JavaSdkManualTest {

    public static void main(String[] args) {

        System.out.println("\n========================================");
        System.out.println("       DT3 JAVA SDK MANUAL TEST");
        System.out.println("========================================");

        testNormalLogging();
        testMasking();
        testValidSchema();
        testLenientMissingRequiredField();
        testStrictMissingRequiredField();
        testValidationOff();

        System.out.println("\n========================================");
        System.out.println("       ALL MANUAL TESTS COMPLETED");
        System.out.println("========================================");
    }

    // ============================================================
    // 1. NORMAL LOGGING TEST
    // ============================================================

    private static void testNormalLogging() {

        System.out.println("\n\n========================================");
        System.out.println("1. NORMAL LOGGING TEST");
        System.out.println("========================================");

        SdkConfig config = createValidConfig();

        Logger logger = LoggerFactory.createLogger(config);

        System.out.println("\n--- INFO ---");

        logger.info(
                "Application started",
                Map.of("event.name", "APPLICATION_START")
        );

        System.out.println("\n--- DEBUG ---");

        logger.debug(
                "Debug message",
                Map.of("event.name", "DEBUG_TEST")
        );

        System.out.println("\n--- WARN ---");

        logger.warn(
                "Something looks suspicious",
                Map.of("event.name", "WARNING_TEST")
        );

        System.out.println("\n--- ERROR ---");

        Exception exception =
                new RuntimeException("Something went wrong");

        logger.error(
                "Operation failed",
                exception,
                Map.of("event.name", "ERROR_TEST")
        );

        System.out.println("\n--- CONTEXT ---");

        logger.info(
                "User logged in",
                Map.of(
                        "event.name", "USER_LOGIN",
                        "user.id", "12345",
                        "action", "login"
                )
        );

        System.out.println("\n--- TENANT ---");

        logger.info(
                "Tenant operation",
                Map.of(
                        "event.name", "TENANT_OPERATION",
                        "tenant.id", "tenant-123",
                        "tenant.region", "India",
                        "tenant.environment", "test"
                )
        );

        logger.flush();

        System.out.println("\nNormal logging test completed.");
    }

    // ============================================================
    // 2. MASKING TEST
    // ============================================================

    private static void testMasking() {

        System.out.println("\n\n========================================");
        System.out.println("2. MASKING TEST");
        System.out.println("========================================");

        SdkConfig config = createValidConfig();

        config.setMaskingEnabled(true);

        Logger logger = LoggerFactory.createLogger(config);

        Map<String, Object> sensitiveData = new HashMap<>();

        sensitiveData.put("event.name", "MASKING_TEST");
        sensitiveData.put("username", "testuser");
        sensitiveData.put("password", "secret123");
        sensitiveData.put("token", "abc123");
        sensitiveData.put("email", "test@example.com");
        sensitiveData.put("api_key", "key123");

        Map<String, Object> nested = new HashMap<>();

        nested.put("password", "nested-secret");
        nested.put("email", "nested@example.com");

        sensitiveData.put("nested", nested);

        logger.info(
                "Testing sensitive data masking",
                sensitiveData
        );

        logger.flush();

        System.out.println(
                "\nCheck the output above: sensitive values should be masked."
        );
    }

    // ============================================================
    // 3. VALID SCHEMA TEST
    // ============================================================

    private static void testValidSchema() {

        System.out.println("\n\n========================================");
        System.out.println("3. VALID SCHEMA TEST");
        System.out.println("========================================");

        SdkConfig config = createValidConfig();

        config.setValidationMode(ValidationMode.STRICT);

        Logger logger = LoggerFactory.createLogger(config);

        try {

            logger.info(
                    "Valid schema event",
                    Map.of(
                            "event.name",
                            "VALID_SCHEMA_TEST"
                    )
            );

            logger.flush();

            System.out.println(
                    "\nPASS: Valid event was accepted by schema validation."
            );

        } catch (Exception e) {

            System.out.println(
                    "\nFAIL: Valid event was rejected."
            );

            System.out.println(
                    "Error: " + e.getMessage()
            );
        }
    }

    // ============================================================
    // 4. LENIENT - MISSING REQUIRED FIELD
    // ============================================================

    private static void testLenientMissingRequiredField() {

        System.out.println("\n\n========================================");
        System.out.println("4. LENIENT MISSING REQUIRED FIELD TEST");
        System.out.println("========================================");

        SdkConfig config = new SdkConfig();

        config.setServiceName("validation-test");
        config.setServiceVersion("1.0.0");

        // deployment.environment intentionally NOT configured.

        config.setExporter("stdout");
        config.setValidationMode(ValidationMode.LENIENT);

        Logger logger = LoggerFactory.createLogger(config);

        try {

            logger.info(
                    "Testing missing required property",
                    Map.of(
                            "event.name",
                            "MISSING_ENVIRONMENT_TEST"
                    )
            );

            logger.flush();

            System.out.println("\nExpected:");

            System.out.println(
                    "deployment.environment should be reported as missing."
            );

            System.out.println(
                    "The event should NOT contain deployment.environment=unknown."
            );

        } catch (Exception e) {

            System.out.println(
                    "\nFAIL: LENIENT validation threw an exception."
            );

            System.out.println(
                    "Error: " + e.getMessage()
            );
        }
    }

    // ============================================================
    // 5. STRICT - MISSING REQUIRED FIELD
    // ============================================================

    private static void testStrictMissingRequiredField() {

        System.out.println("\n\n========================================");
        System.out.println("5. STRICT MISSING REQUIRED FIELD TEST");
        System.out.println("========================================");

        SdkConfig config = new SdkConfig();

        config.setServiceName("strict-test");
        config.setServiceVersion("1.0.0");

        // deployment.environment intentionally NOT configured.

        config.setExporter("stdout");
        config.setValidationMode(ValidationMode.STRICT);

        Logger logger = LoggerFactory.createLogger(config);

        try {

            logger.info(
                    "This event should fail validation",
                    Map.of(
                            "event.name",
                            "STRICT_VALIDATION_TEST"
                    )
            );

            System.out.println(
                    "\nFAIL: Event was accepted."
            );

        } catch (Exception e) {

            System.out.println(
                    "\nPASS: STRICT validation rejected the invalid event."
            );

            System.out.println(
                    "Exception: " + e.getMessage()
            );
        }
    }

    // ============================================================
    // 6. OFF - MISSING REQUIRED FIELD
    // ============================================================

    private static void testValidationOff() {

        System.out.println("\n\n========================================");
        System.out.println("6. VALIDATION OFF TEST");
        System.out.println("========================================");

        SdkConfig config = new SdkConfig();

        config.setServiceName("off-test");
        config.setServiceVersion("1.0.0");

        // deployment.environment intentionally NOT configured.

        config.setExporter("stdout");
        config.setValidationMode(ValidationMode.OFF);

        Logger logger = LoggerFactory.createLogger(config);

        try {

            logger.info(
                    "Validation is disabled",
                    Map.of(
                            "event.name",
                            "VALIDATION_OFF_TEST"
                    )
            );

            logger.flush();

            System.out.println(
                    "\nPASS: Event was emitted with validation OFF."
            );

        } catch (Exception e) {

            System.out.println(
                    "\nFAIL: Event failed even though validation is OFF."
            );

            System.out.println(
                    "Error: " + e.getMessage()
            );
        }
    }

    // ============================================================
    // COMMON VALID CONFIGURATION
    // ============================================================

    private static SdkConfig createValidConfig() {

        SdkConfig config = new SdkConfig();

        config.setServiceName("test-app");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        config.setExporter("stdout");
        config.setMaskingEnabled(true);

        return config;
    }
}