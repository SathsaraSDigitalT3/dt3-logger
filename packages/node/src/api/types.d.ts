/**
 * @module api/types
 * @description Core type definitions for DT3 Commons Platform SDK.
 *
 * This module defines all foundational types, enums, and interfaces used
 * throughout the SDK. It is the single source of truth for the event schema,
 * configuration shape, and validation contracts.
 *
 * @packageDocumentation
 */
/** Generic key-value context bag attached to log events. */
export type Context = Record<string, unknown>;
/**
 * Execution-scoped tracing and correlation metadata.
 *
 * These convenience property names are mapped to the canonical event fields
 * `trace.id`, `span.id`, `parent.span.id`, and `correlation.id`.
 */
export interface LogContext {
    traceId?: string;
    spanId?: string;
    parentSpanId?: string;
    correlationId?: string;
}
/** Structured attributes attached to a log event. */
export type Attributes = Record<string, unknown>;
/** HTTP-style string-to-string header map. */
export type Headers = Record<string, string>;
/**
 * Canonical log severity levels aligned with OpenTelemetry Logs.
 */
export declare enum Severity {
    DEBUG = "DEBUG",
    INFO = "INFO",
    WARN = "WARN",
    ERROR = "ERROR",
    FATAL = "FATAL"
}
/**
 * Controls how schema validation failures are handled.
 *
 * - **STRICT** — Validation errors throw a `ValidationError`.
 * - **LENIENT** — Validation errors are attached to the event but do not throw.
 * - **OFF** — No validation is performed.
 */
export declare enum ValidationMode {
    STRICT = "STRICT",
    LENIENT = "LENIENT",
    OFF = "OFF"
}
/**
 * Canonical structured log event emitted by the SDK.
 *
 * Required fields are enforced by `SchemaValidator` at runtime, not just
 * by the TypeScript compiler, so that dynamically-constructed events are
 * also covered.
 */
export interface LogEvent {
    /** ISO-8601 timestamp of the event. */
    timestamp: string;
    /** Severity level. Must be a `Severity` enum value or custom string. */
    severity: Severity | string;
    /** Human-readable log message. */
    message: string;
    /** Dot-separated event name, e.g. `http.request.completed`. */
    'event.name': string;
    /** Schema version used by this event. */
    'schema.version': string;
    /** Name of the emitting SDK. */
    'sdk.name': string;
    /** Version of the emitting SDK. */
    'sdk.version': string;
    /** Logical service name. */
    'service.name': string;
    /** Semantic version of the service. */
    'service.version': string;
    /** Deployment environment (e.g. production, staging). */
    'deployment.environment': string;
    'trace.id'?: string;
    'span.id'?: string;
    'parent.span.id'?: string;
    'correlation.id'?: string;
    'tenant.id'?: string;
    'tenant.region'?: string;
    'tenant.environment'?: string;
    'user.id'?: string;
    'session.id'?: string;
    'duration.ms'?: number;
    'error.type'?: string;
    'error.message'?: string;
    'error.stack'?: string;
    'error.code'?: string;
    'error.retryable'?: boolean;
    /** Custom attributes bag. */
    attributes?: Attributes;
    /** Validation errors discovered during processing (populated in LENIENT mode). */
    'dt3.validation.errors'?: ValidationErrorDetail[];
    /** Field paths that were masked by the MaskingEngine. */
    'dt3.security.masked_fields'?: string[];
    /** Catch-all for forward-compatible extension fields. */
    [key: string]: unknown;
}
/**
 * A sanitized diagnostic for one JSON Schema validation failure.
 *
 * All members correspond to the canonical validation-result schema contract.
 */
export interface ValidationErrorDetail {
    /** Dot-separated field path, or `$` when the failure applies to the root object. */
    field: string;
    /** Sanitized description of the failed validation rule. */
    message: string;
    /** JSON Schema keyword that was violated. */
    rule: string;
}
/**
 * Result of validating a `LogEvent` against the schema.
 */
export interface ValidationResult {
    /** Whether the event passed all validation rules. */
    valid: boolean;
    /** Structured schema-rule diagnostics, empty when `valid` is true. */
    errors: ValidationErrorDetail[];
    /** The validation mode that was in effect. */
    mode: ValidationMode;
}
/**
 * SDK configuration object.
 *
 * Only `service.name`, `service.version`, and `deployment.environment`
 * are required.  All other fields have sensible defaults applied by
 * `resolveConfig()`.
 */
export interface SdkConfig {
    /** Logical service name (required). */
    'service.name': string;
    /** Semantic version of the service (required). */
    'service.version': string;
    /** Deployment environment, e.g. production / staging / development (required). */
    'deployment.environment': string;
    /** Schema version string.  Defaults to `'1.0.0'`. */
    'schema.version'?: string;
    /** SDK identifier.  Defaults to `'@digitalt3/commons'`. */
    'sdk.name'?: string;
    /** SDK version.  Defaults to `'0.1.0'`. */
    'sdk.version'?: string;
    /** Validation mode.  Defaults to `ValidationMode.LENIENT`. */
    'validation.mode'?: ValidationMode | string;
    /** When true, exporter/transport failures are swallowed.  Defaults to `true`. */
    fail_open?: boolean;
    /** Exporter backend: `'stdout'`, `'file'`, `'http'`, `'otlp'`.  Defaults to `'stdout'`. */
    exporter?: string;
    /** File path when exporter is `'file'`. */
    'exporter.file.path'?: string;
    /** HTTP endpoint when exporter is `'http'` or `'otlp'`. */
    'exporter.http.endpoint'?: string;
    /** Canonical HTTP request timeout in milliseconds. Defaults to `5000`. */
    'exporter.http.timeout'?: number;
    /** @deprecated Use `exporter.http.timeout`; the canonical key takes precedence. */
    'exporter.http.timeout_ms'?: number;
    /** Optional HTTP request headers for the HTTP exporter. */
    'exporter.http.headers'?: Headers;
    /** OTLP/HTTP Logs endpoint when exporter is `'otlp'`. */
    'otlp.endpoint'?: string;
    /** OTLP/HTTP request timeout in milliseconds. Defaults to `10000`. */
    'otlp.timeout'?: number;
    /** Optional request headers for the OTLP/HTTP exporter. */
    'otlp.headers'?: Headers;
    /** Whether field masking is enabled.  Defaults to `true`. */
    'masking.enabled'?: boolean;
    /** Additional field names to mask (merged with defaults). */
    'masking.fields'?: string[];
    /** Whether event batching is enabled.  Defaults to `false`. */
    'batching.enabled'?: boolean;
    /** Maximum batch size before an automatic flush.  Defaults to `100`. */
    'batching.max_size'?: number;
    /** Batch flush interval in milliseconds.  Defaults to `5000`. */
    'batching.flush_interval_ms'?: number;
}
