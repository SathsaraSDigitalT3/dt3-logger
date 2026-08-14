"""Automated tests for the DT3 Python SDK JSON Lines file transport."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from dt3_api.logger import Logger
from dt3_sdk import FileTransport, ValidationError, create_logger


class _FailingFile:
    """In-memory file double that raises a configured error for one operation."""

    def __init__(self, operation: str) -> None:
        """Initialize the file double with an operation that should fail."""
        self._operation = operation
        self.write_calls: list[str] = []

    def write(self, value: str) -> int:
        """Record a write or raise the configured write failure."""
        if self._operation == "write":
            raise OSError("write failed")
        self.write_calls.append(value)
        return len(value)

    def flush(self) -> None:
        """Raise the configured flush failure when requested."""
        if self._operation == "flush":
            raise OSError("flush failed")

    def close(self) -> None:
        """Provide the close operation required by FileTransport."""


def test_logger_protocol_exposes_flush_and_close() -> None:
    """Ensure the public Logger contract exposes its lifecycle operations."""
    assert callable(getattr(Logger, "flush", None))
    assert callable(getattr(Logger, "close", None))


def test_file_transport_close_is_idempotent(tmp_path: Path) -> None:
    """Closing a file transport repeatedly should safely release it once."""
    transport = FileTransport(tmp_path / "events.jsonl")

    transport.close()
    transport.close()


def test_file_transport_export_after_close_raises_before_serialization(
    tmp_path: Path,
) -> None:
    """Closed transports must reject even values that cannot be JSON serialized."""
    transport = FileTransport(tmp_path / "events.jsonl")
    transport.close()

    with pytest.raises(RuntimeError, match="File transport is closed"):
        transport.export({"not.serializable": object()})


def test_file_transport_write_failure_is_propagated(tmp_path: Path) -> None:
    """An underlying write failure must reach the caller unchanged."""
    transport = FileTransport(tmp_path / "events.jsonl")
    transport._file.close()
    transport._file = _FailingFile("write")

    with pytest.raises(OSError, match="write failed"):
        transport.export({"event.name": "WRITE_FAILURE"})


def test_file_transport_flush_failure_is_propagated(tmp_path: Path) -> None:
    """An underlying flush failure must reach the caller unchanged."""
    transport = FileTransport(tmp_path / "events.jsonl")
    transport._file.close()
    transport._file = _FailingFile("flush")

    with pytest.raises(OSError, match="flush failed"):
        transport.flush()


def test_file_transport_writes_each_jsonl_record_in_one_write(tmp_path: Path) -> None:
    """Each serialized JSONL record should be passed to the file in one write."""
    transport = FileTransport(tmp_path / "events.jsonl")
    transport._file.close()
    failing_file = _FailingFile("none")
    transport._file = failing_file

    transport.export({"event.name": "ATOMIC_WRITE", "message": "valid JSONL"})

    assert len(failing_file.write_calls) == 1
    assert failing_file.write_calls[0].endswith("\n")
    assert json.loads(failing_file.write_calls[0]) == {
        "event.name": "ATOMIC_WRITE",
        "message": "valid JSONL",
    }
    transport.close()


def _config(file_path: Path, validation_mode: str = "LENIENT", **overrides: object) -> dict[str, object]:
    """Build a valid file-exporting SDK configuration for a test."""
    return {
        "service.name": "file-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "file",
        "file.path": str(file_path),
        "validation.mode": validation_mode,
        **overrides,
    }


def _read_events(file_path: Path) -> list[dict[str, object]]:
    """Parse every non-empty JSONL line from a file transport destination."""
    return [
        json.loads(line)
        for line in file_path.read_text(encoding="utf-8").splitlines()
        if line
    ]


def _assert_canonical_event_shape(event: dict[str, object]) -> None:
    """Assert required canonical schema fields and their expected primitive types."""
    required_fields = {
        "timestamp": str,
        "severity": str,
        "message": str,
        "event.name": str,
        "schema.version": str,
        "sdk.name": str,
        "sdk.version": str,
        "service.name": str,
        "service.version": str,
        "deployment.environment": str,
    }
    for field, expected_type in required_fields.items():
        assert isinstance(event[field], expected_type)


def test_file_exporter_creates_configured_path_and_writes_parseable_canonical_jsonl(
    tmp_path: Path,
) -> None:
    file_path = tmp_path / "nested" / "logs" / "events.jsonl"
    logger = create_logger(_config(file_path))

    logger.info("File transport started", {"event.name": "FILE_TRANSPORT_STARTED"})
    logger.flush()
    logger.close()

    assert file_path.exists()
    lines = file_path.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    event = json.loads(lines[0])
    _assert_canonical_event_shape(event)
    assert event["severity"] == "INFO"
    assert event["message"] == "File transport started"
    assert event["event.name"] == "FILE_TRANSPORT_STARTED"


def test_file_exporter_writes_one_json_event_per_line_for_multiple_events(
    tmp_path: Path,
) -> None:
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))

    logger.debug("First", {"event.name": "FIRST_EVENT"})
    logger.warn("Second", {"event.name": "SECOND_EVENT"})
    logger.flush()
    logger.close()

    events = _read_events(file_path)
    assert [event["event.name"] for event in events] == ["FIRST_EVENT", "SECOND_EVENT"]
    assert [event["severity"] for event in events] == ["DEBUG", "WARN"]
    assert file_path.read_bytes().endswith(b"\n")


def test_file_exporter_appends_without_overwriting_existing_contents(tmp_path: Path) -> None:
    file_path = tmp_path / "events.jsonl"
    existing_event = {"preserved": True}
    file_path.write_text(json.dumps(existing_event) + "\n", encoding="utf-8")

    logger = create_logger(_config(file_path))
    logger.info("Appended", {"event.name": "APPENDED_EVENT"})
    logger.flush()
    logger.close()

    events = _read_events(file_path)
    assert events[0] == existing_event
    assert events[1]["event.name"] == "APPENDED_EVENT"


def test_file_exporter_flush_makes_written_event_visible(tmp_path: Path) -> None:
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path))

    logger.info("Flush event", {"event.name": "FLUSH_EVENT"})
    logger.flush()

    events = _read_events(file_path)
    assert len(events) == 1
    assert events[0]["event.name"] == "FLUSH_EVENT"
    logger.close()


def test_file_exporter_masks_sensitive_values_before_export(tmp_path: Path) -> None:
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(
        _config(
            file_path,
            **{
                "masking.fields": ["customerCode"],
                "masking.replacement_value": "***",
                "masking.track_masked_fields": True,
            },
        )
    )

    logger.info(
        "Sensitive context",
        {
            "event.name": "SENSITIVE_CONTEXT",
            "password": "do-not-write",
            "attributes": {"customerCode": "customer-secret"},
        },
    )
    logger.flush()
    logger.close()

    raw_output = file_path.read_text(encoding="utf-8")
    event = _read_events(file_path)[0]
    assert "do-not-write" not in raw_output
    assert "customer-secret" not in raw_output
    assert event["password"] == "***"
    assert event["attributes"] == {"customerCode": "***"}
    assert event["dt3.security.masked_fields"] == [
        "password",
        "attributes.customerCode",
    ]


def test_file_exporter_strict_invalid_event_is_not_written(tmp_path: Path) -> None:
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path, validation_mode="STRICT"))

    with pytest.raises(ValidationError):
        logger.info("Invalid", {"event.name": "invalid-name"})

    logger.close()
    assert not file_path.exists() or file_path.read_text(encoding="utf-8") == ""


def test_file_exporter_lenient_invalid_event_includes_structured_diagnostics(
    tmp_path: Path,
) -> None:
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path, validation_mode="LENIENT"))

    logger.info("Invalid", {"event.name": "invalid-name"})
    logger.flush()
    logger.close()

    event = _read_events(file_path)[0]
    diagnostics = event["dt3.validation.errors"]
    assert isinstance(diagnostics, list)
    assert any(
        error["field"] == "event.name"
        and error["message"] == "violates schema rule 'pattern'"
        and error["rule"] == "pattern"
        for error in diagnostics
    )


def test_file_exporter_off_mode_preserves_validation_bypass_behavior(
    tmp_path: Path,
) -> None:
    file_path = tmp_path / "events.jsonl"
    logger = create_logger(_config(file_path, validation_mode="OFF"))

    logger.info(
        "Validation bypass",
        {"event.name": "invalid-name", "duration.ms": -1},
    )
    logger.flush()
    logger.close()

    event = _read_events(file_path)[0]
    assert event["event.name"] == "invalid-name"
    assert event["duration.ms"] == -1
    assert "dt3.validation.errors" not in event


def test_file_exporter_reports_open_failures_without_sensitive_input(
    tmp_path: Path,
) -> None:
    blocked_parent = tmp_path / "not-a-directory"
    blocked_parent.write_text("file", encoding="utf-8")
    secret_path = blocked_parent / "secret-value.jsonl"

    with pytest.raises(OSError) as error:
        create_logger(_config(secret_path))

    assert "secret-value" not in str(error.value)


def test_file_exporter_requires_a_configured_file_path() -> None:
    with pytest.raises(ValueError, match="file.path"):
        create_logger(
            {
                "service.name": "file-test-service",
                "service.version": "1.0.0",
                "deployment.environment": "test",
                "exporter": "file",
            }
        )
