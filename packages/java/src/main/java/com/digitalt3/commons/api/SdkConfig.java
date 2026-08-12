package com.digitalt3.commons.api;

import java.util.ArrayList;
import java.util.List;

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
