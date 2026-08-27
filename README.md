# DT3 Commons Platform SDK

[![CI](https://github.com/digitalt3/digitalt3-commons/actions/workflows/ci.yml/badge.svg)](https://github.com/digitalt3/digitalt3-commons/actions/workflows/ci.yml)
[![Python](https://github.com/digitalt3/digitalt3-commons/actions/workflows/python.yml/badge.svg)](https://github.com/digitalt3/digitalt3-commons/actions/workflows/python.yml)
[![Node.js](https://github.com/digitalt3/digitalt3-commons/actions/workflows/node.yml/badge.svg)](https://github.com/digitalt3/digitalt3-commons/actions/workflows/node.yml)

> A cross-language, cloud-agnostic, OpenTelemetry-aligned platform SDK for building observable, secure, multi-tenant services.

## Overview

DT3 Commons is a production-grade platform SDK that provides reusable components for:

- **Unified Structured Logging** — JSON-structured, schema-validated log events
- **Typed Domain & AI Events** — API, database, messaging, and `kavia.*` AI builders
- **Event Emitter** — Typed emit helpers over the canonical `event()` pipeline
- **Distributed Tracing** — W3C Trace Context plus lightweight in-SDK span creation
- **Multi-Tenancy** — First-class tenant context propagation
- **Sensitive Data Masking** — Recursive field-level redaction (including AI prompts/responses)
- **Schema Validation** — Contract enforcement with STRICT/LENIENT/OFF modes
- **Pluggable Multi-Sink Export** — Fan-out to stdout, file, HTTP, OTLP, and custom sinks
- **Adoption Accelerator** — Project templates, CI templates, compliance checks

## Architecture

The SDK follows a **Core Specification + Language Adapters** model:

```
specs/          → Language-neutral contracts (source of truth)
schemas/        → JSON Schema definitions
packages/       → Language-specific SDK implementations
integrations/   → Framework adapters (FastAPI, Express, Spring)
bundles/        → Opinionated DT3 glue layers
```

Pipeline:

```
App → Logger / EventEmitter / Tracer
   → EventFactory (auto event.id)
   → Enrichment → Masking → Validation → optional Batching
   → MultiSinkFanout (per-sink failure isolation)
```

All SDKs implement the same cross-language contract:

| Component | Python | Node.js/TS | Java | Go | C++ | Ruby |
|-----------|--------|------------|------|----|-----|------|
| Logger    | ✅ Full | ✅ Full   | ✅ Full | 📝 Planned | 📝 Planned | 📝 Planned |
| Timer     | ✅ Full | ✅ Full   | ✅ Full | 📝 Planned | 📝 Planned | 📝 Planned |
| Masking   | ✅ Full | ✅ Full   | ✅ Full | 📝 Planned | 📝 Planned | 📝 Planned |
| Validation| ✅ Full | ✅ Full   | ✅ Full | 📝 Planned | 📝 Planned | 📝 Planned |
| Exporters / Sinks | ✅ Full | ✅ Full | ✅ Full | 📝 Planned | 📝 Planned | 📝 Planned |
| Domain / AI events | ✅ Full | ✅ Full | ✅ Full | 📝 Planned | 📝 Planned | 📝 Planned |
| Tracer / Spans | ✅ Full | ✅ Full | ✅ Full | 📝 Planned | 📝 Planned | 📝 Planned |

## Quick Start

### Python

```bash
pip install dt3-commons
```

```python
from dt3_sdk import create_logger

logger = create_logger({
    "service.name": "my-service",
    "service.version": "1.0.0",
    "deployment.environment": "dev",
    "exporter": "stdout"
})

logger.info("User login completed", {
    "event.name": "USER_LOGIN",
    "tenant.id": "tenant-123",
    "attributes": {"login.method": "oauth"}
})
```

### Node.js / TypeScript

```bash
npm install @digitalt3/commons
```

```typescript
import { createLogger } from '@digitalt3/commons';

const logger = createLogger({
  'service.name': 'my-service',
  'service.version': '1.0.0',
  'deployment.environment': 'dev',
  exporter: 'stdout'
});

logger.info('User login completed', {
  'event.name': 'USER_LOGIN',
  'tenant.id': 'tenant-123',
  attributes: { 'login.method': 'oauth' }
});
```

## Repository Structure

```
digitalt3-commons/
├── specs/                  # Language-neutral platform contracts
├── schemas/                # JSON Schema definitions
├── semantic-conventions/   # OpenTelemetry-aligned attribute naming
├── protocol/               # Wire format and propagation standards
├── packages/               # Language-specific SDK packages
│   ├── python/             # Python 3.10+ (full implementation)
│   ├── node/               # Node.js/TypeScript (full implementation)
│   ├── java/               # Java 17+ (full implementation)
│   ├── go/                 # Go (planned)
│   ├── cpp/                # C++ (planned)
│   └── ruby/               # Ruby (planned)
├── integrations/           # Framework-specific integrations
├── bundles/                # Opinionated DT3 glue-layer bundles
├── collector/              # OpenTelemetry Collector configs
├── scaffolding/            # Starter templates and CI templates
├── compliance/             # OPA/Rego compliance policies
├── kavia-skill/            # Kavia AI skill integration
├── examples/               # Runnable end-to-end examples
├── docs/                   # Architecture, standards, ADRs
└── tools/                  # Code generation and validation
```

See [docs/folder-structure.md](docs/folder-structure.md) for detailed ownership rules.

## Design Principles

1. **OpenTelemetry Alignment** — Compatible concepts, not a competing framework
2. **API/SDK Separation** — Contracts separate from implementations
3. **Fail-Open by Default** — Logging failures never break applications
4. **Multi-Tenancy First-Class** — Tenant context flows through everything
5. **Cloud Agnostic** — No vendor-specific dependencies
6. **Cross-Language Contracts** — Same concepts, idiomatic implementations

## Documentation

- [Architecture](docs/architecture.md)
- [Getting Started](docs/getting-started.md)
- [Logging Standard](docs/logging-standard.md)
- [Tracing Standard](docs/tracing-standard.md)
- [Multi-Tenancy Standard](docs/tenancy-standard.md)
- [Masking Standard](docs/masking-standard.md)
- [Validation Standard](docs/validation-standard.md)
- [Cross-Language Contract](docs/cross-language-contract.md)
- [Adoption Accelerator](docs/adoption-accelerator.md)
- [Kavia AI Integration](docs/kavia-skill-integration.md)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

MIT — see [LICENSE](LICENSE).

## Security

See [SECURITY.md](SECURITY.md) for vulnerability reporting.
