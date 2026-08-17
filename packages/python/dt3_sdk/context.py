"""Execution-scoped context propagation for DT3 structured log events."""

from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar, Token
from typing import Any, Dict, Iterator

_ACTIVE_LOG_CONTEXT: ContextVar[Dict[str, Any]] = ContextVar(
    "dt3_active_log_context",
    default={},
)

_CANONICAL_FIELD_NAMES = {
    "trace_id": "trace.id",
    "span_id": "span.id",
    "parent_span_id": "parent.span.id",
    "correlation_id": "correlation.id",
}


def _canonicalize_context(context: Dict[str, Any]) -> Dict[str, Any]:
    """Return a copied context with supported convenience names canonicalized."""
    normalized_context: Dict[str, Any] = {}
    for key, value in context.items():
        canonical_key = _CANONICAL_FIELD_NAMES.get(key, key)
        normalized_context[canonical_key] = value
    return normalized_context


# PUBLIC_INTERFACE
@contextmanager
def logger_context(**context: Any) -> Iterator[None]:
    """Temporarily attach execution context to DT3 log events.

    Convenience argument names ``trace_id``, ``span_id``, ``parent_span_id``,
    and ``correlation_id`` are mapped to the canonical DT3 event fields
    ``trace.id``, ``span.id``, ``parent.span.id``, and ``correlation.id``.
    Canonical field names and other schema-compatible metadata are also
    accepted directly. Nested scopes inherit unspecified parent values and
    restore the prior context on exit.

    Args:
        **context: Schema-compatible context metadata for the current execution
            scope.

    Yields:
        None while the supplied context is active.

    Raises:
        TypeError: If a context key is not a string.
    """
    if any(not isinstance(key, str) for key in context):
        raise TypeError("logger context keys must be strings")

    merged_context = dict(_ACTIVE_LOG_CONTEXT.get())
    merged_context.update(_canonicalize_context(context))
    token: Token[Dict[str, Any]] = _ACTIVE_LOG_CONTEXT.set(merged_context)
    try:
        yield
    finally:
        _ACTIVE_LOG_CONTEXT.reset(token)


def get_active_logger_context() -> Dict[str, Any]:
    """Return a copy of the currently active execution-scoped log context."""
    return dict(_ACTIVE_LOG_CONTEXT.get())
