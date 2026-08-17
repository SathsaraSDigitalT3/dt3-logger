"""Focused tests for execution-scoped DT3 Python SDK context propagation."""

from __future__ import annotations

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest

from dt3_sdk import create_logger, logger_context


def _config(**overrides: object) -> dict[str, object]:
    """Build a valid stdout SDK configuration for context propagation tests."""
    return {
        "service.name": "context-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "stdout",
        **overrides,
    }


def _events_from_stdout(capsys: pytest.CaptureFixture[str]) -> list[dict[str, Any]]:
    """Parse all JSON events emitted by the stdout exporter."""
    return [
        json.loads(line)
        for line in capsys.readouterr().out.splitlines()
        if line
    ]


def test_context_is_attached_to_events_and_reused_within_scope(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Active context must be attached to every event logged in its scope."""
    logger = create_logger(_config())

    with logger_context(
        trace_id="a" * 32,
        span_id="b" * 16,
        correlation_id="request-42",
    ):
        logger.info("Request started", {"event.name": "REQUEST_STARTED"})
        logger.info("Request completed", {"event.name": "REQUEST_COMPLETED"})

    events = _events_from_stdout(capsys)
    assert [event["event.name"] for event in events] == [
        "REQUEST_STARTED",
        "REQUEST_COMPLETED",
    ]
    for event in events:
        assert event["trace.id"] == "a" * 32
        assert event["span.id"] == "b" * 16
        assert event["correlation.id"] == "request-42"


def test_context_is_cleared_after_scope_and_nested_scopes_restore_parent(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Nested scopes must inherit parent context and restore it on exit."""
    logger = create_logger(_config())

    with logger_context(trace_id="a" * 32, correlation_id="outer-request"):
        logger.info("Outer before", {"event.name": "OUTER_BEFORE"})
        with logger_context(span_id="b" * 16, correlation_id="inner-request"):
            logger.info("Inner", {"event.name": "INNER"})
        logger.info("Outer after", {"event.name": "OUTER_AFTER"})

    logger.info("No context", {"event.name": "NO_CONTEXT"})
    outer_before, inner, outer_after, no_context = _events_from_stdout(capsys)

    assert outer_before["trace.id"] == "a" * 32
    assert outer_before["correlation.id"] == "outer-request"
    assert "span.id" not in outer_before

    assert inner["trace.id"] == "a" * 32
    assert inner["span.id"] == "b" * 16
    assert inner["correlation.id"] == "inner-request"

    assert outer_after["trace.id"] == "a" * 32
    assert outer_after["correlation.id"] == "outer-request"
    assert "span.id" not in outer_after

    assert "trace.id" not in no_context
    assert "span.id" not in no_context
    assert "correlation.id" not in no_context


def test_explicit_event_context_overrides_active_context(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Per-event context must take precedence over active scoped metadata."""
    logger = create_logger(_config())

    with logger_context(
        trace_id="a" * 32,
        span_id="b" * 16,
        correlation_id="scope-request",
    ):
        logger.info(
            "Explicit values",
            {
                "event.name": "EXPLICIT_CONTEXT",
                "trace.id": "c" * 32,
                "correlation.id": "event-request",
            },
        )

    event = _events_from_stdout(capsys)[0]
    assert event["trace.id"] == "c" * 32
    assert event["span.id"] == "b" * 16
    assert event["correlation.id"] == "event-request"


def test_logging_without_context_preserves_existing_behavior(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Logs without an active context must emit unchanged canonical events."""
    logger = create_logger(_config())

    logger.info("No scoped context", {"event.name": "NO_SCOPED_CONTEXT"})

    event = _events_from_stdout(capsys)[0]
    assert event["event.name"] == "NO_SCOPED_CONTEXT"
    assert event["message"] == "No scoped context"
    assert "trace.id" not in event
    assert "span.id" not in event
    assert "correlation.id" not in event


@pytest.mark.asyncio
async def test_async_tasks_keep_their_own_context(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Concurrent asyncio tasks must not leak context into one another."""
    logger = create_logger(_config())

    async def log_request(request_id: str, trace_id: str) -> None:
        with logger_context(trace_id=trace_id, correlation_id=request_id):
            await asyncio.sleep(0)
            logger.info(
                f"Handled {request_id}",
                {"event.name": "ASYNC_REQUEST_HANDLED"},
            )

    await asyncio.gather(
        log_request("request-a", "a" * 32),
        log_request("request-b", "b" * 32),
    )

    events = {
        event["correlation.id"]: event for event in _events_from_stdout(capsys)
    }
    assert events["request-a"]["trace.id"] == "a" * 32
    assert events["request-b"]["trace.id"] == "b" * 32


def test_context_reaches_file_exporter(tmp_path: Path) -> None:
    """Active context must be present in final JSONL file transport events."""
    destination = tmp_path / "events.jsonl"
    logger = create_logger(
        _config(exporter="file", **{"exporter.file.path": str(destination)})
    )

    with logger_context(trace_id="a" * 32, correlation_id="file-request"):
        logger.info("File context", {"event.name": "FILE_CONTEXT"})

    logger.flush()
    logger.close()
    event = json.loads(destination.read_text(encoding="utf-8").strip())
    assert event["trace.id"] == "a" * 32
    assert event["correlation.id"] == "file-request"


def test_context_reaches_http_and_otlp_exporters(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Context must reach final events for HTTP and OTLP transport selections."""
    captured_http: list[dict[str, Any]] = []
    captured_otlp: list[dict[str, Any]] = []

    def capture_http(event: dict[str, Any]) -> None:
        captured_http.append(event)

    def capture_otlp(event: dict[str, Any]) -> None:
        captured_otlp.append(event)

    http_logger = create_logger(
        _config(
            exporter="http",
            **{"exporter.http.endpoint": "https://logs.example.test/events"},
        )
    )
    otlp_logger = create_logger(
        _config(
            exporter="otlp",
            **{"otlp.endpoint": "https://collector.example.test/v1/logs"},
        )
    )
    assert http_logger._http_transport is not None
    assert otlp_logger._otlp_transport is not None
    monkeypatch.setattr(http_logger._http_transport, "export", capture_http)
    monkeypatch.setattr(otlp_logger._otlp_transport, "export", capture_otlp)

    with logger_context(trace_id="a" * 32, correlation_id="transport-request"):
        http_logger.info("HTTP context", {"event.name": "HTTP_CONTEXT"})
        otlp_logger.info("OTLP context", {"event.name": "OTLP_CONTEXT"})

    for event in [*captured_http, *captured_otlp]:
        assert event["trace.id"] == "a" * 32
        assert event["correlation.id"] == "transport-request"
