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
| `tenant.*` | DT3 | Multi-tenancy context |
| `dt3.*` | DT3 | DT3-specific extensions |
| `kavia.*` | DT3 | Kavia AI specific |

## Rules

- Prefer OpenTelemetry semantic conventions where they exist
- Use `dt3.*` namespace only for DT3-specific concepts
- Use dot-separated lowercase names
- Do NOT invent custom names for concepts that have OTel equivalents
