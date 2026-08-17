import { AsyncLocalStorage } from 'node:async_hooks';

import { LogContext } from '../api/types';

type CanonicalLogContext = Record<string, string>;

const activeLogContext = new AsyncLocalStorage<CanonicalLogContext>();

const canonicalFieldNames: Record<keyof LogContext, string> = {
  traceId: 'trace.id',
  spanId: 'span.id',
  parentSpanId: 'parent.span.id',
  correlationId: 'correlation.id',
};

const canonicalizeContext = (context: LogContext): CanonicalLogContext => {
  const normalized: CanonicalLogContext = {};

  for (const [key, canonicalKey] of Object.entries(canonicalFieldNames) as Array<
    [keyof LogContext, string]
  >) {
    const value = context[key];
    if (value !== undefined) {
      normalized[canonicalKey] = value;
    }
  }

  return normalized;
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
 * @param context - Trace and correlation identifiers for the new scope.
 * @param callback - Synchronous or asynchronous work to execute in the scope.
 * @returns The callback result.
 */
export function withLogContext<T>(context: LogContext, callback: () => T): T {
  const parentContext = activeLogContext.getStore() ?? {};
  const scopedContext = { ...parentContext, ...canonicalizeContext(context) };

  return activeLogContext.run(scopedContext, callback);
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
