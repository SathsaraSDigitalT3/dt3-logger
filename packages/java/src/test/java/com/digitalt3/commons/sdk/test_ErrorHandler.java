package com.digitalt3.commons.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.digitalt3.commons.api.SdkConfig;

import org.junit.Test;

/**
 * Unit tests for centralized SDK error classification, reporting, and disposition.
 */
public class test_ErrorHandler {

    @Test
    public void classifyMapsKnownFailuresToStableCodesAndRetryability() {
        ErrorHandler handler = handler(true, false, new ByteArrayOutputStream(), 10, null);

        Dt3ErrorReport validation = handler.classify(
            new LogEventValidationException("invalid event")
        );
        Dt3ErrorReport timeout = handler.classify(
            new HttpTransportError("request timed out")
        );
        Dt3ErrorReport rejected = handler.classify(
            new OtlpTransportError("response status 503")
        );
        Dt3ErrorReport statusTextUnavailable = handler.classify(
            new OtlpTransportError("status lookup unavailable")
        );
        Dt3ErrorReport unavailable = handler.classify(
            new HttpTransportError("connection refused")
        );
        Dt3ErrorReport fileWrite = handler.classify(
            new UncheckedIOException("cannot write", new java.io.IOException("disk full"))
        );
        Dt3ErrorReport lifecycle = handler.classify(
            new IllegalStateException("Logger is closed")
        );
        Dt3ErrorReport batching = handler.classify(
            new IllegalStateException("batch queue failed")
        );
        Dt3ErrorReport configuration = handler.classify(
            new IllegalArgumentException("unsupported exporter")
        );

        assertEquals(Dt3ErrorCode.VALIDATION_FAILED, validation.getCode());
        assertFalse(validation.isRetryable());
        assertEquals(Dt3ErrorCode.TRANSPORT_TIMEOUT, timeout.getCode());
        assertTrue(timeout.isRetryable());
        assertEquals(Dt3ErrorCode.TRANSPORT_REJECTED, rejected.getCode());
        assertFalse(rejected.isRetryable());
        assertEquals(Dt3ErrorCode.TRANSPORT_UNAVAILABLE, statusTextUnavailable.getCode());
        assertTrue(statusTextUnavailable.isRetryable());
        assertEquals(Dt3ErrorCode.TRANSPORT_UNAVAILABLE, unavailable.getCode());
        assertTrue(unavailable.isRetryable());
        assertEquals(Dt3ErrorCode.FILE_WRITE_FAILED, fileWrite.getCode());
        assertFalse(fileWrite.isRetryable());
        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, lifecycle.getCode());
        assertEquals(Dt3ErrorCode.BATCH_ABORTED, batching.getCode());
        assertEquals(Dt3ErrorCode.CONFIGURATION_INVALID, configuration.getCode());
    }

    @Test
    public void reportNotifiesObserverWithSanitizedFieldsAndCumulativeOccurrences() {
        AtomicReference<Dt3ErrorReport> observed = new AtomicReference<>();
        ErrorHandler handler = handler(
            true,
            false,
            new ByteArrayOutputStream(),
            10,
            observed::set
        );
        IllegalArgumentException error = new IllegalArgumentException("bad configuration");

        handler.report(error, Dt3ErrorPhase.CONFIGURATION);
        Dt3ErrorReport firstReport = observed.get();
        handler.report(new IllegalArgumentException("another bad configuration"), Dt3ErrorPhase.CONFIGURATION);
        Dt3ErrorReport secondReport = observed.get();

        assertEquals(Dt3ErrorCode.CONFIGURATION_INVALID, firstReport.getCode());
        assertEquals(Dt3ErrorPhase.CONFIGURATION, firstReport.getPhase());
        assertEquals("DT3 SDK handled DT3_CONFIG_INVALID", firstReport.getMessage());
        assertFalse(firstReport.isRetryable());
        assertEquals(1L, firstReport.getOccurrences());
        assertEquals(2L, secondReport.getOccurrences());
        assertEquals(
            Map.of(
                "error.type", "IllegalArgumentException",
                "error.code", "DT3_CONFIG_INVALID",
                "error.retryable", false
            ),
            firstReport.toFields()
        );
        assertThrows(
            UnsupportedOperationException.class,
            () -> firstReport.toFields().put("additional", "field")
        );
    }

    @Test
    public void reportUsesExceptionTypeWhenExceptionMessageIsNull() {
        AtomicReference<Dt3ErrorReport> observed = new AtomicReference<>();
        ErrorHandler handler = handler(
            true,
            false,
            new ByteArrayOutputStream(),
            10,
            observed::set
        );

        handler.report(new IllegalStateException(), Dt3ErrorPhase.BATCHING);

        assertEquals("DT3 SDK handled DT3_BATCH_ABORTED", observed.get().getMessage());
        assertEquals(Dt3ErrorCode.BATCH_ABORTED, observed.get().getCode());
    }

    @Test
    public void reportPreservesCallerSuppliedPhaseForTypedFailures() {
        AtomicReference<Dt3ErrorReport> observed = new AtomicReference<>();
        ErrorHandler handler = handler(
            true,
            false,
            new ByteArrayOutputStream(),
            10,
            observed::set
        );

        handler.report(
            new HttpTransportError(
                "transport unavailable",
                null,
                Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
                true
            ),
            Dt3ErrorPhase.BATCHING
        );

        assertEquals(Dt3ErrorCode.TRANSPORT_UNAVAILABLE, observed.get().getCode());
        assertEquals(Dt3ErrorPhase.BATCHING, observed.get().getPhase());
        assertTrue(observed.get().isRetryable());
    }

    @Test
    public void reportSuppressesObserverFailuresAndContinuesCounting() {
        ErrorHandler handler = handler(
            true,
            false,
            new ByteArrayOutputStream(),
            10,
            report -> {
                throw new IllegalStateException("observer failure");
            }
        );

        handler.report(new IllegalArgumentException("bad configuration"), Dt3ErrorPhase.CONFIGURATION);
        handler.report(new IllegalArgumentException("bad configuration"), Dt3ErrorPhase.CONFIGURATION);

        assertEquals(
            Map.of(Dt3ErrorCode.CONFIGURATION_INVALID.getValue(), 2L),
            handler.snapshot()
        );
    }

    @Test
    public void reportEmitsDiagnosticsAtConfiguredPerCodeRateLimit() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ErrorHandler handler = handler(true, true, output, 1, null);

        handler.report(new IllegalArgumentException("first problem"), Dt3ErrorPhase.CONFIGURATION);
        handler.report(new IllegalArgumentException("second problem"), Dt3ErrorPhase.CONFIGURATION);
        handler.report(new HttpTransportError("request timed out"), Dt3ErrorPhase.DELIVERY);

        String diagnostics = output.toString();
        assertEquals(1, countOccurrences(diagnostics, "code=DT3_CONFIG_INVALID"));
        assertEquals(1, countOccurrences(diagnostics, "code=DT3_TRANSPORT_TIMEOUT"));
        assertTrue(diagnostics.contains("phase=configuration"));
        assertTrue(diagnostics.contains("retryable=false"));
        assertTrue(diagnostics.contains("occurrences=1"));
        assertEquals(
            Map.of(
                Dt3ErrorCode.CONFIGURATION_INVALID.getValue(), 2L,
                Dt3ErrorCode.TRANSPORT_TIMEOUT.getValue(), 1L
            ),
            handler.snapshot()
        );
    }

    @Test
    public void reportResetsDiagnosticQuotaAfterTheConfiguredWindowRollsOver() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicLong clock = new AtomicLong(1_000L);
        ErrorHandler handler = new ErrorHandler(
            true,
            true,
            new PrintStream(output),
            1,
            null,
            clock::get
        );

        handler.report(new IllegalArgumentException("first problem"), Dt3ErrorPhase.CONFIGURATION);
        handler.report(new IllegalArgumentException("suppressed problem"), Dt3ErrorPhase.CONFIGURATION);
        clock.addAndGet(60_000L);
        handler.report(new IllegalArgumentException("after reset"), Dt3ErrorPhase.CONFIGURATION);

        assertEquals(2, countOccurrences(output.toString(), "code=DT3_CONFIG_INVALID"));
        assertTrue(output.toString().contains("occurrences=3"));
    }

    @Test
    public void reportNeverIncludesRawExceptionDetailsInDiagnostics() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ErrorHandler handler = handler(true, true, output, 10, null);

        handler.report(
            new IllegalArgumentException("secret stack details"),
            Dt3ErrorPhase.CONFIGURATION
        );

        String diagnostics = output.toString();
        assertTrue(diagnostics.contains("message=DT3 SDK handled DT3_CONFIG_INVALID"));
        assertTrue(diagnostics.contains("type=IllegalArgumentException"));
        assertFalse(diagnostics.contains("secret stack details"));
        assertFalse(diagnostics.contains("java.lang.IllegalArgumentException"));
        assertFalse(diagnostics.contains("test_ErrorHandler"));
    }

    @Test
    public void reportDoesNotIncludeStackTraceOrRawExceptionDetails() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ErrorHandler handler = new ErrorHandler(
            true,
            true,
            new PrintStream(output),
            10,
            null
        );

        handler.report(
            new IllegalArgumentException("secret stack details"),
            Dt3ErrorPhase.CONFIGURATION
        );

        String diagnostics = output.toString();
        assertTrue(diagnostics.contains("code=DT3_CONFIG_INVALID"));
        assertTrue(diagnostics.contains("type=IllegalArgumentException"));
        assertFalse(diagnostics.contains("secret stack details"));
        assertFalse(diagnostics.contains("java.lang.IllegalArgumentException"));
        assertFalse(diagnostics.contains("test_ErrorHandler"));
    }

    @Test
    public void handleSuppressesNonValidationFailureWhenFailOpenIsEnabled() {
        ErrorHandler handler = handler(true, false, new ByteArrayOutputStream(), 10, null);

        handler.handle(new HttpTransportError("request timed out"), Dt3ErrorPhase.DELIVERY);

        assertEquals(
            Map.of(Dt3ErrorCode.TRANSPORT_TIMEOUT.getValue(), 1L),
            handler.snapshot()
        );
    }

    @Test
    public void handleRethrowsOriginalFailureWhenFailOpenIsDisabled() {
        ErrorHandler handler = handler(false, false, new ByteArrayOutputStream(), 10, null);
        IllegalArgumentException error = new IllegalArgumentException("invalid configuration");

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> handler.handle(error, Dt3ErrorPhase.CONFIGURATION)
        );

        assertSame(error, thrown);
        assertEquals(
            Map.of(Dt3ErrorCode.CONFIGURATION_INVALID.getValue(), 1L),
            handler.snapshot()
        );
    }

    @Test
    public void constructorRejectsNonPositiveDiagnosticRateLimits() {
        assertThrows(
            IllegalArgumentException.class,
            () -> handler(true, false, new ByteArrayOutputStream(), 0, null)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> handler(true, false, new ByteArrayOutputStream(), -1, null)
        );
    }

    @Test
    public void stdoutLoggerReportsConstructionConfigurationFailuresBeforeRethrowing() {
        AtomicReference<Dt3ErrorReport> observed = new AtomicReference<>();
        SdkConfig config = new SdkConfig();
        config.setExporter("unsupported");
        config.setErrorDiagnosticsEnabled(false);
        config.setErrorObserver(observed::set);

        IllegalArgumentException thrown = assertThrows(
            IllegalArgumentException.class,
            () -> new StdoutLogger(config)
        );

        assertEquals("Unsupported exporter: unsupported", thrown.getMessage());
        assertEquals(Dt3ErrorCode.CONFIGURATION_INVALID, observed.get().getCode());
        assertEquals(Dt3ErrorPhase.CONFIGURATION, observed.get().getPhase());
        assertEquals(1L, observed.get().getOccurrences());
    }

    @Test
    public void stdoutLoggerReportsStrictValidationFailuresAndAlwaysRethrowsThem() {
        AtomicReference<Dt3ErrorReport> observed = new AtomicReference<>();
        SdkConfig config = new SdkConfig();
        config.setValidationMode(com.digitalt3.commons.api.ValidationMode.STRICT);
        config.setFailOpen(true);
        config.setErrorDiagnosticsEnabled(false);
        config.setErrorObserver(observed::set);
        StdoutLogger logger = new StdoutLogger(config);

        assertThrows(LogEventValidationException.class, () -> logger.info(null, Map.of()));

        assertEquals(Dt3ErrorCode.VALIDATION_FAILED, observed.get().getCode());
        assertEquals(Dt3ErrorPhase.VALIDATION, observed.get().getPhase());
    }

    @Test
    public void stdoutLoggerSuppressesOrRethrowsTransportFailuresAccordingToFailOpen() throws Exception {
        int unavailablePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unavailablePort = socket.getLocalPort();
        }
        String endpoint = "http://127.0.0.1:" + unavailablePort + "/logs";

        AtomicReference<Dt3ErrorReport> failOpenObserved = new AtomicReference<>();
        SdkConfig failOpenConfig = httpConfig(endpoint, true, failOpenObserved::set);
        new StdoutLogger(failOpenConfig).info("transport failure", Map.of());
        assertEquals(Dt3ErrorCode.TRANSPORT_UNAVAILABLE, failOpenObserved.get().getCode());
        assertEquals(Dt3ErrorPhase.DELIVERY, failOpenObserved.get().getPhase());

        AtomicReference<Dt3ErrorReport> failClosedObserved = new AtomicReference<>();
        SdkConfig failClosedConfig = httpConfig(endpoint, false, failClosedObserved::set);
        assertThrows(
            HttpTransportError.class,
            () -> new StdoutLogger(failClosedConfig).info("transport failure", Map.of())
        );
        assertEquals(Dt3ErrorCode.TRANSPORT_UNAVAILABLE, failClosedObserved.get().getCode());
        assertEquals(Dt3ErrorPhase.DELIVERY, failClosedObserved.get().getPhase());
    }

    @Test
    public void stdoutLoggerReportsLifecycleErrorsAfterClose() {
        AtomicReference<Dt3ErrorReport> observed = new AtomicReference<>();
        SdkConfig config = new SdkConfig();
        config.setErrorDiagnosticsEnabled(false);
        config.setErrorObserver(observed::set);
        StdoutLogger logger = new StdoutLogger(config);
        logger.close();

        Dt3SdkException thrown = assertThrows(
            Dt3SdkException.class,
            () -> logger.info("after close", Map.of())
        );

        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, thrown.getCode());
        assertEquals(Dt3ErrorPhase.LIFECYCLE, thrown.getPhase());
        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, observed.get().getCode());
        assertEquals(Dt3ErrorPhase.LIFECYCLE, observed.get().getPhase());
    }

    private ErrorHandler handler(
        boolean failOpen,
        boolean diagnosticsEnabled,
        ByteArrayOutputStream output,
        int rateLimitPerMinute,
        java.util.function.Consumer<Dt3ErrorReport> observer
    ) {
        return new ErrorHandler(
            failOpen,
            diagnosticsEnabled,
            new PrintStream(output),
            rateLimitPerMinute,
            observer
        );
    }

    private SdkConfig httpConfig(
        String endpoint,
        boolean failOpen,
        java.util.function.Consumer<Dt3ErrorReport> observer
    ) {
        SdkConfig config = new SdkConfig();
        config.setExporter("http");
        config.setHttpEndpoint(endpoint);
        config.setHttpTimeout(100);
        config.setFailOpen(failOpen);
        config.setErrorDiagnosticsEnabled(false);
        config.setErrorObserver(observer);
        return config;
    }

    private int countOccurrences(String content, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = content.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }
}
