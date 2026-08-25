import { Dt3Error, Dt3ErrorCode, Dt3ErrorPhase } from './errors';

/**
 * Immutable, sanitized information about an SDK failure handled by ErrorHandler.
 */
export interface Dt3ErrorReport {
  readonly code: Dt3ErrorCode;
  readonly phase: Dt3ErrorPhase;
  readonly message: string;
  readonly retryable: boolean;
  readonly error: unknown;
  readonly occurrences: number;
}

/**
 * Application callback invoked after every handled SDK failure.
 */
export type OnErrorCallback = (report: Dt3ErrorReport) => void;

/**
 * Configuration for the centralized SDK error handler.
 */
export interface ErrorHandlerOptions {
  failOpen?: boolean;
  diagnosticsEnabled?: boolean;
  diagnosticsWrite?: (line: string) => void;
  includeStack?: boolean;
  rateLimitPerMinute?: number;
  onError?: OnErrorCallback;
}

/**
 * Classifies, records, diagnoses, and disposes of SDK-internal failures.
 *
 * Diagnostics write to an independent sink rather than the SDK logger, so an
 * exporter failure cannot recursively produce another logger failure.
 */
export class ErrorHandler {
  private readonly failOpen: boolean;
  private readonly diagnosticsEnabled: boolean;
  private readonly writeDiagnosticLine: (line: string) => void;
  private readonly includeStack: boolean;
  private readonly rateLimitPerMinute: number;
  private readonly onError?: OnErrorCallback;
  private readonly counts = new Map<Dt3ErrorCode, number>();
  private readonly windowStart = new Map<Dt3ErrorCode, number>();
  private readonly windowEmitted = new Map<Dt3ErrorCode, number>();

  /**
   * Create an error handler.
   *
   * @param options - Failure-policy, diagnostic, and callback settings.
   * @throws Dt3Error when the rate limit is invalid.
   */
  constructor(options: ErrorHandlerOptions = {}) {
    // Only an omitted setting gets the default. Explicit null is invalid
    // configuration and must reach the positive-integer validation below.
    const rateLimitPerMinute =
      options.rateLimitPerMinute === undefined ? 20 : options.rateLimitPerMinute;
    if (!Number.isInteger(rateLimitPerMinute) || rateLimitPerMinute <= 0) {
      throw new Dt3Error('error.rate_limit_per_minute must be a positive integer', {
        code: Dt3ErrorCode.ConfigurationInvalid,
        retryable: false,
        phase: Dt3ErrorPhase.Configuration,
      });
    }

    this.failOpen = options.failOpen ?? true;
    this.diagnosticsEnabled = options.diagnosticsEnabled ?? true;
    this.writeDiagnosticLine = options.diagnosticsWrite ?? ((line) => process.stderr.write(line));
    this.includeStack = options.includeStack ?? false;
    this.rateLimitPerMinute = rateLimitPerMinute;
    this.onError = options.onError;
  }

  // PUBLIC_INTERFACE
  /**
   * Record and observe a failure without applying the fail-open disposition.
   *
   * @param error - The originating thrown value.
   * @param phase - Pipeline stage that produced the failure.
   * @param context - Non-sensitive diagnostic labels.
   */
  public report(
    error: unknown,
    phase: Dt3ErrorPhase,
    context: Record<string, string> = {},
  ): void {
    const { code, retryable } = this.classify(error);
    const occurrences = (this.counts.get(code) ?? 0) + 1;
    this.counts.set(code, occurrences);

    const report: Dt3ErrorReport = {
      code,
      phase,
      message: ErrorHandler.messageFor(error),
      retryable,
      error,
      occurrences,
    };

    if (this.diagnosticsEnabled && this.allowEmission(code)) {
      this.writeDiagnostic(report, context);
    }

    if (this.onError) {
      try {
        this.onError(report);
      } catch {
        // Application callback failures are intentionally isolated from the
        // logger pipeline and must never recurse through diagnostics.
      }
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Record a failure and apply the configured fail-open disposition.
   *
   * @param error - The originating thrown value.
   * @param phase - Pipeline stage that produced the failure.
   * @param context - Non-sensitive diagnostic labels.
   * @throws The original error when fail-open is disabled.
   */
  public handle(
    error: unknown,
    phase: Dt3ErrorPhase,
    context: Record<string, string> = {},
  ): void {
    this.report(error, phase, context);
    if (!this.failOpen) {
      throw error;
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Execute a synchronous operation under the configured failure policy.
   *
   * @param operation - Synchronous work that may fail.
   * @param phase - Pipeline stage for errors from the operation.
   * @param context - Non-sensitive diagnostic labels.
   * @returns True on success, false when a failure was suppressed.
   */
  public guard(
    operation: () => void,
    phase: Dt3ErrorPhase,
    context: Record<string, string> = {},
  ): boolean {
    try {
      operation();
      return true;
    } catch (error) {
      this.handle(error, phase, context);
      return false;
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Execute asynchronous work under the configured failure policy.
   *
   * @param operation - Asynchronous work that may fail.
   * @param phase - Pipeline stage for errors from the operation.
   * @param context - Non-sensitive diagnostic labels.
   * @returns True on success, false when a failure was suppressed.
   */
  public async guardAsync(
    operation: () => Promise<void>,
    phase: Dt3ErrorPhase,
    context: Record<string, string> = {},
  ): Promise<boolean> {
    try {
      await operation();
      return true;
    } catch (error) {
      this.handle(error, phase, context);
      return false;
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Map a thrown value onto the stable DT3 error taxonomy.
   *
   * @param error - Any value thrown by SDK or Node runtime code.
   * @returns The canonical code and retryability determination.
   */
  public classify(error: unknown): { code: Dt3ErrorCode; retryable: boolean } {
    if (error instanceof Dt3Error) {
      return { code: error.code, retryable: error.retryable };
    }

    if (error instanceof RangeError) {
      return { code: Dt3ErrorCode.MaskingFailed, retryable: false };
    }

    if (error instanceof TypeError) {
      return { code: Dt3ErrorCode.SerializationFailed, retryable: false };
    }

    const nodeCode = ErrorHandler.nodeCodeFor(error);
    if (nodeCode === 'ETIMEDOUT' || nodeCode === 'ESOCKETTIMEDOUT') {
      return { code: Dt3ErrorCode.TransportTimeout, retryable: true };
    }

    if (nodeCode === 'ECONNREFUSED' || nodeCode === 'ENOTFOUND' || nodeCode === 'EPIPE') {
      return { code: Dt3ErrorCode.TransportUnavailable, retryable: true };
    }

    if (nodeCode === 'EACCES' || nodeCode === 'ENOENT') {
      return { code: Dt3ErrorCode.TransportUnavailable, retryable: false };
    }

    return { code: Dt3ErrorCode.Unknown, retryable: false };
  }

  // PUBLIC_INTERFACE
  /**
   * Return cumulative handled-error counts keyed by canonical error code.
   *
   * @returns A snapshot that cannot mutate handler state.
   */
  public snapshot(): Record<string, number> {
    return Object.fromEntries(this.counts.entries());
  }

  private allowEmission(code: Dt3ErrorCode): boolean {
    const now = Date.now();
    const start = this.windowStart.get(code);

    if (start === undefined || now - start >= 60_000) {
      this.windowStart.set(code, now);
      this.windowEmitted.set(code, 1);
      return true;
    }

    const emitted = this.windowEmitted.get(code) ?? 0;
    if (emitted < this.rateLimitPerMinute) {
      this.windowEmitted.set(code, emitted + 1);
      return true;
    }

    return false;
  }

  private writeDiagnostic(report: Dt3ErrorReport, context: Record<string, string>): void {
    const labels = Object.entries(context)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, value]) => `${key}=${value}`)
      .join(' ');
    const type = report.error instanceof Error ? report.error.name : typeof report.error;
    let line =
      `[dt3-sdk] level=error code=${report.code} phase=${report.phase} ` +
      `retryable=${report.retryable} occurrences=${report.occurrences} ` +
      `type=${type} message=${JSON.stringify(report.message)}`;

    if (labels) {
      line = `${line} ${labels}`;
    }

    try {
      this.writeDiagnosticLine(`${line}\n`);
      if (this.includeStack && report.error instanceof Error && report.error.stack) {
        this.writeDiagnosticLine(`${report.error.stack}\n`);
      }
    } catch {
      // The diagnostic stream is best effort. A broken stderr must not turn a
      // fail-open logging failure into an application failure.
    }
  }

  private static messageFor(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
  }

  private static nodeCodeFor(error: unknown): string | undefined {
    if (error === null || typeof error !== 'object') {
      return undefined;
    }

    const code = (error as { code?: unknown }).code;
    return typeof code === 'string' ? code : undefined;
  }
}
