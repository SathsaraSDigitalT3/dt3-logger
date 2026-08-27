"""Messaging transport envelope adapter."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, Mapping, Optional


def wrap_log_event(
    log_event: Mapping[str, Any],
    *,
    event_type: Optional[str] = None,
    event_version: str = "1.0.0",
    source: Optional[str] = None,
) -> Dict[str, Any]:
    """Wrap a canonical LogEvent as schemas/event-envelope.schema.json payload."""
    name = event_type or str(log_event.get("event.name", "GENERIC_EVENT"))
    return {
        "event_type": name,
        "event_version": event_version,
        "timestamp": log_event.get("timestamp")
        or datetime.now(timezone.utc).isoformat(),
        "source": source or log_event.get("service.name"),
        "correlation_id": log_event.get("correlation.id"),
        "tenant_id": log_event.get("tenant.id"),
        "payload": dict(log_event),
        "metadata": {},
    }
