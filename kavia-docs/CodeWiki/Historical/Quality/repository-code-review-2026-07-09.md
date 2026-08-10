[CodeWiki](../index.md) / [Historical](../Historical/index.md) / [Quality](index.md)

# Repository Code Review (2026-07-09)

## Review basis
- Workspace files reviewed directly; git/KDiff unavailable in this environment (workspace not recognized as a git repository).
- Focused on core SDK behavior and contract/tooling enforcement.
- Key files reviewed:
  - `packages/node/src/sdk/impl/LoggerImpl.ts`
  - `packages/python/dt3_sdk/impl/logger_impl.py`
  - `packages/node/src/api/types.ts`
  - `schemas/log-event.schema.json`
  - `tools/schema-validator/validate_schemas.py` (mock)
  - `tools/compatibility-checker/check_contracts.py` (mock)

## Executive summary
The repository has a strong specifications-first structure (specs + JSON Schemas + language SDK packages). However, the current Node and Python SDK implementations and the schema/compatibility tooling are skeletal and do not enforce the published contracts (masking, validation modes, exporter abstraction, and contract verification). This creates high risk of spec drift and ineffective CI checks.

## Findings

### Must fix (correctness / contract enforcement)
1. **Schema validation tooling is a stub**
   - `tools/schema-validator/validate_schemas.py` prints a success message and exits 0.
   - Impact: schema regressions cannot be detected; CI target provides false confidence.
   - Recommendation:
     - Implement real JSON Schema Draft 2020-12 validation over `schemas/*.schema.json` and sample payloads.

2. **Compatibility checker tooling is a stub**
   - `tools/compatibility-checker/check_contracts.py` prints success and exits 0.
   - Impact: breaking contract changes go undetected.
   - Recommendation:
     - Implement compatibility checks (e.g., required fields removed/type changes) and/or validate SDK outputs against schema.

3. **Node Logger implementation diverges from repo specs**
   - No masking, validation, exporter abstraction beyond stdout, fail-open handling, or batching pipeline.
   - `sdk.name` hardcoded to `dt3-node` (inconsistent with package identity `@digitalt3/commons`).
   - Recommendation:
     - Implement a pipeline aligned with `specs/logging.yaml` and validate events against `schemas/log-event.schema.json`.

4. **Python Logger implementation diverges from repo specs**
   - No masking, validation, exporter abstraction beyond stdout, fail-open handling, or batching pipeline.
   - Only captures error type + message; no stack trace.
   - Contains unused imports (`logging`, `uuid`).
   - Recommendation:
     - Add traceback capture, remove unused imports, implement masking/validation/exporter pipeline.

### Should fix soon (quality / maintainability)
1. **Spec/schema constraints not enforced in implementations**
   - `event.name` pattern in schema requires UPPER_SNAKE_CASE, but implementations accept arbitrary context value.
   - Recommendation:
     - STRICT: raise/throw; LENIENT: attach `dt3.validation.errors`; OFF: skip validation.

2. **Config key conventions inconsistent**
   - TS types define namespaced keys (`validation.mode`, `masking.enabled`, etc.), but implementations currently use loosely typed config.
   - Recommendation:
     - Introduce config resolution and document supported config keys consistently across languages.

3. **Tests are minimal and do not validate behavior**
   - Current tests only verify object creation.
   - Recommendation:
     - Add tests verifying schema compliance, masking behavior, validation mode semantics, and exporter output.

4. **Java API contract/doc mismatch**
   - `Timer` Javadoc references `Logger#startTimer`, but Java `Logger` interface shown does not define it.
   - Recommendation:
     - Add `startTimer` or adjust documentation.

### Nice to have
- Improve typing (prefer `unknown` over `any` in TS; narrow via validation).
- Add golden test vectors for cross-language parity (same event input → expected canonical fields).

## Suggested test plan
- Schema compliance: validate emitted events against `schemas/log-event.schema.json`.
- Masking: assert sensitive fields are redacted and optionally tracked.
- Validation modes: STRICT vs LENIENT vs OFF behaviors.
- Exporters: stdout/file/http stubs with contract tests.

## Notes / limitations
This review is static and repository-wide; it does not reflect change history due to git/KDiff unavailability in the runtime.
