import { Logger } from '../../api/Logger';
import { Headers, LogEvent, ValidationMode } from '../../api/types';
import { FileTransport } from '../FileTransport';
import { HttpTransport } from '../HttpTransport';
import { MaskingEngine } from '../masking';
import { OtlpTransport } from '../OtlpTransport';
import { LogEventValidator, ValidationError } from '../validation';

/**
 * Concrete DT3 logger that builds, validates, and exports structured events.
 */
export class LoggerImpl implements Logger {
  private readonly config: Record<string, unknown>;
  private readonly exporter: string;
  private readonly failOpen: boolean;
  private readonly validationMode: ValidationMode;
  private readonly maskingEngine: MaskingEngine;
  private readonly validator: LogEventValidator;
  private readonly fileTransport?: FileTransport;
  private readonly httpTransport?: HttpTransport;
  private readonly otlpTransport?: OtlpTransport;
  private closed = false;

  /**
   * Create a logger from SDK configuration.
   *
   * @param config - Service metadata, exporter, masking, and validation configuration.
   */
  constructor(config: Record<string, unknown>) {
    this.config = config;
    this.exporter = typeof config.exporter === 'string' ? config.exporter : 'stdout';
    this.failOpen = config.fail_open !== false;
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
          )
        : undefined;
    this.otlpTransport =
      this.exporter === 'otlp'
        ? new OtlpTransport(
            typeof config['otlp.endpoint'] === 'string' ? config['otlp.endpoint'] : '',
            typeof config['otlp.timeout'] === 'number' ? config['otlp.timeout'] : 10000,
            this.resolveHeaders(config['otlp.headers'], 'otlp.headers'),
          )
        : undefined;
    this.validationMode = this.resolveValidationMode(config['validation.mode']);
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
      throw new Error('Logger is closed');
    }
  }

  private handleDeliveryFailure(error: unknown): void {
    if (!this.failOpen) {
      throw error;
    }
  }

  private log(
    level: string,
    message: string,
    context?: Record<string, unknown>,
    error?: Error,
  ): void {
    this.ensureOpen();

    const eventName =
      typeof context?.['event.name'] === 'string' ? context['event.name'] : 'GENERIC_EVENT';
    const logEvent: Record<string, unknown> = { ...context };

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
    }

    const maskedResult = this.maskingEngine.mask(logEvent);
    const maskedEvent = maskedResult.data;

    if (maskedResult.maskedFields.length > 0) {
      maskedEvent['dt3.security.masked_fields'] = maskedResult.maskedFields;
    }

    const validationResult = this.validator.validate(maskedEvent, this.validationMode);
    if (!validationResult.valid) {
      if (validationResult.mode === ValidationMode.STRICT) {
        throw new ValidationError(validationResult.errors);
      }

      if (validationResult.mode === ValidationMode.LENIENT) {
        maskedEvent['dt3.validation.errors'] = validationResult.errors;
      }
    }

    try {
      if (this.exporter === 'stdout') {
        console.log(JSON.stringify(maskedEvent));
      } else if (this.exporter === 'file') {
        this.fileTransport?.export(maskedEvent as LogEvent);
      } else if (this.exporter === 'http') {
        this.httpTransport?.export(maskedEvent as LogEvent);
      } else if (this.exporter === 'otlp') {
        this.otlpTransport?.export(maskedEvent as LogEvent);
      }
    } catch (deliveryError) {
      this.handleDeliveryFailure(deliveryError);
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
   * Settle delivery work initiated before the flush boundary.
   *
   * @returns A promise that rejects only when a delivery failure is configured
   * to fail closed.
   */
  public async flush(): Promise<void> {
    this.ensureOpen();

    try {
      this.fileTransport?.flush();
      await this.httpTransport?.flush();
      await this.otlpTransport?.flush();
    } catch (error) {
      this.handleDeliveryFailure(error);
    }
  }

  // PUBLIC_INTERFACE
  /**
   * Close the logger and its active transport.
   *
   * Closing is idempotent. Subsequent logging and flush calls fail with a
   * documented terminal-state error.
   */
  public close(): void {
    if (this.closed) {
      return;
    }

    this.closed = true;
    this.httpTransport?.close();
    this.otlpTransport?.close();
  }
}
