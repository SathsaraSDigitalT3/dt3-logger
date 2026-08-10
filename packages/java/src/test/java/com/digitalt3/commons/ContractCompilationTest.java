package com.digitalt3.commons;

import com.digitalt3.commons.api.*;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Verifies that all API contracts compile and basic construction works.
 */
public class ContractCompilationTest {

    @Test
    public void testLogEventBuilder() {
        LogEvent event = LogEvent.builder()
            .timestamp("2026-07-03T10:00:00.000Z")
            .severity("INFO")
            .message("Test event")
            .eventName("TEST_EVENT")
            .schemaVersion("1.0.0")
            .sdkName("dt3-commons-java")
            .sdkVersion("0.1.0")
            .serviceName("test-service")
            .serviceVersion("1.0.0")
            .deploymentEnvironment("test")
            .build();

        assertNotNull(event);
        assertEquals("INFO", event.getSeverity());
        assertEquals("TEST_EVENT", event.getEventName());

        Map<String, Object> map = event.toMap();
        assertEquals("TEST_EVENT", map.get("event.name"));
    }

    @Test
    public void testTraceContext() {
        TraceContext ctx = new TraceContext(
            "4bf92f3577b34da6a3ce929d0e0e4736",
            "00f067aa0ba902b7",
            null,
            "9f4d7c1a-0f88-46fa-9383-b19f78c36d90"
        );
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", ctx.getTraceId());
    }

    @Test
    public void testTenantContext() {
        TenantContext ctx = new TenantContext("tenant-123", "apac", "production");
        assertEquals("tenant-123", ctx.getTenantId());
        assertEquals("apac", ctx.getTenantRegion());
    }

    @Test
    public void testSdkConfig() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("test-service");
        config.setValidationMode(ValidationMode.STRICT);
        assertTrue(config.isFailOpen());
        assertEquals(ValidationMode.STRICT, config.getValidationMode());
    }

    @Test
    public void testValidationModeEnum() {
        assertEquals(3, ValidationMode.values().length);
        assertEquals(ValidationMode.STRICT, ValidationMode.valueOf("STRICT"));
        assertEquals(ValidationMode.LENIENT, ValidationMode.valueOf("LENIENT"));
        assertEquals(ValidationMode.OFF, ValidationMode.valueOf("OFF"));
    }
}
