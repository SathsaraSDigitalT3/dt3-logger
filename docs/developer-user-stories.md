# DT3 Commons — developer user stories

Simple list of what you can do with the SDK in a real project.
Written for interns and new teammates. Prefer copy-paste patterns from
[testing with real apps](./testing-with-real-apps.md).

Each story has:

- **As a…** who you are  
- **I want…** the feature  
- **So that…** why it helps  
- **How** the practical thing to call (Python · Node · Java)

---

## 1. Log a normal message

**As a** backend developer  
**I want** to log info / warn / error with a clear event name  
**So that** I can search logs by what happened, not only by free text  

**How**

- Python: `logger.info("Order saved", {"event.name": "ORDER_SAVED", "order.id": "42"})`
- Node: `logger.info('Order saved', { 'event.name': 'ORDER_SAVED', 'order.id': '42' })`
- Java: `logger.info("Order saved", Map.of("event.name", "ORDER_SAVED", "order.id", "42"))`

Also available: `debug`, `warn`, `error`, `fatal`.

---

## 2. See JSON on my console while coding

**As a** developer on my laptop  
**I want** logs printed as JSON to stdout  
**So that** I can confirm logging works without Kafka or a collector  

**How** — set `"exporter": "stdout"` (or `config.setExporter("stdout")` in Java).

---

## 3. Attach one correlation id to a whole request

**As a** API developer  
**I want** every log in one HTTP request to share the same `correlation.id`  
**So that** I can filter one user request in CloudWatch / ELK / Grafana  

**How**

- Python: `with logger_context(correlation_id=…): …`
- Node: `await logger.withContext({ correlationId: … }, async () => { … })`
- Java: `try (LogContext.Scope s = logger.withContext(LogContext.builder().correlationId(…).build())) { … }`

Tip: read `x-correlation-id` from the incoming request header.

---

## 4. Auto-generate correlation ids when the client sends none

**As a** service owner  
**I want** a correlation id even if the client forgot the header  
**So that** every request is still traceable  

**How** — set `correlation.auto_generate` / `setAutoGenerateCorrelationId(true)`.

---

## 5. Carry tenant info on every log

**As a** multi-tenant app developer  
**I want** `tenant.id` (and optional region / environment) on logs  
**So that** support can filter one customer’s traffic  

**How** — put tenant fields in scoped context or on each log call:

- `tenant.id`, `tenant.region`, `tenant.environment`

---

## 6. Pass trace context to another service (HTTP headers)

**As a** developer calling another API  
**I want** to inject / extract W3C trace headers  
**So that** logs across services share the same `trace.id`  

**How**

- Python: `inject(headers)` / `extract(headers)` then `logger_context(…)`
- Node: `inject(context, headers)` / `extract(headers)` then `logger.withContext(…)`
- Java: `LogContext.inject(headers)` / `LogContext.extract(headers)` then `withContext`

Headers involved: `traceparent`, `tracestate`, `x-correlation-id`, tenant headers.

---

## 7. Time how long a block of code takes

**As a** developer  
**I want** a timer that emits one event with `duration.ms`  
**So that** I can spot slow checkout / DB / AI calls  

**How**

- Python / Node / Java: `timer = logger.create_timer("ORDER_PROCESSING")` (Java: `createTimer`), then `start()` → work → `stop()`

---

## 8. Emit a typed API / HTTP event

**As a** API developer  
**I want** standard `INCOMING_HTTP` / `OUTGOING_HTTP` events  
**So that** dashboards can chart status codes and routes the same way in every language  

**How**

- Python: `emitter.emit_api("INCOMING_HTTP", "GET /orders", method="GET", route="/orders", status_code=200)`
- Node: `emitter.emitApi('INCOMING_HTTP', { 'http.request.method': 'GET', … })`
- Java: `emitter.emit(ApiEvents.incomingHttp("GET", "/orders", 200, 12.5))`

---

## 9. Emit a database event

**As a** developer talking to Postgres / Dynamo / etc.  
**I want** a clear DB event name and attributes  
**So that** slow queries are easy to find  

**How**

- Python: `emitter.emit_db(…)`
- Node: `emitter.emitDatabase(…)`
- Java: builders in `DatabaseEvents` + `emitter.emit(…)`

---

## 10. Emit a messaging / worker event

**As a** queue or Kafka worker developer  
**I want** consume / produce style events  
**So that** retries and failures show up like other services  

**How**

- Python: `emitter.emit_messaging(…)`
- Node: `emitter.emitMessaging(…)`
- Java: `MessagingEvents` + `emitter.emit(…)`

---

## 11. Log AI prompt and response safely

**As a** developer calling an LLM  
**I want** AI request / response events with `kavia.*` fields  
**So that** we can debug latency, tokens, and model — without leaking secrets when masking is on  

**How**

- Python: `emitter.emit_ai_request(…)` / `emitter.emit_ai_response(…)`
- Node: `emitter.emitAiRequest(…)` / `emitter.emitAiResponse(…)`
- Java: `emitter.emitAiRequest(…)` / `emitter.emitAiResponse(…)`

Also available: tool / memory / RAG / agent / safety helpers (`build_ai_*` / `AiEvents`).

---

## 12. Hide passwords and tokens automatically

**As a** developer  
**I want** sensitive fields redacted before export  
**So that** I do not accidentally log secrets  

**How** — leave masking enabled (default path) and configure field names if needed (`masking.fields`, replacement value). AI fields such as prompt/response are included in the masking set.

---

## 13. Fail loud or soft on bad events

**As a** team lead  
**I want** validation STRICT in CI / local, softer in production if needed  
**So that** bad shapes are caught early without always crashing the app  

**How** — `validation.mode`: `STRICT` | `LENIENT` | `OFF`.

---

## 14. Write logs to a file

**As a** developer without a log collector  
**I want** JSON Lines on disk  
**So that** I can `tail` or upload the file later  

**How** — `"exporter": "file"` + file path config (`exporter.file.path` / `setFilePath`).

---

## 15. Send logs over HTTP

**As a** developer with a central ingest URL  
**I want** each event POSTed as JSON  
**So that** the platform can store / index them  

**How** — `"exporter": "http"` + endpoint (and optional headers / timeout).

---

## 16. Send logs with OpenTelemetry (OTLP)

**As a** team using an OTel collector  
**I want** OTLP/HTTP JSON export  
**So that** logs land next to other telemetry  

**How** — `"exporter": "otlp"` + `otlp.endpoint` (and optional headers).

---

## 17. Send logs to Kafka or Event Hub

**As a** platform developer  
**I want** a Kafka / Event Hub sink  
**So that** downstream jobs can consume the same events  

**How** — `"exporter": "kafka"` or `"eventhub"` plus the matching endpoint / topic / headers config.

---

## 18. Send the same event to more than one place

**As a** developer  
**I want** stdout **and** file (or HTTP) at the same time  
**So that** I can debug locally while still shipping events  

**How**

- Config: `"exporters": ["stdout", "file"]`
- Or at runtime: `logger.register_sink(…)` / `registerSink(…)`

One sink failing should not block the others.

---

## 19. Batch many events before sending

**As a** high-traffic service developer  
**I want** batching  
**So that** we reduce HTTP/OTLP chatter  

**How** — `batching.enabled`, `batching.max_size`, `batching.flush_interval_ms`. Call `flush()` / `close()` on shutdown.

---

## 20. Create a lightweight span for a piece of work

**As a** developer  
**I want** `startSpan` / `withSpan` without installing the full OpenTelemetry SDK  
**So that** nested work gets correct `trace.id` / `span.id` on logs  

**How**

- Python: `tracer = create_tracer(logger)` then `tracer.with_span("checkout", lambda span: …)` or `span = tracer.start_span("checkout")` … `span.end()`
- Node: `logger.createTracer().withSpan('checkout', () => { … })`
- Java: `logger.createTracer().withSpan("checkout", span -> { … })` or `startSpan`

Every event still gets trace/span ids when auto-generate is on (default).

---

## 21. Add a breadcrumb on a span

**As a** developer debugging a long operation  
**I want** `span.addEvent("payment_authorized")`  
**So that** important steps show up without a full separate logger call pattern  

**How** — use the `Span` returned by the tracer (`addEvent`).

---

## 22. Shut down cleanly

**As a** service developer  
**I want** `flush` + `close` on process exit  
**So that** the last events are not lost when batching or HTTP is slow  

**How** — call `logger.flush()` then `logger.close()` in your shutdown hook.

---

## Quick “start here” path for interns

1. Install the library (`pip` / `npm` / Maven) → [testing with real apps](./testing-with-real-apps.md)  
2. Do stories **1**, **2**, **3**, **8** in your project  
3. Add **12** (masking) before logging real user data  
4. Add **7** or **20** when you care about latency  
5. Change exporter (**14–17**) only when a collector exists  

## Related docs

- [Test with a real application](./testing-with-real-apps.md)
- Package details: `packages/python/README.md`, `packages/node/README.md`, `packages/java/README.md`
- Deeper standards: [logging](./logging-standard.md), [tracing](./tracing-standard.md), [masking](./masking-standard.md), [tenancy](./tenancy-standard.md)
