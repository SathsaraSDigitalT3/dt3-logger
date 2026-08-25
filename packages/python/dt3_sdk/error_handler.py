"""Centralized failure handling for the DT3 Commons Python SDK."""

from __future__ import annotations

import sys
import threading
import time
import traceback
from dataclasses import dataclass
from typing import Any, Callable, Mapping, Optional, TextIO

from .errors import (
    Dt3Error,
    Dt3ErrorCode,
    Dt3ErrorPhase,
)


@dataclass(frozen=True)
class Dt3ErrorReport:
    """An immutable, sanitized description of a handled SDK failure."""

    code: Dt3ErrorCode
    phase: Dt3ErrorPhase
    message: str
    retryable: bool
    error: BaseException
    occurrences: int

    # PUBLIC_INTERFACE
    def to_fields(self) -> dict[str, Any]:
        """Return canonical schema fields describing this handled failure."""
        return {
            "error.type": type(self.error).__name__,
            "error.message": self.message,
            "error.code": self.code.value,
            "error.retryable": self.retryable,
        }


OnErrorCallback = Callable[[Dt3ErrorReport], None]


class ErrorHandler:
    """Classify, record, diagnose, and dispose of SDK-internal failures."""

    def __init__(
        self,
        *,
        fail_open: bool = True,
        diagnostics_enabled: bool = True,
        diagnostics_stream: Optional[TextIO] = None,
        include_stack: bool = False,
        rate_limit_per_minute: int = 20,
        on_error: Optional[OnErrorCallback] = None,
    ) -> None:
        """Create an error handler with fail-open and diagnostic controls."""
        if (
            isinstance(rate_limit_per_minute, bool)
            or not isinstance(rate_limit_per_minute, int)
            or rate_limit_per_minute <= 0
        ):
            from .errors import Dt3ConfigurationError

            raise Dt3ConfigurationError(
                "error.rate_limit_per_minute must be a positive integer"
            )

        self._fail_open = fail_open
        self._diagnostics_enabled = diagnostics_enabled
        self._stream = diagnostics_stream if diagnostics_stream is not None else sys.stderr
        self._include_stack = include_stack
        self._rate_limit = rate_limit_per_minute
        self._on_error = on_error
        self._lock = threading.Lock()
        self._counts: dict[Dt3ErrorCode, int] = {}
        self._window_start: dict[Dt3ErrorCode, float] = {}
        self._window_emitted: dict[Dt3ErrorCode, int] = {}

    # PUBLIC_INTERFACE
    def report(
        self,
        error: BaseException,
        *,
        phase: Dt3ErrorPhase,
        context: Optional[Mapping[str, str]] = None,
    ) -> None:
        """Record and observe a failure without applying a delivery disposition."""
        code, retryable = self.classify(error)
        with self._lock:
            self._counts[code] = self._counts.get(code, 0) + 1
            occurrences = self._counts[code]
            should_emit = self._allow_emission_locked(code)

        report = Dt3ErrorReport(
            code=code,
            phase=phase,
            message=str(error),
            retryable=retryable,
            error=error,
            occurrences=occurrences,
        )
        if self._diagnostics_enabled and should_emit:
            self._write_diagnostic(report, context or {})

        if self._on_error is not None:
            try:
                self._on_error(report)
            except Exception:
                # Application callbacks cannot be permitted to recurse through
                # the logger's own internal failure path.
                pass

    # PUBLIC_INTERFACE
    def handle(
        self,
        error: BaseException,
        *,
        phase: Dt3ErrorPhase,
        context: Optional[Mapping[str, str]] = None,
    ) -> None:
        """Record a failure and apply the configured fail-open disposition."""
        self.report(error, phase=phase, context=context)
        if not self._fail_open:
            raise error

    # PUBLIC_INTERFACE
    def guard(
        self,
        operation: Callable[[], None],
        *,
        phase: Dt3ErrorPhase,
        context: Optional[Mapping[str, str]] = None,
    ) -> bool:
        """Run an operation under the configured failure policy."""
        try:
            operation()
            return True
        except (KeyboardInterrupt, SystemExit, MemoryError):
            raise
        except BaseException as error:
            self.handle(error, phase=phase, context=context)
            return False

    # PUBLIC_INTERFACE
    def classify(self, error: BaseException) -> tuple[Dt3ErrorCode, bool]:
        """Map an arbitrary exception to a canonical error code and retryability."""
        if isinstance(error, Dt3Error):
            return error.code, error.retryable
        if isinstance(error, TimeoutError):
            return Dt3ErrorCode.TRANSPORT_TIMEOUT, True
        if isinstance(error, RecursionError):
            return Dt3ErrorCode.MASKING_FAILED, False
        if isinstance(error, TypeError):
            return Dt3ErrorCode.SERIALIZATION_FAILED, False
        if isinstance(error, OSError):
            return Dt3ErrorCode.TRANSPORT_UNAVAILABLE, True
        if isinstance(error, ValueError):
            return Dt3ErrorCode.CONFIGURATION_INVALID, False
        if isinstance(error, RuntimeError):
            return Dt3ErrorCode.LIFECYCLE_CLOSED, False
        return Dt3ErrorCode.UNKNOWN, False

    # PUBLIC_INTERFACE
    def snapshot(self) -> dict[str, int]:
        """Return cumulative handled-error counts keyed by stable error code."""
        with self._lock:
            return {code.value: count for code, count in self._counts.items()}

    def _allow_emission_locked(self, code: Dt3ErrorCode) -> bool:
        """Return whether the code remains within its current diagnostic window."""
        now = time.monotonic()
        window_start = self._window_start.get(code)
        if window_start is None or now - window_start >= 60.0:
            self._window_start[code] = now
            self._window_emitted[code] = 1
            return True

        emitted = self._window_emitted[code]
        if emitted < self._rate_limit:
            self._window_emitted[code] = emitted + 1
            return True
        return False

    def _write_diagnostic(
        self,
        report: Dt3ErrorReport,
        context: Mapping[str, str],
    ) -> None:
        """Write one best-effort diagnostic line without invoking the logger."""
        labels = " ".join(
            f"{key}={value}" for key, value in sorted(context.items())
        )
        line = (
            f"[dt3-sdk] level=error code={report.code.value} "
            f"phase={report.phase.value} "
            f"retryable={str(report.retryable).lower()} "
            f"occurrences={report.occurrences} "
            f"type={type(report.error).__name__} "
            f"message={report.message!r}"
        )
        if labels:
            line = f"{line} {labels}"

        try:
            self._stream.write(f"{line}\n")
            if self._include_stack and report.error.__traceback__ is not None:
                self._stream.write(
                    "".join(
                        traceback.format_exception(
                            type(report.error),
                            report.error,
                            report.error.__traceback__,
                        )
                    )
                )
            self._stream.flush()
        except Exception:
            # A broken diagnostic stream must not turn fail-open logging into an
            # application failure.
            pass
