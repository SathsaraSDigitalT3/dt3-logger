package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.LogTransport;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.Timer;
import com.digitalt3.commons.api.Tracer;
import com.digitalt3.commons.api.ValidationMode;
import com.digitalt3.commons.api.Validator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Synchronous logger that builds, masks, validates, and exports structured events.
 *
 * <p>Despite its retained historical name, this implementation supports the
 * configured stdout, file, HTTP, and OTLP exporters, including multi-sink fan-out.
 * The processing order is canonical event creation, masking, validation, then
 * transport delivery.</p>
 *
 * @since 0.1.0
 */
public final class StdoutLogger implements Logger {

    private static final String GENERIC_EVENT = "GENERIC_EVENT";
    private static final String CANONICAL_EVENT_NAME_PATTERN = "^[A-Z][A-Z0-9_]*$";
    private static final String DEFAULT_SCHEMA_VERSION = "1.1.0";

    private final SdkConfig config;
    private final RecursiveMaskingEngine maskingEngine;
    private final MapEventValidator eventValidator;
    private final MultiSinkFanout sinkFanout;
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
            this.sinkFanout = new MultiSinkFanout(
                createConfiguredTransports(suppliedConfig),
                constructionHandler
            );
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
     * Register an additional export sink for concurrent fan-out.
     *
     * @param sink transport to register
     */
    @Override
    public synchronized void registerSink(LogTransport sink) {
        ensureOpen();
        sinkFanout.addSink(Objects.requireNonNull(sink, "sink must not be null"));
    }

    // PUBLIC_INTERFACE
    /**
     * Create a tracer bound to this logger.
     *
     * @return tracer using this logger for span events
     */
    @Override
    public synchronized Tracer createTracer() {
        ensureOpen();
        return new TracerImpl(this, config.isTracingSpanEventsEnabled());
    }

    // PUBLIC_INTERFACE
    /**
     * Flush the configured synchronous transport(s).
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
        sinkFanout.flush();
    }

    // PUBLIC_INTERFACE
    /**
     * Close the configured transport(s) and prevent subsequent logging or flushes.
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
        sinkFanout.shutdown();
    }

    private List<LogTransport> createConfiguredTransports(SdkConfig sdkConfig) {
        List<String> exporterNames = resolveExporterNames(sdkConfig);
        List<LogTransport> transports = new ArrayList<>();
        for (String exporterName : exporterNames) {
            transports.add(createTransport(exporterName, sdkConfig));
        }
        return transports;
    }

    private List<String> resolveExporterNames(SdkConfig sdkConfig) {
        List<String> configuredExporters = sdkConfig.getExporters();
        if (configuredExporters != null && !configuredExporters.isEmpty()) {
            return configuredExporters;
        }

        String exporter = sdkConfig.getExporter();
        if (exporter == null || exporter.trim().isEmpty()) {
            return List.of("stdout");
        }
        return List.of(exporter.trim());
    }

    private LogTransport createTransport(String exporter, SdkConfig sdkConfig) {
        String normalized = exporter == null ? "" : exporter.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "", "stdout" -> new StdoutTransport();
            case "file" -> new FileTransport(sdkConfig.getFilePath());
            case "http" -> new HttpTransport(
                sdkConfig.getHttpEndpoint(),
                sdkConfig.getHttpTimeout(),
                sdkConfig.getHttpHeaders()
            );
            case "otlp" -> new OtlpTransport(
                sdkConfig.getOtlpEndpoint(),
                sdkConfig.getOtlpTimeout(),
                sdkConfig.getOtlpHeaders()
            );
            case "kafka" -> KafkaTransport.kafkaRest(
                sdkConfig.getKafkaTopic(),
                sdkConfig.getKafkaRestEndpoint(),
                sdkConfig.getKafkaTimeout(),
                sdkConfig.getKafkaHeaders()
            );
            case "eventhub" -> KafkaTransport.eventHub(
                sdkConfig.getEventHubEndpoint(),
                sdkConfig.getEventHubTimeout(),
                sdkConfig.getEventHubHeaders()
            );
            default -> throw new IllegalArgumentException("Unsupported exporter: " + exporter);
        };
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

        @Override
        public long finish() {
            return stop();
        }

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
        sinkFanout.writeJson(serializedEvent, finalEvent);
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
        event.put("schema.version", valueOrDefault(config.getSchemaVersion(), DEFAULT_SCHEMA_VERSION));
        event.put("sdk.name", valueOrDefault(config.getSdkName(), "dt3-commons-java"));
        event.put("sdk.version", valueOrDefault(config.getSdkVersion(), "0.1.0"));
        putIfConfigured(event, "service.name", config.getServiceName());
        putIfConfigured(event, "service.version", config.getServiceVersion());
        if (config.getDeploymentEnvironment() != null) {
            event.put("deployment.environment", config.getDeploymentEnvironment());
        }
        applyComponentName(event);
        event.putAll(safeContext);
        event.put("timestamp", event.get("timestamp"));
        event.put("severity", severity);
        event.put("message", message);
        event.put(
            "event.name",
            suppliedEventName instanceof String ? suppliedEventName : GENERIC_EVENT
        );
        event.put("schema.version", valueOrDefault(config.getSchemaVersion(), DEFAULT_SCHEMA_VERSION));
        event.put("sdk.name", valueOrDefault(config.getSdkName(), "dt3-commons-java"));
        event.put("sdk.version", valueOrDefault(config.getSdkVersion(), "0.1.0"));
        putIfConfigured(event, "service.name", config.getServiceName());
        putIfConfigured(event, "service.version", config.getServiceVersion());
        removeIfNotConfigured(event, "service.name", config.getServiceName());
        removeIfNotConfigured(event, "service.version", config.getServiceVersion());
        if (config.getDeploymentEnvironment() != null) {
            event.put("deployment.environment", config.getDeploymentEnvironment());
        }
        applyComponentName(event);
        ensureEventId(event);
        ensureTraceIds(event);

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
        event.put("schema.version", valueOrDefault(config.getSchemaVersion(), DEFAULT_SCHEMA_VERSION));
        event.put("sdk.name", valueOrDefault(config.getSdkName(), "dt3-commons-java"));
        event.put("sdk.version", valueOrDefault(config.getSdkVersion(), "0.1.0"));
        putIfConfigured(event, "service.name", config.getServiceName());
        putIfConfigured(event, "service.version", config.getServiceVersion());
        putIfConfigured(event, "deployment.environment", config.getDeploymentEnvironment());
        removeIfNotConfigured(event, "service.name", config.getServiceName());
        removeIfNotConfigured(event, "service.version", config.getServiceVersion());
        removeIfNotConfigured(event, "deployment.environment", config.getDeploymentEnvironment());
        applyComponentName(event);
        ensureEventId(event);
        ensureTraceIds(event);
        return event;
    }

    private void ensureEventId(Map<String, Object> event) {
        Object eventId = event.get("event.id");
        if (!(eventId instanceof String text) || text.isBlank()) {
            event.put("event.id", UUID.randomUUID().toString());
        }
    }

    private void ensureTraceIds(Map<String, Object> event) {
        if (!config.isTracingAutoGenerateIds()) {
            return;
        }
        Object traceId = event.get("trace.id");
        if (!(traceId instanceof String traceText) || !traceText.matches("^[a-f0-9]{32}$")) {
            event.put("trace.id", randomHex(16));
        }
        Object spanId = event.get("span.id");
        if (!(spanId instanceof String spanText) || !spanText.matches("^[a-f0-9]{16}$")) {
            event.put("span.id", randomHex(8));
        }
    }

    private static String randomHex(int byteLength) {
        byte[] bytes = new byte[byteLength];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder builder = new StringBuilder(byteLength * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private void applyComponentName(Map<String, Object> event) {
        Object existing = event.get("component.name");
        if (existing instanceof String text && !text.isBlank()) {
            return;
        }
        putIfConfigured(event, "component.name", config.getComponentName());
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
