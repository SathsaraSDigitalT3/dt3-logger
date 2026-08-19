package com.digitalt3.commons.api;

/**
 * Single-use monotonic timer associated with a {@link Logger}.
 *
 * <p>Start a timer with {@link #start()}, then complete it once using
 * {@link #stop()} or {@link #finish()}. Completion emits an INFO canonical
 * event with the configured event name and a non-negative {@code duration.ms}
 * through the owning logger's normal event pipeline.</p>
 *
 * @since 0.1.0
 */
public interface Timer {

    // PUBLIC_INTERFACE
    /**
     * Start this timer.
     *
     * @return this timer
     * @throws IllegalStateException if the timer has already started or the logger is closed
     */
    Timer start();

    // PUBLIC_INTERFACE
    /**
     * Stop this timer, record its duration, and emit its completion event.
     *
     * @return the measured non-negative duration in milliseconds
     * @throws IllegalStateException if the timer has not started, has already completed,
     *     or the logger is closed
     */
    long stop();

    // PUBLIC_INTERFACE
    /**
     * Complete this timer as an alias for {@link #stop()}.
     *
     * @return the measured non-negative duration in milliseconds
     * @throws IllegalStateException if the timer has not started, has already completed,
     *     or the logger is closed
     */
    long finish();

    // PUBLIC_INTERFACE
    /**
     * Return the measured duration after this timer has completed.
     *
     * @return the measured non-negative duration in milliseconds
     * @throws IllegalStateException if this timer has not completed
     */
    long durationMs();
}
