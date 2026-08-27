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

## Timers

Create a timer from an existing logger, call `start()`, then call `stop()` (or
the equivalent `finish()`) to emit exactly one canonical `INFO` event. The
timer name becomes `event.name`, the elapsed monotonic duration is recorded in
`duration.ms`, and the elapsed duration in milliseconds is also returned.

```python
from dt3_sdk import create_logger

logger = create_logger({"service.name": "orders-api", "service.version": "1.0.0"})
timer = logger.create_timer("ORDER_PROCESSING", {"order.id": "order-42"}).start()

# Process the order.
elapsed_ms = timer.stop()
```

Timers inherit the logger's active `logger_context`, validation, masking,
exporter, batching, and lifecycle behavior. A timer is single-use: starting it
twice, stopping it before it starts, or stopping it more than once raises
`RuntimeError`. Creating or stopping a timer after the logger closes also
raises the established `RuntimeError("Logger is closed")`.

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

## Structured events (schema 1.1.0)

Identity fields `event.id` (auto-generated UUID), `operation.id`, and
`component.name` are optional on every event. Typed builders and
`EventEmitter` produce canonical LogEvents for API, database, messaging, and
`kavia.*` AI domains without replacing `logger.event`.

```python
from dt3_sdk import EventEmitter, build_api_event, create_logger, create_tracer

logger = create_logger({
    "service.name": "orders-api",
    "service.version": "1.0.0",
    "deployment.environment": "production",
    "exporters": ["stdout"],  # fan-out list; or register_sink()
})
emitter = EventEmitter(logger)
emitter.emit(build_api_event(
    "INCOMING_HTTP",
    "GET /orders",
    method="GET",
    route="/orders",
    status_code=200,
    duration_ms=12.5,
))

tracer = create_tracer(logger)
with tracer.start_span("checkout"):
    logger.info("nested work", {"event.name": "CHECKOUT_STEP"})
```

Configure multiple sinks with `exporters: ["stdout", "file"]` or
`logger.register_sink(custom_sink)`. Prompt/response AI payloads are masked by
default; token counts use `kavia.tokens.*` so they are not redacted.
