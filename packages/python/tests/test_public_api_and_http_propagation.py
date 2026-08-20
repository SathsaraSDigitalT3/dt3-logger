"""Focused tests for public event APIs and HTTP context propagation."""

from __future__ import annotations

import json
import re
from typing import Any

import pytest

from dt3_sdk import create_logger, extract, inject, logger_context


def _config(**overrides: object) -> dict[str, object]:
    """Build a valid stdout logger configuration for API tests."""
    return {
        "service.name": "public-api-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "stdout",
        **overrides,
    }


def _event(capsys: pytest.CaptureFixture[str]) -> dict[str, Any]:
    """Return the single JSON event captured from stdout."""
    return json.loads(capsys.readouterr().out.strip())


def test_fatal_uses_the_canonical_logging_pipeline(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Fatal events must retain standard enrichment and method-owned severity."""
    logger = create_logger(_config())

    logger.fatal("Unrecoverable failure", {"event.name": "FATAL_FAILURE"})

    event = _event(capsys)
    assert event["severity"] == "FATAL"
    assert event["event.name"] == "FATAL_FAILURE"
    assert event["message"] == "Unrecoverable failure"


def test_event_uses_canonical_pipeline_and_severity(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """The event API must preserve user data while applying normal enrichment."""
    logger = create_logger(_config())

    logger.event(
        {
            "severity": "WARN",
            "message": "Queued work delayed",
            "event.name": "QUEUE_DELAYED",
            "queue.name": "orders",
        }
    )

    event = _event(capsys)
    assert event["severity"] == "WARN"
    assert event["message"] == "Queued work delayed"
    assert event["event.name"] == "QUEUE_DELAYED"
    assert event["queue.name"] == "orders"
    assert event["service.name"] == "public-api-test-service"


def test_event_rejects_invalid_public_inputs() -> None:
    """Invalid direct-event inputs must fail deterministically."""
    logger = create_logger(_config())

    with pytest.raises(TypeError, match="event_object must be a mapping"):
        logger.event("not-an-event")  # type: ignore[arg-type]
    with pytest.raises(TypeError, match="event_object.message must be a string"):
        logger.event({"message": 1})
    with pytest.raises(ValueError, match="event_object.severity"):
        logger.event({"message": "Bad", "severity": "NOTICE"})


def test_inject_and_extract_round_trip_http_trace_and_tenant_context() -> None:
    """Propagation must round-trip W3C trace headers and DT3 tenant fields."""
    context = {
        "trace.id": "a" * 32,
        "span.id": "b" * 16,
        "trace.flags": "01",
        "tracestate": "vendor=value",
        "correlation.id": "request-42",
        "tenant.id": "tenant-9",
        "tenant.region": "us-east-1",
        "tenant.environment": "production",
    }
    headers: dict[str, str] = {}

    inject(context, headers)
    extracted = extract({key.upper(): value for key, value in headers.items()})

    assert headers["traceparent"] == f"00-{'a' * 32}-{'b' * 16}-01"
    assert extracted == context


def test_extract_ignores_malformed_traceparent_and_generates_when_requested() -> None:
    """Malformed trace data is safe while configured correlation generation works."""
    extracted = extract(
        {"traceparent": "invalid", "x-tenant-id": "tenant-9"},
        auto_generate_correlation_id=True,
    )

    assert "trace.id" not in extracted
    assert extracted["tenant.id"] == "tenant-9"
    assert re.fullmatch(
        r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        extracted["correlation.id"],
    )


def test_logger_auto_generated_correlation_id_persists_for_scope(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Configured generation must reuse one ID on later logs in the same scope."""
    logger = create_logger(_config(**{"tracing.auto_generate_correlation_id": True}))

    with logger_context(trace_id="a" * 32):
        logger.info("First", {"event.name": "FIRST"})
        logger.info("Second", {"event.name": "SECOND"})

    events = [
        json.loads(line)
        for line in capsys.readouterr().out.splitlines()
        if line
    ]
    assert events[0]["correlation.id"] == events[1]["correlation.id"]
    assert re.fullmatch(
        r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
        events[0]["correlation.id"],
    )
