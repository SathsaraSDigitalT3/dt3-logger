import { Logger } from '../../api/Logger';
import { MaskingEngine } from '../masking';

/**
 * Concrete DT3 logger that builds structured events and exports them to stdout.
 */
export class LoggerImpl implements Logger {
  private readonly config: Record<string, unknown>;
  private readonly exporter: string;
  private readonly maskingEngine: MaskingEngine;

  /**
   * Create a logger from SDK configuration.
   *
   * @param config - Service metadata, exporter, and masking configuration.
   */
  constructor(config: Record<string, unknown>) {
    this.config = config;
    this.exporter = typeof config.exporter === 'string' ? config.exporter : 'stdout';
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
  }

  private log(
    level: string,
    message: string,
    context?: Record<string, unknown>,
    error?: Error,
  ): void {
    const eventName =
      typeof context?.['event.name'] === 'string' ? context['event.name'] : 'GENERIC_EVENT';

    const logEvent: Record<string, unknown> = {
      timestamp: new Date().toISOString(),
      severity: level,
      message,
      'event.name': eventName,
      'schema.version':
        typeof this.config['schema.version'] === 'string' ? this.config['schema.version'] : '1.0.0',
      'sdk.name': typeof this.config['sdk.name'] === 'string' ? this.config['sdk.name'] : '@digitalt3/commons',
      'sdk.version': typeof this.config['sdk.version'] === 'string' ? this.config['sdk.version'] : '0.1.0',
      'service.name':
        typeof this.config['service.name'] === 'string' ? this.config['service.name'] : 'unknown',
      'service.version':
        typeof this.config['service.version'] === 'string' ? this.config['service.version'] : 'unknown',
      'deployment.environment':
        typeof this.config['deployment.environment'] === 'string'
          ? this.config['deployment.environment']
          : 'unknown',
      ...context,
    };

    if (error) {
      logEvent['error.type'] = error.name;
      logEvent['error.message'] = error.message;
      logEvent['error.stack'] = error.stack;
    }

    const maskedResult = this.maskingEngine.mask(logEvent);
    if (maskedResult.maskedFields.length > 0) {
      maskedResult.data['dt3.security.masked_fields'] = maskedResult.maskedFields;
    }

    if (this.exporter === 'stdout') {
      console.log(JSON.stringify(maskedResult.data));
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
   * Flush pending log events. The stdout exporter has no buffered events.
   */
  public flush(): void {
    // No-op for stdout.
  }
}
