package com.digitalt3.commons.api;

/**
 * Distributed trace context aligned with W3C Trace Context.
 *
 * @since 0.1.0
 */
public class TraceContext {
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String correlationId;

    public TraceContext() {}

    public TraceContext(String traceId, String spanId, String parentSpanId, String correlationId) {
        this.traceId = traceId;
        this.spanId = spanId;
        this.parentSpanId = parentSpanId;
        this.correlationId = correlationId;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public String getParentSpanId() { return parentSpanId; }
    public void setParentSpanId(String parentSpanId) { this.parentSpanId = parentSpanId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
}
