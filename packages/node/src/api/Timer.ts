import { Context } from './types';

/**
 * Optional metadata attached to the timer completion event.
 */
export type TimerContext = Context;

/**
 * Measures one operation and emits its final duration through a logger.
 */
export interface Timer {
  /**
   * Start the timer and return it for fluent use.
   *
   * @returns This timer instance.
   * @throws Error when the timer has already started or its logger is closed.
   */
  start(): Timer;

  /**
   * Stop the timer, emit one completion event, and return elapsed milliseconds.
   *
   * @returns The non-negative elapsed duration in milliseconds.
   * @throws Error when the timer has not started, is already stopped, or its logger is closed.
   */
  stop(): number;

  /**
   * Alias for {@link stop}.
   *
   * @returns The non-negative elapsed duration in milliseconds.
   */
  finish(): number;
}
