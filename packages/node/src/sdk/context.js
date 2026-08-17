"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.withLogContext = withLogContext;
exports.getActiveLogContext = getActiveLogContext;
const node_async_hooks_1 = require("node:async_hooks");
const activeLogContext = new node_async_hooks_1.AsyncLocalStorage();
const canonicalFieldNames = {
    traceId: 'trace.id',
    spanId: 'span.id',
    parentSpanId: 'parent.span.id',
    correlationId: 'correlation.id',
};
const canonicalizeContext = (context) => {
    const normalized = {};
    for (const [key, canonicalKey] of Object.entries(canonicalFieldNames)) {
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
function withLogContext(context, callback) {
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
function getActiveLogContext() {
    return { ...(activeLogContext.getStore() ?? {}) };
}
