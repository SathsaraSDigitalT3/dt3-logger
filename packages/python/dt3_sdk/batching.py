"""Thread-safe batching support for final DT3 structured log events."""

from __future__ import annotations

import threading
from collections.abc import Callable, Mapping
from typing import Any


class EventBatcher:
    """Buffer final events and deliver them in order using an event callback."""

    def __init__(
        self,
        deliver: Callable[[Mapping[str, Any]], None],
        *,
        max_size: int,
        flush_interval_ms: int,
    ) -> None:
        """Initialize a batching buffer.

        Args:
            deliver: Callback that synchronously delivers one final event.
            max_size: Number of buffered events that triggers an immediate flush.
            flush_interval_ms: Maximum time final events remain buffered.

        Raises:
            ValueError: If the batch size or flush interval is not a positive integer.
        """
        if isinstance(max_size, bool) or not isinstance(max_size, int) or max_size <= 0:
            raise ValueError("batching.max_size must be a positive integer")
        if (
            isinstance(flush_interval_ms, bool)
            or not isinstance(flush_interval_ms, int)
            or flush_interval_ms <= 0
        ):
            raise ValueError(
                "batching.flush_interval_ms must be a positive integer in milliseconds"
            )

        self._deliver = deliver
        self._max_size = max_size
        self._flush_interval_seconds = flush_interval_ms / 1000
        self._events: list[dict[str, Any]] = []
        self._lock = threading.RLock()
        self._closed = False
        self._aborted = False
        self._timer: threading.Timer | None = None

    # PUBLIC_INTERFACE
    def add(self, event: Mapping[str, Any]) -> None:
        """Buffer one final event and flush when the configured size is reached.

        Once a fail-closed delivery error aborts the batcher lifecycle, later
        events are discarded without delivery. This preserves the terminal
        failure state and prevents implicit retries.

        Args:
            event: Final masked and validation-processed event to deliver.

        Raises:
            RuntimeError: If the batcher is already closed.
            Exception: Any fail-closed delivery error raised by the callback.
        """
        with self._lock:
            if self._closed:
                raise RuntimeError("Batcher is closed")
            self._events.append(dict(event))
            self._schedule_flush_locked()
            should_flush = len(self._events) >= self._max_size

        if should_flush:
            self.flush()

    # PUBLIC_INTERFACE
    def flush(self) -> None:
        """Synchronously deliver all currently buffered events in insertion order.

        Events successfully accepted by the transport are never requeued. A
        delivery exception aborts the current batcher lifecycle: the failed
        event and unattempted suffix are not retried implicitly by a later
        flush or close call.
        """
        with self._lock:
            if self._aborted:
                return
            pending_events = self._take_events_locked()

        if not pending_events:
            return

        for index, event in enumerate(pending_events):
            try:
                self._deliver(event)
            except Exception:
                self._abort_after_delivery_failure()
                raise

    # PUBLIC_INTERFACE
    def close(self) -> None:
        """Flush pending events and prevent future buffering.

        Closing is idempotent. A delivery failure does not retry the failed
        event or any successfully exported earlier event, preventing duplicate
        delivery during lifecycle cleanup.
        """
        with self._lock:
            if self._closed:
                return
            self._closed = True
            self._cancel_timer_locked()
            if self._aborted:
                self._events = []
                return
            pending_events = self._take_events_locked()

        if not pending_events:
            return

        for event in pending_events:
            try:
                self._deliver(event)
            except Exception:
                # The logger applies fail-open/fail-closed policy at delivery
                # time. A closed batcher cannot safely retain events for retry,
                # and retrying here could duplicate delivery with ambiguous
                # transports, so remaining events are not replayed.
                raise

    def _schedule_flush_locked(self) -> None:
        """Schedule interval delivery only when final events are buffered."""
        if self._timer is None and self._events and not self._closed:
            self._timer = threading.Timer(
                self._flush_interval_seconds,
                self._flush_from_timer,
            )
            self._timer.daemon = True
            self._timer.start()

    def _flush_from_timer(self) -> None:
        """Flush due events without leaking timer-thread delivery exceptions."""
        with self._lock:
            self._timer = None
            if self._closed or not self._events:
                return

        try:
            self.flush()
        except Exception:
            # The logger's configured delivery policy is applied by its callback.
            # Fail-closed failures abort the batcher; fail-open callbacks do not
            # raise, so their normal delivery lifecycle continues.
            return

    def _take_events_locked(self) -> list[dict[str, Any]]:
        """Detach pending events and cancel an obsolete interval timer."""
        if not self._events:
            return []
        self._cancel_timer_locked()
        pending_events = self._events
        self._events = []
        return pending_events

    def _abort_after_delivery_failure(self) -> None:
        """Discard pending work after a fail-closed delivery exception.

        The logger callback raises only when the configured fail-closed policy
        requires the original error to surface. Retrying either the failed
        event or later events during cleanup could duplicate delivery or violate
        the aborted batch lifecycle, so no pending events are retained.
        """
        with self._lock:
            self._aborted = True
            self._events = []
            self._cancel_timer_locked()

    def _cancel_timer_locked(self) -> None:
        """Cancel the currently scheduled timer, if any."""
        if self._timer is not None:
            self._timer.cancel()
            self._timer = None
