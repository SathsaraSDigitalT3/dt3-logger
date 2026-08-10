import { Logger } from '../../api/Logger';

export class LoggerImpl implements Logger {
  private config: Record<string, any>;
  private exporter: string;

  constructor(config: Record<string, any>) {
    this.config = config;
    this.exporter = config.exporter || 'stdout';
  }

  private log(level: string, message: string, context?: Record<string, any>, error?: Error) {
    const eventName = context?.['event.name'] || 'GENERIC_EVENT';
    const logEvent: Record<string, any> = {
      timestamp: new Date().toISOString(),
      severity: level,
      message,
      'event.name': eventName,
      'schema.version': this.config['schema.version'] || '1.0.0',
      'sdk.name': 'dt3-node',
      'sdk.version': '0.1.0',
      'service.name': this.config['service.name'] || 'unknown',
      'service.version': this.config['service.version'] || 'unknown',
      'deployment.environment': this.config['deployment.environment'] || 'unknown',
      ...context
    };

    if (error) {
      logEvent['error.type'] = error.name;
      logEvent['error.message'] = error.message;
      logEvent['error.stack'] = error.stack;
    }

    if (this.exporter === 'stdout') {
      console.log(JSON.stringify(logEvent));
    }
  }

  debug(message: string, context?: Record<string, any>): void {
    this.log('DEBUG', message, context);
  }

  info(message: string, context?: Record<string, any>): void {
    this.log('INFO', message, context);
  }

  warn(message: string, context?: Record<string, any>): void {
    this.log('WARN', message, context);
  }

  error(message: string, error?: Error, context?: Record<string, any>): void {
    this.log('ERROR', message, context, error);
  }

  flush(): void {
    // No-op for stdout
  }
}
