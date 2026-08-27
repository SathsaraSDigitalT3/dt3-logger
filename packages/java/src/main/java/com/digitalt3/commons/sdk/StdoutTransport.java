package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.LogTransport;

import java.util.Objects;

/**
 * Synchronously writes final DT3 structured events as JSON lines to {@link System#out}.
 *
 * @since 0.1.0
 */
public final class StdoutTransport implements LogTransport {

    // PUBLIC_INTERFACE
    /**
     * Serialize and print one final canonical structured event.
     *
     * @param logEvent already-masked and validation-processed event
     */
    @Override
    public synchronized void write(LogEvent logEvent) {
        Objects.requireNonNull(logEvent, "logEvent must not be null");
        System.out.println(StdoutLogger.toJson(logEvent.toMap()));
    }

    /**
     * Print a serialized final event produced by the logger pipeline.
     *
     * @param serializedEvent canonical JSON event without a trailing line separator
     */
    synchronized void writeJson(String serializedEvent) {
        Objects.requireNonNull(serializedEvent, "serializedEvent must not be null");
        System.out.println(serializedEvent);
    }

    // PUBLIC_INTERFACE
    /**
     * Flush standard output.
     */
    @Override
    public void flush() {
        System.out.flush();
    }

    // PUBLIC_INTERFACE
    /**
     * No-op shutdown for stdout.
     */
    @Override
    public void shutdown() {
        // stdout does not own closable resources
    }
}
