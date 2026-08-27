import { LogEvent, Severity } from '../../api/types';

/** Well-known database event names. */
export type DatabaseEventName = 'DB_QUERY_STARTED' | 'DB_QUERY_COMPLETED' | 'DB_QUERY_FAILED';

/**
 * Caller-supplied fields for a database structured event.
 */
export interface DatabaseEventFields {
  message?: string;
  severity?: Severity | string;
  'db.system'?: string;
  'db.operation'?: string;
  'db.name'?: string;
  'db.sql.table'?: string;
  'duration.ms'?: number;
  [key: string]: unknown;
}

const defaultMessages: Record<DatabaseEventName, string> = {
  DB_QUERY_STARTED: 'Database query started',
  DB_QUERY_COMPLETED: 'Database query completed',
  DB_QUERY_FAILED: 'Database query failed',
};

/**
 * Build a partial canonical LogEvent for a database operation.
 *
 * @param eventName - Database lifecycle event name.
 * @param fields - Database attributes and optional message/severity overrides.
 * @returns A partial LogEvent suitable for `Logger.event` / `EventEmitter.emit`.
 */
export function buildDatabaseEvent(
  eventName: DatabaseEventName,
  fields: DatabaseEventFields = {},
): Partial<LogEvent> {
  const {
    message,
    severity,
    'db.system': system,
    'db.operation': operation,
    'db.name': name,
    'db.sql.table': table,
    'duration.ms': durationMs,
    ...rest
  } = fields;

  const event: Partial<LogEvent> = {
    ...rest,
    'event.name': eventName,
    message: message ?? defaultMessages[eventName],
    severity: severity ?? (eventName === 'DB_QUERY_FAILED' ? Severity.ERROR : Severity.INFO),
  };

  if (system !== undefined) {
    event['db.system'] = system;
  }
  if (operation !== undefined) {
    event['db.operation'] = operation;
  }
  if (name !== undefined) {
    event['db.name'] = name;
  }
  if (table !== undefined) {
    event['db.sql.table'] = table;
  }
  if (durationMs !== undefined) {
    event['duration.ms'] = durationMs;
  }

  return event;
}
