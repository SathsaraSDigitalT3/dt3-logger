package com.digitalt3.commons.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical structured log event.
 * <p>
 * Represents a single log entry conforming to the DT3 Commons
 * log event schema (schemas/log-event.schema.json).
 * </p>
 *
 * @since 0.1.0
 */
public class LogEvent {
    private String timestamp;
    private String severity;
    private String message;
    private String eventName;
    private String schemaVersion;
    private String sdkName;
    private String sdkVersion;
    private String serviceName;
    private String serviceVersion;
    private String deploymentEnvironment;
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String correlationId;
    private String tenantId;
    private String tenantRegion;
    private String tenantEnvironment;
    private String userId;
    private String sessionId;
    private Double durationMs;
    /**
     * Adapter-only duration representation used to preserve malformed shared
     * fixture values for canonical validation without widening the public API.
     */
    private Object rawDurationMs;
    private String errorType;
    private String errorMessage;
    private String errorStack;
    private String errorCode;
    private Boolean errorRetryable;
    /** Adapter-only raw value retained for canonical fixture validation. */
    private Object rawErrorRetryable;
    private Map<String, Object> attributes;
    /** Adapter-only raw value retained for canonical fixture validation. */
    private Object rawAttributes;
    private List<Validator.ValidationErrorDetail> validationErrors;
    private List<String> maskedFields;

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    // Standard getters and setters for all fields
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getEventName() { return eventName; }
    public void setEventName(String eventName) { this.eventName = eventName; }
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; }
    public String getSdkName() { return sdkName; }
    public void setSdkName(String sdkName) { this.sdkName = sdkName; }
    public String getSdkVersion() { return sdkVersion; }
    public void setSdkVersion(String sdkVersion) { this.sdkVersion = sdkVersion; }
    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }
    public String getServiceVersion() { return serviceVersion; }
    public void setServiceVersion(String serviceVersion) { this.serviceVersion = serviceVersion; }
    public String getDeploymentEnvironment() { return deploymentEnvironment; }
    public void setDeploymentEnvironment(String env) { this.deploymentEnvironment = env; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }
    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getTenantRegion() { return tenantRegion; }
    public void setTenantRegion(String tenantRegion) { this.tenantRegion = tenantRegion; }
    public String getTenantEnvironment() { return tenantEnvironment; }
    public void setTenantEnvironment(String tenantEnvironment) { this.tenantEnvironment = tenantEnvironment; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Double getDurationMs() { return durationMs; }
    public void setDurationMs(Double durationMs) {
        this.durationMs = durationMs;
        this.rawDurationMs = durationMs;
    }
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getErrorStack() { return errorStack; }
    public void setErrorStack(String errorStack) { this.errorStack = errorStack; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public Boolean getErrorRetryable() { return errorRetryable; }
    public void setErrorRetryable(Boolean errorRetryable) {
        this.errorRetryable = errorRetryable;
        this.rawErrorRetryable = errorRetryable;
    }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
        this.rawAttributes = attributes;
    }
    public List<Validator.ValidationErrorDetail> getValidationErrors() { return validationErrors; }
    public void setValidationErrors(List<Validator.ValidationErrorDetail> errors) {
        this.validationErrors = errors;
    }
    public List<String> getMaskedFields() { return maskedFields; }
    public void setMaskedFields(List<String> maskedFields) { this.maskedFields = maskedFields; }

    /**
     * Convert to a flat map using dot-separated keys.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        if (timestamp != null) map.put("timestamp", timestamp);
        if (severity != null) map.put("severity", severity);
        if (message != null) map.put("message", message);
        if (eventName != null) map.put("event.name", eventName);
        if (schemaVersion != null) map.put("schema.version", schemaVersion);
        if (sdkName != null) map.put("sdk.name", sdkName);
        if (sdkVersion != null) map.put("sdk.version", sdkVersion);
        if (serviceName != null) map.put("service.name", serviceName);
        if (serviceVersion != null) map.put("service.version", serviceVersion);
        if (deploymentEnvironment != null) map.put("deployment.environment", deploymentEnvironment);
        if (traceId != null) map.put("trace.id", traceId);
        if (spanId != null) map.put("span.id", spanId);
        if (parentSpanId != null) map.put("parent.span.id", parentSpanId);
        if (correlationId != null) map.put("correlation.id", correlationId);
        if (tenantId != null) map.put("tenant.id", tenantId);
        if (tenantRegion != null) map.put("tenant.region", tenantRegion);
        if (tenantEnvironment != null) map.put("tenant.environment", tenantEnvironment);
        if (userId != null) map.put("user.id", userId);
        if (sessionId != null) map.put("session.id", sessionId);
        Object durationValue = rawDurationMs != null ? rawDurationMs : durationMs;
        if (durationValue != null) map.put("duration.ms", durationValue);
        if (errorType != null) map.put("error.type", errorType);
        if (errorMessage != null) map.put("error.message", errorMessage);
        if (errorStack != null) map.put("error.stack", errorStack);
        if (errorCode != null) map.put("error.code", errorCode);
        Object retryableValue = rawErrorRetryable != null ? rawErrorRetryable : errorRetryable;
        if (retryableValue != null) map.put("error.retryable", retryableValue);
        Object attributesValue = rawAttributes != null ? rawAttributes : attributes;
        if (attributesValue != null) {
            map.put("attributes", attributesValue);
            if (attributesValue instanceof Map<?, ?> rawAttributeMap) {
                Map<String, Object> nestedAttributes = new HashMap<>();
                for (Map.Entry<?, ?> entry : rawAttributeMap.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        nestedAttributes.put(key, entry.getValue());
                    }
                }
                map.putAll(nestedAttributes);
            }
        }
        if (validationErrors != null) map.put("dt3.validation.errors", validationErrors);
        if (maskedFields != null) map.put("dt3.security.masked_fields", maskedFields);
        return map;
    }

    public static class Builder {
        private final LogEvent event = new LogEvent();
        public Builder timestamp(String v) { event.timestamp = v; return this; }
        public Builder severity(String v) { event.severity = v; return this; }
        public Builder message(String v) { event.message = v; return this; }
        public Builder eventName(String v) { event.eventName = v; return this; }
        public Builder schemaVersion(String v) { event.schemaVersion = v; return this; }
        public Builder sdkName(String v) { event.sdkName = v; return this; }
        public Builder sdkVersion(String v) { event.sdkVersion = v; return this; }
        public Builder serviceName(String v) { event.serviceName = v; return this; }
        public Builder serviceVersion(String v) { event.serviceVersion = v; return this; }
        public Builder deploymentEnvironment(String v) { event.deploymentEnvironment = v; return this; }
        public Builder traceId(String v) { event.traceId = v; return this; }
        public Builder spanId(String v) { event.spanId = v; return this; }
        public Builder correlationId(String v) { event.correlationId = v; return this; }
        public Builder tenantId(String v) { event.tenantId = v; return this; }
        public Builder durationMs(Double v) {
            event.durationMs = v;
            event.rawDurationMs = v;
            return this;
        }
        Builder rawDurationMs(Object v) {
            event.rawDurationMs = v;
            return this;
        }
        public Builder errorRetryable(Boolean v) {
            event.errorRetryable = v;
            event.rawErrorRetryable = v;
            return this;
        }
        Builder rawErrorRetryable(Object v) {
            event.rawErrorRetryable = v;
            return this;
        }
        public Builder attributes(Map<String, Object> v) {
            event.attributes = v;
            event.rawAttributes = v;
            return this;
        }
        Builder rawAttributes(Object v) {
            event.rawAttributes = v;
            return this;
        }
        public LogEvent build() { return event; }
    }
}
