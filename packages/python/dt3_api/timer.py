"""Public timing lifecycle contract for the DT3 Python SDK."""

from __future__ import annotations

from typing import Protocol


class Timer(Protocol):
    """Measure an operation and emit its final duration through a logger."""

    # PUBLIC_INTERFACE
    def start(self) -> "Timer":
        """Start the timer and return it for fluent use."""

    # PUBLIC_INTERFACE
    def stop(self) -> float:
        """Stop the timer, emit its completion event, and return elapsed milliseconds."""

    # PUBLIC_INTERFACE
    def finish(self) -> float:
        """Alias for stop that emits the completion event and returns elapsed milliseconds."""
