# DT3 Commons Java API Contracts

The Java SDK provides synchronous structured logging with stdout, JSON Lines file, HTTP JSON, and OTLP/HTTP JSON exporters. Events are always processed through the canonical pipeline: context enrichment, masking, validation, batching when configured, and transport delivery.

## FATAL logging and direct canonical events

`Logger` supports the standard `debug`, `info`, `warn`, and `error` methods plus `fatal`:

```java
Logger logger = LoggerFactory.createLogger(config);

logger.fatal(
    "The process cannot continue",
    Map.of("event.name", "UNRECOVERABLE_FAILURE")
);
```

`fatal(...)` emits a canonical event with `severity` set to `FATAL` and otherwise follows the same masking, validation, batching, fail-open/fail-closed, and transport behavior as other log methods.

Use `event(LogEvent)` to submit an existing canonical Java event through the same pipeline. The supplied `LogEvent` is not mutated.

```java
LogEvent event = LogEvent.builder()
    .timestamp("2026-08-19T07:13:03Z")
    .severity("INFO")
    .message("Order received")
    .eventName("ORDER_RECEIVED")
    .correlationId("request-123")
    .tenantId("tenant-a")
    .attributes(Map.of("order.id", "123"))
    .build();

logger.event(event);
```

Missing logger-managed defaults such as timestamp, schema metadata, SDK metadata, and configured service metadata are enriched before validation. In `STRICT` mode invalid direct events raise `LogEventValidationException`; `LENIENT` attaches canonical validation diagnostics; and `OFF` bypasses validation.

## Execution-scoped context propagation

`LogContext` attaches trace, correlation, and tenant metadata to all events created in a current-thread execution scope. It is useful for request handling, message processing, and other logical operations where repeatedly passing the same identifiers to every logging call would be error-prone.

Use the returned scope with Java try-with-resources so context is restored even when the scoped work throws:

```java
Logger logger = LoggerFactory.createLogger(config);

try (LogContext.Scope ignored = logger.withContext(
    LogContext.builder()
        .traceId("0123456789abcdef0123456789abcdef")
        .spanId("0123456789abcdef")
        .parentSpanId("fedcba9876543210")
        .correlationId("request-123")
        .tenantId("tenant-a")
        .tenantRegion("eu-west-1")
        .tenantEnvironment("production")
        .build()
)) {
    logger.info("Request started", Map.of("event.name", "REQUEST_STARTED"));
    logger.info("Request completed", Map.of("event.name", "REQUEST_COMPLETED"));
}
```

The builder maps values only to canonical fields:

| Builder method | Canonical event field |
| --- | --- |
| `traceId(...)` | `trace.id` |
| `spanId(...)` | `span.id` |
| `parentSpanId(...)` | `parent.span.id` |
| `traceFlags(...)` | `trace.flags` |
| `tracestate(...)` | `tracestate` |
| `correlationId(...)` | `correlation.id` |
| `tenantId(...)` | `tenant.id` |
| `tenantRegion(...)` | `tenant.region` |
| `tenantEnvironment(...)` | `tenant.environment` |

Nested scopes inherit context fields that they do not replace. Closing an inner scope restores its parent scope; closing the outer scope clears it for the current thread. Context uses `ThreadLocal`, so independent threads do not leak metadata into each other.

Final event precedence is: logger-owned fields, then explicit event context, then active scoped context. In other words, event-level `trace.id` or `correlation.id` values override scoped values for that event, while logger method severity and configured service metadata cannot be overridden.

## HTTP trace, correlation, and tenant propagation

`LogContext.inject(...)` serializes active canonical fields into a mutable HTTP header map:

- W3C `traceparent`, using `trace.id`, `span.id`, and `trace.flags`
- W3C `tracestate`
- `x-correlation-id`
- `x-tenant-id`
- `x-tenant-region`
- `x-tenant-environment`

```java
Map<String, String> headers = new LinkedHashMap<>();
LogContext context = LogContext.builder()
    .traceId("0123456789abcdef0123456789abcdef")
    .spanId("0123456789abcdef")
    .tracestate("vendor=value")
    .correlationId("request-123")
    .tenantId("tenant-a")
    .tenantRegion("eu-west-1")
    .tenantEnvironment("production")
    .build();

context.inject(headers);
```

`LogContext.extract(...)` parses incoming headers case-insensitively and returns a context that can be scoped with `logger.withContext(...)`:

```java
LogContext incoming = LogContext.extract(requestHeaders);

try (LogContext.Scope ignored = logger.withContext(incoming)) {
    logger.info("Request accepted", Map.of("event.name", "REQUEST_ACCEPTED"));
}
```

Malformed or missing `traceparent` values are ignored safely. Independently valid `tracestate`, correlation, and tenant headers are retained. Closing the scope prevents extracted values from leaking to later work on the same thread.

## Automatic correlation-ID generation

Enable automatic generation when requests do not already supply a correlation ID:

```java
SdkConfig config = new SdkConfig();
config.setAutoGenerateCorrelationId(true);
// Equivalent canonical map key: correlation.auto_generate = true
```

When enabled, the logger generates a UUID only if neither the active context nor explicit event fields contain a nonblank `correlation.id`. Explicit and extracted correlation IDs are never replaced. A generated ID is retained for later events in the same active `LogContext` scope; independent unscoped events receive independent IDs.

## Cross-thread and executor propagation

`ThreadLocal` context is intentionally not transferred implicitly to executor work. Explicitly wrap submitted work with `LogContext.wrap(...)` to propagate a point-in-time context snapshot. The worker's prior context is restored after the task completes, preventing leakage in reused executor threads.

```java
ExecutorService executor = Executors.newFixedThreadPool(4);

try (LogContext.Scope ignored = logger.withContext(
    LogContext.builder().correlationId("request-123").build()
)) {
    Future<?> future = executor.submit(LogContext.wrap(() ->
        logger.info("Worker completed", Map.of("event.name", "WORKER_COMPLETED"))
    ));
    future.get();
}
```

Both `Runnable` and `Callable<T>` are supported. Wrap work at submission time so each task carries only the context that was active for that task.

## HTTP exporter

Configure the HTTP exporter through `SdkConfig`:

```java
SdkConfig config = new SdkConfig();
config.setServiceName("orders");
config.setServiceVersion("1.0.0");
config.setDeploymentEnvironment("production");
config.setExporter("http");
config.setHttpEndpoint("https://logs.example.com/v1/events");
// Maps to exporter.http.timeout; value is in milliseconds.
config.setHttpTimeout(5_000);
config.setHttpHeaders(Map.of("Authorization", "Bearer <token>"));

Logger logger = LoggerFactory.createLogger(config);
logger.info("Order created", Map.of("event.name", "ORDER_CREATED"));
```

The Java `setHttpTimeout(long)` API configures the canonical `exporter.http.timeout` value in milliseconds (default: `5000`). The transport sends each final event synchronously using `POST` with `Content-Type: application/json`. Custom headers are included except an attempted `Content-Type` override, because all exported DT3 events are JSON. Only 2xx responses are successful. Timeouts, connection failures, and non-2xx responses are transport failures; `failOpen` controls whether the logger swallows them (`true`, the default) or propagates `HttpTransportError` (`false`).

## Error reporting contracts

`ErrorHandler` applies the same retryability policy across SDKs. File-write failures, including generic `UncheckedIOException` failures and `FileTransport` write failures, are classified as `DT3_FILE_WRITE_FAILED` and are **not retryable** because permission, path, directory-target, and storage failures require configuration or environmental remediation.

The phase in `ErrorHandler.report(error, phase)` and `handle(error, phase)` is always the caller-supplied handling phase, even for a `Dt3SdkException` that carries its own originating phase. This identifies where the pipeline handled the error. By contrast, `classify(typedError)` has no caller phase and returns the typed exception’s intrinsic phase.

## OTLP/HTTP JSON Logs exporter

Configure the OTLP exporter with the canonical cross-language properties `exporter = "otlp"`, `otlp.endpoint`, `otlp.timeout`, and `otlp.headers`:

```java
SdkConfig config = new SdkConfig();
config.setServiceName("orders");
config.setServiceVersion("1.0.0");
config.setDeploymentEnvironment("production");
config.setExporter("otlp");
config.setOtlpEndpoint("https://collector.example.com/v1/logs");
// Maps to otlp.timeout; value is in milliseconds (default: 10000).
config.setOtlpTimeout(10_000);
config.setOtlpHeaders(Map.of("Authorization", "Bearer <token>"));
config.setFailOpen(false);

Logger logger = LoggerFactory.createLogger(config);
logger.info("Order created", Map.of("event.name", "ORDER_CREATED", "order.id", "123"));
```

The SDK processes every event through masking and validation before mapping it to the OTLP Logs JSON request shape. The transport sends one synchronous HTTP `POST` per event, uses `Content-Type: application/json`, and accepts any 2xx response as successful delivery. Custom headers are applied, but cannot override the required content type. Endpoint, timeout, connection, and non-2xx failures are represented by `OtlpTransportError`; `failOpen=true` (the default) swallows these transport failures, while `failOpen=false` propagates them.
