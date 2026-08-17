"""Automated tests for the DT3 Python SDK synchronous HTTP transport."""

from __future__ import annotations

import json
from typing import Any
from urllib.error import HTTPError, URLError

import pytest

from dt3_sdk import HttpTransport, HttpTransportError, ValidationError, create_logger


class _Response:
    """HTTP response double with a configurable status code."""

    def __init__(self, status: int = 200) -> None:
        """Initialize a successful response double."""
        self._status = status

    def __enter__(self) -> "_Response":
        """Enter the context-manager response lifecycle."""
        return self

    def __exit__(self, exc_type: object, exc_value: object, traceback: object) -> bool:
        """Exit the context-manager response lifecycle."""
        return False

    def getcode(self) -> int:
        """Return the configured HTTP response status."""
        return self._status


def _config(validation_mode: str = "LENIENT", **overrides: object) -> dict[str, object]:
    """Build a valid HTTP-exporting SDK configuration for a test."""
    return {
        "service.name": "http-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "http",
        "http.endpoint": "https://logs.example.test/v1/events",
        "http.timeout": 3.5,
        "validation.mode": validation_mode,
        **overrides,
    }


def _canonical_config(**overrides: object) -> dict[str, object]:
    """Build a configuration using canonical HTTP keys and millisecond timeout."""
    return {
        "service.name": "http-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "http",
        "exporter.http.endpoint": "https://canonical.example.test/v1/events",
        "exporter.http.timeout": 3500,
        **overrides,
    }


def _captured_event(request: Any) -> dict[str, object]:
    """Decode a UTF-8 JSON request payload captured from the transport."""
    return json.loads(request.data.decode("utf-8"))


def test_http_exporter_posts_final_json_payload_with_content_type_and_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """HTTP export must POST the canonical final event with merged headers."""
    captured: dict[str, object] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        captured["timeout"] = timeout
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    logger = create_logger(
        _config(**{"http.headers": {"Authorization": "Bearer integration-token"}})
    )

    logger.info(
        "Connected to 東京",
        {"event.name": "HTTP_EXPORT_STARTED", "attributes": {"region": "東京"}},
    )

    request = captured["request"]
    assert request.full_url == "https://logs.example.test/v1/events"
    assert request.get_method() == "POST"
    assert request.get_header("Content-type") == "application/json"
    assert request.get_header("Authorization") == "Bearer integration-token"
    assert captured["timeout"] == 3.5

    event = _captured_event(request)
    assert event["message"] == "Connected to 東京"
    assert event["attributes"] == {"region": "東京"}
    assert "東京".encode("utf-8") in request.data
    assert b"\\u6771\\u4eac" not in request.data


@pytest.mark.parametrize(
    ("headers", "message"),
    [
        (["Authorization", "Bearer integration-token"], "http.headers must be a mapping"),
        ({1: "Bearer integration-token"}, "invalid header name"),
        ({" ": "Bearer integration-token"}, "invalid header name"),
        ({"X-Unsafe\r\nInjected": "value"}, "invalid header name"),
        ({"Authorization": 123}, "string header value"),
        ({"X-Unsafe": "value\r\nInjected: true"}, "invalid header value"),
    ],
)
def test_http_transport_rejects_invalid_headers_at_construction(
    headers: object,
    message: str,
) -> None:
    """Invalid http.headers configuration must fail before any export attempt."""
    with pytest.raises(ValueError, match=message):
        HttpTransport("https://logs.example.test/v1/events", headers=headers)


def test_http_transport_rejects_whitespace_only_endpoint() -> None:
    """Whitespace-only HTTP endpoints must be rejected during construction."""
    with pytest.raises(ValueError, match="http.endpoint"):
        HttpTransport("   ")


def test_configured_headers_cannot_override_json_content_type(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """HTTP payloads must always use application/json content type."""
    captured: dict[str, object] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    logger = create_logger(
        _config(**{"http.headers": {"content-type": "text/plain", "X-Client": "test"}})
    )

    logger.info("Content type", {"event.name": "CONTENT_TYPE_TEST"})

    request = captured["request"]
    assert request.get_header("Content-type") == "application/json"
    assert request.get_header("X-client") == "test"


def test_http_transport_propagates_timeout_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Timeout failures must not be reported as successful exports."""

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        raise TimeoutError("timed out")

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    transport = HttpTransport("https://logs.example.test/v1/events", timeout=1.0)

    with pytest.raises(HttpTransportError, match="timed out"):
        transport.export({"event.name": "TIMEOUT_TEST"})


def test_http_transport_propagates_http_error_status(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """HTTP error responses must explicitly fail the export."""

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        raise HTTPError(request.full_url, 503, "Service Unavailable", None, None)

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    transport = HttpTransport("https://logs.example.test/v1/events")

    with pytest.raises(HttpTransportError, match="status 503"):
        transport.export({"event.name": "HTTP_STATUS_FAILURE"})


def test_http_transport_rejects_non_success_response_status(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A non-2xx response returned by the HTTP client must fail export."""

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        return _Response(400)

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    transport = HttpTransport("https://logs.example.test/v1/events")

    with pytest.raises(HttpTransportError, match="status 400"):
        transport.export({"event.name": "FAILED_EVENT"})


def test_http_transport_accepts_202_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A 202 response remains a successful HTTP export."""

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        return _Response(202)

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    transport = HttpTransport("https://logs.example.test/v1/events")

    transport.export({"event.name": "ACCEPTED_EVENT"})


def test_http_transport_propagates_connection_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Connection/request failures must explicitly fail the export."""

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        raise URLError("connection refused")

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    transport = HttpTransport("https://logs.example.test/v1/events")

    with pytest.raises(HttpTransportError, match="connection refused"):
        transport.export({"event.name": "CONNECTION_FAILURE"})


def test_strict_invalid_event_is_not_sent_to_http(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """STRICT validation must prevent an invalid event from reaching HTTP."""
    request_count = 0

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        nonlocal request_count
        request_count += 1
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    logger = create_logger(_config("STRICT"))

    with pytest.raises(ValidationError):
        logger.info("Invalid", {"event.name": "invalid-name"})

    assert request_count == 0


def test_lenient_invalid_event_sends_structured_validation_diagnostics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """LENIENT validation must export sanitized structured diagnostics."""
    captured: dict[str, object] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    logger = create_logger(_config("LENIENT"))

    logger.info("Invalid", {"event.name": "invalid-name"})

    diagnostics = _captured_event(captured["request"])["dt3.validation.errors"]
    assert isinstance(diagnostics, list)
    assert any(
        error["field"] == "event.name"
        and error["message"] == "violates schema rule 'pattern'"
        and error["rule"] == "pattern"
        for error in diagnostics
    )


def test_off_mode_bypasses_validation_and_sends_no_diagnostics(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """OFF mode must export without validating or attaching diagnostics."""
    captured: dict[str, object] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    logger = create_logger(_config("OFF"))

    logger.info(
        "Validation bypass",
        {"event.name": "invalid-name", "duration.ms": -1},
    )

    event = _captured_event(captured["request"])
    assert event["event.name"] == "invalid-name"
    assert event["duration.ms"] == -1
    assert "dt3.validation.errors" not in event


def test_sensitive_fields_are_masked_before_http_export(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The HTTP payload must contain masked data, never sensitive input."""
    captured: dict[str, object] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    logger = create_logger(
        _config(
            **{
                "masking.replacement_value": "***",
                "masking.track_masked_fields": True,
            }
        )
    )

    logger.info(
        "Sensitive event",
        {
            "event.name": "SENSITIVE_HTTP_EVENT",
            "password": "do-not-export",
            "attributes": {"token": "nested-secret"},
        },
    )

    payload = captured["request"].data.decode("utf-8")
    event = json.loads(payload)
    assert "do-not-export" not in payload
    assert "nested-secret" not in payload
    assert event["password"] == "***"
    assert event["attributes"]["token"] == "***"
    assert event["dt3.security.masked_fields"] == [
        "password",
        "attributes.token",
    ]


def test_http_transport_flush_and_close_lifecycle(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Synchronous lifecycle calls must be supported and close must be idempotent."""

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    transport = HttpTransport("https://logs.example.test/v1/events")

    transport.flush()
    transport.close()
    transport.close()

    with pytest.raises(RuntimeError, match="HTTP transport is closed"):
        transport.flush()
    with pytest.raises(RuntimeError, match="HTTP transport is closed"):
        transport.export({"event.name": "CLOSED_TRANSPORT"})


def test_canonical_http_configuration_wins_over_legacy_alias_and_uses_milliseconds(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Canonical exporter keys must win over Python aliases and convert to seconds."""
    captured: dict[str, object] = {}

    def fake_urlopen(request: Any, timeout: float) -> _Response:
        captured["request"] = request
        captured["timeout"] = timeout
        return _Response()

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", fake_urlopen)
    logger = create_logger(
        _canonical_config(
            **{
                "http.endpoint": "https://legacy.example.test/events",
                "http.timeout": 0.25,
                "exporter.http.headers": {"X-Canonical": "yes"},
                "http.headers": {"X-Legacy": "no"},
            }
        )
    )

    logger.info("Canonical configuration", {"event.name": "CANONICAL_HTTP"})

    request = captured["request"]
    assert request.full_url == "https://canonical.example.test/v1/events"
    assert captured["timeout"] == 3.5
    assert request.get_header("X-canonical") == "yes"
    assert request.get_header("X-legacy") is None


@pytest.mark.parametrize("fail_open", [True, False])
def test_http_logger_applies_fail_open_only_to_delivery_failures(
    monkeypatch: pytest.MonkeyPatch,
    fail_open: bool,
) -> None:
    """HTTP transport failures must be swallowed only when fail_open is enabled."""

    def failing_urlopen(request: Any, timeout: float) -> _Response:
        raise URLError("collector unavailable")

    monkeypatch.setattr("dt3_sdk.http_transport.urlopen", failing_urlopen)
    logger = create_logger(_canonical_config(fail_open=fail_open))

    if fail_open:
        logger.info("Continue", {"event.name": "HTTP_FAIL_OPEN"})
    else:
        with pytest.raises(HttpTransportError, match="collector unavailable"):
            logger.info("Raise", {"event.name": "HTTP_FAIL_CLOSED"})


@pytest.mark.parametrize("fail_open", [True, False])
def test_http_logger_rejects_closed_lifecycle_operations(
    fail_open: bool,
) -> None:
    """Closed loggers must reject lifecycle work regardless of fail-open policy."""
    logger = create_logger(_canonical_config(fail_open=fail_open))
    logger.close()

    with pytest.raises(RuntimeError, match="Logger is closed"):
        logger.flush()


def test_stdout_exporter_behavior_remains_unaffected(capsys) -> None:
    """Using stdout must remain independent of HTTP transport configuration."""
    logger = create_logger(
        {
            "service.name": "stdout-test-service",
            "service.version": "1.0.0",
            "deployment.environment": "test",
            "exporter": "stdout",
            "http.endpoint": "https://logs.example.test/v1/events",
        }
    )

    logger.info("Stdout event", {"event.name": "STDOUT_EVENT"})
    logger.flush()
    logger.close()

    event = json.loads(capsys.readouterr().out)
    assert event["event.name"] == "STDOUT_EVENT"
    assert event["message"] == "Stdout event"
