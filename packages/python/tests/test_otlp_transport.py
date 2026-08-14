"""Automated tests for the DT3 Python SDK synchronous OTLP transport."""

from __future__ import annotations

import json
from typing import Any
from urllib.error import HTTPError, URLError

import pytest

from dt3_sdk import (
    OtlpTransport,
    OtlpTransportError,
    ValidationError,
    create_logger,
)


class _Response:
    """HTTP response double with a configurable status code."""

    def __init__(self, status: int = 200) -> None:
        """Initialize a response double."""
        self._status = status

    def __enter__(self) -> "_Response":
        """Enter the response context-manager lifecycle."""
        return self

    def __exit__(self, exc_type: object, exc_value: object, traceback: object) -> bool:
        """Exit the response context-manager lifecycle."""
        return False

    def getcode(self) -> int:
        """Return the configured status code."""
        return self._status


def _config(validation_mode: str = "LENIENT", **overrides: object) -> dict[str, object]:
    """Build a valid OTLP-exporting SDK configuration for a test."""
    return {
        "service.name": "otlp-test-service",
        "service.version": "1.2.3",
        "deployment.environment": "test",
        "exporter": "otlp",
        "otlp.endpoint": "https://collector.example.test/v1/logs",
        "otlp.timeout": 3.5,
        "validation.mode": validation_mode,
        **overrides,
    }


def _payload(request: Any) -> dict[str, Any]:
    """Decode a captured OTLP JSON request body."""
    return json.loads(request.data.decode("utf-8"))


def _attributes(entries: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Index OTLP attribute objects by their keys."""
    return {entry["key"]: entry["value"] for entry in entries}


def _record(payload: dict[str, Any]) -> dict[str, Any]:
    """Return the single OTLP record contained in a test payload."""
    return payload["resourceLogs"][0]["scopeLogs"][0]["logRecords"][0]


def test_otlp_exporter_posts_standard_otlp_logs_json_payload(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """OTLP export must POST an OTLP Logs JSON request to the configured endpoint."""
    captured: dict[str, Any] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        captured["timeout"] = timeout
        return _Response(202)

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", fake_urlopen)
    logger = create_logger(
        _config(**{"otlp.headers": {"Authorization": "Bearer integration-token"}})
    )

    logger.info(
        "Connected",
        {
            "event.name": "OTLP_EXPORT_STARTED",
            "tenant.id": "tenant-17",
            "attributes": {"region": "east"},
        },
    )

    request = captured["request"]
    assert request.full_url == "https://collector.example.test/v1/logs"
    assert request.get_method() == "POST"
    assert request.get_header("Content-type") == "application/json"
    assert request.get_header("Authorization") == "Bearer integration-token"
    assert captured["timeout"] == 3.5

    payload = _payload(request)
    record = _record(payload)
    resources = _attributes(payload["resourceLogs"][0]["resource"]["attributes"])
    log_attributes = _attributes(record["attributes"])

    assert record["body"] == {"stringValue": "Connected"}
    assert record["severityText"] == "INFO"
    assert record["severityNumber"] == 9
    assert resources["service.name"] == {"stringValue": "otlp-test-service"}
    assert resources["service.version"] == {"stringValue": "1.2.3"}
    assert resources["deployment.environment"] == {"stringValue": "test"}
    assert resources["tenant.id"] == {"stringValue": "tenant-17"}
    assert log_attributes["event.name"] == {"stringValue": "OTLP_EXPORT_STARTED"}
    assert log_attributes["attributes"]["kvlistValue"]["values"] == [
        {"key": "region", "value": {"stringValue": "east"}}
    ]


@pytest.mark.parametrize(
    ("severity", "expected_number"),
    [
        ("DEBUG", 5),
        ("INFO", 9),
        ("WARN", 13),
        ("ERROR", 17),
    ],
)
def test_otlp_severity_mapping(severity: str, expected_number: int) -> None:
    """DT3 severity values must map to their OTLP severity number equivalents."""
    payload = OtlpTransport.to_otlp_payload(
        {
            "timestamp": "2026-08-14T06:20:35+00:00",
            "severity": severity,
            "message": "Test event",
        }
    )

    record = _record(payload)
    assert record["severityText"] == severity
    assert record["severityNumber"] == expected_number


def test_otlp_timestamp_and_error_details_are_preserved() -> None:
    """The OTLP record must retain timestamp and structured DT3 error details."""
    payload = OtlpTransport.to_otlp_payload(
        {
            "timestamp": "1970-01-01T00:00:01+00:00",
            "severity": "ERROR",
            "message": "Operation failed",
            "error.type": "ValueError",
            "error.message": "Invalid input",
            "error.stack": "traceback text",
        }
    )

    record = _record(payload)
    attributes = _attributes(record["attributes"])
    assert record["timeUnixNano"] == "1000000000"
    assert attributes["error.type"] == {"stringValue": "ValueError"}
    assert attributes["error.message"] == {"stringValue": "Invalid input"}
    assert attributes["error.stack"] == {"stringValue": "traceback text"}


@pytest.mark.parametrize(
    ("endpoint", "message"),
    [
        ("", "otlp.endpoint"),
        ("   ", "otlp.endpoint"),
    ],
)
def test_otlp_transport_rejects_invalid_endpoints(
    endpoint: str,
    message: str,
) -> None:
    """Empty or whitespace OTLP endpoints must fail during transport creation."""
    with pytest.raises(ValueError, match=message):
        OtlpTransport(endpoint)


@pytest.mark.parametrize(
    ("headers", "message"),
    [
        (["Authorization", "token"], "otlp.headers must be a mapping"),
        ({1: "token"}, "invalid header name"),
        ({"Authorization": 123}, "string header value"),
        ({"X-Unsafe": "value\r\nInjected: true"}, "invalid header value"),
    ],
)
def test_otlp_transport_rejects_invalid_headers(
    headers: object,
    message: str,
) -> None:
    """Invalid OTLP header configuration must fail before export."""
    with pytest.raises(ValueError, match=message):
        OtlpTransport("https://collector.example.test/v1/logs", headers=headers)


def test_otlp_preserves_masked_data_and_never_exports_raw_secrets(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Logger masking must finish before the OTLP transport sees the final event."""
    captured: dict[str, Any] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        return _Response()

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", fake_urlopen)
    logger = create_logger(
        _config(
            **{
                "masking.replacement_value": "***",
                "masking.track_masked_fields": True,
            }
        )
    )

    logger.info(
        "Masked payload",
        {
            "event.name": "OTLP_MASKED_EVENT",
            "password": "do-not-export",
            "attributes": {"token": "nested-secret"},
        },
    )

    encoded_payload = captured["request"].data.decode("utf-8")
    attributes = _attributes(_record(_payload(captured["request"]))["attributes"])
    assert "do-not-export" not in encoded_payload
    assert "nested-secret" not in encoded_payload
    assert attributes["password"] == {"stringValue": "***"}
    assert attributes["attributes"]["kvlistValue"]["values"] == [
        {"key": "token", "value": {"stringValue": "***"}}
    ]


def test_strict_validation_prevents_otlp_export(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """STRICT validation must reject invalid events before the OTLP request."""
    exports = 0

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        nonlocal exports
        exports += 1
        return _Response()

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", fake_urlopen)
    logger = create_logger(_config("STRICT"))

    with pytest.raises(ValidationError):
        logger.info("Invalid", {"event.name": "invalid-name"})

    assert exports == 0


def test_lenient_validation_exports_structured_diagnostics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """LENIENT validation must preserve diagnostics in OTLP record attributes."""
    captured: dict[str, Any] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        return _Response()

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", fake_urlopen)
    logger = create_logger(_config("LENIENT"))

    logger.info("Invalid", {"event.name": "invalid-name"})

    attributes = _attributes(_record(_payload(captured["request"]))["attributes"])
    errors = attributes["dt3.validation.errors"]["arrayValue"]["values"]
    assert any(
        error["kvlistValue"]["values"][0]["key"] == "field"
        for error in errors
    )


def test_off_validation_exports_without_diagnostics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """OFF mode must export invalid content without validation diagnostics."""
    captured: dict[str, Any] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        return _Response()

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", fake_urlopen)
    logger = create_logger(_config("OFF"))

    logger.info("Bypass validation", {"event.name": "invalid-name", "duration.ms": -1})

    attributes = _attributes(_record(_payload(captured["request"]))["attributes"])
    assert attributes["event.name"] == {"stringValue": "invalid-name"}
    assert attributes["duration.ms"] == {"intValue": "-1"}
    assert "dt3.validation.errors" not in attributes


def test_logger_swallows_otlp_transport_errors_after_pipeline_processing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Logger calls must fail open when the OTLP collector cannot accept events."""
    exported_events: list[dict[str, Any]] = []

    def failing_export(self: OtlpTransport, event: dict[str, Any]) -> None:
        exported_events.append(event)
        raise OtlpTransportError("collector unavailable")

    monkeypatch.setattr(OtlpTransport, "export", failing_export)
    logger = create_logger(
        _config(
            **{
                "masking.replacement_value": "***",
                "masking.track_masked_fields": True,
            }
        )
    )

    logger.info(
        "Continue application execution",
        {
            "event.name": "OTLP_FAIL_OPEN",
            "password": "sensitive-value",
        },
    )

    assert len(exported_events) == 1
    assert exported_events[0]["password"] == "***"
    assert exported_events[0]["dt3.security.masked_fields"] == ["password"]


def test_otlp_transport_propagates_http_connection_and_timeout_failures(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Non-2xx responses, connection failures, and timeouts must fail exports."""
    transport = OtlpTransport("https://collector.example.test/v1/logs")

    def http_failure(request: Any, timeout: float) -> _Response:
        raise HTTPError(request.full_url, 503, "Unavailable", None, None)

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", http_failure)
    with pytest.raises(OtlpTransportError, match="status 503"):
        transport.export({"message": "failure"})

    def connection_failure(request: Any, timeout: float) -> _Response:
        raise URLError("connection refused")

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", connection_failure)
    with pytest.raises(OtlpTransportError, match="connection refused"):
        transport.export({"message": "failure"})

    def timeout_failure(request: Any, timeout: float) -> _Response:
        raise TimeoutError("timed out")

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", timeout_failure)
    with pytest.raises(OtlpTransportError, match="timed out"):
        transport.export({"message": "failure"})


def test_otlp_transport_flush_close_and_post_close_behavior(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Synchronous lifecycle operations must be available and close idempotent."""

    def successful_export(request: Any, timeout: float) -> _Response:
        return _Response()

    monkeypatch.setattr("dt3_sdk.otlp_transport.urlopen", successful_export)
    transport = OtlpTransport("https://collector.example.test/v1/logs")

    transport.flush()
    transport.export({"message": "before close"})
    transport.close()
    transport.close()

    with pytest.raises(RuntimeError, match="OTLP transport is closed"):
        transport.flush()
    with pytest.raises(RuntimeError, match="OTLP transport is closed"):
        transport.export({"message": "after close"})
