# Platform Specifications

This directory contains language-neutral platform contracts that serve as the **source of truth** for all SDK implementations.

## Files

| File | Description |
|------|-------------|
| `logging.yaml` | Structured logging standard |
| `tracing.yaml` | Distributed tracing standard |
| `tenancy.yaml` | Multi-tenancy context standard |
| `masking.yaml` | Sensitive data masking standard |
| `validation.yaml` | Schema validation standard |
| `errors.yaml` | Error handling standard |
| `events.yaml` | Event envelope standard |
| `config.yaml` | SDK configuration standard |
| `workers.yaml` | Worker/job processing standard |
| `versioning.yaml` | Versioning and compatibility standard |
| `api-events.yaml` | API/HTTP typed event contracts |
| `database-events.yaml` | Database typed event contracts |
| `messaging-events.yaml` | Messaging/worker typed event contracts |
| `ai-events.yaml` | AI observability event hierarchy |

## Rules

- Specifications are **language-neutral** — they define concepts, not implementations
- Every SDK must implement the contracts defined here
- Changes to specs require updating all affected SDKs
- Specs use YAML for readability and tooling compatibility
- Do NOT put language-specific code in this directory
