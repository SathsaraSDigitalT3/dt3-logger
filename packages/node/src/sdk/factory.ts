import { Logger } from '../api/Logger';
import { LoggerImpl } from './impl/LoggerImpl';

// PUBLIC_INTERFACE
/**
 * Create a configured logger instance.
 *
 * @param config - Logger service, exporter, processing, and error-handler configuration.
 * @returns The created logger.
 * @throws The original configuration or construction error after it is reported.
 */
export function createLogger(config: Record<string, unknown>): Logger {
  return new LoggerImpl(config);
}
