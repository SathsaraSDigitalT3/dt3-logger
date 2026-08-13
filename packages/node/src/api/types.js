"use strict";
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
Object.defineProperty(exports, "__esModule", { value: true });
exports.ValidationMode = exports.Severity = void 0;
/**
 * Canonical log severity levels aligned with OpenTelemetry Logs.
 */
var Severity;
(function (Severity) {
    Severity["DEBUG"] = "DEBUG";
    Severity["INFO"] = "INFO";
    Severity["WARN"] = "WARN";
    Severity["ERROR"] = "ERROR";
    Severity["FATAL"] = "FATAL";
})(Severity || (exports.Severity = Severity = {}));
/**
 * Controls how schema validation failures are handled.
 *
 * - **STRICT** — Validation errors throw a `ValidationError`.
 * - **LENIENT** — Validation errors are attached to the event but do not throw.
 * - **OFF** — No validation is performed.
 */
var ValidationMode;
(function (ValidationMode) {
    ValidationMode["STRICT"] = "STRICT";
    ValidationMode["LENIENT"] = "LENIENT";
    ValidationMode["OFF"] = "OFF";
})(ValidationMode || (exports.ValidationMode = ValidationMode = {}));
