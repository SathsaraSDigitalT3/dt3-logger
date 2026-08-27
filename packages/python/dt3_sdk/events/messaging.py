"""Messaging and worker event builders."""

from __future__ import annotations

from typing import Any, Dict, Optional


def build_messaging_event(
    event_name: str,
    message: str,
    *,
    system: Optional[str] = None,
    destination: Optional[str] = None,
    operation: Optional[str] = None,
    message_id: Optional[str] = None,
    duration_ms: Optional[float] = None,
    severity: str = "INFO",
    **extra: Any,
) -> Dict[str, Any]:
    """Build a canonical messaging/worker LogEvent fragment."""
    event: Dict[str, Any] = {
        "event.name": event_name,
        "message": message,
        "severity": severity,
    }
    if system is not None:
        event["messaging.system"] = system
    if destination is not None:
        event["messaging.destination"] = destination
    if operation is not None:
        event["messaging.operation"] = operation
    if message_id is not None:
        event["messaging.message.id"] = message_id
    if duration_ms is not None:
        event["duration.ms"] = duration_ms
    event.update(extra)
    return event
