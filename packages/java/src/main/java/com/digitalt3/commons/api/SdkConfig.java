package com.digitalt3.commons.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK configuration.
 *
 * @since 0.1.0
 */
public class SdkConfig {
    private String serviceName;
    private String serviceVersion;
    private String deploymentEnvironment;
    private String schemaVersion = "1.0.0";
    private String sdkName = "dt3-commons-java";
    private String sdkVersion = "0.1.0";
    private ValidationMode validationMode = ValidationMode.LENIENT;
    private boolean failOpen = true;
    private String exporter = "stdout";
    private String filePath;
    private String httpEndpoint;
    private long httpTimeout = 5000;
    private Map<String, String> httpHeaders = new LinkedHashMap<>();
    private String otlpEndpoint;
    private long otlpTimeout = 10000;
    private Map<String, String> otlpHeaders = new LinkedHashMap<>();
    private boolean maskingEnabled = true;
    private List<String> maskingFields = new ArrayList<>();
    private String maskingReplacementValue = "[REDACTED]";
    private boolean maskingTrackMaskedFields;
    private boolean batchingEnabled;
    private int batchingMaxSize = 100;
    private long batchingFlushIntervalMs = 5000;
    private boolean autoGenerateCorrelationId;

    // PUBLIC_INTERFACE
    /**
     * Create SDK configuration from canonical dot-keyed values.
     *
     * <p>Canonical keys take precedence over legacy aliases. Supported aliases
     * are {@code file.path}, {@code http.endpoint}, {@code http.timeout}, and
     * {@code http.headers}; they are retained only for compatibility. Timeouts
     * supplied through either key form are milliseconds.</p>
     *
     * @param values configuration values keyed by canonical dot-key names
     * @return populated SDK configuration
     * @throws IllegalArgumentException when a recognized value has the wrong type
     */
    public static SdkConfig fromMap(Map<String, ?> values) {
        SdkConfig config = new SdkConfig();
        config.apply(values);
        return config;
    }

    // PUBLIC_INTERFACE
    /**
     * Apply canonical dot-keyed configuration values to this instance.
     *
     * <p>Canonical values win whenever both canonical and supported legacy
     * aliases are supplied. Unknown keys are ignored so callers can share a
     * configuration map across SDK versions.</p>
     *
     * @param values configuration values keyed by canonical dot-key names
     * @throws IllegalArgumentException when a recognized value has the wrong type
     */
    public void apply(Map<String, ?> values) {
        if (values == null) {
            return;
        }

        setStringIfPresent(values, "service.name", this::setServiceName);
        setStringIfPresent(values, "service.version", this::setServiceVersion);
        setStringIfPresent(values, "deployment.environment", this::setDeploymentEnvironment);
        setStringIfPresent(values, "schema.version", this::setSchemaVersion);
        setStringIfPresent(values, "sdk.name", this::setSdkName);
        setStringIfPresent(values, "sdk.version", this::setSdkVersion);
        setStringIfPresent(values, "exporter", this::setExporter);
        setStringIfPresent(
            values,
            preferredKey(values, "exporter.file.path", "file.path"),
            this::setFilePath
        );
        setStringIfPresent(
            values,
            preferredKey(values, "exporter.http.endpoint", "http.endpoint"),
            this::setHttpEndpoint
        );
        setLongIfPresent(
            values,
            preferredKey(values, "exporter.http.timeout", "http.timeout"),
            this::setHttpTimeout
        );
        setHeadersIfPresent(
            values,
            preferredKey(values, "exporter.http.headers", "http.headers"),
            this::setHttpHeaders
        );
        setStringIfPresent(values, "otlp.endpoint", this::setOtlpEndpoint);
        setLongIfPresent(values, "otlp.timeout", this::setOtlpTimeout);
        setHeadersIfPresent(values, "otlp.headers", this::setOtlpHeaders);
        setBooleanIfPresent(values, "masking.enabled", this::setMaskingEnabled);
        setStringListIfPresent(values, "masking.fields", this::setMaskingFields);
        setBooleanIfPresent(values, "batching.enabled", this::setBatchingEnabled);
        setBooleanIfPresent(
            values,
            "correlation.auto_generate",
            this::setAutoGenerateCorrelationId
        );
        setPositiveIntIfPresent(values, "batching.max_size", this::setBatchingMaxSize);
        setPositiveLongIfPresent(
            values,
            "batching.flush_interval_ms",
            this::setBatchingFlushIntervalMs
        );

        Object failOpenValue = values.get("fail_open");
        if (failOpenValue != null) {
            if (!(failOpenValue instanceof Boolean booleanValue)) {
                throw new IllegalArgumentException("fail_open must be a boolean");
            }
            setFailOpen(booleanValue);
        }

        Object validationModeValue = values.get("validation.mode");
        if (validationModeValue != null) {
            if (!(validationModeValue instanceof String modeName)) {
                throw new IllegalArgumentException("validation.mode must be a string");
            }
            try {
                setValidationMode(ValidationMode.valueOf(modeName.toUpperCase()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                    "validation.mode must be STRICT, LENIENT, or OFF",
                    exception
                );
            }
        }
    }

    private static String preferredKey(Map<String, ?> values, String canonicalKey, String legacyKey) {
        return values.containsKey(canonicalKey) ? canonicalKey : legacyKey;
    }

    private void setStringIfPresent(
        Map<String, ?> values,
        String key,
        java.util.function.Consumer<String> setter
    ) {
        Object value = values.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        setter.accept(stringValue);
    }

    private void setLongIfPresent(
        Map<String, ?> values,
        String key,
        java.util.function.LongConsumer setter
    ) {
        Object value = values.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof Number numberValue)) {
            throw new IllegalArgumentException(key + " must be a number of milliseconds");
        }
        setter.accept(numberValue.longValue());
    }

    private void setPositiveIntIfPresent(
        Map<String, ?> values,
        String key,
        java.util.function.IntConsumer setter
    ) {
        Object value = values.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof Number numberValue)
            || numberValue.doubleValue() != Math.rint(numberValue.doubleValue())
            || numberValue.doubleValue() > Integer.MAX_VALUE
            || numberValue.doubleValue() < Integer.MIN_VALUE
            || numberValue.intValue() <= 0) {
            throw new IllegalArgumentException(key + " must be a positive integer");
        }
        setter.accept(numberValue.intValue());
    }

    private void setPositiveLongIfPresent(
        Map<String, ?> values,
        String key,
        java.util.function.LongConsumer setter
    ) {
        Object value = values.get(key);
        if (!(value instanceof Number numberValue) || numberValue.longValue() <= 0) {
            if (value != null) {
                throw new IllegalArgumentException(key + " must be a positive integer in milliseconds");
            }
            return;
        }
        if (numberValue.doubleValue() != Math.rint(numberValue.doubleValue())) {
            throw new IllegalArgumentException(key + " must be a positive integer in milliseconds");
        }
        setter.accept(numberValue.longValue());
    }

    private void setBooleanIfPresent(
        Map<String, ?> values,
        String key,
        java.util.function.Consumer<Boolean> setter
    ) {
        Object value = values.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof Boolean booleanValue)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        setter.accept(booleanValue);
    }

    private void setStringListIfPresent(
        Map<String, ?> values,
        String key,
        java.util.function.Consumer<List<String>> setter
    ) {
        Object value = values.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof List<?> rawFields)) {
            throw new IllegalArgumentException(key + " must be a list of strings");
        }

        List<String> fields = new ArrayList<>();
        for (Object field : rawFields) {
            if (!(field instanceof String stringField)) {
                throw new IllegalArgumentException(key + " must be a list of strings");
            }
            fields.add(stringField);
        }
        setter.accept(fields);
    }

    @SuppressWarnings("unchecked")
    private void setHeadersIfPresent(
        Map<String, ?> values,
        String key,
        java.util.function.Consumer<Map<String, String>> setter
    ) {
        Object value = values.get(key);
        if (value == null) {
            return;
        }
        if (!(value instanceof Map<?, ?> rawHeaders)) {
            throw new IllegalArgumentException(key + " must be a map of string values");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawHeaders.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof String headerValue)) {
                throw new IllegalArgumentException(key + " must be a map of string values");
            }
            headers.put(name, headerValue);
        }
        setter.accept(headers);
    }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getServiceVersion() { return serviceVersion; }
    public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
    public String getDeploymentEnvironment() { return deploymentEnvironment; }
    public void setDeploymentEnvironment(String deploymentEnvironment) {
        this.deploymentEnvironment = deploymentEnvironment;
    }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getSdkName() { return sdkName; }
    public void setSdkName(String sdkName) { this.sdkName = sdkName; }
    public String getSdkVersion() { return sdkVersion; }
    public void setSdkVersion(String sdkVersion) { this.sdkVersion = sdkVersion; }
    public ValidationMode getValidationMode() { return validationMode; }
    public void setValidationMode(ValidationMode validationMode) { this.validationMode = validationMode; }
    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }
    public String getExporter() { return exporter; }
    public void setExporter(String exporter) { this.exporter = exporter; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getHttpEndpoint() { return httpEndpoint; }
    public void setHttpEndpoint(String httpEndpoint) { this.httpEndpoint = httpEndpoint; }
    /**
     * Return the HTTP request timeout configured by {@code exporter.http.timeout}, in milliseconds.
     *
     * @return the configured HTTP timeout in milliseconds
     */
    public long getHttpTimeout() { return httpTimeout; }

    /**
     * Configure the HTTP request timeout for {@code exporter.http.timeout}, in milliseconds.
     *
     * @param httpTimeout timeout in milliseconds; it must be greater than zero when HTTP export is used
     */
    public void setHttpTimeout(long httpTimeout) { this.httpTimeout = httpTimeout; }

    /**
     * Return custom headers configured for HTTP event export.
     *
     * @return a defensive copy of header name/value pairs
     */
    public Map<String, String> getHttpHeaders() {
        return Map.copyOf(httpHeaders);
    }

    /**
     * Configure custom headers for HTTP event export.
     *
     * @param httpHeaders header name/value pairs; {@code null} clears configured headers
     */
    public void setHttpHeaders(Map<String, String> httpHeaders) {
        this.httpHeaders = httpHeaders == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(httpHeaders);
    }

    /**
     * Return the OTLP Logs endpoint configured by {@code otlp.endpoint}.
     *
     * @return the configured OTLP endpoint, or {@code null} when unset
     */
    public String getOtlpEndpoint() { return otlpEndpoint; }

    /**
     * Configure the OTLP Logs endpoint for {@code otlp.endpoint}.
     *
     * @param otlpEndpoint HTTP or HTTPS OTLP Logs endpoint, commonly ending in {@code /v1/logs}
     */
    public void setOtlpEndpoint(String otlpEndpoint) { this.otlpEndpoint = otlpEndpoint; }

    /**
     * Return the OTLP request timeout configured by {@code otlp.timeout}, in milliseconds.
     *
     * @return the configured OTLP timeout in milliseconds
     */
    public long getOtlpTimeout() { return otlpTimeout; }

    /**
     * Configure the OTLP request timeout for {@code otlp.timeout}, in milliseconds.
     *
     * @param otlpTimeout timeout in milliseconds; it must be greater than zero when OTLP export is used
     */
    public void setOtlpTimeout(long otlpTimeout) { this.otlpTimeout = otlpTimeout; }

    /**
     * Return custom headers configured for OTLP export.
     *
     * @return a defensive copy of OTLP header name/value pairs
     */
    public Map<String, String> getOtlpHeaders() {
        return Map.copyOf(otlpHeaders);
    }

    /**
     * Configure custom headers for OTLP export.
     *
     * @param otlpHeaders header name/value pairs; {@code null} clears configured headers
     */
    public void setOtlpHeaders(Map<String, String> otlpHeaders) {
        this.otlpHeaders = otlpHeaders == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(otlpHeaders);
    }
    public boolean isMaskingEnabled() { return maskingEnabled; }
    public void setMaskingEnabled(boolean maskingEnabled) { this.maskingEnabled = maskingEnabled; }

    /**
     * Return additional sensitive field names supplied by the application.
     *
     * @return a defensive copy of configured sensitive field names
     */
    public List<String> getMaskingFields() {
        return List.copyOf(maskingFields);
    }

    /**
     * Configure additional field names that must be redacted case-insensitively.
     *
     * @param maskingFields caller-defined sensitive field names
     */
    public void setMaskingFields(List<String> maskingFields) {
        this.maskingFields = maskingFields == null ? new ArrayList<>() : new ArrayList<>(maskingFields);
    }

    /**
     * Return the replacement used for masked values.
     *
     * @return the configured replacement value
     */
    public String getMaskingReplacementValue() {
        return maskingReplacementValue;
    }

    /**
     * Configure the replacement used for masked values.
     *
     * @param maskingReplacementValue replacement text, defaulting to {@code [REDACTED]} when null
     */
    public void setMaskingReplacementValue(String maskingReplacementValue) {
        this.maskingReplacementValue =
            maskingReplacementValue == null ? "[REDACTED]" : maskingReplacementValue;
    }

    /**
     * Return whether masked field paths should be added to emitted events.
     *
     * @return {@code true} when masked paths should be tracked
     */
    public boolean isMaskingTrackMaskedFields() {
        return maskingTrackMaskedFields;
    }

    /**
     * Configure whether masked field paths should be tracked.
     *
     * @param maskingTrackMaskedFields whether to emit redacted field paths
     */
    public void setMaskingTrackMaskedFields(boolean maskingTrackMaskedFields) {
        this.maskingTrackMaskedFields = maskingTrackMaskedFields;
    }

    // PUBLIC_INTERFACE
    /**
     * Return whether logger-level buffering is enabled.
     *
     * @return {@code true} when finalized events are buffered before delivery
     */
    public boolean isBatchingEnabled() { return batchingEnabled; }

    // PUBLIC_INTERFACE
    /**
     * Enable or disable logger-level buffering.
     *
     * @param batchingEnabled whether finalized events should be buffered
     */
    public void setBatchingEnabled(boolean batchingEnabled) {
        this.batchingEnabled = batchingEnabled;
    }

    // PUBLIC_INTERFACE
    /**
     * Return whether an absent scoped correlation ID is generated automatically.
     *
     * @return {@code true} when each correlation-less execution scope receives a UUID
     */
    public boolean isAutoGenerateCorrelationId() {
        return autoGenerateCorrelationId;
    }

    // PUBLIC_INTERFACE
    /**
     * Configure automatic correlation-ID generation.
     *
     * <p>A UUID is generated only when no active or explicit event correlation
     * ID exists. Explicit and extracted IDs are never replaced.</p>
     *
     * @param autoGenerateCorrelationId whether automatic generation is enabled
     */
    public void setAutoGenerateCorrelationId(boolean autoGenerateCorrelationId) {
        this.autoGenerateCorrelationId = autoGenerateCorrelationId;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the event count that triggers an immediate batch flush.
     *
     * @return the positive maximum number of buffered events
     */
    public int getBatchingMaxSize() { return batchingMaxSize; }

    // PUBLIC_INTERFACE
    /**
     * Configure the event count that triggers an immediate batch flush.
     *
     * @param batchingMaxSize positive maximum number of buffered events
     * @throws IllegalArgumentException when the value is not positive
     */
    public void setBatchingMaxSize(int batchingMaxSize) {
        if (batchingMaxSize <= 0) {
            throw new IllegalArgumentException("batching.max_size must be a positive integer");
        }
        this.batchingMaxSize = batchingMaxSize;
    }

    // PUBLIC_INTERFACE
    /**
     * Return the maximum time a buffered event remains pending.
     *
     * @return the positive flush interval in milliseconds
     */
    public long getBatchingFlushIntervalMs() { return batchingFlushIntervalMs; }

    // PUBLIC_INTERFACE
    /**
     * Configure the maximum time a buffered event remains pending.
     *
     * @param batchingFlushIntervalMs positive interval in milliseconds
     * @throws IllegalArgumentException when the value is not positive
     */
    public void setBatchingFlushIntervalMs(long batchingFlushIntervalMs) {
        if (batchingFlushIntervalMs <= 0) {
            throw new IllegalArgumentException(
                "batching.flush_interval_ms must be a positive integer in milliseconds"
            );
        }
        this.batchingFlushIntervalMs = batchingFlushIntervalMs;
    }
}
