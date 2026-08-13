"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
const src_1 = require("./src");
// ============================================================
// DT3 NODE / TYPESCRIPT SDK MANUAL TEST
// ============================================================
console.log("\n========================================");
console.log("       DT3 NODE / TYPESCRIPT SDK TEST");
console.log("========================================");
// ============================================================
// 1. NORMAL LOGGING TEST
// ============================================================
console.log("\n========================================");
console.log("1. NORMAL LOGGING TEST");
console.log("========================================");
const logger = (0, src_1.createLogger)({
    "service.name": "test-app",
    "service.version": "1.0.0",
    "deployment.environment": "test",
    "schema.version": "1.2.0",
    "exporter": "stdout",
    "masking.enabled": true,
});
console.log("\n--- INFO TEST ---");
logger.info("Application started", {
    "event.name": "APPLICATION_START",
});
console.log("\n--- DEBUG TEST ---");
logger.debug("Debug message", {
    "event.name": "DEBUG_TEST",
});
console.log("\n--- WARN TEST ---");
logger.warn("Something looks suspicious", {
    "event.name": "WARNING_TEST",
});
console.log("\n--- ERROR TEST ---");
try {
    throw new Error("Something went wrong");
}
catch (error) {
    logger.error("Operation failed", error, {
        "event.name": "ERROR_TEST",
    });
}
console.log("\n--- CONTEXT TEST ---");
logger.info("User logged in", {
    "event.name": "USER_LOGIN",
    "user.id": "12345",
    "action": "login",
});
console.log("\n--- TENANT TEST ---");
logger.info("Tenant operation", {
    "event.name": "TENANT_OPERATION",
    "tenant.id": "tenant-123",
    "tenant.region": "India",
    "tenant.environment": "test",
});
// ============================================================
// 2. MASKING TEST
// ============================================================
console.log("\n========================================");
console.log("2. MASKING TEST");
console.log("========================================");
logger.info("User login", {
    "event.name": "MASKING_TEST",
    username: "testuser",
    password: "secret123",
    token: "abc123",
    email: "test@example.com",
    api_key: "my-secret-key",
    nested: {
        password: "nested-secret",
        email: "nested@example.com",
    },
    users: [
        {
            password: "array-secret",
        },
    ],
    // Case-insensitive masking test
    Password: "case-secret",
    TOKEN: "case-token",
});
// ============================================================
// 3. VALID SCHEMA TEST - STRICT
// ============================================================
console.log("\n========================================");
console.log("3. VALID SCHEMA TEST - STRICT");
console.log("========================================");
const validSchemaLogger = (0, src_1.createLogger)({
    "service.name": "schema-test",
    "service.version": "1.0.0",
    "deployment.environment": "test",
    "schema.version": "1.2.0",
    "exporter": "stdout",
    "validation.mode": "STRICT",
});
try {
    validSchemaLogger.info("Valid schema event", {
        "event.name": "VALID_SCHEMA_TEST",
    });
    console.log("\nPASS: Valid event was accepted by STRICT validation.");
}
catch (error) {
    console.log("\nFAIL: Valid event was rejected.");
    console.log(error);
}
// ============================================================
// 4. LENIENT VALIDATION
//    deployment.environment intentionally missing
// ============================================================
console.log("\n========================================");
console.log("4. LENIENT VALIDATION TEST");
console.log("========================================");
const lenientLogger = (0, src_1.createLogger)({
    "service.name": "validation-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",
    // deployment.environment intentionally missing
    "validation.mode": "LENIENT",
});
try {
    lenientLogger.info("Testing missing required field", {
        "event.name": "LENIENT_TEST",
    });
    console.log("\nPASS: LENIENT validation allowed the event.");
    console.log("Expected: validation error should appear in the JSON output.");
    console.log("Expected missing field: deployment.environment");
}
catch (error) {
    console.log("\nFAIL: LENIENT validation stopped the event.");
    console.log(error);
}
// ============================================================
// 5. STRICT VALIDATION
//    deployment.environment intentionally missing
// ============================================================
console.log("\n========================================");
console.log("5. STRICT VALIDATION TEST");
console.log("========================================");
const strictLogger = (0, src_1.createLogger)({
    "service.name": "strict-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",
    // deployment.environment intentionally missing
    "validation.mode": "STRICT",
});
try {
    strictLogger.info("This event should fail validation", {
        "event.name": "STRICT_TEST",
    });
    console.log("\nFAIL: STRICT validation accepted the invalid event.");
}
catch (error) {
    console.log("\nPASS: STRICT validation rejected the invalid event.");
    console.log("Validation error:", error);
}
// ============================================================
// 6. VALIDATION OFF
//    deployment.environment intentionally missing
// ============================================================
console.log("\n========================================");
console.log("6. VALIDATION OFF TEST");
console.log("========================================");
const validationOffLogger = (0, src_1.createLogger)({
    "service.name": "off-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",
    // deployment.environment intentionally missing
    "validation.mode": "OFF",
});
try {
    validationOffLogger.info("Validation is disabled", {
        "event.name": "VALIDATION_OFF_TEST",
    });
    console.log("\nPASS: Event was emitted with validation OFF.");
}
catch (error) {
    console.log("\nFAIL: Event failed even though validation is OFF.");
    console.log(error);
}
// ============================================================
// 7. DEFAULT VALIDATION MODE TEST
// ============================================================
console.log("\n========================================");
console.log("7. DEFAULT VALIDATION MODE TEST");
console.log("========================================");
const defaultLogger = (0, src_1.createLogger)({
    "service.name": "default-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",
    // validation.mode intentionally NOT specified
    // SDK should use its default validation mode.
});
try {
    defaultLogger.info("Testing default validation mode", {
        "event.name": "DEFAULT_VALIDATION_TEST",
    });
    console.log("\nPASS: Default validation mode allowed the event.");
    console.log("Expected default behavior: LENIENT.");
}
catch (error) {
    console.log("\nFAIL: Default validation rejected the event.");
    console.log(error);
}
// ============================================================
// 8. FLUSH TEST
// ============================================================
console.log("\n========================================");
console.log("8. FLUSH TEST");
console.log("========================================");
if (typeof logger.flush === "function") {
    logger.flush();
    console.log("PASS: Flush completed.");
}
else {
    console.log("INFO: flush() is not implemented.");
}
// ============================================================
// COMPLETE
// ============================================================
console.log("\n========================================");
console.log("   ALL NODE / TYPESCRIPT TESTS COMPLETED");
console.log("========================================");
