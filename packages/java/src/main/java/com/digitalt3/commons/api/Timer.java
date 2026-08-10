package com.digitalt3.commons.api;

import java.util.Map;

/**
 * Timer for measuring operation duration.
 * <p>
 * Created via {@link Logger#startTimer(String, Map)}.
 * When {@link #end(boolean, Map)} is called, the timer records
 * duration.ms in the resulting log event.
 * </p>
 *
 * @since 0.1.0
 */
public interface Timer {

    /**
     * End the timer and emit a log event with duration.
     *
     * @param success Whether the timed operation succeeded
     * @param context Additional context to include
     * @return The emitted log event as a map
     */
    Map<String, Object> end(boolean success, Map<String, Object> context);
}
