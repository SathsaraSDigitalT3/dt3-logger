"""Pluggable event sink abstractions for the DT3 Commons Python SDK."""

from __future__ import annotations

import json
import sys
from typing import Any, Callable, List, Mapping, Optional, Protocol, Sequence, Tuple


class EventSink(Protocol):
    """PUBLIC_INTERFACE Destination that receives final structured log events."""

    def export(self, event: Mapping[str, Any]) -> None:
        """Export one already-processed canonical log event."""

    def flush(self) -> None:
        """Flush any buffered sink state."""

    def close(self) -> None:
        """Release sink resources; subsequent export calls may fail."""


class StdoutSink:
    """Write final DT3 log events as JSON lines to stdout."""

    # PUBLIC_INTERFACE
    def export(self, event: Mapping[str, Any]) -> None:
        """Serialize and print one final structured DT3 log event."""
        print(json.dumps(dict(event), ensure_ascii=False))

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Flush stdout."""
        sys.stdout.flush()

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Flush stdout; the stream itself is process-owned."""
        self.flush()


class TransportSink:
    """Adapt a transport with export/flush/close to the EventSink surface."""

    def __init__(self, transport: Any) -> None:
        self._transport = transport

    def export(self, event: Mapping[str, Any]) -> None:
        self._transport.export(event)

    def flush(self) -> None:
        self._transport.flush()

    def close(self) -> None:
        self._transport.close()


OnSinkError = Callable[[str, BaseException], None]


class MultiSinkFanout:
    """Fan out export/flush/close to multiple sinks with per-sink isolation."""

    def __init__(
        self,
        sinks: Optional[Sequence[Tuple[str, EventSink]]] = None,
        on_error: Optional[OnSinkError] = None,
    ) -> None:
        """Create a fan-out sink.

        Args:
            sinks: Initial ``(name, sink)`` pairs.
            on_error: Optional callback invoked when a sink operation fails.
                Exceptions raised by the callback are captured so remaining sinks
                still run; the last disposition error is re-raised after fan-out.
        """
        self._sinks: List[Tuple[str, EventSink]] = list(sinks or ())
        self._on_error = on_error
        self._anonymous_count = 0

    # PUBLIC_INTERFACE
    def register(self, sink: EventSink, name: Optional[str] = None) -> str:
        """Register an additional sink and return its assigned name."""
        return self.add(sink, name)

    # PUBLIC_INTERFACE
    def add(self, sink: EventSink, name: Optional[str] = None) -> str:
        """Register an additional sink and return its assigned name."""
        sink_name = name if isinstance(name, str) and name.strip() else None
        if sink_name is None:
            self._anonymous_count += 1
            sink_name = f"sink-{self._anonymous_count}"
        self._sinks.append((sink_name, sink))
        return sink_name

    @property
    def sinks(self) -> List[Tuple[str, EventSink]]:
        """Return a copy of the registered ``(name, sink)`` pairs."""
        return list(self._sinks)

    # PUBLIC_INTERFACE
    def export(self, event: Mapping[str, Any]) -> None:
        """Export to every sink; one failure does not skip the others."""
        self._fanout(lambda sink: sink.export(event))

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Flush every sink with per-sink isolation."""
        self._fanout(lambda sink: sink.flush())

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Close every sink with per-sink isolation."""
        self._fanout(lambda sink: sink.close())

    def _fanout(self, operation: Callable[[EventSink], None]) -> None:
        """Run an operation against each sink, isolating and collecting failures."""
        disposition_error: Optional[BaseException] = None
        for name, sink in self._sinks:
            try:
                operation(sink)
            except Exception as error:
                if self._on_error is not None:
                    try:
                        self._on_error(name, error)
                    except Exception as raised:
                        disposition_error = raised
                else:
                    disposition_error = error
        if disposition_error is not None:
            raise disposition_error
