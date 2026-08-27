import { LogEvent, Severity } from '../../api/types';

/** Well-known messaging / worker event names. */
export type MessagingEventName =
  | 'WORKER_JOB_RECEIVED'
  | 'WORKER_JOB_STARTED'
  | 'WORKER_JOB_COMPLETED'
  | 'WORKER_JOB_FAILED'
  | 'WORKER_JOB_RETRIED'
  | 'MESSAGING_PUBLISH'
  | 'MESSAGING_RECEIVE';

/**
 * Caller-supplied fields for a messaging / worker structured event.
 */
export interface MessagingEventFields {
  message?: string;
  severity?: Severity | string;
  'messaging.system'?: string;
  'messaging.destination'?: string;
  'messaging.operation'?: string;
  'messaging.message.id'?: string;
  'duration.ms'?: number;
  [key: string]: unknown;
}

const defaultMessages: Record<MessagingEventName, string> = {
  WORKER_JOB_RECEIVED: 'Worker job received',
  WORKER_JOB_STARTED: 'Worker job started',
  WORKER_JOB_COMPLETED: 'Worker job completed',
  WORKER_JOB_FAILED: 'Worker job failed',
  WORKER_JOB_RETRIED: 'Worker job retried',
  MESSAGING_PUBLISH: 'Messaging publish',
  MESSAGING_RECEIVE: 'Messaging receive',
};

/**
 * Build a partial canonical LogEvent for messaging or worker activity.
 *
 * @param eventName - Messaging / worker event name.
 * @param fields - Messaging attributes and optional message/severity overrides.
 * @returns A partial LogEvent suitable for `Logger.event` / `EventEmitter.emit`.
 */
export function buildMessagingEvent(
  eventName: MessagingEventName,
  fields: MessagingEventFields = {},
): Partial<LogEvent> {
  const {
    message,
    severity,
    'messaging.system': system,
    'messaging.destination': destination,
    'messaging.operation': operation,
    'messaging.message.id': messageId,
    'duration.ms': durationMs,
    ...rest
  } = fields;

  const event: Partial<LogEvent> = {
    ...rest,
    'event.name': eventName,
    message: message ?? defaultMessages[eventName],
    severity: severity ?? (eventName === 'WORKER_JOB_FAILED' ? Severity.ERROR : Severity.INFO),
  };

  if (system !== undefined) {
    event['messaging.system'] = system;
  }
  if (destination !== undefined) {
    event['messaging.destination'] = destination;
  }
  if (operation !== undefined) {
    event['messaging.operation'] = operation;
  }
  if (messageId !== undefined) {
    event['messaging.message.id'] = messageId;
  }
  if (durationMs !== undefined) {
    event['duration.ms'] = durationMs;
  }

  return event;
}
