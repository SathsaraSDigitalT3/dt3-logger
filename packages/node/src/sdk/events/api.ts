import { LogEvent, Severity } from '../../api/types';

/** Well-known API / HTTP event names. */
export type ApiEventName = 'INCOMING_HTTP' | 'OUTGOING_HTTP';

/**
 * Caller-supplied fields for an API / HTTP structured event.
 */
export interface ApiEventFields {
  message?: string;
  severity?: Severity | string;
  'http.request.method'?: string;
  'http.route'?: string;
  'http.target'?: string;
  'http.response.status_code'?: number;
  'duration.ms'?: number;
  [key: string]: unknown;
}

/**
 * Build a partial canonical LogEvent for an API / HTTP interaction.
 *
 * @param eventName - `INCOMING_HTTP` or `OUTGOING_HTTP`.
 * @param fields - HTTP attributes and optional message/severity overrides.
 * @returns A partial LogEvent suitable for `Logger.event` / `EventEmitter.emit`.
 */
export function buildApiEvent(
  eventName: ApiEventName,
  fields: ApiEventFields = {},
): Partial<LogEvent> {
  const {
    message,
    severity,
    'http.request.method': method,
    'http.route': route,
    'http.target': target,
    'http.response.status_code': statusCode,
    'duration.ms': durationMs,
    ...rest
  } = fields;

  const event: Partial<LogEvent> = {
    ...rest,
    'event.name': eventName,
    message:
      message ??
      (eventName === 'INCOMING_HTTP' ? 'Incoming HTTP request' : 'Outgoing HTTP request'),
    severity: severity ?? Severity.INFO,
  };

  if (method !== undefined) {
    event['http.request.method'] = method;
  }
  if (route !== undefined) {
    event['http.route'] = route;
  }
  if (target !== undefined) {
    event['http.target'] = target;
  }
  if (statusCode !== undefined) {
    event['http.response.status_code'] = statusCode;
  }
  if (durationMs !== undefined) {
    event['duration.ms'] = durationMs;
  }

  return event;
}

/**
 * Build an incoming HTTP request/response event.
 *
 * @param fields - HTTP attributes and optional message/severity overrides.
 */
export function buildIncomingHttp(fields: ApiEventFields = {}): Partial<LogEvent> {
  return buildApiEvent('INCOMING_HTTP', fields);
}

/**
 * Build an outgoing HTTP request/response event.
 *
 * @param fields - HTTP attributes and optional message/severity overrides.
 */
export function buildOutgoingHttp(fields: ApiEventFields = {}): Partial<LogEvent> {
  return buildApiEvent('OUTGOING_HTTP', fields);
}
