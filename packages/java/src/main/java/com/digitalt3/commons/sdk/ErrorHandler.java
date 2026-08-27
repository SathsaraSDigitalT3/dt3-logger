package com.digitalt3.commons.sdk;

import java.io.PrintStream;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Centralizes SDK failure classification, diagnostics, and fail-open policy.
 *
 * <p>Diagnostics are written directly to a configured stream rather than through
 * the DT3 logger, preventing recursive error logging. Observer failures and
 * diagnostic stream failures are intentionally ignored so they cannot change the
 * disposition of the original SDK operation.</p>
 *
 * @since 0.1.0
 */
public final class ErrorHandler {
    private static final long DIAGNOSTIC_WINDOW_MILLIS = 60_000L;

    private final boolean failOpen;
    private final boolean diagnosticsEnabled;
    private final PrintStream diagnosticsStream;
    private final int rateLimitPerMinute;
    private final Consumer<Dt3ErrorReport> observer;
    private final boolean includeStack;
    private final LongSupplier currentTimeMillis;
    private final Map<Dt3ErrorCode, Long> counts = new EnumMap<>(Dt3ErrorCode.class);
    private final Map<Dt3ErrorCode, Long> windowStarts = new EnumMap<>(Dt3ErrorCode.class);
    private final Map<Dt3ErrorCode, Integer> windowEmissions = new EnumMap<>(Dt3ErrorCode.class);

    /**
     * Create an error handler using safe default diagnostics.
     *
     * @param failOpen whether handled non-validation failures are suppressed
     * @param diagnosticsEnabled whether diagnostic lines are written to stderr
     * @param rateLimitPerMinute maximum diagnostic lines per error code each minute
     * @param observer optional application observer for handled error reports
     */
    public ErrorHandler(
        boolean failOpen,
        boolean diagnosticsEnabled,
        int rateLimitPerMinute,
        Consumer<Dt3ErrorReport> observer
    ) {
        this(
            failOpen,
            diagnosticsEnabled,
            System.err,
            rateLimitPerMinute,
            observer,
            false,
            System::currentTimeMillis
        );
    }

    ErrorHandler(
        boolean failOpen,
        boolean diagnosticsEnabled,
        PrintStream diagnosticsStream,
        int rateLimitPerMinute,
        Consumer<Dt3ErrorReport> observer
    ) {
        this(
            failOpen,
            diagnosticsEnabled,
            diagnosticsStream,
            rateLimitPerMinute,
            observer,
            false,
            System::currentTimeMillis
        );
    }

    public ErrorHandler(
        boolean failOpen,
        boolean diagnosticsEnabled,
        int rateLimitPerMinute,
        Consumer<Dt3ErrorReport> observer,
        boolean includeStack
    ) {
        this(
            failOpen,
            diagnosticsEnabled,
            System.err,
            rateLimitPerMinute,
            observer,
            includeStack,
            System::currentTimeMillis
        );
    }

    ErrorHandler(
        boolean failOpen,
        boolean diagnosticsEnabled,
        PrintStream diagnosticsStream,
        int rateLimitPerMinute,
        Consumer<Dt3ErrorReport> observer,
        LongSupplier currentTimeMillis
    ) {
        this(
            failOpen,
            diagnosticsEnabled,
            diagnosticsStream,
            rateLimitPerMinute,
            observer,
            false,
            currentTimeMillis
        );
    }

    ErrorHandler(
        boolean failOpen,
        boolean diagnosticsEnabled,
        PrintStream diagnosticsStream,
        int rateLimitPerMinute,
        Consumer<Dt3ErrorReport> observer,
        boolean includeStack
    ) {
        this(
            failOpen,
            diagnosticsEnabled,
            diagnosticsStream,
            rateLimitPerMinute,
            observer,
            includeStack,
            System::currentTimeMillis
        );
    }

    ErrorHandler(
        boolean failOpen,
        boolean diagnosticsEnabled,
        PrintStream diagnosticsStream,
        int rateLimitPerMinute,
        Consumer<Dt3ErrorReport> observer,
        boolean includeStack,
        LongSupplier currentTimeMillis
    ) {
        if (rateLimitPerMinute <= 0) {
            throw new IllegalArgumentException(
                "error.rate_limit_per_minute must be a positive integer"
            );
        }

        this.failOpen = failOpen;
        this.diagnosticsEnabled = diagnosticsEnabled;
        this.diagnosticsStream = Objects.requireNonNull(
            diagnosticsStream,
            "diagnosticsStream must not be null"
        );
        this.rateLimitPerMinute = rateLimitPerMinute;
        this.observer = observer;
        this.includeStack = includeStack;
        this.currentTimeMillis = Objects.requireNonNull(
            currentTimeMillis,
            "currentTimeMillis must not be null"
        );
    }

    // PUBLIC_INTERFACE
    /**
     * Record and observe a failure without applying the configured disposition.
     *
     * @param error failure to classify and report
     * @param phase caller-supplied pipeline phase in which the failure was handled;
     *              this value is preserved even when {@code error} is typed
     */
    public void report(RuntimeException error, Dt3ErrorPhase phase) {
        report(error, phase, Collections.emptyMap());
    }

    // PUBLIC_INTERFACE
    /**
     * Record and observe a failure with sanitized diagnostic context without
     * applying the configured disposition.
     *
     * <p>Only {@link RuntimeException} is accepted because SDK call paths
     * surface recoverable failures as runtime exceptions; {@link Error} values
     * are intentionally not suppressed.</p>
     *
     * @param error failure to classify and report
     * @param phase caller-supplied pipeline phase in which the failure was handled;
     *              this value is preserved even when {@code error} is typed
     * @param context non-sensitive diagnostic labels
     */
    public void report(
        RuntimeException error,
        Dt3ErrorPhase phase,
        Map<String, String> context
    ) {
        Objects.requireNonNull(error, "error must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Map<String, String> safeContext = sanitizeContext(context);

        Classification classification = classifyInternal(error);
        Dt3ErrorReport report;
        boolean shouldEmit;
        synchronized (this) {
            long occurrences = counts.getOrDefault(classification.code(), 0L) + 1L;
            counts.put(classification.code(), occurrences);
            shouldEmit = allowDiagnosticLocked(classification.code());
            report = new Dt3ErrorReport(
                classification.code(),
                phase,
                safeMessage(classification.code()),
                classification.retryable(),
                error.getClass().getSimpleName(),
                occurrences
            );
        }

        if (diagnosticsEnabled && shouldEmit) {
            writeDiagnostic(report, error, safeContext);
        }
        if (observer != null) {
            try {
                observer.accept(report);
            } catch (RuntimeException ignored) {
                // Observers must not recurse into or disrupt SDK error handling.
            }
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Record a failure and apply the configured fail-open policy.
     *
     * @param error failure to classify and handle
     * @param phase caller-supplied pipeline phase in which the failure was handled;
     *              this value is preserved even when {@code error} is typed
     * @throws RuntimeException when fail-open is disabled
     */
    public void handle(RuntimeException error, Dt3ErrorPhase phase) {
        handle(error, phase, Collections.emptyMap());
    }

    // PUBLIC_INTERFACE
    /**
     * Record a failure with sanitized diagnostic context and apply the
     * configured fail-open policy.
     *
     * @param error failure to classify and handle
     * @param phase caller-supplied pipeline phase in which the failure was handled;
     *              this value is preserved even when {@code error} is typed
     * @param context non-sensitive diagnostic labels
     * @throws RuntimeException when fail-open is disabled
     */
    public void handle(
        RuntimeException error,
        Dt3ErrorPhase phase,
        Map<String, String> context
    ) {
        report(error, phase, context);
        if (!failOpen) {
            throw error;
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Classify an SDK exception into a stable code and retryability decision.
     *
     * <p>For typed SDK exceptions, the returned report uses the exception's
     * intrinsic phase. In contrast, {@link #report(RuntimeException,
     * Dt3ErrorPhase)} and {@link #handle(RuntimeException, Dt3ErrorPhase)}
     * preserve their caller-supplied phase because it identifies the pipeline
     * location that handled the failure.</p>
     *
     * @param error failure to classify
     * @return immutable classification report with zero occurrences
     */
    public Dt3ErrorReport classify(RuntimeException error) {
        Objects.requireNonNull(error, "error must not be null");
        Classification classification = classifyInternal(error);
        Dt3ErrorPhase phase = error instanceof Dt3SdkException typedError
            ? typedError.getPhase()
            : Dt3ErrorPhase.DELIVERY;
        return new Dt3ErrorReport(
            classification.code(),
            phase,
            safeMessage(classification.code()),
            classification.retryable(),
            error.getClass().getSimpleName(),
            0L,
            true
        );
    }

    // PUBLIC_INTERFACE
    /**
     * Return cumulative handled-error counts keyed by stable error-code wire values.
     *
     * @return immutable error count snapshot
     */
    public synchronized Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        for (Map.Entry<Dt3ErrorCode, Long> entry : counts.entrySet()) {
            snapshot.put(entry.getKey().getValue(), entry.getValue());
        }
        return Map.copyOf(snapshot);
    }

    private Classification classifyInternal(RuntimeException error) {
        if (error instanceof Dt3SdkException typedError) {
            return new Classification(typedError.getCode(), typedError.isRetryable());
        }
        if (error instanceof LogEventValidationException) {
            return new Classification(Dt3ErrorCode.VALIDATION_FAILED, false);
        }
        if (error instanceof MaskingException) {
            return new Classification(Dt3ErrorCode.MASKING_FAILED, false);
        }
        if (error instanceof java.io.UncheckedIOException) {
            // Filesystem failures require configuration or environmental
            // remediation (for example, permissions, an invalid path, or
            // exhausted storage) rather than an immediate retry.
            return new Classification(Dt3ErrorCode.FILE_WRITE_FAILED, false);
        }
        if (error instanceof IllegalArgumentException) {
            return new Classification(Dt3ErrorCode.CONFIGURATION_INVALID, false);
        }
        if (error instanceof IllegalStateException) {
            return new Classification(Dt3ErrorCode.BATCH_ABORTED, false);
        }
        return new Classification(Dt3ErrorCode.UNKNOWN, false);
    }

    private boolean allowDiagnosticLocked(Dt3ErrorCode code) {
        long now = currentTimeMillis.getAsLong();
        Long windowStart = windowStarts.get(code);
        if (windowStart == null || now - windowStart >= DIAGNOSTIC_WINDOW_MILLIS) {
            windowStarts.put(code, now);
            windowEmissions.put(code, 1);
            return true;
        }

        int emitted = windowEmissions.getOrDefault(code, 0);
        if (emitted < rateLimitPerMinute) {
            windowEmissions.put(code, emitted + 1);
            return true;
        }
        return false;
    }

    private void writeDiagnostic(
        Dt3ErrorReport report,
        RuntimeException error,
        Map<String, String> context
    ) {
        try {
            String errorType = report.getErrorType();
            StringBuilder line = new StringBuilder(
                "[dt3-sdk] level=error"
                    + " code=" + report.getCode().getValue()
                    + " phase=" + report.getPhase().getValue()
                    + " retryable=" + report.isRetryable()
                    + " occurrences=" + report.getOccurrences()
                    + " type=" + errorType
            );
            for (Map.Entry<String, String> entry : context.entrySet()) {
                line.append(' ')
                    .append(entry.getKey())
                    .append('=')
                    .append(entry.getValue());
            }
            line.append(" message=").append(report.getMessage());
            diagnosticsStream.println(line);
            if (includeStack) {
                diagnosticsStream.print(stackTrace(error));
            }
            diagnosticsStream.flush();
        } catch (RuntimeException ignored) {
            // Diagnostics must never change the logger's fail-open behavior.
        }
    }

    private String safeMessage(Dt3ErrorCode code) {
        return "DT3 SDK handled " + code.getValue();
    }

    private Map<String, String> sanitizeContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, String> sanitized = new LinkedHashMap<>();
        context.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> sanitized.put(
                sanitizeDiagnosticLabel(entry.getKey()),
                sanitizeDiagnosticLabel(entry.getValue())
            ));
        return Map.copyOf(sanitized);
    }

    private String sanitizeDiagnosticLabel(String value) {
        return Objects.toString(value, "").replaceAll("[\\r\\n=]", "_");
    }

    private String stackTrace(RuntimeException error) {
        java.io.StringWriter output = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(output));
        return output.toString();
    }

    private record Classification(Dt3ErrorCode code, boolean retryable) {
    }
}
