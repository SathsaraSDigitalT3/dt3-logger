"""Typed event emitter wrapping Logger.event."""

from __future__ import annotations

from typing import Any, Mapping

from dt3_sdk.events.ai import build_ai_event
from dt3_sdk.events.api import build_api_event
from dt3_sdk.events.database import build_database_event
from dt3_sdk.events.messaging import build_messaging_event


class EventEmitter:
    """Dispatch typed domain/AI events through Logger.event."""

    def __init__(self, logger: Any) -> None:
        self._logger = logger

    # PUBLIC_INTERFACE
    def emit(self, event_object: Mapping[str, Any]) -> None:
        """Emit a canonical or builder-produced event through the logger pipeline."""
        self._logger.event(dict(event_object))

    # PUBLIC_INTERFACE
    def emit_api(self, event_name: str, message: str, **kwargs: Any) -> None:
        self.emit(build_api_event(event_name, message, **kwargs))

    # PUBLIC_INTERFACE
    def emit_db(self, event_name: str, message: str, **kwargs: Any) -> None:
        self.emit(build_database_event(event_name, message, **kwargs))

    # PUBLIC_INTERFACE
    def emit_messaging(self, event_name: str, message: str, **kwargs: Any) -> None:
        self.emit(build_messaging_event(event_name, message, **kwargs))

    # PUBLIC_INTERFACE
    def emit_ai(self, event_name: str, message: str, **kwargs: Any) -> None:
        self.emit(build_ai_event(event_name, message, **kwargs))

    # PUBLIC_INTERFACE
    def emit_ai_request(self, message: str = "AI request submitted", **kwargs: Any) -> None:
        from dt3_sdk.events.ai import build_ai_request_event

        self.emit(build_ai_request_event(message, **kwargs))

    # PUBLIC_INTERFACE
    def emit_ai_response(self, message: str = "AI response received", **kwargs: Any) -> None:
        from dt3_sdk.events.ai import build_ai_response_event

        self.emit(build_ai_response_event(message, **kwargs))
