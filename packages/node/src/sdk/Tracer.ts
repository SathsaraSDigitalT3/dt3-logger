import { randomBytes } from 'crypto';

import { Logger } from '../api/Logger';
import { Attributes } from '../api/types';
import { Span, SpanOptions, Tracer } from '../api/Tracer';
import {
  activateLogContext,
  getActiveLogContext,
  withLogContext,
} from './context';

const EVENT_NAME_PATTERN = /^[A-Z][A-Z0-9_]*$/;

/**
 * Generate a W3C-compatible lowercase hex identifier.
 *
 * @param byteLength - Number of random bytes (16 for trace id, 8 for span id).
 * @returns Lowercase hex string.
 */
function generateHexId(byteLength: number): string {
  return randomBytes(byteLength).toString('hex');
}

/**
 * Concrete span handle that restores parent context on end.
 */
class SpanImpl implements Span {
  public readonly name: string;
  public readonly traceId: string;
  public readonly spanId: string;
  public readonly parentSpanId?: string;

  private readonly logger: Logger;
  private readonly restoreContext: () => void;
  private readonly spanEventsEnabled: boolean;
  private readonly attributes: Attributes;
  private readonly startedAt: number;
  private ended = false;

  constructor(
    logger: Logger,
    name: string,
    traceId: string,
    spanId: string,
    parentSpanId: string | undefined,
    restoreContext: () => void,
    spanEventsEnabled: boolean,
    attributes: Attributes,
  ) {
    this.logger = logger;
    this.name = name;
    this.traceId = traceId;
    this.spanId = spanId;
    this.parentSpanId = parentSpanId;
    this.restoreContext = restoreContext;
    this.spanEventsEnabled = spanEventsEnabled;
    this.attributes = attributes;
    this.startedAt = Date.now();
  }

  /**
   * End the span and optionally emit a completion event.
   */
  public end(): void {
    if (this.ended) {
      return;
    }

    this.ended = true;
    const durationMs = Math.max(0, Date.now() - this.startedAt);

    if (this.spanEventsEnabled) {
      this.logger.info(`Span completed: ${this.name}`, {
        'event.name': 'SPAN_COMPLETED',
        'duration.ms': durationMs,
        'trace.id': this.traceId,
        'span.id': this.spanId,
        ...(this.parentSpanId ? { 'parent.span.id': this.parentSpanId } : {}),
        ...this.attributes,
      });
    }

    this.restoreContext();
  }

  /**
   * Emit a LogEvent while this span's context remains active.
   *
   * @param name - Event name or free-form label.
   * @param attributes - Optional event fields.
   */
  public addEvent(name: string, attributes: Attributes = {}): void {
    if (this.ended) {
      return;
    }

    const { message, ...rest } = attributes;
    const eventName = EVENT_NAME_PATTERN.test(name) ? name : 'SPAN_EVENT';

    this.logger.info(typeof message === 'string' ? message : name, {
      ...rest,
      'event.name': eventName,
      ...(eventName === 'SPAN_EVENT' ? { 'span.event.name': name } : {}),
      'trace.id': this.traceId,
      'span.id': this.spanId,
      ...(this.parentSpanId ? { 'parent.span.id': this.parentSpanId } : {}),
    });
  }
}

/**
 * Lightweight tracer that scopes W3C ids into the logger context.
 */
export class TracerImpl implements Tracer {
  private readonly logger: Logger;
  private readonly spanEventsEnabled: boolean;

  /**
   * Create a tracer bound to a logger.
   *
   * @param logger - Logger used for span events and context-scoped logging.
   * @param spanEventsEnabled - Whether span completion events are emitted.
   */
  constructor(logger: Logger, spanEventsEnabled = true) {
    this.logger = logger;
    this.spanEventsEnabled = spanEventsEnabled;
  }

  /**
   * Start a span and activate its context for the current async resource.
   *
   * @param name - Span name.
   * @param options - Optional attributes for the completion event.
   * @returns An active span that the caller must end.
   */
  public startSpan(name: string, options: SpanOptions = {}): Span {
    const parent = getActiveLogContext();
    const parentSpanId = typeof parent['span.id'] === 'string' ? parent['span.id'] : undefined;
    const traceId =
      typeof parent['trace.id'] === 'string' ? parent['trace.id'] : generateHexId(16);
    const spanId = generateHexId(8);

    const restoreContext = activateLogContext({
      'trace.id': traceId,
      'span.id': spanId,
      ...(parentSpanId ? { 'parent.span.id': parentSpanId } : {}),
    });

    return new SpanImpl(
      this.logger,
      name,
      traceId,
      spanId,
      parentSpanId,
      restoreContext,
      this.spanEventsEnabled,
      options.attributes ?? {},
    );
  }

  /**
   * Run a callback inside an active span scope and end the span on exit.
   *
   * @param name - Span name.
   * @param callback - Work executed while the span is active.
   * @param options - Optional attributes for the completion event.
   * @returns The callback result.
   */
  public withSpan<T>(name: string, callback: (span: Span) => T, options: SpanOptions = {}): T {
    const parent = getActiveLogContext();
    const parentSpanId = typeof parent['span.id'] === 'string' ? parent['span.id'] : undefined;
    const traceId =
      typeof parent['trace.id'] === 'string' ? parent['trace.id'] : generateHexId(16);
    const spanId = generateHexId(8);

    return withLogContext(
      {
        'trace.id': traceId,
        'span.id': spanId,
        ...(parentSpanId ? { 'parent.span.id': parentSpanId } : {}),
      },
      () => {
        const span = new SpanImpl(
          this.logger,
          name,
          traceId,
          spanId,
          parentSpanId,
          () => undefined,
          this.spanEventsEnabled,
          options.attributes ?? {},
        );

        try {
          return callback(span);
        } finally {
          span.end();
        }
      },
    );
  }
}
