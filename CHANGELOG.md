# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-07-03

### Added

- Initial repository structure and monorepo scaffold
- Language-neutral platform specifications (logging, tracing, tenancy, masking, validation, errors, events, config, workers, versioning)
- JSON Schema definitions for log events, trace context, tenant context, errors, validation results, masking rules, event envelopes, SDK config
- OpenTelemetry-aligned semantic conventions
- Protocol documentation (headers, context propagation, tenant propagation, log record format, event envelope format, OTLP compatibility)
- Python SDK (full implementation): logger, timer, masking, validation, processors, exporters, propagation
- Node.js/TypeScript SDK (full implementation): logger, timer, masking, validation, processors, exporters, propagation
- Java API contracts (interfaces only)
- Future implementation documentation for Go, C++, Ruby
- Integration stubs for FastAPI, Express, Spring, worker-queue, serverless
- Opinionated DT3 bundles (observability, tenant-aware logging, API service, worker service, Kavia-ready)
- OpenTelemetry Collector configurations
- Project templates and service blueprints
- CI templates for GitHub Actions
- OPA/Rego compliance policies
- Kavia AI skill integration
- Runnable examples
- Full documentation suite with 7 ADRs
- Development tooling (codegen, schema-validator, compatibility-checker, repo-scaffolder)
