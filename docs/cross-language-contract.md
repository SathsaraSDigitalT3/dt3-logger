# Cross-Language SDK Parity Contract

## Purpose and authority

This document records the executable cross-language parity contract established for the Python, Node.js, and Java SDKs before implementation begins. The attached approved implementation instructions govern scope and execution order. The repository schemas in `schemas/`, together with the language-neutral specifications in `specs/`, define the canonical behavior that SDK implementation and shared fixtures must verify.

This release is limited to parity for currently supported structured event creation, masking, validation, file export, generic HTTP export, OTLP/HTTP export, configuration, failure policy, and lifecycle behavior. It does not add fatal logging, a direct event API, timers, batching, trace-context APIs, new transports, or unrelated refactoring.

## Canonical schema ownership

`schemas/log-event.schema.json` is the sole canonical structured-log-event schema. Python loads that repository file at runtime, and Java packages it as a Maven resource. Node must stop hand-maintaining a schema mirror and instead distribute a build-derived artifact from this canonical source.

The canonical event schema and `schemas/validation-result.schema.json` must agree on validation diagnostics. `dt3.validation.errors` is an array of sanitized diagnostic objects. Each diagnostic object requires the string properties `field`, `message`, and `rule`, and it must not contain additional properties. Diagnostic messages must not expose rejected caller values, event message text, transport response bodies, or secrets.

The current event schema incorrectly declares diagnostic items as strings while the validation-result schema and all three SDK implementations use objects. Under `specs/versioning.yaml`, changing this declared item type is an incompatible contract change. Before release, maintainers must coordinate a major schema-contract migration, including the schema identifier, emitted event `schema.version`, SDK versions, and release guidance.

## Event ownership and processing pipeline

Each logger creates one flat dot-keyed event. The event is processed in this order: event creation, recursive masking, validation, and exporter delivery. Masking must not mutate caller context. When tracking is enabled and fields were masked, `dt3.security.masked_fields` contains the masked paths.

The logger method owns `severity`. The supported methods map as follows: `debug` maps to `DEBUG`, `info` maps to `INFO`, `warn` maps to `WARN`, and `error` maps to `ERROR`. Caller context may add non-reserved fields but must not override the method-selected severity. The explicit error argument to `error` owns `error.type`, `error.message`, and `error.stack`.

The logger also owns `timestamp`, `message`, `event.name`, `schema.version`, `sdk.name`, `sdk.version`, `service.name`, `service.version`, and `deployment.environment`. `event.name` defaults to `GENERIC_EVENT` only when caller context does not provide a string value. Required metadata must remain absent when not configured so validation can report the real configuration problem rather than accept synthetic placeholders.

## Validation behavior

`STRICT` validates the masked event and prevents delivery when invalid. It raises the language's dedicated or documented validation exception and is never converted into a successful logging operation by `fail_open`.

`LENIENT` validates the masked event, retains all masked non-sensitive values, continues delivery, and attaches `dt3.validation.errors` when validation fails. Java must not redact non-sensitive invalid values merely because they fail a type rule; redaction is the responsibility of masking.

`OFF` skips validation and attaches no validation diagnostics. Validation diagnostics must be deterministic and sanitized in every language.

## Configuration and timeout policy

The canonical configuration namespace is the dot-key contract in `schemas/sdk-config.schema.json`. The canonical exporter keys are `exporter.file.path`, `exporter.http.endpoint`, `exporter.http.timeout`, `exporter.http.headers`, `otlp.endpoint`, `otlp.timeout`, `otlp.headers`, and `fail_open`.

All public timeout values are milliseconds. Python converts milliseconds to the seconds required by its HTTP client only inside the transport boundary. Node consumes milliseconds directly. Java converts milliseconds through `Duration.ofMillis` or an equivalent millisecond-aware API.

Existing language-specific keys may remain as compatibility aliases only. When a canonical key and a legacy alias are both supplied, the canonical key wins. When only a legacy alias is supplied, it may be accepted during the compatibility period, but the SDK documentation and tests must identify its deprecation and deterministic behavior.

## Transport failure and header policy

All generic HTTP and OTLP/HTTP exporters send UTF-8 JSON with `Content-Type: application/json`. Every HTTP status from 200 through 299 is successful. A non-2xx response, timeout, connection failure, serialization failure, write failure, request initialization failure, or closed-transport failure is a delivery failure.

`fail_open` applies only to delivery and lifecycle failures after event construction, masking, and validation. With `fail_open=true`, delivery failures are swallowed without recursive logging or payload disclosure. With `fail_open=false`, the normalized transport failure is observable to the caller. Invalid transport configuration must fail synchronously regardless of `fail_open`.

Generic HTTP and OTLP headers must use nonblank string names and string values. Header names and values must reject carriage-return and line-feed characters. Configured headers cannot override the required JSON content type.

## Lifecycle policy

`flush` settles delivery work that started before the flush boundary. Synchronous transports confirm that they are usable and have no buffered work. Node asynchronous transports must track in-flight delivery and make settlement observable through `flush`.

`close` or `shutdown` is idempotent, releases transport resources when they exist, and establishes a terminal state. Export and flush attempts after terminal closure must fail with a documented closed-transport error. Language-specific lifecycle names may be retained only when aliases or documentation establish equivalent observable behavior.

## OTLP mapping

A final DT3 event maps to one OTLP Logs JSON record at `resourceLogs[0].scopeLogs[0].logRecords[0]`. `timeUnixNano`, `severityText`, `severityNumber`, and `body.stringValue` derive from the final timestamp, method-controlled severity, and message. Severity numbers are `DEBUG` 5, `INFO` 9, `WARN` 13, `ERROR` 17, and `FATAL` 21.

Resource attributes include `service.name`, `service.version`, `deployment.environment`, `tenant.id`, and `tenant.name` when those values are present. The scope name is `dt3.logger`, with `dt3.sdk.name` and `dt3.sdk.version` as scope attributes. Remaining non-reserved DT3 fields become log-record attributes.

## Implementation and verification boundary

STEP-02, STEP-03, and STEP-04 apply this contract to Python, Node.js, and Java without introducing excluded features. Every parity change requires tests. STEP-05 adds shared fixtures that compare normalized behavior across all three languages, including event creation, severity ownership, masking, validation modes and diagnostics, HTTP outcomes, failure policy, OTLP payloads and headers, resource attributes, SDK scope attributes, and tenant attributes.

No acceptance criterion is complete solely from this contract record. The contract establishes the required target; language implementation tests, shared fixtures, package/schema verification, and final documentation verification provide the execution evidence.
