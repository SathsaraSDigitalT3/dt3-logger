import { Headers, LogContext } from '../api/types';
export type PropagationContext = Record<string, unknown>;
type CanonicalLogContext = PropagationContext;
export declare function withLogContext<T>(context: LogContext | PropagationContext, callback: () => T): T;
export declare function inject(context: LogContext | PropagationContext, carrier: Headers): void;
export declare function extract(carrier: Headers, autoGenerateCorrelationId?: boolean): PropagationContext;
export declare function ensureCorrelationId(context: CanonicalLogContext, autoGenerate: boolean): CanonicalLogContext;
export declare function getActiveLogContext(): CanonicalLogContext;
export {};
