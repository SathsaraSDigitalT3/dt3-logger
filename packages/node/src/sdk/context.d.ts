import { LogContext } from '../api/types';
type CanonicalLogContext = Record<string, string>;
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
export declare function withLogContext<T>(context: LogContext, callback: () => T): T;
/**
 * Return a copy of the active execution-scoped canonical log context.
 *
 * This internal pipeline helper never exposes the mutable storage value.
 *
 * @returns The active canonical context, or an empty object when no scope exists.
 */
export declare function getActiveLogContext(): CanonicalLogContext;
export {};
