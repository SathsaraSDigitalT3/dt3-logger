"""Public logging contract for the DT3 Python SDK."""

from __future__ import annotations

from typing import Any, Dict, Mapping, Optional, Protocol

from .timer import Timer


class Logger(Protocol):
    """Public interface for emitting DT3 log events and creating timers."""

    # PUBLIC_INTERFACE
    def debug(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Emit a DEBUG event."""

    # PUBLIC_INTERFACE
    def info(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Emit an INFO event."""

    # PUBLIC_INTERFACE
    def warn(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Emit a WARN event."""

    # PUBLIC_INTERFACE
    def error(
        self,
        message: str,
        error: Optional[Exception] = None,
        context: Optional[Dict[str, Any]] = None,
    ) -> None:
        """Emit an ERROR event with optional structured exception details."""

    # PUBLIC_INTERFACE
    def fatal(self, message: str, context: Optional[Dict[str, Any]] = None) -> None:
        """Emit a FATAL event."""

    # PUBLIC_INTERFACE
    def event(self, event_object: Mapping[str, Any]) -> None:
        """Process a canonical event through the normal logger pipeline."""

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Flush pending log events to configured exporters."""

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Close the logger and its configured exporter."""

    # PUBLIC_INTERFACE
    def register_sink(self, sink: Any, name: Optional[str] = None) -> str:
        """Register an additional event sink for runtime fan-out."""

    # PUBLIC_INTERFACE
    def create_timer(
        self,
        name: str,
        context: Optional[Dict[str, Any]] = None,
    ) -> "Timer":
        """Create an unstarted Timer that emits a completion event when stopped."""
