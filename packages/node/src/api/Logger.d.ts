import { Timer, TimerContext } from './Timer';
import { LogContext, LogEvent } from './types';
export interface Logger {
    debug(message: string, context?: Record<string, unknown>): void;
    info(message: string, context?: Record<string, unknown>): void;
    warn(message: string, context?: Record<string, unknown>): void;
    error(message: string, error?: Error, context?: Record<string, unknown>): void;
    fatal(message: string, context?: Record<string, unknown>): void;
    event(event: LogEvent): void;
    createTimer(name: string, context?: TimerContext): Timer;
    withContext<T>(context: LogContext, callback: () => T): T;
    flush(): Promise<void>;
    close(): void;
}
