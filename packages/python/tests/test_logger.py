from dt3_sdk import create_logger

def test_logger_creation():
    logger = create_logger({
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test"
    })
    assert logger is not None
