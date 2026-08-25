"""FastAPI integration helpers for recording unhandled application exceptions."""

from __future__ import annotations

from typing import Any


# PUBLIC_INTERFACE
def create_error_handler(logger: Any):
    """Create a FastAPI-compatible exception handler that records and re-raises.

    Args:
        logger: A DT3 logger implementing ``error(message, error, context)``.

    Returns:
        An async exception handler suitable for ``app.add_exception_handler``.

    Raises:
        BaseException: The original application exception is re-raised after it
            is recorded so FastAPI remains authoritative for HTTP responses.
    """

    async def dt3_error_handler(request: Any, error: Exception) -> None:
        """Record one request failure and preserve FastAPI's normal error flow."""
        route = getattr(getattr(request, "scope", {}), "get", lambda *_: None)(
            "route"
        )
        route_path = getattr(route, "path", "unknown")
        logger.error(
            "Unhandled request error",
            error,
            {
                "event.name": "REQUEST_FAILED",
                "attributes": {
                    "http.method": getattr(request, "method", "UNKNOWN"),
                    "http.route": route_path,
                },
            },
        )
        raise error

    return dt3_error_handler
