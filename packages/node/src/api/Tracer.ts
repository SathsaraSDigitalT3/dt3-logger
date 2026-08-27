import { Attributes } from './types';

/**
 * Options supplied when starting a span.
 */
export interface SpanOptions {
  /** Optional attributes recorded on the span completion event. */
  attributes?: Attributes;
}

/**
 * Lightweight W3C-compatible span handle.
 *
 * Spans participate in logging via scoped `LogContext` (`trace.id`, `span.id`,
 * `parent.span.id`) rather than requiring the full OpenTelemetry SDK.
 */
export interface Span {
  /** Human-readable span name. */
  readonly name: string;

  /** W3C trace id (32 lowercase hex characters). */
  readonly traceId: string;

  /** W3C span id (16 lowercase hex characters). */
  readonly spanId: string;

  /** Parent span id when this span is nested. */
  readonly parentSpanId?: string;

  /**
   * End the span, restore the previous log context, and optionally emit a
   * span-completion LogEvent.
   */
  end(): void;

  /**
   * Emit a LogEvent under the active span context.
   *
   * @param name - UPPER_SNAKE_CASE event name, or a free-form label mapped to `SPAN_EVENT`.
   * @param attributes - Optional event fields merged into the emitted event.
   */
  addEvent(name: string, attributes?: Attributes): void;
}

/**
 * Creates and scopes W3C-compatible spans for structured logging.
 */
export interface Tracer {
  /**
   * Start a span and activate its trace context for the current async scope.
   *
   * @param name - Span name used in completion events.
   * @param options - Optional span attributes.
   * @returns An active span that must be ended by the caller.
   */
  startSpan(name: string, options?: SpanOptions): Span;

  /**
   * Run a callback with an active span scope and end the span on exit.
   *
   * @param name - Span name used in completion events.
   * @param callback - Work executed while the span context is active.
   * @param options - Optional span attributes.
   * @returns The callback result.
   */
  withSpan<T>(name: string, callback: (span: Span) => T, options?: SpanOptions): T;
}
