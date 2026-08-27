"""API/HTTP event builders."""

from __future__ import annotations

from typing import Any, Dict, Optional


def build_api_event(
    event_name: str,
    message: str,
    *,
    method: Optional[str] = None,
    route: Optional[str] = None,
    target: Optional[str] = None,
    status_code: Optional[int] = None,
    duration_ms: Optional[float] = None,
    severity: str = "INFO",
    **extra: Any,
) -> Dict[str, Any]:
    """Build a canonical API/HTTP LogEvent fragment."""
    event: Dict[str, Any] = {
        "event.name": event_name,
        "message": message,
        "severity": severity,
    }
    if method is not None:
        event["http.request.method"] = method
    if route is not None:
        event["http.route"] = route
    if target is not None:
        event["http.target"] = target
    if status_code is not None:
        event["http.response.status_code"] = status_code
    if duration_ms is not None:
        event["duration.ms"] = duration_ms
    event.update(extra)
    return event
