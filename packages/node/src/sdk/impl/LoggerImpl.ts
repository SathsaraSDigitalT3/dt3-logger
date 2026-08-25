import { Logger } from '../../api/Logger';
import { Timer, TimerContext } from '../../api/Timer';
import { Headers, LogContext, LogEvent, Severity, ValidationMode } from '../../api/types';
import { ErrorHandler, OnErrorCallback } from '../ErrorHandler';
import { EventBatcher } from '../batching';
import { FileTransport } from '../FileTransport';
import { HttpTransport } from '../HttpTransport';
import { ensureCorrelationId, getActiveLogContext, withLogContext } from '../context';
import {
  Dt3ConfigurationError,
  Dt3Error,
  Dt3ErrorCode,
  Dt3ErrorPhase,
  Dt3LifecycleError,
  Dt3MaskingError,
} from '../errors';
import { MaskingEngine } from '../masking';
import { OtlpTransport } from '../OtlpTransport';
import { TimerImpl } from '../Timer';
import { LogEventValidator, ValidationError } from '../validation';

/**
 * Concrete DT3 logger that builds, validates, batches, and exports structured events.
 */
export class LoggerImpl implements Logger {
  private readonly config: Record<string, unknown>;
  private readonly exporter: string;
  private readonly failOpen: boolean;
  private readonly errorHandler: ErrorHandler;
  private readonly validationMode: ValidationMode;
  private readonly autoGenerateCorrelationId: boolean;
  private readonly maskingEngine: MaskingEngine;
  private readonly validator: LogEventValidator;
  private readonly fileTransport?: FileTransport;
  private readonly httpTransport?: HttpTransport;
  private readonly otlpTransport?: OtlpTransport;
  private readonly batcher?: EventBatcher;
  private readonly observedAsyncDeliveryFailures = new WeakSet<object>();
  private closed = false;

  /**
   * Create a logger from SDK configuration.
   *
   * @param config - Service metadata, exporter, masking, validation, and batching configuration.
   */
  constructor(config: Record<string, unknown>) {
    this.config = config;
    this.exporter = typeof config.exporter === 'string' ? config.exporter : 'stdout';
    this.failOpen = this.requireBoolean(config.fail_open ?? true, 'fail_open');
    this.errorHandler = new ErrorHandler({
      failOpen: this.failOpen,
      diagnosticsEnabled: config['error.diagnostics.enabled'] !== false,
      includeStack: config['error.include_stack'] === true,
      rateLimitPerMinute:
        config['error.rate_limit_per_minute'] === undefined
          ? undefined
          : (config['error.rate_limit_per_minute'] as number),
      onError:
        typeof config['error.on_error'] === 'function'
          ? (config['error.on_error'] as OnErrorCallback)
          : undefined,
    });
    if (!['stdout', 'file', 'http', 'otlp'].includes(this.exporter)) {
      throw new Dt3ConfigurationError(`Unsupported exporter: ${this.exporter}`);
    }

    this.fileTransport =
      this.exporter === 'file'
        ? new FileTransport(
            typeof config['exporter.file.path'] === 'string' ? config['exporter.file.path'] : '',
          )
        : undefined;
    this.httpTransport =
      this.exporter === 'http'
        ? new HttpTransport(
            typeof config['exporter.http.endpoint'] === 'string'
              ? config['exporter.http.endpoint']
              : '',
            this.resolveHttpTimeout(config),
            this.resolveHeaders(config['exporter.http.headers'], 'exporter.http.headers'),
            (error) => this.observeAsyncDeliveryFailure(error),
            !this.failOpen,
          )
        : undefined;
    this.otlpTransport =
      this.exporter === 'otlp'
        ? new OtlpTransport(
            typeof config['otlp.endpoint'] === 'string' ? config['otlp.endpoint'] : '',
            typeof config['otlp.timeout'] === 'number' ? config['otlp.timeout'] : 10000,
            this.resolveHeaders(config['otlp.headers'], 'otlp.headers'),
            (error) => this.observeAsyncDeliveryFailure(error),
            !this.failOpen,
          )
        : undefined;
    this.validationMode = this.resolveValidationMode(config['validation.mode']);
    this.autoGenerateCorrelationId = this.requireBoolean(
      config['tracing.auto_generate_correlation_id'] ?? false,
      'tracing.auto_generate_correlation_id',
    );
    this.maskingEngine = new MaskingEngine({
      sensitiveFields: Array.isArray(config['masking.fields'])
        ? config['masking.fields'].filter((field): field is string => typeof field === 'string')
        : undefined,
      replacementValue:
        typeof config['masking.replacement_value'] === 'string'
          ? config['masking.replacement_value']
          : undefined,
      trackMaskedFields: config['masking.track_masked_fields'] === true,
      enabled: config['masking.enabled'] !== false,
    });
    this.validator = new LogEventValidator();

    if (this.requireBoolean(config['batching.enabled'] ?? false, 'batching.enabled')) {
      const maxSize = this.resolveBatchingNumber(config['batching.max_size'], 'batching.max_size', 100);
      const flushIntervalMs = this.resolveBatchingNumber(
        config['batching.flush_interval_ms'],
        'batching.flush_interval_ms',
        5000,
      );

      this.batcher = new EventBatcher(
        (event) => this.exportWithPolicy(event),
        maxSize,
        flushIntervalMs,
        (error) => this.reportBatchingFailure(error),
      );
    }
  }

  private resolveBatchingNumber(value: unknown, key: string, defaultValue: number): number {
    if (value === undefined) {
      return defaultValue;
    }
    if (typeof value !== 'number') {
      throw new Error(`${key} must be a number`);
    }
    return value;
  }

  private resolveValidationMode(value: unknown): ValidationMode {
    const normalizedMode = typeof value === 'string' ? value.toUpperCase() : ValidationMode.LENIENT;

    if (!Object.values(ValidationMode).includes(normalizedMode as ValidationMode)) {
      throw new Error('validation.mode must be one of STRICT, LENIENT, or OFF');
    }

    return normalizedMode as ValidationMode;
  }

  private resolveHttpTimeout(config: Record<string, unknown>): number {
    const canonicalTimeout = config['exporter.http.timeout'];
    const legacyTimeout = config['exporter.http.timeout_ms'];

    if (canonicalTimeout !== undefined) {
      if (typeof canonicalTimeout !== 'number') {
        throw new Error('exporter.http.timeout must be a number of milliseconds');
      }
      return canonicalTimeout;
    }

    if (legacyTimeout !== undefined) {
      if (typeof legacyTimeout !== 'number') {
        throw new Error('exporter.http.timeout_ms must be a number of milliseconds');
      }
      return legacyTimeout;
    }

    return 5000;
  }

  private resolveHeaders(value: unknown, key: string): Headers | undefined {
    if (value === undefined) {
      return undefined;
    }
    if (value === null || Array.isArray(value) || typeof value !== 'object') {
      throw new Error(`${key} must be a mapping of safe string header names to string values`);
    }

    const headers = Object.entries(value);
    if (
      headers.some(
        ([name, headerValue]) =>
          name.trim().length === 0 ||
          /[\r\n]/.test(name) ||
          typeof headerValue !== 'string' ||
          /[\r\n]/.test(headerValue),
      )
    ) {
      throw new Error(`${key} must be a mapping of safe string header names to string values`);
    }

    return Object.fromEntries(headers) as Headers;
  }

  private ensureOpen(): void {
    if (this.closed) {
      const error = new Dt3LifecycleError('Logger is closed');
      this.errorHandler.report(error, Dt3ErrorPhase.Lifecycle, { exporter: this.exporter });
      throw error;
    }
  }

  /**
   * Verify that the logger remains usable by a timer lifecycle operation.
   *
   * This intentionally delegates to the existing logger lifecycle gate so
   * timers fail with the same documented closed-logger error as log methods.
   */
  public ensureTimerLoggerOpen(): void {
    this.ensureOpen();
  }

  private requireBoolean(value: unknown, key: string): boolean {
    if (typeof value !== 'boolean') {
      throw new Dt3ConfigurationError(`${key} must be a boolean`);
    }
    return value;
  }

  private handleDeliveryFailure(error: unknown): void {
    if (this.wasAsyncDeliveryFailureObserved(error)) {
      // The transport observer has already emitted diagnostics, invoked the
      // callback, and incremented the snapshot. Retain fail-closed behavior
      // by surfacing the original rejection from flush without reporting it
      // a second time.
      if (!this.failOpen) {
        throw error;
      }
      return;
    }

    this.errorHandler.handle(error, Dt3ErrorPhase.Delivery, { exporter: this.exporter });
  }

  private observeAsyncDeliveryFailure(error: unknown): void {
    // Rejection observers cannot safely throw. `flush()` remains responsible
    // for surfacing fail-closed transport failures to application code.
    if (this.isWeaklyReferenceable(error)) {
      this.observedAsyncDeliveryFailures.add(error);
    }
    this.errorHandler.report(error, Dt3ErrorPhase.Delivery, { exporter: this.exporter });
  }

  private wasAsyncDeliveryFailureObserved(error: unknown): boolean {
    return this.isWeaklyReferenceable(error) && this.observedAsyncDeliveryFailures.has(error);
  }

  private isWeaklyReferenceable(value: unknown): value is object {
    return (typeof value === 'object' && value !== null) || typeof value === 'function';
  }

  private reportBatchingFailure(error: unknown): void {
    this.errorHandler.report(error, Dt3ErrorPhase.Batching, { exporter: this.exporter });
  }

  private export(event: LogEvent): void {
    if (this.exporter === 'stdout') {
      console.log(JSON.stringify(event));
    } else if (this.exporter === 'file') {
      this.fileTransport?.export(event);
    } else if (this.exporter === 'http') {
      this.httpTransport?.export(event);
    } else if (this.exporter === 'otlp') {
      this.otlpTransport?.export(event);
    }
  }

  private exportWithPolicy(event: LogEvent): void {
    this.errorHandler.guard(() => this.export(event), Dt3ErrorPhase.Delivery, {
      exporter: this.exporter,
    });
  }

  private log(
    level: string,
    message: string,
    context?: Record<string, unknown>,
    error?: Error,
  ): void {
    this.ensureOpen();

    // Explicit event context intentionally overrides values inherited from the
    // active execution scope. Logger-owned fields are asserted below.
    const callerContext = {
      ...ensureCorrelationId(getActiveLogContext(), this.autoGenerateCorrelationId),
      ...context,
    };
    const eventName =
      typeof callerContext['event.name'] === 'string' ? callerContext['event.name'] : 'GENERIC_EVENT';
    const logEvent: Record<string, unknown> = { ...callerContext };

    // The logger owns these fields; setting them after context prevents caller
    // input from overriding method-selected severity or logger metadata.
    Object.assign(logEvent, {
      timestamp: new Date().toISOString(),
      severity: level,
      message,
      'event.name': eventName,
      'schema.version':
        typeof this.config['schema.version'] === 'string' ? this.config['schema.version'] : '1.0.0',
      'sdk.name': typeof this.config['sdk.name'] === 'string' ? this.config['sdk.name'] : '@digitalt3/commons',
      'sdk.version': typeof this.config['sdk.version'] === 'string' ? this.config['sdk.version'] : '0.1.0',
    });

    for (const field of ['service.name', 'service.version', 'deployment.environment'] as const) {
      if (typeof this.config[field] === 'string') {
        logEvent[field] = this.config[field];
      }
    }

    if (error) {
      logEvent['error.type'] = error.name;
      logEvent['error.message'] = error.message;
      logEvent['error.stack'] = error.stack;
      if (error instanceof Dt3Error) {
        logEvent['error.code'] = error.code;
        logEvent['error.retryable'] = error.retryable;
      } else {
        const metadata = error as Error & { code?: unknown; retryable?: unknown };
        if (typeof metadata.code === 'string') {
          logEvent['error.code'] = metadata.code;
        }
        if (typeof metadata.retryable === 'boolean') {
          logEvent['error.retryable'] = metadata.retryable;
        }
      }
    }

    let maskedEvent: Record<string, unknown>;
    let maskedFields: string[];
    try {
      const maskedResult = this.maskingEngine.mask(logEvent);
      maskedEvent = maskedResult.data;
      maskedFields = maskedResult.maskedFields;
    } catch (maskingError) {
      const handledError = new Dt3MaskingError('masking failed for the supplied context', maskingError);
      this.errorHandler.handle(handledError, Dt3ErrorPhase.Masking, { exporter: this.exporter });
      return;
    }

    if (maskedFields.length > 0) {
      maskedEvent['dt3.security.masked_fields'] = maskedFields;
    }

    const validationResult = this.validator.validate(maskedEvent, this.validationMode);
    if (!validationResult.valid) {
      if (validationResult.mode === ValidationMode.STRICT) {
        const validationError = new ValidationError(validationResult.errors);
        this.errorHandler.report(
          new Dt3Error(validationError.message, {
            code: Dt3ErrorCode.ValidationFailed,
            retryable: false,
            phase: Dt3ErrorPhase.Validation,
            cause: validationError,
          }),
          Dt3ErrorPhase.Validation,
          { mode: 'STRICT' },
        );
        throw validationError;
      }

      if (validationResult.mode === ValidationMode.LENIENT) {
        maskedEvent['dt3.validation.errors'] = validationResult.errors;
      }
    }

    if (this.batcher) {
      this.batcher.add(maskedEvent as LogEvent);
    } else {
      this.exportWithPolicy(maskedEvent as LogEvent);
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Export a DEBUG log event.
   *
   * @param message - Human-readable event message.
   * @param context - Optional structured event context.
   */
  public debug(message: string, context?: Record<string, unknown>): void {
    this.log('DEBUG', message, context);
  }

  // PUBLIC_INTERFACE
  /**
   * Run a callback with trace and correlation context attached to all logs
   * created in the callback's execution scope.
   *
   * @param context - Convenience trace and correlation identifiers.
   * @param callback - Synchronous or asynchronous work to run in the scope.
   * @returns The callback result.
   */
  public withContext<T>(context: LogContext, callback: () => T): T {
    return withLogContext(context, callback);
  }

  // PUBLIC_INTERFACE
  /**
   * Export an INFO log event.
   *
   * @param message - Human-readable event message.
   * @param context - Optional structured event context.
   */
  public info(message: string, context?: Record<string, unknown>): void {
    this.log('INFO', message, context);
  }

  // PUBLIC_INTERFACE
  /**
   * Export a WARN log event.
   *
   * @param message - Human-readable event message.
   * @param context - Optional structured event context.
   */
  public warn(message: string, context?: Record<string, unknown>): void {
    this.log('WARN', message, context);
  }

  // PUBLIC_INTERFACE
  /**
   * Export an ERROR log event with optional error details.
   *
   * @param message - Human-readable event message.
   * @param error - Optional error to include in structured event fields.
   * @param context - Optional structured event context.
   */
  public error(message: string, error?: Error, context?: Record<string, unknown>): void {
    this.log('ERROR', message, context, error);
  }

  // PUBLIC_INTERFACE
  /**
   * Return cumulative SDK-internal error counts by canonical DT3 error code.
   *
   * @returns A snapshot of handled-error counts since logger construction.
   */
  public errorSnapshot(): Record<string, number> {
    return this.errorHandler.snapshot();
  }

  // PUBLIC_INTERFACE
  /**
   * Export a FATAL log event through the canonical processing pipeline.
   *
   * @param message - Human-readable event message.
   * @param context - Optional structured event context.
   */
  public fatal(message: string, context?: Record<string, unknown>): void {
    this.log(Severity.FATAL, message, context);
  }

  // PUBLIC_INTERFACE
  /**
   * Process a supplied canonical event through the normal logger pipeline.
   *
   * The method preserves supplied schema-compatible fields while retaining
   * logger ownership of event timestamps, service metadata, and severity.
   *
   * @param event - Canonical event object. It is copied and never mutated.
   * @throws TypeError if the event is missing a string message.
   * @throws Error if the event severity is unsupported.
   */
  public event(event: LogEvent): void {
    if (event === null || typeof event !== 'object' || Array.isArray(event)) {
      throw new TypeError('event must be an object');
    }

    const suppliedEvent = { ...event } as Record<string, unknown>;
    const message = suppliedEvent.message;
    if (typeof message !== 'string') {
      throw new TypeError('event.message must be a string');
    }

    delete suppliedEvent.message;
    const severity = typeof suppliedEvent.severity === 'string' ? suppliedEvent.severity.toUpperCase() : 'INFO';
    delete suppliedEvent.severity;
    if (!Object.values(Severity).includes(severity as Severity)) {
      throw new Error('event.severity must be a supported severity');
    }

    this.log(severity, message, suppliedEvent);
  }

  // PUBLIC_INTERFACE
  /**
   * Create an unstarted timer that emits a canonical INFO completion event.
   *
   * Completion delegates to {@link info}, preserving active context, masking,
   * validation, batching, and the configured transport/exporter behavior.
   *
   * @param name - Non-blank canonical event name for the completion event.
   * @param context - Optional event metadata for the completion event.
   * @returns A new unstarted timer.
   * @throws Error when the logger is closed or the timer name is invalid.
   */
  public createTimer(name: string, context?: TimerContext): Timer {
    this.ensureOpen();
    return new TimerImpl(this, name, context);
  }

  // PUBLIC_INTERFACE
  /**
   * Flush buffered events and settle delivery work initiated before the flush boundary.
   *
   * @returns A promise that rejects only when a delivery failure is configured
   * to fail closed.
   */
  public async flush(): Promise<void> {
    this.ensureOpen();

    try {
      this.batcher?.flush();
      this.fileTransport?.flush();
      await this.httpTransport?.flush();
      await this.otlpTransport?.flush();
    } catch (error) {
      this.handleDeliveryFailure(error);
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Flush remaining events, close the active transport, and prevent future logging.
   *
   * Closing is idempotent. Subsequent logging and flush calls fail with a
   * documented terminal-state error.
   */
  public close(): void {
    if (this.closed) {
      return;
    }

    this.closed = true;
    try {
      this.batcher?.close();
      this.fileTransport?.flush();
    } catch (error) {
      this.handleDeliveryFailure(error);
    }
    this.httpTransport?.close();
    this.otlpTransport?.close();
  }
}
