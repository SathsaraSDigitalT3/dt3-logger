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
export class TimerImpl implements Timer {
  private startedAt?: bigint;
  private stopped = false;

  /**
   * Create an unstarted timer.
   *
   * @param logger - The logger that emits the completion event.
   * @param name - Canonical completion event name.
   * @param context - Optional completion-event metadata.
   */
  constructor(
    private readonly logger: TimerLogger,
    private readonly name: string,
    private readonly context: TimerContext = {},
  ) {
    if (typeof name !== 'string') {
      throw new TypeError('name must be a string');
    }
    if (name.trim().length === 0) {
      throw new Error('name must not be blank');
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Start this timer.
   *
   * @returns This timer for fluent usage.
   * @throws Error if already started or the associated logger is closed.
   */
  public start(): TimerImpl {
    if (this.startedAt !== undefined) {
      throw new Error('Timer has already been started');
    }

    this.logger.ensureTimerLoggerOpen();
    this.startedAt = process.hrtime.bigint();
    return this;
  }

  // PUBLIC_INTERFACE
  /**
   * Stop this timer, emit its completion event, and return elapsed milliseconds.
   *
   * @returns The non-negative elapsed duration in milliseconds.
   * @throws Error if not started, already stopped, or the logger is closed.
   */
  public stop(): number {
    if (this.startedAt === undefined) {
      throw new Error('Timer has not been started');
    }
    if (this.stopped) {
      throw new Error('Timer has already been stopped');
    }

    this.logger.ensureTimerLoggerOpen();
    const elapsedMs = Number(process.hrtime.bigint() - this.startedAt) / 1_000_000;
    const completionContext: Record<string, unknown> = {
      ...this.context,
      'event.name': this.name,
      'duration.ms': elapsedMs,
    };

    this.logger.info(`${this.name} completed`, completionContext);
    this.stopped = true;
    return elapsedMs;
  }

  // PUBLIC_INTERFACE
  /**
   * Finish this timer.
   *
   * @returns The non-negative elapsed duration in milliseconds.
   */
  public finish(): number {
    return this.stop();
  }
}
