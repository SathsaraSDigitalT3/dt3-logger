from unittest.mock import patch

import pytest

from dt3_sdk import create_logger
from dt3_sdk.http_transport import HttpTransport


def test_logger_creation():
    logger = create_logger({
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test"
    })
    assert logger is not None


def test_legacy_http_timeout_alias_remains_in_seconds():
    with patch.object(HttpTransport, "__init__", return_value=None) as transport_init:
        create_logger({
            "service.name": "test-service",
            "service.version": "1.0.0",
            "deployment.environment": "test",
            "exporter": "http",
            "http.endpoint": "https://collector.example.test/events",
            "http.timeout": 5,
        })

    assert transport_init.call_args.kwargs["timeout"] == 5.0


def test_canonical_http_timeout_remains_in_milliseconds():
    with patch.object(HttpTransport, "__init__", return_value=None) as transport_init:
        create_logger({
            "service.name": "test-service",
            "service.version": "1.0.0",
            "deployment.environment": "test",
            "exporter": "http",
            "exporter.http.endpoint": "https://collector.example.test/events",
            "exporter.http.timeout": 5000,
            "http.timeout": 1,
        })

    assert transport_init.call_args.kwargs["timeout"] == 5.0


@pytest.mark.parametrize("fail_open", [True, False])
def test_closed_logger_rejects_log_and_flush_operations(fail_open):
    logger = create_logger({
        "service.name": "test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "fail_open": fail_open,
    })

    logger.close()
    logger.close()

    with pytest.raises(RuntimeError, match="Logger is closed"):
        logger.info("Closed logger", {"event.name": "CLOSED_LOGGER"})

    with pytest.raises(RuntimeError, match="Logger is closed"):
        logger.flush()
