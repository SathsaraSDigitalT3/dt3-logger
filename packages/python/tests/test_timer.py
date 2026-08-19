"""Focused tests for the public DT3 Python SDK Timer API."""

from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

import pytest

from dt3_api.logger import Logger
from dt3_api.timer import Timer
from dt3_sdk import TimerImpl, create_logger, logger_context


def _config(file_path: Path) -> dict[str, object]:
    """Build a valid file-exporting configuration for Timer API tests."""
    return {
        "service.name": "timer-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "file",
        "exporter.file.path": str(file_path),
    }


def _events(file_path: Path) -> list[dict[str, object]]:
    """Read all JSON Lines emitted by a test logger."""
    return [
        json.loads(line)
        for line in file_path.read_text(encoding="utf-8").splitlines()
        if line
    ]


def test_timer_is_available_from_logger_and_public_package_entry_point(
    tmp_path: Path,
) -> None:
    """A caller can create a public Timer through the normal SDK entry point."""
    logger = create_logger(_config(tmp_path / "events.jsonl"))

    timer = logger.create_timer("TIMER_STARTED")

    assert isinstance(timer, TimerImpl)
    assert callable(getattr(Logger, "create_timer", None))
    assert callable(getattr(Timer, "start", None))
    assert callable(getattr(Timer, "stop", None))
    assert callable(getattr(Timer, "finish", None))
    logger.close()


def test_timer_stop_returns_elapsed_duration_and_emits_canonical_event(
    tmp_path: Path,
) -> None:
    """Stopping emits a normal canonical INFO event with a duration."""
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))
    timer = logger.create_timer("ORDER_PROCESSING", {"order.id": "order-42"})

    with patch("dt3_sdk.timer.time.perf_counter", side_effect=[10.0, 10.125]):
        assert timer.start() is timer
        elapsed_ms = timer.stop()

    logger.close()
    event = _events(file_path)[0]
    assert elapsed_ms == pytest.approx(125.0)
    assert event["event.name"] == "ORDER_PROCESSING"
    assert event["severity"] == "INFO"
    assert event["message"] == "ORDER_PROCESSING completed"
    assert event["duration.ms"] == pytest.approx(125.0)
    assert event["order.id"] == "order-42"
    for field in (
        "timestamp",
        "schema.version",
        "sdk.name",
        "sdk.version",
        "service.name",
        "service.version",
        "deployment.environment",
    ):
        assert field in event


def test_timer_finish_is_stop_alias_and_preserves_active_context(tmp_path: Path) -> None:
    """Finishing uses the existing context propagation mechanism."""
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))

    with logger_context(correlation_id="request-7"):
        timer = logger.create_timer("REQUEST_DURATION").start()
        with patch("dt3_sdk.timer.time.perf_counter", return_value=timer._started_at + 0.01):
            elapsed_ms = timer.finish()

    logger.close()
    event = _events(file_path)[0]
    assert elapsed_ms == pytest.approx(10.0)
    assert event["correlation.id"] == "request-7"
    assert event["event.name"] == "REQUEST_DURATION"


def test_repeated_independent_timers_emit_independent_events(tmp_path: Path) -> None:
    """Separate timers can be used repeatedly with no shared state."""
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))

    with patch("dt3_sdk.timer.time.perf_counter", side_effect=[1.0, 1.02, 2.0, 2.05]):
        logger.create_timer("FIRST_TIMER").start().stop()
        logger.create_timer("SECOND_TIMER").start().stop()

    logger.close()
    events = _events(file_path)
    assert [event["event.name"] for event in events] == ["FIRST_TIMER", "SECOND_TIMER"]
    assert events[0]["duration.ms"] == pytest.approx(20.0)
    assert events[1]["duration.ms"] == pytest.approx(50.0)


@pytest.mark.parametrize(
    ("name", "error_type", "message"),
    [
        (None, TypeError, "name must be a string"),
        ("", ValueError, "name must not be blank"),
        ("   ", ValueError, "name must not be blank"),
    ],
)
def test_timer_creation_rejects_invalid_names(
    tmp_path: Path,
    name: object,
    error_type: type[Exception],
    message: str,
) -> None:
    """Timer creation validates invalid public API input deterministically."""
    logger = create_logger(_config(tmp_path / "events.jsonl"))

    with pytest.raises(error_type, match=message):
        logger.create_timer(name)  # type: ignore[arg-type]

    logger.close()


def test_timer_rejects_invalid_lifecycle_usage_and_only_emits_once(tmp_path: Path) -> None:
    """A timer cannot stop before start, start twice, or stop twice."""
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))
    timer = logger.create_timer("LIFECYCLE_TIMER")

    with pytest.raises(RuntimeError, match="Timer has not been started"):
        timer.stop()

    with patch("dt3_sdk.timer.time.perf_counter", side_effect=[1.0, 1.1]):
        timer.start()
        with pytest.raises(RuntimeError, match="Timer has already been started"):
            timer.start()
        timer.stop()

    with pytest.raises(RuntimeError, match="Timer has already been stopped"):
        timer.stop()

    logger.close()
    assert len(_events(file_path)) == 1


def test_timer_respects_closed_logger_lifecycle_without_emitting(tmp_path: Path) -> None:
    """Timer creation and completion reject a closed logger and do not leak events."""
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))
    timer = logger.create_timer("CLOSED_TIMER").start()
    logger.close()

    with pytest.raises(RuntimeError, match="Logger is closed"):
        logger.create_timer("ANOTHER_TIMER")

    with pytest.raises(RuntimeError, match="Logger is closed"):
        timer.stop()

    assert not file_path.exists() or _events(file_path) == []


def test_existing_logger_behavior_remains_available_without_timer(tmp_path: Path) -> None:
    """Ordinary logger calls retain their existing event behavior."""
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))

    logger.info("Regular event", {"event.name": "REGULAR_EVENT"})
    logger.close()

    event = _events(file_path)[0]
    assert event["event.name"] == "REGULAR_EVENT"
    assert event["message"] == "Regular event"
    assert "duration.ms" not in event
