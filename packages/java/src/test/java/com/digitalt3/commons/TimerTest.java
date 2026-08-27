package com.digitalt3.commons;

import com.digitalt3.commons.api.LogContext;
import com.digitalt3.commons.api.Logger;
import com.digitalt3.commons.api.LoggerFactory;
import com.digitalt3.commons.api.SdkConfig;
import com.digitalt3.commons.api.Timer;
import com.digitalt3.commons.sdk.Dt3ErrorCode;
import com.digitalt3.commons.sdk.Dt3ErrorPhase;
import com.digitalt3.commons.sdk.Dt3SdkException;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Focused tests for the public Java Timer API and its logger-pipeline behavior.
 */
public class TimerTest {

    @Test
    public void createTimerIsAvailableFromPublicLoggerApi() {
        Logger logger = createLogger();
        try {
            Timer timer = logger.createTimer("DATABASE_QUERY");
            assertNotNull(timer);
        } finally {
            logger.close();
        }
    }

    @Test
    public void startAndStopMeasureANonNegativeDuration() {
        Logger logger = createLogger();
        try {
            Timer timer = logger.createTimer("DATABASE_QUERY");
            assertEquals(timer, timer.start());

            long duration = timer.stop();

            assertTrue(duration >= 0L);
            assertEquals(duration, timer.durationMs());
        } finally {
            logger.close();
        }
    }

    @Test
    public void finishAliasesStop() {
        Logger logger = createLogger();
        try {
            Timer timer = logger.createTimer("CACHE_LOOKUP");
            timer.start();

            long duration = timer.finish();

            assertTrue(duration >= 0L);
            assertEquals(duration, timer.durationMs());
        } finally {
            logger.close();
        }
    }

    @Test
    public void completionEmitsCanonicalInfoTimerEventExactlyOnce() {
        Logger logger = createLogger();
        String output = captureStdout(() -> {
            Timer timer = logger.createTimer("DATABASE_QUERY");
            timer.start();
            timer.stop();
            logger.flush();
        });

        String[] lines = output.trim().split("\\R");
        assertEquals(1, lines.length);
        String event = lines[0];
        assertTrue(event.contains("\"severity\":\"INFO\""));
        assertTrue(event.contains("\"message\":\"DATABASE_QUERY\""));
        assertTrue(event.contains("\"event.name\":\"DATABASE_QUERY\""));
        assertTrue(event.matches(".*\"duration\\.ms\":\\d+.*"));
        assertTrue(event.contains("\"timestamp\":"));
        assertTrue(event.contains("\"schema.version\":\"1.1.0\""));
        assertTrue(event.contains("\"sdk.name\":\"dt3-commons-java\""));
        assertTrue(event.contains("\"sdk.version\":\"0.1.0\""));
        assertTrue(event.contains("\"service.name\":\"timer-test\""));
        assertTrue(event.contains("\"service.version\":\"1.0.0\""));
        assertTrue(event.contains("\"deployment.environment\":\"test\""));
        logger.close();
    }

    @Test
    public void timerCompletionIncludesActiveLoggerContext() {
        Logger logger = createLogger();
        String output = captureStdout(() -> {
            try (LogContext.Scope ignored = logger.withContext(
                LogContext.builder()
                    .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                    .spanId("00f067aa0ba902b7")
                    .correlationId("request-123")
                    .build()
            )) {
                Timer timer = logger.createTimer("CONTEXT_TIMER");
                timer.start();
                timer.stop();
            }
        });

        assertTrue(output.contains("\"trace.id\":\"4bf92f3577b34da6a3ce929d0e0e4736\""));
        assertTrue(output.contains("\"span.id\":\"00f067aa0ba902b7\""));
        assertTrue(output.contains("\"correlation.id\":\"request-123\""));
        logger.close();
    }

    @Test
    public void independentlyCreatedTimersCanCompleteSeparately() {
        Logger logger = createLogger();
        String output = captureStdout(() -> {
            Timer first = logger.createTimer("FIRST_TIMER");
            Timer second = logger.createTimer("SECOND_TIMER");

            first.start();
            second.start();
            first.stop();
            second.finish();
        });

        assertEquals(2, output.trim().split("\\R").length);
        assertTrue(output.contains("\"event.name\":\"FIRST_TIMER\""));
        assertTrue(output.contains("\"event.name\":\"SECOND_TIMER\""));
        logger.close();
    }

    @Test
    public void invalidLifecycleTransitionsThrowIllegalStateException() {
        Logger logger = createLogger();
        try {
            Timer timer = logger.createTimer("LIFECYCLE_TIMER");

            expectIllegalState(timer::stop);
            expectIllegalState(timer::durationMs);

            timer.start();
            expectIllegalState(timer::start);

            timer.stop();
            expectIllegalState(timer::stop);
            expectIllegalState(timer::finish);
        } finally {
            logger.close();
        }
    }

    @Test
    public void blankAndInvalidTimerNamesAreRejected() {
        Logger logger = createLogger();
        try {
            expectIllegalArgument(() -> logger.createTimer(null));
            expectIllegalArgument(() -> logger.createTimer(""));
            expectIllegalArgument(() -> logger.createTimer("   "));
            expectIllegalArgument(() -> logger.createTimer("database-query"));
            expectIllegalArgument(() -> logger.createTimer("database query"));
        } finally {
            logger.close();
        }
    }

    @Test
    public void closedLoggerRejectsTimerCreationAndUse() {
        Logger closedBeforeCreation = createLogger();
        closedBeforeCreation.close();
        expectLifecycleClosed(() -> closedBeforeCreation.createTimer("CLOSED_LOGGER_TIMER"));

        Logger logger = createLogger();
        Timer timer = logger.createTimer("CLOSED_TIMER");
        logger.close();

        expectLifecycleClosed(timer::start);
        expectLifecycleClosed(timer::stop);
    }

    private Logger createLogger() {
        SdkConfig config = new SdkConfig();
        config.setServiceName("timer-test");
        config.setServiceVersion("1.0.0");
        config.setDeploymentEnvironment("test");
        return LoggerFactory.createLogger(config);
    }

    private String captureStdout(Runnable action) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
        try {
            action.run();
        } finally {
            System.setOut(originalOut);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private void expectIllegalState(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected lifecycle failure.
        }
    }

    private void expectLifecycleClosed(Runnable action) {
        Dt3SdkException exception = assertThrows(Dt3SdkException.class, action::run);
        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, exception.getCode());
        assertEquals(Dt3ErrorPhase.LIFECYCLE, exception.getPhase());
        assertTrue(!exception.isRetryable());
    }

    private void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected name validation failure.
        }
    }
}
