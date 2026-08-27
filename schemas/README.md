# JSON Schema Definitions

This directory contains JSON Schema definitions used for runtime and build-time validation.

## Schemas

| Schema | Description |
|--------|-------------|
| `log-event.schema.json` | Canonical log event structure (schema 1.1.0) |
| `trace-context.schema.json` | Distributed trace context |
| `tenant-context.schema.json` | Multi-tenant context |
| `error.schema.json` | Structured error data |
| `validation-result.schema.json` | Validation outcome |
| `masking-rules.schema.json` | Masking configuration |
| `event-envelope.schema.json` | Async/messaging transport wrapper (not used by the log pipeline) |
| `sdk-config.schema.json` | SDK configuration |
| `api-event.schema.json` | API/HTTP attribute catalog |
| `database-event.schema.json` | Database attribute catalog |
| `messaging-event.schema.json` | Messaging attribute catalog |
| `ai-event.schema.json` | AI (`kavia.*`) attribute catalog |

## Usage

Schemas use **JSON Schema Draft 2020-12**.

SDKs validate log events against `log-event.schema.json` at runtime (when validation is enabled).

CI pipelines validate sample events using `tools/schema-validator/validate_schemas.py`.

Domain schemas document well-known attributes for typed builders; the wire format remains the canonical log event (`additionalProperties: true`).

`event-envelope.schema.json` is scoped to async messaging transports. Adapters may wrap a LogEvent as `payload`; the logger pipeline never validates against this schema.

## Rules

- All schemas must be valid JSON Schema Draft 2020-12
- Changes must maintain backward compatibility within a MAJOR version
- Schema version is tracked in `specs/versioning.yaml`
