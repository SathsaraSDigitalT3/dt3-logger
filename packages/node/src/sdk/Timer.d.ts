import { Timer, TimerContext } from '../api/Timer';
import { Logger } from '../api/Logger';

/**
 * Internal logger capability used to preserve normal pipeline and lifecycle behavior.
 */
interface TimerLogger extends Logger {
    info(message: string, context?: Record<string, unknown>): void;
    ensureTimerLoggerOpen(): void;
}

/**
 * Measure elapsed monotonic time and emit one canonical completion event.
 */
export declare class TimerImpl implements Timer {
    private readonly logger;
    private readonly name;
    private readonly context;
    private startedAt?;
    private stopped;
    /**
     * Create an unstarted timer.
     *
     * @param logger - The logger that emits the completion event.
     * @param name - Canonical completion event name.
     * @param context - Optional completion-event metadata.
     */
    constructor(logger: TimerLogger, name: string, context?: TimerContext);
    /**
     * Start this timer.
     *
     * @returns This timer for fluent usage.
     * @throws Error if already started or the associated logger is closed.
     */
    start(): TimerImpl;
    /**
     * Stop this timer, emit its completion event, and return elapsed milliseconds.
     *
     * @returns The non-negative elapsed duration in milliseconds.
     * @throws Error if not started, already stopped, or the logger is closed.
     */
    stop(): number;
    /**
     * Finish this timer.
     *
     * @returns The non-negative elapsed duration in milliseconds.
     */
    finish(): number;
}
export {};
