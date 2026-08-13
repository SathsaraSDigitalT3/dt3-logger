from dt3_sdk import create_logger

logger = create_logger({
    "service.name": "test-app",
    "service.version": "1.0.0",
    "deployment.environment": "test",
    "schema.version": "1.2.0",
    "exporter": "stdout"
})

print("\n--- INFO TEST ---")
logger.info("Application started")

print("\n--- DEBUG TEST ---")
logger.debug("Debug message")

print("\n--- WARN TEST ---")
logger.warn("Something looks suspicious")

print("\n--- ERROR TEST ---")
try:
    10 / 0
except Exception as e:
    logger.error("Calculation failed", error=e)

print("\n--- CONTEXT TEST ---")
logger.info(
    "User logged in",
    context={
        "event.name": "USER_LOGIN",
        "user.id": "12345",
        "action": "login"
    }

)

print("\n--- MASKING TEST ---")

logger.info(
    "User login",
    context={
        "username": "testuser",
        "password": "secret123",
        "token": "abc123",
        "email": "test@example.com"
    }
)



print("\n--- FLUSH TEST ---")
logger.flush()
print("Flush completed")

