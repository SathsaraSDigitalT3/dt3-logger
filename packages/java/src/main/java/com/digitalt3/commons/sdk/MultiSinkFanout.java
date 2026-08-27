package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.LogTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fans out final events to one or more sinks with per-sink failure isolation.
 *
 * @since 0.1.0
 */
public final class MultiSinkFanout implements LogTransport {

    private final List<LogTransport> sinks = new ArrayList<>();
    private final ErrorHandler errorHandler;

    /**
     * Create a fan-out transport.
     *
     * @param sinks initial sinks; may be empty
     * @param errorHandler handler used for per-sink delivery failures
     */
    public MultiSinkFanout(List<LogTransport> sinks, ErrorHandler errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler must not be null");
        if (sinks != null) {
            for (LogTransport sink : sinks) {
                addSink(sink);
            }
        }
    }

    /**
     * Register an additional sink for subsequent writes.
     *
     * @param sink transport to add
     */
    public synchronized void addSink(LogTransport sink) {
        Objects.requireNonNull(sink, "sink must not be null");
        sinks.add(sink);
    }

    /**
     * Return a snapshot of registered sinks.
     *
     * @return defensive copy of sinks
     */
    public synchronized List<LogTransport> getSinks() {
        return List.copyOf(sinks);
    }

    // PUBLIC_INTERFACE
    /**
     * Deliver one event to every registered sink, isolating failures per sink.
     *
     * @param logEvent already-processed event
     */
    @Override
    public synchronized void write(LogEvent logEvent) {
        Objects.requireNonNull(logEvent, "logEvent must not be null");
        for (LogTransport sink : sinks) {
            try {
                sink.write(logEvent);
            } catch (RuntimeException exception) {
                errorHandler.handle(exception, Dt3ErrorPhase.DELIVERY);
            }
        }
    }

    /**
     * Deliver a serialized JSON event to sinks that support JSON write paths,
     * falling back to {@link LogTransport#write(LogEvent)} via a map-backed event.
     *
     * @param serializedEvent canonical JSON
     * @param eventMap canonical event map used when a sink lacks a JSON path
     */
    synchronized void writeJson(String serializedEvent, java.util.Map<String, Object> eventMap) {
        Objects.requireNonNull(serializedEvent, "serializedEvent must not be null");
        Objects.requireNonNull(eventMap, "eventMap must not be null");
        CanonicalMapEvent event = new CanonicalMapEvent(eventMap);
        for (LogTransport sink : sinks) {
            try {
                if (sink instanceof StdoutTransport stdoutTransport) {
                    stdoutTransport.writeJson(serializedEvent);
                } else if (sink instanceof FileTransport fileTransport) {
                    fileTransport.writeJson(serializedEvent);
                } else if (sink instanceof HttpTransport httpTransport) {
                    httpTransport.writeJson(serializedEvent);
                } else if (sink instanceof OtlpTransport otlpTransport) {
                    otlpTransport.writeEventMap(eventMap);
                } else {
                    sink.write(event);
                }
            } catch (RuntimeException exception) {
                errorHandler.handle(exception, Dt3ErrorPhase.DELIVERY);
            }
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Flush every registered sink with per-sink failure isolation.
     */
    @Override
    public synchronized void flush() {
        for (LogTransport sink : sinks) {
            try {
                sink.flush();
            } catch (RuntimeException exception) {
                errorHandler.handle(exception, Dt3ErrorPhase.DELIVERY);
            }
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Shut down every registered sink with per-sink failure isolation.
     */
    @Override
    public synchronized void shutdown() {
        for (LogTransport sink : sinks) {
            try {
                sink.shutdown();
            } catch (RuntimeException exception) {
                errorHandler.handle(exception, Dt3ErrorPhase.LIFECYCLE);
            }
        }
    }

    /**
     * LogEvent whose {@link #toMap()} returns a pre-built canonical map.
     */
    static final class CanonicalMapEvent extends LogEvent {
        private final java.util.Map<String, Object> canonical;

        CanonicalMapEvent(java.util.Map<String, Object> canonical) {
            this.canonical = new java.util.LinkedHashMap<>(canonical);
        }

        @Override
        public java.util.Map<String, Object> toMap() {
            return new java.util.LinkedHashMap<>(canonical);
        }
    }
}
