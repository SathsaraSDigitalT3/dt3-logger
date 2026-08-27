"""Focused tests for DT3 Python SDK batching."""

from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any

import pytest
from dt3_sdk import create_logger, logger_context
from dt3_sdk.errors import Dt3Error, Dt3ErrorCode
from dt3_sdk.http_transport import HttpTransportError


def _config(**overrides: object) -> dict[str, object]:
    """Build a batching-enabled stdout logger configuration for tests."""
    return {
        "service.name": "batching-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "stdout",
        "batching.enabled": True,
        "batching.max_size": 3,
        "batching.flush_interval_ms": 1000,
        **overrides,
    }


def _stdout_events(capsys: pytest.CaptureFixture[str]) -> list[dict[str, Any]]:
    """Parse JSON events currently emitted through the stdout exporter."""
    return [
        json.loads(line)
        for line in capsys.readouterr().out.splitlines()
        if line
    ]


def test_batching_is_disabled_by_default(capsys: pytest.CaptureFixture[str]) -> None:
    """Existing logger behavior must remain immediate unless batching is enabled."""
    logger = create_logger(_config(**{"batching.enabled": False}))

    logger.info("Immediate", {"event.name": "IMMEDIATE_EVENT"})

    assert _stdout_events(capsys)[0]["event.name"] == "IMMEDIATE_EVENT"
    logger.close()


def test_events_are_buffered_until_manual_flush(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """A sub-threshold batch must not export before an explicit flush."""
    logger = create_logger(_config())

    logger.info("Buffered", {"event.name": "BUFFERED_EVENT"})
    assert _stdout_events(capsys) == []

    logger.flush()
    assert _stdout_events(capsys)[0]["event.name"] == "BUFFERED_EVENT"
    logger.close()


def test_batch_size_flush_preserves_event_order(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """The configured maximum batch size must trigger ordered delivery."""
    logger = create_logger(_config())

    for number in range(3):
        logger.info(
            f"Event {number}",
            {"event.name": "BATCH_SIZE_EVENT", "sequence": number},
        )

    events = _stdout_events(capsys)
    assert [event["sequence"] for event in events] == [0, 1, 2]
    logger.close()


def test_interval_flush_sends_pending_events(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """The interval timer must flush a pending under-sized batch."""
    logger = create_logger(_config(**{"batching.flush_interval_ms": 25}))

    logger.info("Timed", {"event.name": "INTERVAL_EVENT"})
    time.sleep(0.15)

    assert _stdout_events(capsys)[0]["event.name"] == "INTERVAL_EVENT"
    logger.close()


def test_empty_flush_does_not_export(capsys: pytest.CaptureFixture[str]) -> None:
    """Repeated flushes without buffered events must not produce output."""
    logger = create_logger(_config())

    logger.flush()
    logger.flush()

    assert _stdout_events(capsys) == []
    logger.close()


def test_close_flushes_remaining_events(capsys: pytest.CaptureFixture[str]) -> None:
    """Closing the logger must synchronously drain its pending event buffer."""
    logger = create_logger(_config())

    logger.info("Closing", {"event.name": "CLOSE_EVENT"})
    logger.close()

    assert _stdout_events(capsys)[0]["event.name"] == "CLOSE_EVENT"


def test_context_is_captured_when_the_event_is_created(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Each buffered event must retain its own scoped context."""
    logger = create_logger(_config())

    with logger_context(trace_id="a" * 32, correlation_id="request-a"):
        logger.info("A", {"event.name": "CONTEXT_A"})
    with logger_context(trace_id="b" * 32, correlation_id="request-b"):
        logger.info("B", {"event.name": "CONTEXT_B"})

    logger.flush()
    events = {event["correlation.id"]: event for event in _stdout_events(capsys)}
    assert events["request-a"]["trace.id"] == "a" * 32
    assert events["request-b"]["trace.id"] == "b" * 32
    logger.close()


def test_explicit_event_context_is_retained_inside_a_batch(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Explicit context must retain its normal precedence in buffered events."""
    logger = create_logger(_config())

    with logger_context(trace_id="a" * 32):
        logger.info(
            "Explicit",
            {
                "event.name": "EXPLICIT_BATCH_CONTEXT",
                "trace.id": "b" * 32,
            },
        )

    logger.flush()
    assert _stdout_events(capsys)[0]["trace.id"] == "b" * 32
    logger.close()


def test_concurrent_logging_does_not_drop_or_duplicate_events(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Thread-safe buffering must preserve every independently logged event."""
    logger = create_logger(
        _config(
            **{
                "batching.max_size": 1000,
                "batching.flush_interval_ms": 10000,
            }
        )
    )
    event_count = 40

    def log_event(index: int) -> None:
        """Emit one uniquely identified test event."""
        logger.info(
            f"Event {index}",
            {"event.name": "CONCURRENT_BATCH_EVENT", "sequence": index},
        )

    threads = [threading.Thread(target=log_event, args=(index,)) for index in range(event_count)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    logger.flush()
    sequences = [event["sequence"] for event in _stdout_events(capsys)]
    assert sorted(sequences) == list(range(event_count))
    assert len(sequences) == event_count
    logger.close()


def test_file_transport_works_with_batching(tmp_path: Path) -> None:
    """Batching must retain JSONL file transport compatibility."""
    destination = tmp_path / "events.jsonl"
    logger = create_logger(
        _config(
            exporter="file",
            **{
                "exporter.file.path": str(destination),
                "batching.max_size": 2,
            },
        )
    )

    logger.info("One", {"event.name": "FILE_BATCH_ONE"})
    logger.info("Two", {"event.name": "FILE_BATCH_TWO"})
    logger.close()

    events = [
        json.loads(line)
        for line in destination.read_text(encoding="utf-8").splitlines()
    ]
    assert [event["event.name"] for event in events] == [
        "FILE_BATCH_ONE",
        "FILE_BATCH_TWO",
    ]


def test_http_transport_works_with_batching(monkeypatch: pytest.MonkeyPatch) -> None:
    """Batching must invoke HTTP delivery once per final event in batch order."""
    captured: list[dict[str, Any]] = []
    logger = create_logger(
        _config(
            exporter="http",
            **{
                "exporter.http.endpoint": "https://logs.example.test/events",
                "batching.max_size": 2,
            },
        )
    )
    assert logger._http_transport is not None
    monkeypatch.setattr(
        logger._http_transport,
        "export",
        lambda event: captured.append(dict(event)),
    )

    logger.info("One", {"event.name": "HTTP_BATCH_ONE"})
    logger.info("Two", {"event.name": "HTTP_BATCH_TWO"})

    assert [event["event.name"] for event in captured] == [
        "HTTP_BATCH_ONE",
        "HTTP_BATCH_TWO",
    ]
    logger.close()


def test_otlp_transport_works_with_batching(monkeypatch: pytest.MonkeyPatch) -> None:
    """Batching must invoke OTLP delivery once per final event in batch order."""
    captured: list[dict[str, Any]] = []
    logger = create_logger(
        _config(
            exporter="otlp",
            **{
                "otlp.endpoint": "https://collector.example.test/v1/logs",
                "batching.max_size": 2,
            },
        )
    )
    assert logger._otlp_transport is not None
    monkeypatch.setattr(
        logger._otlp_transport,
        "export",
        lambda event: captured.append(dict(event)),
    )

    logger.info("One", {"event.name": "OTLP_BATCH_ONE"})
    logger.info("Two", {"event.name": "OTLP_BATCH_TWO"})

    assert [event["event.name"] for event in captured] == [
        "OTLP_BATCH_ONE",
        "OTLP_BATCH_TWO",
    ]
    logger.close()


@pytest.mark.parametrize(
    ("fail_open", "raises"),
    [(True, False), (False, True)],
)
def test_batch_delivery_failures_follow_fail_open_policy(
    monkeypatch: pytest.MonkeyPatch,
    fail_open: bool,
    raises: bool,
) -> None:
    """A batch transport failure must retain normal logger failure semantics."""
    logger = create_logger(
        _config(
            exporter="http",
            fail_open=fail_open,
            **{
                "exporter.http.endpoint": "https://logs.example.test/events",
                "batching.max_size": 1,
            },
        )
    )
    assert logger._http_transport is not None

    def failing_export(event: dict[str, Any]) -> None:
        """Simulate a normalized HTTP transport failure."""
        raise HttpTransportError("collector unavailable")

    monkeypatch.setattr(logger._http_transport, "export", failing_export)

    if raises:
        with pytest.raises(HttpTransportError, match="collector unavailable"):
            logger.info("Failure", {"event.name": "BATCH_FAILURE"})
    else:
        logger.info("Failure", {"event.name": "BATCH_FAILURE"})

    logger.close()


def test_fail_closed_batch_failure_reports_discarded_events_and_rejects_later_logs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A terminal batch abort must expose loss and reject subsequent logging."""
    reports: list[Any] = []
    logger = create_logger(
        _config(
            exporter="http",
            fail_open=False,
            **{
                "exporter.http.endpoint": "https://logs.example.test/events",
                "batching.max_size": 3,
                "error.on_error": reports.append,
            },
        )
    )
    assert logger._http_transport is not None
    attempts: list[str] = []

    def partially_failing_export(event: dict[str, Any]) -> None:
        """Accept the first event and consistently fail the second event."""
        event_name = str(event["event.name"])
        attempts.append(event_name)
        if event_name == "SECOND_EVENT":
            raise HttpTransportError("collector unavailable")

    monkeypatch.setattr(logger._http_transport, "export", partially_failing_export)

    logger.info("First", {"event.name": "FIRST_EVENT"})
    logger.info("Second", {"event.name": "SECOND_EVENT"})
    with pytest.raises(HttpTransportError, match="collector unavailable"):
        logger.info("Third", {"event.name": "THIRD_EVENT"})

    # An aborted batcher must not retry failed or unattempted events when a
    # caller explicitly flushes. Subsequent logging is explicitly rejected.
    logger.flush()
    with pytest.raises(Dt3Error, match="batcher aborted"):
        logger.info("After abort", {"event.name": "AFTER_ABORT_EVENT"})
    logger.flush()
    logger.close()

    assert attempts == ["FIRST_EVENT", "SECOND_EVENT"]
    assert logger.error_snapshot() == {
        "DT3_TRANSPORT_UNAVAILABLE": 1,
        "DT3_BATCH_ABORTED": 2,
    }
    assert [report.code for report in reports] == [
        Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
        Dt3ErrorCode.BATCH_ABORTED,
        Dt3ErrorCode.BATCH_ABORTED,
    ]
    assert [report.occurrences for report in reports] == [1, 1, 2]
    assert [report.error_type for report in reports[1:]] == ["Dt3Error", "Dt3Error"]


@pytest.mark.parametrize(
    ("config_key", "value", "message"),
    [
        ("batching.enabled", "yes", "batching.enabled"),
        ("batching.max_size", 0, "batching.max_size"),
        ("batching.flush_interval_ms", 0, "batching.flush_interval_ms"),
    ],
)
def test_batching_configuration_is_validated(
    config_key: str,
    value: object,
    message: str,
) -> None:
    """Invalid batching configuration must fail during logger construction."""
    config = _config()
    config[config_key] = value

    with pytest.raises(ValueError, match=message):
        create_logger(config)
