package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.Timer;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.api.Validator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
    private final ErrorHandler errorHandler;
    private boolean closed;

    /**
     * Create a logger from SDK metadata and supported masking/export settings.
     *
     * @param config SDK configuration
     * @throws IllegalArgumentException if exporter configuration is unsupported
     *     or the file exporter has no destination path
     */
    public StdoutLogger(SdkConfig config) {
        SdkConfig suppliedConfig = Objects.requireNonNull(config, "config must not be null");
        ErrorHandler constructionHandler = createConstructionErrorHandler(suppliedConfig);
        try {
            this.config = suppliedConfig;
            this.errorHandler = constructionHandler;
            this.maskingEngine = new RecursiveMaskingEngine(
                suppliedConfig.getMaskingFields(),
                suppliedConfig.getMaskingReplacementValue(),
                suppliedConfig.isMaskingTrackMaskedFields(),
                suppliedConfig.isMaskingEnabled()
            );
            this.eventValidator = new MapEventValidator();
            this.fileTransport = createFileTransport(suppliedConfig);
            this.httpTransport = createHttpTransport(suppliedConfig);
            this.otlpTransport = createOtlpTransport(suppliedConfig);
            this.batcher = suppliedConfig.isBatchingEnabled()
                ? new EventBatcher(
                    this::writeFinalEvent,
                    this::handleTimerBatchFailure,
                    suppliedConfig.getBatchingMaxSize(),
                    suppliedConfig.getBatchingFlushIntervalMs()
                )
                : null;
        } catch (RuntimeException exception) {
            constructionHandler.report(exception, Dt3ErrorPhase.CONFIGURATION);
            throw exception;
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Return the centralized handler used for SDK-internal errors.
     *
     * @return the configured error handler
     */
    public ErrorHandler getErrorHandler() {
        return errorHandler;
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
     * Export a FATAL structured event.
     *
     * @param message human-readable event message
     * @param context optional structured context
     */
    @Override
    public void fatal(String message, Map<String, Object> context) {
        log("FATAL", message, context, null);
    }

    // PUBLIC_INTERFACE
    /**
     * Process an existing canonical event through the normal logger pipeline.
     *
     * @param event canonical event to enrich, mask, validate, batch, and export
     */
    @Override
    public void event(LogEvent event) {
        ensureOpen();
        Objects.requireNonNull(event, "event must not be null");
        processEvent(createCanonicalEvent(event.toMap()));
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
            try {
                batcher.flush();
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.BATCHING, false);
            }
        }
        if (fileTransport != null) {
            try {
                fileTransport.flush();
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.DELIVERY, false);
            }
            return;
        }
        if (httpTransport != null) {
            try {
                httpTransport.flush();
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.DELIVERY, false);
            }
            return;
        }
        if (otlpTransport != null) {
            try {
                otlpTransport.flush();
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.DELIVERY, false);
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
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.BATCHING, false);
            }
        }

        closed = true;
        if (fileTransport != null) {
            try {
                fileTransport.shutdown();
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.LIFECYCLE, false);
            }
        } else if (httpTransport != null) {
            try {
                httpTransport.shutdown();
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.LIFECYCLE, false);
            }
        } else if (otlpTransport != null) {
            try {
                otlpTransport.shutdown();
            } catch (RuntimeException exception) {
                handleFailure(exception, Dt3ErrorPhase.LIFECYCLE, false);
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
        if ("http".equalsIgnoreCase(exporter) || "otlp".equalsIgnoreCase(exporter)) {
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

    private ErrorHandler createConstructionErrorHandler(SdkConfig sdkConfig) {
        try {
            return new ErrorHandler(
                sdkConfig.isFailOpen(),
                sdkConfig.isErrorDiagnosticsEnabled(),
                sdkConfig.getErrorRateLimitPerMinute(),
                sdkConfig.getErrorObserver(),
                sdkConfig.isErrorDiagnosticsIncludeStack()
            );
        } catch (RuntimeException exception) {
            ErrorHandler fallbackHandler = new ErrorHandler(true, true, 20, null);
            fallbackHandler.report(exception, Dt3ErrorPhase.CONFIGURATION);
            throw exception;
        }
    }

    private void log(String severity, String message, Map<String, Object> context, Throwable error) {
        ensureOpen();
        ValidationMode validationMode = config.getValidationMode();
        if (validationMode == null) {
            handleFailure(
                new IllegalArgumentException(
                    "validationMode must be STRICT, LENIENT, or OFF"
                ),
                Dt3ErrorPhase.CONFIGURATION,
                false
            );
            return;
        }

        processEvent(createEvent(severity, message, context, error));
    }

    private void processEvent(Map<String, Object> event) {
        ValidationMode validationMode = config.getValidationMode();
        if (validationMode == null) {
            handleFailure(
                new IllegalArgumentException(
                    "validationMode must be STRICT, LENIENT, or OFF"
                ),
                Dt3ErrorPhase.CONFIGURATION,
                false
            );
            return;
        }

        Map<String, Object> maskedEvent;
        try {
            maskedEvent = maskingEngine.mask(event);
        } catch (RuntimeException exception) {
            handleFailure(exception, Dt3ErrorPhase.MASKING, false);
            return;
        }

        List<String> maskedFields = maskingEngine.getMaskedFields();
        if (!maskedFields.isEmpty()) {
            maskedEvent.put("dt3.security.masked_fields", maskedFields);
        }

        List<Validator.ValidationErrorDetail> validationErrors;
        try {
            validationErrors = eventValidator.apply(maskedEvent, validationMode);
        } catch (LogEventValidationException exception) {
            // Strict validation rejection is never converted to a success by
            // fail_open; this preserves the established validation contract.
            handleFailure(exception, Dt3ErrorPhase.VALIDATION, true);
            return;
        } catch (RuntimeException exception) {
            handleFailure(exception, Dt3ErrorPhase.VALIDATION, false);
            return;
        }

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
        } catch (Dt3SdkException exception) {
            handleFailure(exception, exception.getPhase(), false);
        } catch (RuntimeException exception) {
            handleFailure(exception, Dt3ErrorPhase.DELIVERY, false);
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
        handleFailure(exception, Dt3ErrorPhase.DELIVERY, false);
    }

    private void handleFailure(
        RuntimeException exception,
        Dt3ErrorPhase phase,
        boolean alwaysThrow
    ) {
        if (alwaysThrow) {
            errorHandler.report(exception, phase);
            throw exception;
        }
        errorHandler.handle(exception, phase);
    }

    private void handleTimerBatchFailure(RuntimeException exception) {
        errorHandler.report(exception, Dt3ErrorPhase.BATCHING);
    }

    private Map<String, Object> createEvent(
        String severity,
        String message,
        Map<String, Object> context,
        Throwable error
    ) {
        Map<String, Object> safeContext = new LinkedHashMap<>(LogContext.activeValues());
        if (context != null) {
            safeContext.putAll(context);
        }
        ensureCorrelationId(safeContext);
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
            event.put("error.message", valueOrDefault(error.getMessage(), ""));
            if (error instanceof Dt3SdkException sdkError) {
                event.put("error.code", sdkError.getCode().getValue());
                event.put("error.retryable", sdkError.isRetryable());
            } else {
                event.put("error.code", Dt3ErrorCode.UNKNOWN.getValue());
                event.put("error.retryable", false);
            }
        }

        return event;
    }

    private Map<String, Object> createCanonicalEvent(Map<String, Object> suppliedEvent) {
        Map<String, Object> scopedContext = new LinkedHashMap<>(LogContext.activeValues());
        Map<String, Object> event = new LinkedHashMap<>(suppliedEvent);
        scopedContext.putAll(event);
        ensureCorrelationId(scopedContext);

        event.putAll(scopedContext);
        event.putIfAbsent("timestamp", Instant.now().toString());
        event.putIfAbsent("event.name", GENERIC_EVENT);
        event.put("schema.version", valueOrDefault(config.getSchemaVersion(), "1.0.0"));
        event.put("sdk.name", valueOrDefault(config.getSdkName(), "dt3-commons-java"));
        event.put("sdk.version", valueOrDefault(config.getSdkVersion(), "0.1.0"));
        putIfConfigured(event, "service.name", config.getServiceName());
        putIfConfigured(event, "service.version", config.getServiceVersion());
        putIfConfigured(event, "deployment.environment", config.getDeploymentEnvironment());
        removeIfNotConfigured(event, "service.name", config.getServiceName());
        removeIfNotConfigured(event, "service.version", config.getServiceVersion());
        removeIfNotConfigured(event, "deployment.environment", config.getDeploymentEnvironment());
        return event;
    }

    private void ensureCorrelationId(Map<String, Object> context) {
        Object correlationId = context.get("correlation.id");
        if (correlationId instanceof String text && !text.isBlank()) {
            String scopedCorrelationId = LogContext.establishCorrelationId(text);
            context.put("correlation.id", scopedCorrelationId == null ? text : scopedCorrelationId);
            return;
        }

        if (config.isAutoGenerateCorrelationId()) {
            String generatedCorrelationId = UUID.randomUUID().toString();
            String scopedCorrelationId = LogContext.establishCorrelationId(generatedCorrelationId);
            context.put(
                "correlation.id",
                scopedCorrelationId == null ? generatedCorrelationId : scopedCorrelationId
            );
        }
    }

    private void ensureOpen() {
        if (closed) {
            Dt3SdkException exception = new Dt3SdkException(
                "Logger is closed",
                Dt3ErrorCode.LIFECYCLE_CLOSED,
                false,
                Dt3ErrorPhase.LIFECYCLE
            );
            handleFailure(exception, Dt3ErrorPhase.LIFECYCLE, true);
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
