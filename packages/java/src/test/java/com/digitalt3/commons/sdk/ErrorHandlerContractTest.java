package com.digitalt3.commons.sdk;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Contract tests for canonical DT3 error taxonomy and disposition behavior.
 */
public class ErrorHandlerContractTest {

    @Test
    public void exposesCanonicalCrossLanguageErrorCodeWireValues() {
        assertEquals("DT3_CONFIG_INVALID", Dt3ErrorCode.CONFIGURATION_INVALID.getValue());
        assertEquals("DT3_EXPORTER_UNSUPPORTED", Dt3ErrorCode.EXPORTER_UNSUPPORTED.getValue());
        assertEquals("DT3_TRANSPORT_REJECTED", Dt3ErrorCode.TRANSPORT_REJECTED.getValue());
        assertEquals("DT3_BATCH_ABORTED", Dt3ErrorCode.BATCH_ABORTED.getValue());
        assertEquals("DT3_LIFECYCLE_CLOSED", Dt3ErrorCode.LIFECYCLE_CLOSED.getValue());
    }

    @Test
    public void exposesCanonicalCrossLanguagePhaseWireValues() {
        assertEquals("configuration", Dt3ErrorPhase.CONFIGURATION.getValue());
        assertEquals("enrichment", Dt3ErrorPhase.ENRICHMENT.getValue());
        assertEquals("delivery", Dt3ErrorPhase.DELIVERY.getValue());
        assertEquals("lifecycle", Dt3ErrorPhase.LIFECYCLE.getValue());
    }

    @Test
    public void typedTransportClassificationDoesNotParseMessages() {
        ErrorHandler handler = new ErrorHandler(true, false, 20, null);

        Dt3ErrorReport timeout = handler.classify(
            new HttpTransportError(
                "safe arbitrary description",
                null,
                Dt3ErrorCode.TRANSPORT_TIMEOUT,
                true
            )
        );
        Dt3ErrorReport rejection = handler.classify(new OtlpTransportError(503));

        assertEquals(Dt3ErrorCode.TRANSPORT_TIMEOUT, timeout.getCode());
        assertTrue(timeout.isRetryable());
        assertEquals(Dt3ErrorCode.TRANSPORT_REJECTED, rejection.getCode());
        assertTrue(rejection.isRetryable());
    }

    @Test
    public void scheduledBatchTransportFailureIsClassifiedAsNonRetryableBatchAbort() {
        List<Dt3ErrorReport> reports = new ArrayList<>();
        List<RuntimeException> timerFailures = new ArrayList<>();
        ErrorHandler handler = new ErrorHandler(true, false, 20, reports::add);
        HttpTransportError transportFailure = new HttpTransportError(
            "request timed out",
            null,
            Dt3ErrorCode.TRANSPORT_TIMEOUT,
            true
        );
        EventBatcher batcher = new EventBatcher(
            event -> {
                throw transportFailure;
            },
            failure -> {
                timerFailures.add(failure);
                handler.report(failure, Dt3ErrorPhase.BATCHING);
            },
            2,
            1
        );

        batcher.add(Map.of("event", "first"));
        try {
            Thread.sleep(100);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting scheduled batch failure", exception);
        }

        assertEquals(1, reports.size());
        Dt3ErrorReport report = reports.get(0);
        assertEquals(Dt3ErrorCode.BATCH_ABORTED, report.getCode());
        assertEquals(Dt3ErrorPhase.BATCHING, report.getPhase());
        assertFalse(report.isRetryable());
        assertEquals("Dt3SdkException", report.getErrorType());
        assertEquals(1, timerFailures.size());
        assertEquals(transportFailure, timerFailures.get(0).getCause());

        Dt3SdkException lifecycleFailure = assertThrows(
            Dt3SdkException.class,
            () -> batcher.add(Map.of("event", "later"))
        );
        assertEquals(Dt3ErrorCode.LIFECYCLE_CLOSED, lifecycleFailure.getCode());
        assertEquals(Dt3ErrorPhase.LIFECYCLE, lifecycleFailure.getPhase());
    }

    @Test
    public void typedMaskingFailureIsClassifiedWithoutMessageMatching() {
        ErrorHandler handler = new ErrorHandler(true, false, 20, null);

        Dt3ErrorReport report = handler.classify(
            new MaskingException("safe arbitrary description")
        );

        assertEquals(Dt3ErrorCode.MASKING_FAILED, report.getCode());
        assertFalse(report.isRetryable());
    }

    @Test
    public void failOpenReportsAndSuppressesHandledFailure() {
        List<Dt3ErrorReport> reports = new ArrayList<>();
        ErrorHandler handler = new ErrorHandler(true, false, 20, reports::add);

        handler.handle(
            new Dt3SdkException(
                "file unavailable",
                Dt3ErrorCode.FILE_WRITE_FAILED,
                false,
                Dt3ErrorPhase.DELIVERY
            ),
            Dt3ErrorPhase.DELIVERY
        );

        assertEquals(1, reports.size());
        assertEquals(Dt3ErrorCode.FILE_WRITE_FAILED, reports.get(0).getCode());
        assertFalse(reports.get(0).isRetryable());
        assertEquals(Long.valueOf(1L), handler.snapshot().get("DT3_FILE_WRITE_FAILED"));
    }

    @Test
    public void failClosedReportsThenRethrowsHandledFailure() {
        List<Dt3ErrorReport> reports = new ArrayList<>();
        ErrorHandler handler = new ErrorHandler(false, false, 20, reports::add);
        Dt3SdkException failure = new Dt3SdkException(
            "delivery failed",
            Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
            true,
            Dt3ErrorPhase.DELIVERY
        );

        assertThrows(
            Dt3SdkException.class,
            () -> handler.handle(failure, Dt3ErrorPhase.DELIVERY)
        );

        assertEquals(1, reports.size());
        assertEquals(Dt3ErrorCode.TRANSPORT_UNAVAILABLE, reports.get(0).getCode());
        assertFalse(reports.get(0).toFields().isEmpty());
    }

    @Test
    public void usesTheExplicitHandlingPhaseAndExposesCanonicalReportFields() {
        ErrorHandler handler = new ErrorHandler(true, false, 20, null);
        Dt3ErrorReport report = handler.classify(
            new Dt3SdkException(
                "delivery failed",
                Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
                true,
                Dt3ErrorPhase.DELIVERY
            )
        );

        assertEquals(Dt3ErrorPhase.DELIVERY, report.getPhase());
        assertEquals("Dt3SdkException", report.getErrorType());
        assertEquals("delivery", report.toFields().get("dt3.error.phase"));
        assertFalse(report.toFields().containsKey("error.phase"));
        assertFalse(report.toFields().containsValue("delivery failed"));
    }

    @Test
    public void reportPreservesCallerPhaseWhileClassifyUsesTypedPhase() {
        List<Dt3ErrorReport> reports = new ArrayList<>();
        ErrorHandler handler = new ErrorHandler(true, false, 20, reports::add);
        Dt3SdkException failure = new Dt3SdkException(
            "delivery failed",
            Dt3ErrorCode.TRANSPORT_UNAVAILABLE,
            true,
            Dt3ErrorPhase.DELIVERY
        );

        handler.report(failure, Dt3ErrorPhase.BATCHING);

        assertEquals(Dt3ErrorPhase.BATCHING, reports.get(0).getPhase());
        assertEquals(Dt3ErrorPhase.DELIVERY, handler.classify(failure).getPhase());
    }

    @Test
    public void sanitizesDiagnosticContextInContextAwareReportingApi() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ErrorHandler handler = new ErrorHandler(
            true,
            true,
            new PrintStream(output),
            20,
            null
        );

        handler.report(
            new IllegalStateException("sensitive failure"),
            Dt3ErrorPhase.DELIVERY,
            Map.of("request\nid", "trusted\nvalue=forged")
        );

        String diagnostic = output.toString();
        assertTrue(diagnostic.contains("request_id=trusted_value_forged"));
        assertFalse(diagnostic.contains("\nvalue="));
        assertFalse(diagnostic.contains("sensitive failure"));
    }
}
