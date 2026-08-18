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

## Batching

Batching reduces the overhead of frequent logging by holding final events until
a batch reaches its configured size or its flush interval expires. Events are
created, enriched with context, masked, and validated **before** they enter the
buffer, so each event retains the context active at the time it was logged.

Batching is disabled by default to preserve existing immediate-export behavior.
Enable it with the canonical SDK configuration keys:

```python
from dt3_sdk import create_logger

logger = create_logger(
    {
        "service.name": "orders-api",
        "service.version": "1.0.0",
        "deployment.environment": "production",
        "exporter": "http",
        "exporter.http.endpoint": "https://logs.example.test/v1/events",
        "batching.enabled": True,
        "batching.max_size": 100,
        "batching.flush_interval_ms": 5000,
    }
)
```

| Configuration key | Default | Description |
| --- | --- | --- |
| `batching.enabled` | `false` | Enables buffered delivery when `true`. |
| `batching.max_size` | `100` | Positive number of events that causes an immediate flush. |
| `batching.flush_interval_ms` | `5000` | Positive maximum time, in milliseconds, an under-sized batch remains buffered. |

Use `logger.flush()` to synchronously send all events buffered before the flush
boundary. It is safe to call when no events are pending and then flushes the
selected transport. `logger.close()` is idempotent, flushes any remaining batch
before closing the transport, and prevents subsequent log or flush operations.

Batching works with the existing stdout, JSON Lines file, HTTP, and OTLP/HTTP
exporters without changing the canonical event schema. File, HTTP, and OTLP
transports receive final events in the same order they were added to a batch.
Transport delivery continues to honor `fail_open`: when it is `true` (the
default), normalized delivery failures are swallowed and the batch remains
available for a later flush; when `false`, the failure is raised.
