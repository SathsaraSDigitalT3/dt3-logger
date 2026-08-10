# DT3 Commons — Framework Integrations

This directory contains **framework-specific integration guides** that show how to
wire the DT3 Commons Platform SDK into popular application frameworks and runtime
environments.

## Available Integrations

| Integration | Language | Description |
|---|---|---|
| [python-fastapi](./python-fastapi/) | Python | Middleware for FastAPI with tenant extraction, request/response logging |
| [node-express](./node-express/) | TypeScript / JS | Express.js middleware with tenant extraction, request logging |
| [java-spring](./java-spring/) | Java | Spring Boot filter and interceptor integration |
| [worker-queue](./worker-queue/) | Any | Context propagation through message-queue workers |
| [serverless](./serverless/) | Any | AWS Lambda, GCP Functions, Azure Functions patterns |

## Integration Philosophy

Every integration follows the same principles:

1. **Facade-first** — Always use the DT3 logger facade; never call native
   logging primitives directly.
2. **Context propagation** — Trace ID, correlation ID, and tenant context flow
   automatically through the request lifecycle.
3. **Masking by default** — Sensitive data is redacted before log events leave
   the process.
4. **Cloud-agnostic** — Integrations emit OpenTelemetry-compatible signals and
   never hard-code a specific cloud backend.
5. **Minimal coupling** — Each integration is a thin adapter layer; business
   logic stays framework-free.

## How to Use

Pick the integration that matches your framework, then follow the README inside
that directory. All integrations assume you have already installed the
appropriate DT3 Commons SDK package (`dt3-commons` for Python,
`@dt3-commons/sdk` for Node, or the Java API contract).

## Contributing

When adding a new integration:

1. Create a subdirectory named `<language>-<framework>` (e.g. `go-gin`).
2. Add a `README.md` with setup instructions, code snippets, and a working
   minimal example.
3. Reference the DT3 Commons logging specification in `specs/logging.yaml`.
4. Include at least one test or validation snippet.
