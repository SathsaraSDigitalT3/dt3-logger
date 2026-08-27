# Semantic Conventions

OpenTelemetry-aligned and DT3-specific attribute naming rules.

These conventions define the standard attribute names used across all DT3 Commons SDK implementations.

## Namespaces

| Namespace | Source | Description |
|-----------|--------|-------------|
| `service.*` | OpenTelemetry | Service identification |
| `deployment.*` | OpenTelemetry | Deployment info |
| `trace.*` | W3C/OTel | Distributed trace context |
| `error.*` | OpenTelemetry | Error information |
| `http.*` | OpenTelemetry | HTTP semantics |
| `db.*` | OpenTelemetry | Database semantics |
| `messaging.*` | OpenTelemetry | Messaging semantics |
| `tenant.*` | DT3 | Multi-tenancy context |
| `dt3.*` | DT3 | DT3-specific extensions |
| `kavia.*` | DT3 | Kavia AI specific |
| `event.*` / `operation.*` / `component.*` | DT3 | Event identity fields |

## Attribute files

| File | Description |
|------|-------------|
| `common-attributes.yaml` | Shared attributes |
| `service-attributes.yaml` | Service identification |
| `log-attributes.yaml` | Log event identity and timing |
| `trace-attributes.yaml` | Trace context |
| `tenant-attributes.yaml` | Tenancy |
| `error-attributes.yaml` | Errors |
| `validation-attributes.yaml` | Validation diagnostics |
| `http-attributes.yaml` | HTTP/API |
| `db-attributes.yaml` | Database |
| `messaging-attributes.yaml` | Messaging/worker |
| `kavia-ai-attributes.yaml` | AI observability |

## Rules

- Prefer OpenTelemetry semantic conventions where they exist
- Use `dt3.*` namespace only for DT3-specific concepts
- Use `kavia.*` for AI attributes (token counts must not use the literal field name `token`)
- Use dot-separated lowercase names
- Do NOT invent custom names for concepts that have OTel equivalents
