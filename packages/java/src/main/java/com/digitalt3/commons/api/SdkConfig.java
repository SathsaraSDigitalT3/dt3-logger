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
}
