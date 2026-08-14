package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.SdkConfig;
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

    private final SdkConfig config;
    private final RecursiveMaskingEngine maskingEngine;
    private final MapEventValidator eventValidator;
    private final FileTransport fileTransport;
    private final HttpTransport httpTransport;
    private final OtlpTransport otlpTransport;

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
     * Flush the configured synchronous transport.
     */
    @Override
    public void flush() {
        if (fileTransport != null) {
            try {
                fileTransport.flush();
            } catch (RuntimeException exception) {
                handleTransportFailure(exception);
            }
            return;
        }
        if (httpTransport != null) {
            try {
                httpTransport.flush();
            } catch (RuntimeException exception) {
                handleTransportFailure(exception);
            }
            return;
        }
        if (otlpTransport != null) {
            try {
                otlpTransport.flush();
            } catch (RuntimeException exception) {
                handleTransportFailure(exception);
            }
            return;
        }

        System.out.flush();
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
        ValidationMode validationMode = config.getValidationMode();
        if (validationMode == null) {
            throw new IllegalArgumentException(
                "validationMode must be STRICT, LENIENT, or OFF"
            );
        }

        try {
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
                redactInvalidValues(maskedEvent, validationErrors);
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

            writeFinalEvent(maskedEvent);
        } catch (IllegalArgumentException validationException) {
            if (validationMode == ValidationMode.STRICT) {
                throw validationException;
            }
        } catch (RuntimeException exception) {
            handleTransportFailure(exception);
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

    /**
     * Remove caller-provided values that failed type validation before lenient export.
     *
     * <p>Schema diagnostics intentionally contain only structural locations. This
     * additional replacement prevents the invalid value itself from being emitted
     * alongside those diagnostics.</p>
     *
     * @param event event that will be serialized
     * @param validationErrors sanitized schema diagnostics
     */
    private void redactInvalidValues(
        Map<String, Object> event,
        List<Validator.ValidationErrorDetail> validationErrors
    ) {
        for (Validator.ValidationErrorDetail validationError : validationErrors) {
            if (!"type".equals(validationError.rule())) {
                continue;
            }

            String field = validationError.field();
            if (event.containsKey(field)) {
                event.put(field, "[REDACTED]");
            }
        }
    }

    private Map<String, Object> createEvent(
        String severity,
        String message,
        Map<String, Object> context,
        Throwable error
    ) {
        Map<String, Object> safeContext = context == null ? Map.of() : context;
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
        event.put("service.name", valueOrDefault(config.getServiceName(), "unknown"));
        event.put("service.version", valueOrDefault(config.getServiceVersion(), "unknown"));
        if (config.getDeploymentEnvironment() != null) {
            event.put("deployment.environment", config.getDeploymentEnvironment());
        }
        event.putAll(safeContext);
        // Caller context may add custom attributes but must not override logger-selected severity.
        event.put("severity", severity);

        if (error != null) {
            event.put("error.type", error.getClass().getSimpleName());
            event.put("error.message", error.getMessage());
            event.put("error.stack", stackTrace(error));
        }

        return event;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return value == null ? defaultValue : value;
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
