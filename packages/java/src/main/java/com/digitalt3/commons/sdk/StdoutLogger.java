package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.Timer;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.api.Validator;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Synchronous logger that builds, masks, validates, and exports structured events.
 *
 * <p>Despite its retained historical name, this implementation supports the
 * configured stdout and file exporters. The processing order is canonical event
 * creation, masking, validation, then transport delivery.</p>
 *
 * @since 0.1.0
 */
public final class StdoutLogger implements Logger {

    private static final String GENERIC_EVENT = "GENERIC_EVENT";
    private static final String CANONICAL_EVENT_NAME_PATTERN = "^[A-Z][A-Z0-9_]*$";

    private final SdkConfig config;
    private final RecursiveMaskingEngine maskingEngine;
    private final MapEventValidator eventValidator;
    private final FileTransport fileTransport;
    private final HttpTransport httpTransport;
    private final OtlpTransport otlpTransport;
    private final EventBatcher batcher;
    private boolean closed;

    /**
     * Create a logger from SDK metadata and supported masking/export settings.
     *
     * @param config SDK configuration
     * @throws IllegalArgumentException if exporter configuration is unsupported
     *     or the file exporter has no destination path
     */
    public StdoutLogger(SdkConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.maskingEngine = new RecursiveMaskingEngine(
            config.getMaskingFields(),
            config.getMaskingReplacementValue(),
            config.isMaskingTrackMaskedFields(),
            config.isMaskingEnabled()
        );
        this.eventValidator = new MapEventValidator();
        this.fileTransport = createFileTransport(config);
        this.httpTransport = createHttpTransport(config);
        this.otlpTransport = createOtlpTransport(config);
        this.batcher = config.isBatchingEnabled()
            ? new EventBatcher(
                this::writeFinalEvent,
                config.getBatchingMaxSize(),
                config.getBatchingFlushIntervalMs()
            )
            : null;
    }

    // PUBLIC_INTERFACE
    /**
     * Export a DEBUG structured event.
     *
     * @param message human-readable event message
     * @param context optional structured context
     */
    @Override
    public void debug(String message, Map<String, Object> context) {
        log("DEBUG", message, context, null);
    }

    // PUBLIC_INTERFACE
    /**
     * Export an INFO structured event.
     *
     * @param message human-readable event message
     * @param context optional structured context
     */
    @Override
    public void info(String message, Map<String, Object> context) {
        log("INFO", message, context, null);
    }

    // PUBLIC_INTERFACE
    /**
     * Export a WARN structured event.
     *
     * @param message human-readable event message
     * @param context optional structured context
     */
    @Override
    public void warn(String message, Map<String, Object> context) {
        log("WARN", message, context, null);
    }

    // PUBLIC_INTERFACE
    /**
     * Export an ERROR structured event with optional exception details.
     *
     * @param message human-readable event message
     * @param error exception to include in error fields, or {@code null}
     * @param context optional structured context
     */
    @Override
    public void error(String message, Throwable error, Map<String, Object> context) {
        log("ERROR", message, context, error);
    }

    // PUBLIC_INTERFACE
    /**
     * Create an unstarted single-use timer associated with this logger.
     *
     * @param name canonical UPPER_SNAKE_CASE completion event name
     * @return a new timer which emits through this logger on completion
     * @throws IllegalArgumentException if the supplied event name is invalid
     * @throws IllegalStateException if the logger is closed
     */
    @Override
    public synchronized Timer createTimer(String name) {
        ensureOpen();
        validateTimerName(name);
        return new LoggerTimer(name);
    }

    // PUBLIC_INTERFACE
    /**
     * Flush the configured synchronous transport.
     */
    @Override
    public void flush() {
        ensureOpen();
        if (batcher != null) {
            batcher.flush();
        }
        if (fileTransport != null) {
            try {
                fileTransport.flush();
            } catch (IllegalStateException exception) {
                handleTransportFailure(exception);
            }
            return;
        }
        if (httpTransport != null) {
            try {
                httpTransport.flush();
            } catch (HttpTransportError | OtlpTransportError exception) {
                handleTransportFailure(exception);
            }
            return;
        }
        if (otlpTransport != null) {
            try {
                otlpTransport.flush();
            } catch (HttpTransportError | OtlpTransportError exception) {
                handleTransportFailure(exception);
            }
            return;
        }

        System.out.flush();
    }

    // PUBLIC_INTERFACE
    /**
     * Close the configured transport and prevent subsequent logging or flushes.
     *
     * <p>This operation is idempotent. The Java SDK retains {@code close} as
     * the logger lifecycle name while transports retain their existing
     * {@code shutdown} contract.</p>
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        if (batcher != null) {
            try {
                batcher.close();
            } catch (HttpTransportError | OtlpTransportError | IllegalStateException exception) {
                handleTransportFailure(exception);
            }
        }
        closed = true;

        if (fileTransport != null) {
            try {
                fileTransport.shutdown();
            } catch (IllegalStateException exception) {
                handleTransportFailure(exception);
            }
        } else if (httpTransport != null) {
            try {
                httpTransport.shutdown();
            } catch (HttpTransportError | OtlpTransportError exception) {
                handleTransportFailure(exception);
            }
        } else if (otlpTransport != null) {
            try {
                otlpTransport.shutdown();
            } catch (HttpTransportError | OtlpTransportError exception) {
                handleTransportFailure(exception);
            }
        }
    }

    private FileTransport createFileTransport(SdkConfig sdkConfig) {
        String exporter = sdkConfig.getExporter();
        if (exporter == null || exporter.trim().isEmpty() || "stdout".equalsIgnoreCase(exporter)) {
            return null;
        }
        if ("file".equalsIgnoreCase(exporter)) {
            return new FileTransport(sdkConfig.getFilePath());
        }
        if ("http".equalsIgnoreCase(exporter)) {
            return null;
        }
        if ("otlp".equalsIgnoreCase(exporter)) {
            return null;
        }

        throw new IllegalArgumentException("Unsupported exporter: " + exporter);
    }

    private HttpTransport createHttpTransport(SdkConfig sdkConfig) {
        return "http".equalsIgnoreCase(sdkConfig.getExporter())
            ? new HttpTransport(
                sdkConfig.getHttpEndpoint(),
                sdkConfig.getHttpTimeout(),
                sdkConfig.getHttpHeaders()
            )
            : null;
    }

    private OtlpTransport createOtlpTransport(SdkConfig sdkConfig) {
        return "otlp".equalsIgnoreCase(sdkConfig.getExporter())
            ? new OtlpTransport(
                sdkConfig.getOtlpEndpoint(),
                sdkConfig.getOtlpTimeout(),
                sdkConfig.getOtlpHeaders()
            )
            : null;
    }

    private void log(String severity, String message, Map<String, Object> context, Throwable error) {
        ensureOpen();
        ValidationMode validationMode = config.getValidationMode();
        if (validationMode == null) {
            throw new IllegalArgumentException(
                "validationMode must be STRICT, LENIENT, or OFF"
            );
        }

        Map<String, Object> event = createEvent(severity, message, context, error);
        Map<String, Object> maskedEvent = maskingEngine.mask(event);
        List<String> maskedFields = maskingEngine.getMaskedFields();

        if (!maskedFields.isEmpty()) {
            maskedEvent.put("dt3.security.masked_fields", maskedFields);
        }

        List<Validator.ValidationErrorDetail> validationErrors = eventValidator.apply(
            maskedEvent,
            validationMode
        );
        if (!validationErrors.isEmpty() && validationMode == ValidationMode.LENIENT) {
            maskedEvent.put(
                "dt3.validation.errors",
                validationErrors.stream()
                    .map(errorDetail -> Map.of(
                        "field", errorDetail.field(),
                        "message", errorDetail.message(),
                        "rule", errorDetail.rule()
                    ))
                    .toList()
            );
        }

        try {
            if (batcher != null) {
                batcher.add(maskedEvent);
            } else {
                writeFinalEvent(maskedEvent);
            }
        } catch (HttpTransportError | OtlpTransportError | IllegalStateException exception) {
            handleTransportFailure(exception);
        }
    }

    private void validateTimerName(String name) {
        if (name == null || name.trim().isEmpty() || !name.matches(CANONICAL_EVENT_NAME_PATTERN)) {
            throw new IllegalArgumentException(
                "timer name must be a canonical UPPER_SNAKE_CASE event name"
            );
        }
    }

    /**
     * Monotonic single-use timer that delegates completion to the owning logger.
     */
    private final class LoggerTimer implements Timer {
        private final String name;
        private boolean started;
        private boolean completed;
        private long startedAtNanos;
        private long measuredDurationMs;

        private LoggerTimer(String name) {
            this.name = name;
        }

        // PUBLIC_INTERFACE
        /**
         * Start this timer using the JVM monotonic clock.
         *
         * @return this timer
         */
        @Override
        public synchronized Timer start() {
            ensureOpen();
            if (started) {
                throw new IllegalStateException("Timer has already started");
            }

            started = true;
            startedAtNanos = System.nanoTime();
            return this;
        }

        // PUBLIC_INTERFACE
        /**
         * Stop once and emit the timer completion event through the logger pipeline.
         *
         * @return the measured non-negative duration in milliseconds
         */
        @Override
        public synchronized long stop() {
            ensureOpen();
            if (!started) {
                throw new IllegalStateException("Timer has not started");
            }
            if (completed) {
                throw new IllegalStateException("Timer has already completed");
            }

            long elapsedNanos = System.nanoTime() - startedAtNanos;
            measuredDurationMs = Math.max(0L, elapsedNanos / 1_000_000L);
            completed = true;
            log(
                "INFO",
                name,
                Map.of("event.name", name, "duration.ms", measuredDurationMs),
                null
            );
            return measuredDurationMs;
        }

        // PUBLIC_INTERFACE
        /**
         * Complete this timer as an alias for {@link #stop()}.
         *
         * @return the measured non-negative duration in milliseconds
         */
        @Override
        public long finish() {
            return stop();
        }

        // PUBLIC_INTERFACE
        /**
         * Return the completed timer's measured duration.
         *
         * @return measured non-negative duration in milliseconds
         */
        @Override
        public synchronized long durationMs() {
            if (!completed) {
                throw new IllegalStateException("Timer has not completed");
            }
            return measuredDurationMs;
        }
    }

    private void writeFinalEvent(Map<String, Object> finalEvent) {
        String serializedEvent = toJson(finalEvent);
        if (fileTransport != null) {
            fileTransport.writeJson(serializedEvent);
            return;
        }
        if (httpTransport != null) {
            httpTransport.writeJson(serializedEvent);
            return;
        }
        if (otlpTransport != null) {
            otlpTransport.writeEventMap(finalEvent);
            return;
        }

        System.out.println(serializedEvent);
    }

    private void handleTransportFailure(RuntimeException exception) {
        if (!config.isFailOpen()) {
            throw exception;
        }
    }

    private Map<String, Object> createEvent(
        String severity,
        String message,
        Map<String, Object> context,
        Throwable error
    ) {
        // Scoped values are applied before explicit per-event context so callers
        // can override trace/correlation fields for a single event. Logger-owned
        // fields remain reasserted below after both context sources are merged.
        Map<String, Object> safeContext = new LinkedHashMap<>(LogContext.activeValues());
        if (context != null) {
            safeContext.putAll(context);
        }
        Object suppliedEventName = safeContext.get("event.name");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("severity", severity);
        event.put("message", message);
        event.put(
            "event.name",
            suppliedEventName instanceof String ? suppliedEventName : GENERIC_EVENT
        );
        event.put("schema.version", valueOrDefault(config.getSchemaVersion(), "1.0.0"));
        event.put("sdk.name", valueOrDefault(config.getSdkName(), "dt3-commons-java"));
        event.put("sdk.version", valueOrDefault(config.getSdkVersion(), "0.1.0"));
        putIfConfigured(event, "service.name", config.getServiceName());
        putIfConfigured(event, "service.version", config.getServiceVersion());
        if (config.getDeploymentEnvironment() != null) {
            event.put("deployment.environment", config.getDeploymentEnvironment());
        }
        event.putAll(safeContext);
        // Reassert every logger-owned field after context merging.
        event.put("timestamp", event.get("timestamp"));
        event.put("severity", severity);
        event.put("message", message);
        event.put(
            "event.name",
            suppliedEventName instanceof String ? suppliedEventName : GENERIC_EVENT
        );
        event.put("schema.version", valueOrDefault(config.getSchemaVersion(), "1.0.0"));
        event.put("sdk.name", valueOrDefault(config.getSdkName(), "dt3-commons-java"));
        event.put("sdk.version", valueOrDefault(config.getSdkVersion(), "0.1.0"));
        putIfConfigured(event, "service.name", config.getServiceName());
        putIfConfigured(event, "service.version", config.getServiceVersion());
        removeIfNotConfigured(event, "service.name", config.getServiceName());
        removeIfNotConfigured(event, "service.version", config.getServiceVersion());
        if (config.getDeploymentEnvironment() != null) {
            event.put("deployment.environment", config.getDeploymentEnvironment());
        }

        if (error != null) {
            event.put("error.type", error.getClass().getSimpleName());
            event.put("error.message", error.getMessage());
            event.put("error.stack", stackTrace(error));
        }

        return event;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Logger is closed");
        }
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private void putIfConfigured(Map<String, Object> event, String field, String value) {
        if (value != null) {
            event.put(field, value);
        }
    }

    private void removeIfNotConfigured(Map<String, Object> event, String field, String value) {
        if (value == null) {
            event.remove(field);
        }
    }

    private String stackTrace(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof String text) {
            return "\"" + escapeJson(text) + "\"";
        }

        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }

        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                entries.add(toJson(String.valueOf(entry.getKey())) + ":" + toJson(entry.getValue()));
            }
            return "{" + String.join(",", entries) + "}";
        }

        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            for (Object element : iterable) {
                values.add(toJson(element));
            }
            return "[" + String.join(",", values) + "]";
        }

        if (value.getClass().isArray()) {
            List<String> values = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                values.add(toJson(java.lang.reflect.Array.get(value, index)));
            }
            return "[" + String.join(",", values) + "]";
        }

        return toJson(String.valueOf(value));
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
