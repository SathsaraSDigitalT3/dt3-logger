from dt3_sdk import create_logger


print("\n========================================")
print("       DT3 PYTHON SDK TEST")
print("========================================")


# ============================================================
# 1. NORMAL LOGGING TEST
# ============================================================

print("\n========================================")
print("1. NORMAL LOGGING TEST")
print("========================================")

logger = create_logger({
    "service.name": "test-app",
    "service.version": "1.0.0",
    "deployment.environment": "test",
    "schema.version": "1.2.0",
    "exporter": "stdout",
    "masking.enabled": True,
})

print("\n--- INFO TEST ---")

logger.info(
    "Application started",
    context={
        "event.name": "APPLICATION_START"
    }
)


print("\n--- DEBUG TEST ---")

logger.debug(
    "Debug message",
    context={
        "event.name": "DEBUG_TEST"
    }
)


print("\n--- WARN TEST ---")

logger.warn(
    "Something looks suspicious",
    context={
        "event.name": "WARNING_TEST"
    }
)


print("\n--- ERROR TEST ---")

try:
    10 / 0
except Exception as e:
    logger.error(
        "Calculation failed",
        error=e,
        context={
            "event.name": "ERROR_TEST"
        }
    )


print("\n--- CONTEXT TEST ---")

logger.info(
    "User logged in",
    context={
        "event.name": "USER_LOGIN",
        "user.id": "12345",
        "action": "login"
    }
)


print("\n--- TENANT TEST ---")

logger.info(
    "Tenant operation",
    context={
        "event.name": "TENANT_OPERATION",
        "tenant.id": "tenant-123",
        "tenant.region": "India",
        "tenant.environment": "test"
    }
)


# ============================================================
# 2. MASKING TEST
# ============================================================

print("\n========================================")
print("2. MASKING TEST")
print("========================================")

logger.info(
    "User login",
    context={
        "event.name": "MASKING_TEST",
        "username": "testuser",
        "password": "secret123",
        "token": "abc123",
        "email": "test@example.com",
        "api_key": "my-secret-key",

        "nested": {
            "password": "nested-secret",
            "email": "nested@example.com"
        },

        "users": [
            {
                "password": "array-secret"
            }
        ],

        # Case-insensitive masking test
        "Password": "case-secret",
        "TOKEN": "case-token"
    }
)


# ============================================================
# 3. VALID SCHEMA TEST - STRICT
# ============================================================

print("\n========================================")
print("3. VALID SCHEMA TEST - STRICT")
print("========================================")

valid_schema_logger = create_logger({
    "service.name": "schema-test",
    "service.version": "1.0.0",
    "deployment.environment": "test",
    "schema.version": "1.2.0",
    "exporter": "stdout",

    "validation.mode": "STRICT"
})

try:

    valid_schema_logger.info(
        "Valid schema event",
        context={
            "event.name": "VALID_SCHEMA_TEST"
        }
    )

    print("\nPASS: Valid event was accepted by STRICT validation.")

except Exception as e:

    print("\nFAIL: Valid event was rejected.")
    print("Validation error:", e)


# ============================================================
# 4. LENIENT VALIDATION TEST
#
# deployment.environment is intentionally missing
# ============================================================

print("\n========================================")
print("4. LENIENT VALIDATION TEST")
print("========================================")

lenient_logger = create_logger({
    "service.name": "validation-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",

    # deployment.environment intentionally missing
    "validation.mode": "LENIENT"
})

try:

    lenient_logger.info(
        "Testing missing required field",
        context={
            "event.name": "LENIENT_TEST"
        }
    )

    print("\nPASS: LENIENT validation allowed the event.")
    print(
        "Expected: validation error should appear in the JSON output."
    )
    print(
        "Expected missing field: deployment.environment"
    )

except Exception as e:

    print("\nFAIL: LENIENT validation unexpectedly stopped the event.")
    print("Error:", e)


# ============================================================
# 5. STRICT VALIDATION TEST
#
# deployment.environment is intentionally missing
# ============================================================

print("\n========================================")
print("5. STRICT VALIDATION TEST")
print("========================================")

strict_logger = create_logger({
    "service.name": "strict-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",

    # deployment.environment intentionally missing
    "validation.mode": "STRICT"
})

try:

    strict_logger.info(
        "This event should fail validation",
        context={
            "event.name": "STRICT_TEST"
        }
    )

    print(
        "\nFAIL: STRICT validation accepted the invalid event."
    )

except Exception as e:

    print(
        "\nPASS: STRICT validation rejected the invalid event."
    )

    print(
        "Validation error:",
        e
    )


# ============================================================
# 6. VALIDATION OFF TEST
#
# deployment.environment is intentionally missing
# ============================================================

print("\n========================================")
print("6. VALIDATION OFF TEST")
print("========================================")

validation_off_logger = create_logger({
    "service.name": "off-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",

    # deployment.environment intentionally missing
    "validation.mode": "OFF"
})

try:

    validation_off_logger.info(
        "Validation is disabled",
        context={
            "event.name": "VALIDATION_OFF_TEST"
        }
    )

    print(
        "\nPASS: Event was emitted with validation OFF."
    )

except Exception as e:

    print(
        "\nFAIL: Event failed even though validation is OFF."
    )

    print("Error:", e)


# ============================================================
# 7. DEFAULT VALIDATION MODE TEST
#
# validation.mode is intentionally NOT configured
# ============================================================

print("\n========================================")
print("7. DEFAULT VALIDATION MODE TEST")
print("========================================")

default_logger = create_logger({
    "service.name": "default-test",
    "service.version": "1.0.0",
    "schema.version": "1.2.0",
    "exporter": "stdout",

    # validation.mode intentionally missing
    # Expected default: LENIENT
})

try:

    default_logger.info(
        "Testing default validation mode",
        context={
            "event.name": "DEFAULT_VALIDATION_TEST"
        }
    )

    print(
        "\nPASS: Default validation mode allowed the event."
    )

    print(
        "Expected default behavior: LENIENT."
    )

except Exception as e:

    print(
        "\nFAIL: Default validation mode rejected the event."
    )

    print("Error:", e)


# ============================================================
# 8. FLUSH TEST
# ============================================================

print("\n========================================")
print("8. FLUSH TEST")
print("========================================")

logger.flush()

print("Flush completed")


# ============================================================
# COMPLETE
# ============================================================

print("\n========================================")
print("   ALL PYTHON SDK TESTS COMPLETED")
print("========================================")