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
 * Synchronous logger that builds masked structured events and writes JSON to stdout.
 *
 * <p>Only stdout export is supported in the current cross-language baseline.
 * Calls are synchronous, so {@link #flush()} delegates directly to stdout.</p>
 *
 * @since 0.1.0
 */
public final class StdoutLogger implements Logger {

    private static final String GENERIC_EVENT = "GENERIC_EVENT";

    private final SdkConfig config;
    private final RecursiveMaskingEngine maskingEngine;
    private final MapEventValidator eventValidator;

    /**
     * Create a logger from SDK metadata and supported masking settings.
     *
     * @param config SDK configuration
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
     * Flush stdout. Events are emitted synchronously and require no buffering.
     */
    @Override
    public void flush() {
        System.out.flush();
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

            System.out.println(toJson(maskedEvent));
        } catch (IllegalArgumentException validationException) {
            if (validationMode == ValidationMode.STRICT) {
                throw validationException;
            }
        } catch (RuntimeException ignored) {
            // Logging is fail-open: the host application must not fail because logging failed.
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

    private String toJson(Object value) {
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

    private String escapeJson(String value) {
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
