# DT3 Commons Node.js SDK

Full implementation of the DT3 Commons logging, tracing, and multi-tenancy SDK for Node.js 18+ and TypeScript.

## Timer API

Create an unstarted timer with `logger.createTimer`, then call `start()` and
`stop()` (or `finish()`) to emit one canonical `INFO` completion event through
the normal logger pipeline. The completion event includes the supplied
`event.name`, a non-negative `duration.ms`, active execution-scoped context,
and any optional timer metadata.

```ts
const timer = logger.createTimer('ORDER_PROCESSING', { 'order.id': 'order-42' });
timer.start();
const elapsedMs = timer.stop();
```

Timers use Node.js high-resolution monotonic time. A timer is single-use:
starting it twice, stopping it before it starts, or stopping it more than once
throws an error. Creating, starting, or completing a timer after logger closure
also fails with the logger’s standard `Logger is closed` lifecycle error.

## Execution-scoped context propagation

The SDK supports execution-scoped trace and correlation context through Node.js
[`AsyncLocalStorage`](https://nodejs.org/api/async_context.html#class-asynclocalstorage).
This makes it possible to establish request context once and have it attached
automatically to every log produced by the request, including logs emitted after
`await`, in promise callbacks, and in nested asynchronous functions.

Use `logger.withContext` to run synchronous or asynchronous work in a scope:

```ts
import { createLogger } from '@digitalt3/commons';

const logger = createLogger({
  'service.name': 'orders-api',
  'service.version': '1.0.0',
  'deployment.environment': 'production',
});

await logger.withContext(
  {
    traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
    spanId: '00f067aa0ba902b7',
    parentSpanId: '0000000000000001',
    correlationId: 'request-123',
  },
  async () => {
    logger.info('Request started', { 'event.name': 'REQUEST_STARTED' });
    await processOrder();
    logger.info('Request completed', { 'event.name': 'REQUEST_COMPLETED' });
  },
);
```

### Canonical field mapping

| Scoped context property | Canonical log-event field |
| --- | --- |
| `traceId` | `trace.id` |
| `spanId` | `span.id` |
| `parentSpanId` | `parent.span.id` |
| `correlationId` | `correlation.id` |

The canonical fields are attached before masking, validation, and transport
delivery, so they work consistently with stdout, file, HTTP, and OTLP exporters.

### Scope behavior and precedence

- Context propagates through promises and `async`/`await`.
- Separate concurrent scopes are isolated; request context is never stored in
  mutable global state.
- Nested scopes inherit unspecified parent values and restore the parent context
  when the nested callback completes.
- No scoped context is added outside `withContext`, preserving existing logger
  behavior.
- Explicit values passed directly to `debug`, `info`, `warn`, or `error` take
  precedence over the active scoped context.
- Logger-owned fields such as severity, service metadata, timestamps, and error
  fields retain their existing precedence over both scoped and event-level input.
