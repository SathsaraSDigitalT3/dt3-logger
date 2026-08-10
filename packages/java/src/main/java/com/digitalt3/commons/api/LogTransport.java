package com.digitalt3.commons.api;

/**
 * Transport/Exporter interface for log event delivery.
 *
 * @since 0.1.0
 */
public interface LogTransport {

    /**
     * Write a single log event.
     *
     * @param logEvent The log event to write
     */
    void write(LogEvent logEvent);

    /**
     * Flush any buffered log events.
     */
    void flush();

    /**
     * Shutdown the transport, releasing resources.
     */
    void shutdown();
}
