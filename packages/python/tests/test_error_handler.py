"""Tests for the DT3 Python SDK centralized error handler."""

from __future__ import annotations

import io
import json
from typing import Any

import pytest

from dt3_sdk import (
    Dt3Error,
    Dt3ErrorCode,
    Dt3ErrorPhase,
    Dt3MaskingError,
    ErrorHandler,
    HttpTransportError,
    ValidationError,
    create_logger,
)


def _config(**overrides: object) -> dict[str, object]:
    """Build the minimum stdout SDK configuration for error-handler tests."""
    return {
        "service.name": "error-handler-test-service",
        "service.version": "1.0.0",
        "deployment.environment": "test",
        "exporter": "stdout",
        **overrides,
    }


def test_handler_classifies_taxonomy_and_legacy_exception_types() -> None:
    """Classification must provide stable codes and retryability decisions."""
    handler = ErrorHandler(diagnostics_enabled=False)

    assert handler.classify(Dt3MaskingError("failed")) == (
        Dt3ErrorCode.MASKING_FAILED,
        False,
    )
    assert handler.classify(TimeoutError("timed out")) == (
        Dt3ErrorCode.TRANSPORT_TIMEOUT,
        True,
    )
    assert handler.classify(OSError("unavailable")) == (
        Dt3ErrorCode.FILE_WRITE_FAILED,
        True,
    )
    assert handler.classify(RuntimeError("callback unavailable")) == (
        Dt3ErrorCode.UNKNOWN,
        False,
    )
    assert handler.classify(Exception("unknown")) == (Dt3ErrorCode.UNKNOWN, False)


def test_handler_rate_limits_diagnostics_per_error_code() -> None:
    """One noisy error code must not exhaust another code's diagnostic budget."""
    stream = io.StringIO()
    handler = ErrorHandler(
        diagnostics_stream=stream,
        rate_limit_per_minute=1,
    )

    handler.handle(OSError("first"), phase=Dt3ErrorPhase.DELIVERY)
    handler.handle(OSError("second"), phase=Dt3ErrorPhase.DELIVERY)
    handler.handle(ValueError("invalid"), phase=Dt3ErrorPhase.CONFIGURATION)

    lines = stream.getvalue().splitlines()
    assert len(lines) == 2
    assert "DT3_FILE_WRITE_FAILED" in lines[0]
    assert "DT3_CONFIG_INVALID" in lines[1]
    assert handler.snapshot() == {
        "DT3_FILE_WRITE_FAILED": 2,
        "DT3_CONFIG_INVALID": 1,
    }


def test_handler_swallows_callback_and_diagnostic_sink_failures() -> None:
    """Broken observability hooks cannot turn a suppressed SDK failure into a crash."""

    class BrokenStream:
        """Diagnostic stream double that always fails."""

        def write(self, value: str) -> int:
            """Fail diagnostic writes."""
            raise OSError("stream unavailable")

        def flush(self) -> None:
            """Fail diagnostic flushing."""
            raise OSError("stream unavailable")

    def failing_callback(report: object) -> None:
        """Fail application callback execution."""
        raise RuntimeError("callback unavailable")

    handler = ErrorHandler(
        diagnostics_stream=BrokenStream(),
        on_error=failing_callback,
    )

    handler.handle(OSError("delivery failed"), phase=Dt3ErrorPhase.DELIVERY)
    assert handler.snapshot() == {"DT3_FILE_WRITE_FAILED": 1}


def test_handler_sanitizes_diagnostic_context_labels() -> None:
    """Diagnostics must exclude unsafe context values and line-injection attempts."""
    stream = io.StringIO()
    handler = ErrorHandler(diagnostics_stream=stream)

    handler.report(
        ValueError("invalid"),
        phase=Dt3ErrorPhase.CONFIGURATION,
        context={
            "request.id": "safe-123",
            "line\ninjection": "unsafe",
            "unsafe_value": "first\nsecond",
            "object": object(),
        },
    )

    line = stream.getvalue()
    assert line.count("\n") == 1
    assert "request.id=safe-123" in line
    assert "line\ninjection" not in line
    assert "unsafe_value" not in line
    assert "object=" not in line


@pytest.mark.parametrize("fatal", [KeyboardInterrupt(), SystemExit(), MemoryError()])
def test_guard_reraises_nonrecoverable_process_control_exceptions(
    fatal: BaseException,
) -> None:
    """Guard must not suppress process-control or memory exhaustion signals."""
    handler = ErrorHandler(diagnostics_enabled=False)

    def raise_fatal() -> None:
        """Raise the parameterized fatal exception."""
        raise fatal

    with pytest.raises(type(fatal)):
        handler.guard(raise_fatal, phase=Dt3ErrorPhase.DELIVERY)


def test_fail_open_transport_failure_emits_diagnostic_callback_and_snapshot(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Fail-open delivery must remain non-throwing while becoming observable."""
    reports: list[Any] = []
    logger = create_logger(
        _config(
            exporter="http",
            **{
                "exporter.http.endpoint": "https://logs.example.test/events",
                "error.on_error": reports.append,
            },
        )
    )
    assert logger._http_transport is not None

    def fail_export(event: dict[str, Any]) -> None:
        """Simulate an unavailable collector."""
        raise HttpTransportError("collector unavailable")

    monkeypatch.setattr(logger._http_transport, "export", fail_export)

    logger.info("Continue", {"event.name": "FAIL_OPEN_EVENT"})

    captured = capsys.readouterr()
    assert "code=DT3_TRANSPORT_UNAVAILABLE" in captured.err
    assert len(reports) == 1
    assert reports[0].code is Dt3ErrorCode.TRANSPORT_UNAVAILABLE
    assert logger.error_snapshot() == {"DT3_TRANSPORT_UNAVAILABLE": 1}


def test_fail_closed_transport_failure_is_reported_then_reraised(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fail-closed delivery must preserve the original failure type."""
    logger = create_logger(
        _config(
            exporter="http",
            fail_open=False,
            **{"exporter.http.endpoint": "https://logs.example.test/events"},
        )
    )
    assert logger._http_transport is not None

    def fail_export(event: dict[str, Any]) -> None:
        """Simulate a rejected transport export."""
        raise HttpTransportError("collector unavailable")

    monkeypatch.setattr(logger._http_transport, "export", fail_export)

    with pytest.raises(HttpTransportError, match="collector unavailable"):
        logger.info("Raise", {"event.name": "FAIL_CLOSED_EVENT"})

    assert logger.error_snapshot() == {"DT3_TRANSPORT_UNAVAILABLE": 1}


def test_cyclic_context_is_reported_and_dropped_under_fail_open(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Cyclic caller context must not crash a fail-open application logger."""
    reports: list[Any] = []
    context: dict[str, object] = {"event.name": "CYCLIC_CONTEXT"}
    context["self"] = context
    logger = create_logger(_config(**{"error.on_error": reports.append}))

    logger.info("Cyclic context", context)

    captured = capsys.readouterr()
    assert captured.out == ""
    assert "code=DT3_MASKING_FAILED" in captured.err
    assert len(reports) == 1
    assert reports[0].code is Dt3ErrorCode.MASKING_FAILED


def test_dt3_errors_populate_canonical_error_code_and_retryability(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """A caller-supplied Dt3Error must enrich the emitted canonical event."""
    logger = create_logger(_config())
    error = Dt3Error(
        "transport degraded",
        code=Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
        retryable=True,
    )

    logger.error(
        "Delivery failed",
        error,
        {"event.name": "DELIVERY_FAILED"},
    )

    event = json.loads(capsys.readouterr().out)
    assert event["error.code"] == "DT3_TRANSPORT_UNAVAILABLE"
    assert event["error.retryable"] is True


def test_strict_validation_is_reported_before_raising(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Strict validation remains fail-closed but now provides a report."""
    reports: list[Any] = []
    logger = create_logger(
        _config(
            **{
                "validation.mode": "STRICT",
                "error.on_error": reports.append,
            }
        )
    )

    with pytest.raises(ValidationError):
        logger.info("Invalid", {"event.name": "not-valid"})

    captured = capsys.readouterr()
    assert "code=DT3_VALIDATION_FAILED" in captured.err
    assert len(reports) == 1
    assert reports[0].phase is Dt3ErrorPhase.VALIDATION


def test_strict_validation_reports_once_then_raises_with_fail_closed_delivery(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Strict validation must not let fail-closed reporting preempt its raise."""
    reports: list[Any] = []
    logger = create_logger(
        _config(
            fail_open=False,
            **{
                "validation.mode": "STRICT",
                "error.on_error": reports.append,
            },
        )
    )

    with pytest.raises(ValidationError, match="Log event failed schema validation"):
        logger.info("Invalid", {"event.name": "not-valid"})

    captured = capsys.readouterr()
    assert captured.err.count("code=DT3_VALIDATION_FAILED") == 1
    assert len(reports) == 1
    assert reports[0].phase is Dt3ErrorPhase.VALIDATION
    assert logger.error_snapshot() == {"DT3_VALIDATION_FAILED": 1}


def test_closed_logger_lifecycle_failure_is_reported() -> None:
    """Closed logger calls remain fail-closed and create a lifecycle report."""
    reports: list[Any] = []
    logger = create_logger(_config(**{"error.on_error": reports.append}))
    logger.close()

    with pytest.raises(RuntimeError, match="Logger is closed"):
        logger.info("Closed", {"event.name": "CLOSED_LOGGER"})

    assert reports[-1].code is Dt3ErrorCode.LIFECYCLE_CLOSED
    assert reports[-1].phase is Dt3ErrorPhase.LIFECYCLE


def test_constructor_configuration_failure_is_reported_before_reraise() -> None:
    """Invalid construction settings notify the configured ErrorHandler observer."""
    reports: list[Any] = []

    with pytest.raises(ValueError, match="fail_open must be a boolean"):
        create_logger(_config(fail_open="invalid", **{"error.on_error": reports.append}))

    assert reports[-1].code is Dt3ErrorCode.CONFIGURATION_INVALID
    assert reports[-1].phase is Dt3ErrorPhase.CONFIGURATION


def test_constructor_invalid_rate_limit_is_reported_before_reraise() -> None:
    """An invalid rate limit must not prevent reporting the construction error."""
    reports: list[Any] = []

    with pytest.raises(ValueError, match="error.rate_limit_per_minute"):
        create_logger(
            _config(
                **{
                    "error.rate_limit_per_minute": "invalid",
                    "error.on_error": reports.append,
                }
            )
        )

    assert reports[-1].code is Dt3ErrorCode.CONFIGURATION_INVALID
    assert reports[-1].phase is Dt3ErrorPhase.CONFIGURATION


def test_constructor_reporting_does_not_stringify_an_invalid_exporter(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Construction diagnostics must not invoke an arbitrary exporter __str__ method."""

    class ExporterWithBrokenStringConversion:
        """Exporter-like value whose string conversion is unsafe."""

        def __str__(self) -> str:
            """Fail if diagnostic reporting attempts to stringify this value."""
            raise AssertionError("exporter values must not be stringified")

    reports: list[Any] = []
    invalid_exporter = ExporterWithBrokenStringConversion()

    with pytest.raises(Exception):
        create_logger(
            _config(
                exporter=invalid_exporter,
                **{"error.on_error": reports.append},
            )
        )

    assert reports[-1].code is Dt3ErrorCode.CONFIGURATION_INVALID
    assert reports[-1].phase is Dt3ErrorPhase.CONFIGURATION
    assert "exporter=invalid" in capsys.readouterr().err
