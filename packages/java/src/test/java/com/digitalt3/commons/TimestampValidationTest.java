package com.digitalt3.commons;

import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.ValidationMode;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Focused regression tests for canonical RFC 3339 timestamp validation.
 */
public class TimestampValidationTest {

    @Test
    public void acceptsCanonicalRfc3339OffsetsAndRejectsInvalidVariants() {
        for (String timestamp : new String[] {
            "2026-08-17T09:42:59Z",
            "2026-08-17T09:42:59.616401+05:30",
            "2026-08-17T09:42:59-07:00"
        }) {
            String output = emit(timestamp);

            assertFalse(
                "Expected valid RFC 3339 timestamp: " + timestamp,
                output.contains("\"field\":\"timestamp\"")
            );
        }

        for (String timestamp : new String[] {
            "2026-08-17 09:42:59Z",
            "2026-08-17T09:42:59+0530",
            "2026-08-17T09:42:59",
            "2026-08-17T25:42:59Z"
        }) {
            String output = emit(timestamp);

            assertTrue(
                "Expected timestamp format diagnostic for: " + timestamp,
                output.contains("\"field\":\"timestamp\"")
                    && output.contains("\"message\":\"Value has an invalid format\"")
                    && output.contains("\"rule\":\"format\"")
            );
        }
    }

    private String emit(String timestamp) {
        SdkConfig config = new SdkConfig();
        config.setServiceName("timestamp-test");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        config.setValidationMode(ValidationMode.LENIENT);

        Logger logger = LoggerFactory.createLogger(config);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("timestamp", timestamp);
            logger.info("Timestamp validation", context);
            logger.flush();
        } finally {
            System.setOut(originalOut);
        }

        return stdout.toString(StandardCharsets.UTF_8).trim();
    }
}
