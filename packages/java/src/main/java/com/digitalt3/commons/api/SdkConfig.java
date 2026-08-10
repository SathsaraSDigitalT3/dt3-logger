package com.digitalt3.commons.api;

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

    // Getters and setters for all fields
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getServiceVersion() { return serviceVersion; }
    public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
    public String getDeploymentEnvironment() { return deploymentEnvironment; }
    public void setDeploymentEnvironment(String deploymentEnvironment) { this.deploymentEnvironment = deploymentEnvironment; }
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
}
