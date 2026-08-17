# DT3 Commons Python SDK

Full implementation of the DT3 Commons logging, tracing, and multi-tenancy SDK for Python 3.10+.

## Context propagation

Use `logger_context` to associate request or execution metadata with every event
created inside a scope. It uses Python `contextvars`, so nested scopes restore
their parent context and concurrent `asyncio` tasks retain their own context.

```python
from dt3_sdk import create_logger, logger_context

logger = create_logger(
    {
        "service.name": "orders-api",
        "service.version": "1.0.0",
        "deployment.environment": "production",
    }
)

with logger_context(
    trace_id="0123456789abcdef0123456789abcdef",
    span_id="0123456789abcdef",
    correlation_id="request-42",
):
    logger.info("Request started", {"event.name": "REQUEST_STARTED"})
```

The convenience names map to canonical event fields:

- `trace_id` → `trace.id`
- `span_id` → `span.id`
- `parent_span_id` → `parent.span.id`
- `correlation_id` → `correlation.id`

Other schema-compatible metadata can be supplied directly. Values passed in an
individual log call's `context` mapping override active scoped context values.
