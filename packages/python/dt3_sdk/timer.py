"""Timer implementation for the DT3 Python SDK."""

from __future__ import annotations

import time
from typing import Any, Dict, Optional

from dt3_sdk.impl.logger_impl import LoggerImpl


class TimerImpl:
    """Measure elapsed monotonic time and emit one canonical completion event."""

    def __init__(
        self,
        logger: LoggerImpl,
        name: str,
        context: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Initialize a timer without starting it.

        Args:
            logger: Open logger used to produce the canonical completion event.
            name: Non-empty event name assigned to the timer completion event.
            context: Optional event metadata merged with active logger context.

        Raises:
            TypeError: If the logger, name, or context has an invalid type.
            ValueError: If the timer name is blank.
        """
        if not isinstance(logger, LoggerImpl):
            raise TypeError("logger must be a LoggerImpl instance")
        if not isinstance(name, str):
            raise TypeError("name must be a string")
        if not name.strip():
            raise ValueError("name must not be blank")
        if context is not None and not isinstance(context, dict):
            raise TypeError("context must be a dictionary or None")

        self._logger = logger
        self._name = name
        self._context = dict(context or {})
        self._started_at: Optional[float] = None
        self._stopped = False
        self._elapsed_ms: Optional[float] = None

    # PUBLIC_INTERFACE
    def start(self) -> "TimerImpl":
        """Start this timer and return it for fluent use.

        Raises:
            RuntimeError: If the timer has already been started or stopped, or the
                associated logger has been closed.
        """
        if self._started_at is not None:
            raise RuntimeError("Timer has already been started")
        self._logger._ensure_open()
        self._started_at = time.perf_counter()
        return self

    # PUBLIC_INTERFACE
    def stop(self) -> float:
        """Stop the timer, emit one completion event, and return elapsed milliseconds.

        Raises:
            RuntimeError: If the timer was not started, is already stopped, or the
                associated logger has been closed.
        """
        if self._started_at is None:
            raise RuntimeError("Timer has not been started")
        if self._stopped:
            raise RuntimeError("Timer has already been stopped")

        self._logger._ensure_open()
        self._elapsed_ms = (time.perf_counter() - self._started_at) * 1000
        event_context = dict(self._context)
        event_context["event.name"] = self._name
        event_context["duration.ms"] = self._elapsed_ms
        self._logger.info(f"{self._name} completed", event_context)
        self._stopped = True
        return self._elapsed_ms

    # PUBLIC_INTERFACE
    def finish(self) -> float:
        """Stop the timer and return elapsed milliseconds."""
        return self.stop()
