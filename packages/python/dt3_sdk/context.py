"""Execution-scoped context propagation for DT3 structured log events."""

from __future__ import annotations

import uuid
from contextlib import contextmanager
from contextvars import ContextVar, Token
from typing import Any, Dict, Iterator, Mapping, MutableMapping

_ACTIVE_LOG_CONTEXT: ContextVar[Dict[str, Any]] = ContextVar(
    "dt3_active_log_context",
    default={},
)

_CANONICAL_FIELD_NAMES = {
    "trace_id": "trace.id",
    "span_id": "span.id",
    "parent_span_id": "parent.span.id",
    "correlation_id": "correlation.id",
    "tenant_id": "tenant.id",
    "tenant_region": "tenant.region",
    "tenant_environment": "tenant.environment",
}

_HEADER_TO_CONTEXT_FIELD = {
    "x-correlation-id": "correlation.id",
    "x-tenant-id": "tenant.id",
    "x-tenant-region": "tenant.region",
    "x-tenant-environment": "tenant.environment",
}


def _canonicalize_context(context: Mapping[str, Any]) -> Dict[str, Any]:
    """Return a copied context with supported convenience names canonicalized."""
    normalized_context: Dict[str, Any] = {}
    for key, value in context.items():
        canonical_key = _CANONICAL_FIELD_NAMES.get(key, key)
        normalized_context[canonical_key] = value
    return normalized_context


def _header_value(carrier: Mapping[str, str], name: str) -> str | None:
    """Return a case-insensitive nonblank header value from a carrier."""
    for key, value in carrier.items():
        if isinstance(key, str) and key.lower() == name and isinstance(value, str):
            stripped_value = value.strip()
            return stripped_value or None
    return None


def _parse_traceparent(value: str | None) -> Dict[str, str]:
    """Safely parse a W3C traceparent header into canonical trace context."""
    if value is None:
        return {}

    parts = value.split("-")
    if len(parts) != 4:
        return {}
    version, trace_id, span_id, trace_flags = parts
    if (
        len(version) != 2
        or len(trace_id) != 32
        or len(span_id) != 16
        or len(trace_flags) != 2
        or any(character not in "0123456789abcdefABCDEF" for character in value.replace("-", ""))
        or trace_id == "0" * 32
        or span_id == "0" * 16
    ):
        return {}

    return {
        "trace.id": trace_id.lower(),
        "span.id": span_id.lower(),
        "trace.flags": trace_flags.lower(),
    }


def ensure_correlation_id(
    context: Mapping[str, Any],
    *,
    auto_generate: bool,
) -> Dict[str, Any]:
    """Return context with one generated correlation ID when configured.

    The generated ID is saved in the active ContextVar scope so all subsequent
    events in the same execution scope receive the same canonical value.
    """
    resolved_context = dict(context)
    if auto_generate and not resolved_context.get("correlation.id"):
        correlation_id = str(uuid.uuid4())
        resolved_context["correlation.id"] = correlation_id
        _ACTIVE_LOG_CONTEXT.set(resolved_context)
    return resolved_context


# PUBLIC_INTERFACE
@contextmanager
def logger_context(**context: Any) -> Iterator[None]:
    """Temporarily attach execution context to DT3 log events.

    Convenience argument names for trace, correlation, and tenant context map
    to canonical DT3 fields. Nested scopes inherit unspecified parent values
    and restore the prior context on exit.

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


# PUBLIC_INTERFACE
def inject(
    context: Mapping[str, Any],
    carrier: MutableMapping[str, str],
) -> None:
    """Inject trace, correlation, and tenant context into an HTTP header carrier.

    Args:
        context: Canonical or convenience-form DT3 context values to propagate.
        carrier: Mutable HTTP header mapping updated in place.

    Raises:
        TypeError: If context is not a mapping or carrier is not mutable.
    """
    if not isinstance(context, Mapping):
        raise TypeError("context must be a mapping")
    if not isinstance(carrier, MutableMapping):
        raise TypeError("carrier must be a mutable mapping")

    normalized_context = _canonicalize_context(context)
    trace_id = normalized_context.get("trace.id")
    span_id = normalized_context.get("span.id")
    trace_flags = normalized_context.get("trace.flags", "01")
    if isinstance(trace_id, str) and isinstance(span_id, str):
        carrier["traceparent"] = f"00-{trace_id}-{span_id}-{trace_flags}"

    tracestate = normalized_context.get("tracestate")
    if isinstance(tracestate, str) and tracestate:
        carrier["tracestate"] = tracestate

    for header_name, context_field in _HEADER_TO_CONTEXT_FIELD.items():
        value = normalized_context.get(context_field)
        if isinstance(value, str) and value:
            carrier[header_name] = value


# PUBLIC_INTERFACE
def extract(
    carrier: Mapping[str, str],
    *,
    auto_generate_correlation_id: bool = False,
) -> Dict[str, Any]:
    """Extract W3C trace, correlation, and tenant context from HTTP headers.

    Malformed or absent traceparent values produce no trace fields. Tenant and
    correlation headers are independently preserved when present.

    Args:
        carrier: HTTP header mapping, matched case-insensitively.
        auto_generate_correlation_id: Generate a UUID v4 correlation ID only
            when no incoming correlation header is available.

    Returns:
        A canonical DT3 context mapping suitable for ``logger_context``.
    """
    if not isinstance(carrier, Mapping):
        raise TypeError("carrier must be a mapping")

    extracted_context: Dict[str, Any] = _parse_traceparent(
        _header_value(carrier, "traceparent")
    )
    tracestate = _header_value(carrier, "tracestate")
    if tracestate is not None:
        extracted_context["tracestate"] = tracestate

    for header_name, context_field in _HEADER_TO_CONTEXT_FIELD.items():
        value = _header_value(carrier, header_name)
        if value is not None:
            extracted_context[context_field] = value

    if auto_generate_correlation_id and "correlation.id" not in extracted_context:
        extracted_context["correlation.id"] = str(uuid.uuid4())
    return extracted_context


def get_active_logger_context() -> Dict[str, Any]:
    """Return a copy of the currently active execution-scoped log context."""
    return dict(_ACTIVE_LOG_CONTEXT.get())
