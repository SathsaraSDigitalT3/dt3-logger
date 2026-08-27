import { randomUUID } from 'node:crypto';
import { AsyncLocalStorage } from 'node:async_hooks';

import { Headers, LogContext } from '../api/types';

export type PropagationContext = Record<string, unknown>;

type CanonicalLogContext = PropagationContext;

const activeLogContext = new AsyncLocalStorage<CanonicalLogContext>();

const canonicalFieldNames: Record<string, string> = {
  traceId: 'trace.id',
  spanId: 'span.id',
  parentSpanId: 'parent.span.id',
  correlationId: 'correlation.id',
  tenantId: 'tenant.id',
  tenantRegion: 'tenant.region',
  tenantEnvironment: 'tenant.environment',
};

const propagationHeaders: Record<string, string> = {
  'x-correlation-id': 'correlation.id',
  'x-tenant-id': 'tenant.id',
  'x-tenant-region': 'tenant.region',
  'x-tenant-environment': 'tenant.environment',
};

const canonicalizeContext = (context: LogContext | PropagationContext): CanonicalLogContext => {
  const normalized: CanonicalLogContext = {};

  for (const [key, value] of Object.entries(context)) {
    if (value !== undefined) {
      normalized[canonicalFieldNames[key] ?? key] = value;
    }
  }

  return normalized;
};

const getHeaderValue = (carrier: Headers, name: string): string | undefined => {
  for (const [key, value] of Object.entries(carrier)) {
    if (key.toLowerCase() === name && typeof value === 'string') {
      const normalizedValue = value.trim();
      return normalizedValue || undefined;
    }
  }

  return undefined;
};

const parseTraceparent = (value: string | undefined): CanonicalLogContext => {
  if (!value) {
    return {};
  }

  const parts = value.split('-');
  if (parts.length !== 4) {
    return {};
  }

  const [version, traceId, spanId, traceFlags] = parts;
  const hex = /^[0-9a-f]{2}$/i;
  if (
    !hex.test(version) ||
    !/^[0-9a-f]{32}$/i.test(traceId) ||
    !/^[0-9a-f]{16}$/i.test(spanId) ||
    !hex.test(traceFlags) ||
    /^0{32}$/i.test(traceId) ||
    /^0{16}$/i.test(spanId)
  ) {
    return {};
  }

  return {
    'trace.id': traceId.toLowerCase(),
    'span.id': spanId.toLowerCase(),
    'trace.flags': traceFlags.toLowerCase(),
  };
};

// PUBLIC_INTERFACE
/**
 * Run a callback with an execution-scoped logging context.
 *
 * Context is stored in Node.js `AsyncLocalStorage`, so it propagates through
 * promises and async/await without shared mutable request state. Nested scopes
 * inherit values that they do not replace and automatically restore the parent
 * scope after the callback returns or its promise settles.
 *
 * @param context - Trace, correlation, tenant, and schema-compatible context values.
 * @param callback - Synchronous or asynchronous work to execute in the scope.
 * @returns The callback result.
 */
export function withLogContext<T>(context: LogContext | PropagationContext, callback: () => T): T {
  const parentContext = activeLogContext.getStore() ?? {};
  const scopedContext = { ...parentContext, ...canonicalizeContext(context) };

  return activeLogContext.run(scopedContext, callback);
}

// PUBLIC_INTERFACE
/**
 * Inject propagation context into a mutable HTTP-header carrier.
 *
 * @param context - Canonical or convenience-form context to serialize.
 * @param carrier - Header mapping updated in place.
 */
export function inject(context: LogContext | PropagationContext, carrier: Headers): void {
  const normalizedContext = canonicalizeContext(context);
  const traceId = normalizedContext['trace.id'];
  const spanId = normalizedContext['span.id'];
  const traceFlags = normalizedContext['trace.flags'] ?? '01';

  if (typeof traceId === 'string' && typeof spanId === 'string' && typeof traceFlags === 'string') {
    carrier.traceparent = `00-${traceId}-${spanId}-${traceFlags}`;
  }

  if (typeof normalizedContext.tracestate === 'string' && normalizedContext.tracestate) {
    carrier.tracestate = normalizedContext.tracestate;
  }

  for (const [headerName, contextField] of Object.entries(propagationHeaders)) {
    const value = normalizedContext[contextField];
    if (typeof value === 'string' && value) {
      carrier[headerName] = value;
    }
  }
}

// PUBLIC_INTERFACE
/**
 * Extract W3C trace, correlation, and tenant metadata from an HTTP-header carrier.
 *
 * Missing or malformed trace headers are ignored while independently valid
 * correlation and tenant headers remain available.
 *
 * @param carrier - HTTP headers, matched case-insensitively.
 * @param autoGenerateCorrelationId - Generate a UUID only when no correlation header exists.
 * @returns Canonical context suitable for `withLogContext`.
 */
export function extract(
  carrier: Headers,
  autoGenerateCorrelationId = false,
): PropagationContext {
  const context: PropagationContext = parseTraceparent(getHeaderValue(carrier, 'traceparent'));
  const tracestate = getHeaderValue(carrier, 'tracestate');

  if (tracestate) {
    context.tracestate = tracestate;
  }

  for (const [headerName, contextField] of Object.entries(propagationHeaders)) {
    const value = getHeaderValue(carrier, headerName);
    if (value) {
      context[contextField] = value;
    }
  }

  if (autoGenerateCorrelationId && typeof context['correlation.id'] !== 'string') {
    context['correlation.id'] = randomUUID();
  }

  return context;
}

/**
 * Return context with a correlation ID generated exactly once per active scope.
 *
 * @param context - Current scoped context.
 * @param autoGenerate - Whether generation is enabled by SDK configuration.
 * @returns A copied canonical context with any generated correlation ID.
 */
export function ensureCorrelationId(
  context: CanonicalLogContext,
  autoGenerate: boolean,
): CanonicalLogContext {
  const resolvedContext = { ...context };

  if (autoGenerate && !resolvedContext['correlation.id']) {
    resolvedContext['correlation.id'] = randomUUID();
    activeLogContext.enterWith(resolvedContext);
  }

  return resolvedContext;
}

/**
 * Return a copy of the active execution-scoped canonical log context.
 *
 * This internal pipeline helper never exposes the mutable storage value.
 *
 * @returns The active canonical context, or an empty object when no scope exists.
 */
export function getActiveLogContext(): CanonicalLogContext {
  return { ...(activeLogContext.getStore() ?? {}) };
}

/**
 * Activate a log context for the current async resource and return a restore function.
 *
 * Used by span start/end to enter and leave scoped trace identifiers without a
 * callback wrapper. Prefer {@link withLogContext} when a callback boundary exists.
 *
 * @param context - Trace, correlation, tenant, and schema-compatible context values.
 * @returns A function that restores the previous context snapshot.
 */
export function activateLogContext(context: LogContext | PropagationContext): () => void {
  const parentContext = { ...(activeLogContext.getStore() ?? {}) };
  const scopedContext = { ...parentContext, ...canonicalizeContext(context) };
  activeLogContext.enterWith(scopedContext);

  return () => {
    activeLogContext.enterWith(parentContext);
  };
}
