"""Database event builders."""

from __future__ import annotations

from typing import Any, Dict, Optional


def build_database_event(
    event_name: str,
    message: str,
    *,
    system: Optional[str] = None,
    operation: Optional[str] = None,
    name: Optional[str] = None,
    table: Optional[str] = None,
    duration_ms: Optional[float] = None,
    severity: str = "INFO",
    **extra: Any,
) -> Dict[str, Any]:
    """Build a canonical database LogEvent fragment."""
    event: Dict[str, Any] = {
        "event.name": event_name,
        "message": message,
        "severity": severity,
    }
    if system is not None:
        event["db.system"] = system
    if operation is not None:
        event["db.operation"] = operation
    if name is not None:
        event["db.name"] = name
    if table is not None:
        event["db.sql.table"] = table
    if duration_ms is not None:
        event["duration.ms"] = duration_ms
    event.update(extra)
    return event
