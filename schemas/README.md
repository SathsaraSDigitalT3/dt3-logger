# JSON Schema Definitions

This directory contains JSON Schema definitions used for runtime and build-time validation.

## Schemas

| Schema | Description |
|--------|-------------|
| `log-event.schema.json` | Canonical log event structure |
| `trace-context.schema.json` | Distributed trace context |
| `tenant-context.schema.json` | Multi-tenant context |
| `error.schema.json` | Structured error data |
| `validation-result.schema.json` | Validation outcome |
| `masking-rules.schema.json` | Masking configuration |
| `event-envelope.schema.json` | Generic event wrapper |
| `sdk-config.schema.json` | SDK configuration |

## Usage

Schemas use **JSON Schema Draft 2020-12**.

SDKs validate log events against `log-event.schema.json` at runtime (when validation is enabled).

CI pipelines validate sample events using `tools/schema-validator/validate_schemas.py`.

## Rules

- All schemas must be valid JSON Schema Draft 2020-12
- Changes must maintain backward compatibility within a MAJOR version
- Schema version is tracked in `specs/versioning.yaml`
