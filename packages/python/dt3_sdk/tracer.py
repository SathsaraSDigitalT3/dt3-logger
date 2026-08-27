"""Lightweight W3C-compatible span creation for the DT3 Commons Python SDK."""

from __future__ import annotations

import re
import secrets
import time
from typing import Any, Callable, Dict, Mapping, Optional, TypeVar, Union

from dt3_sdk.context import get_active_logger_context, logger_context

T = TypeVar("T")


# PUBLIC_INTERFACE
def generate_trace_id() -> str:
    """Return a new 32-character lowercase hex W3C trace id."""
    return secrets.token_hex(16)


# PUBLIC_INTERFACE
def generate_span_id() -> str:
    """Return a new 16-character lowercase hex W3C span id."""
    return secrets.token_hex(8)


def _to_upper_snake(name: str) -> str:
    """Convert a span name into a schema-compatible UPPER_SNAKE event name."""
    cleaned = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_").upper()
    if not cleaned:
        return "SPAN"
    if cleaned[0].isdigit():
        return f"SPAN_{cleaned}"
    return cleaned


class Span:
    """PUBLIC_INTERFACE Active span that scopes trace context onto log events."""

    def __init__(
        self,
        tracer: "Tracer",
        name: str,
        context: Mapping[str, Any],
    ) -> None:
        """Start a span and activate its logger context scope.

        Args:
            tracer: Parent tracer used for span-event emission.
            name: Human span name; converted to UPPER_SNAKE for span events.
            context: Canonical ``trace.id`` / ``span.id`` / ``parent.span.id`` fields.
        """
        self._tracer = tracer
        self.name = name
        self.context = dict(context)
        self.trace_id = str(context.get("trace.id", ""))
        self.span_id = str(context.get("span.id", ""))
        parent = context.get("parent.span.id")
        self.parent_span_id = parent if isinstance(parent, str) else None
        self._start = time.perf_counter()
        self._ended = False
        self._scope = logger_context(**self.context)
        self._scope.__enter__()

    # PUBLIC_INTERFACE
    def add_event(self, name_or_dict: Union[str, Mapping[str, Any]]) -> None:
        """Emit a log event under the active span context.

        Args:
            name_or_dict: Event name string or a full/partial log-event mapping.
        """
        if isinstance(name_or_dict, str):
            event_name = _to_upper_snake(name_or_dict)
            self._tracer._logger.info(
                name_or_dict,
                {"event.name": event_name},
            )
            return
        if not isinstance(name_or_dict, Mapping):
            raise TypeError("name_or_dict must be a string or mapping")
        self._tracer._logger.event(name_or_dict)

    # PUBLIC_INTERFACE
    def end(self) -> None:
        """End the span, restore prior context, and optionally emit a span event."""
        if self._ended:
            return
        self._ended = True
        duration_ms = (time.perf_counter() - self._start) * 1000
        self._scope.__exit__(None, None, None)

        if not self._tracer.span_events_enabled:
            return

        event_name = _to_upper_snake(self.name)
        completion: Dict[str, Any] = {
            "event.name": event_name,
            "duration.ms": duration_ms,
            **self.context,
        }
        self._tracer._logger.info(f"{self.name} completed", completion)

    def __enter__(self) -> "Span":
        return self

    def __exit__(self, exc_type: Any, exc: Any, tb: Any) -> None:
        self.end()


class Tracer:
    """PUBLIC_INTERFACE Create nested spans that participate via logger context."""

    def __init__(self, logger: Any, span_events_enabled: bool = True) -> None:
        """Create a tracer bound to a DT3 logger.

        Args:
            logger: Logger used for span events and ``add_event`` emission.
            span_events_enabled: When true, ending a span emits an INFO LogEvent.
        """
        if not hasattr(logger, "info") or not callable(logger.info):
            raise TypeError("logger must provide a callable info method")
        self._logger = logger
        self.span_events_enabled = bool(span_events_enabled)

    # PUBLIC_INTERFACE
    def start_span(self, name: str) -> Span:
        """Start a child span nested under the currently active context."""
        if not isinstance(name, str) or not name.strip():
            raise ValueError("span name must be a non-empty string")

        active = get_active_logger_context()
        parent_span_id = active.get("span.id")
        trace_id = active.get("trace.id")
        if not isinstance(trace_id, str) or not trace_id:
            trace_id = generate_trace_id()

        span_id = generate_span_id()
        context: Dict[str, Any] = {
            "trace.id": trace_id,
            "span.id": span_id,
        }
        if isinstance(parent_span_id, str) and parent_span_id:
            context["parent.span.id"] = parent_span_id

        return Span(self, name, context)

    # PUBLIC_INTERFACE
    def with_span(self, name: str, fn: Callable[[Span], T]) -> T:
        """Run ``fn`` with an active span and end the span on exit."""
        span = self.start_span(name)
        try:
            return fn(span)
        finally:
            span.end()


# PUBLIC_INTERFACE
def create_tracer(logger: Any, config: Optional[Mapping[str, Any]] = None) -> Tracer:
    """Create a Tracer from a logger and optional SDK configuration.

    Args:
        logger: DT3 logger instance.
        config: Optional config mapping; defaults to ``logger.config`` when present.
            Reads ``tracing.span_events.enabled`` (default True).

    Returns:
        A configured ``Tracer``.
    """
    resolved: Mapping[str, Any]
    if config is not None:
        resolved = config
    elif hasattr(logger, "config") and isinstance(logger.config, Mapping):
        resolved = logger.config
    else:
        resolved = {}

    enabled = resolved.get("tracing.span_events.enabled", True)
    if not isinstance(enabled, bool):
        enabled = True
    return Tracer(logger, span_events_enabled=enabled)
