from dt3_sdk import create_logger

logger = create_logger({
    "service.name": "my-service",
    "service.version": "1.0.0",
    "deployment.environment": "dev",
    "exporter": "stdout"
})

logger.info("User login completed", {
    "event.name": "USER_LOGIN",
    "tenant.id": "tenant-123",
    "attributes": {"login.method": "oauth"}
})
