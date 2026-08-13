import { Logger } from '../api/Logger';
import { LoggerImpl } from './impl/LoggerImpl';

export function createLogger(config: Record<string, unknown>): Logger {
  return new LoggerImpl(config);
}
