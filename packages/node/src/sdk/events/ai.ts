import { LogEvent, Severity } from '../../api/types';

/** Well-known AI / Kavia event names. */
export type AiEventName =
  | 'AI_PROMPT_SUBMITTED'
  | 'AI_RESPONSE_RECEIVED'
  | 'AI_TOOL_INVOCATION'
  | 'AI_MEMORY_RETRIEVAL'
  | 'AI_RAG_RETRIEVAL'
  | 'AI_AGENT_EXECUTION'
  | 'AI_SAFETY_FILTER_APPLIED';

/**
 * Caller-supplied fields for an AI structured event.
 */
export interface AiEventFields {
  message?: string;
  severity?: Severity | string;
  'kavia.provider'?: string;
  'kavia.model'?: string;
  'kavia.model.version'?: string;
  'kavia.prompt'?: string;
  'kavia.response'?: string;
  'kavia.tokens.prompt'?: number;
  'kavia.tokens.completion'?: number;
  'kavia.tokens.total'?: number;
  'kavia.latency.ms'?: number;
  'kavia.cost'?: number;
  'kavia.context_window.size'?: number;
  'kavia.memory.bytes'?: number;
  'kavia.conversation.id'?: string;
  'kavia.agent.id'?: string;
  'kavia.request.id'?: string;
  'kavia.finish_reason'?: string;
  'kavia.cache.hit'?: boolean;
  'kavia.temperature'?: number;
  'kavia.max_tokens'?: number;
  prompt?: string;
  response?: string;
  [key: string]: unknown;
}

const defaultMessages: Record<AiEventName, string> = {
  AI_PROMPT_SUBMITTED: 'AI prompt submitted',
  AI_RESPONSE_RECEIVED: 'AI response received',
  AI_TOOL_INVOCATION: 'AI tool invocation',
  AI_MEMORY_RETRIEVAL: 'AI memory retrieval',
  AI_RAG_RETRIEVAL: 'AI RAG retrieval',
  AI_AGENT_EXECUTION: 'AI agent execution',
  AI_SAFETY_FILTER_APPLIED: 'AI safety filter applied',
};

const kaviaKeys = [
  'kavia.provider',
  'kavia.model',
  'kavia.model.version',
  'kavia.prompt',
  'kavia.response',
  'kavia.tokens.prompt',
  'kavia.tokens.completion',
  'kavia.tokens.total',
  'kavia.latency.ms',
  'kavia.cost',
  'kavia.context_window.size',
  'kavia.memory.bytes',
  'kavia.conversation.id',
  'kavia.agent.id',
  'kavia.request.id',
  'kavia.finish_reason',
  'kavia.cache.hit',
  'kavia.temperature',
  'kavia.max_tokens',
] as const;

/**
 * Build a partial canonical LogEvent for an AI / Kavia interaction.
 *
 * @param eventName - AI lifecycle event name.
 * @param fields - Kavia attributes and optional message/severity overrides.
 * @returns A partial LogEvent suitable for `Logger.event` / `EventEmitter.emit`.
 */
export function buildAiEvent(
  eventName: AiEventName,
  fields: AiEventFields = {},
): Partial<LogEvent> {
  const { message, severity, prompt, response, ...rest } = fields;

  const event: Partial<LogEvent> = {
    ...rest,
    'event.name': eventName,
    message: message ?? defaultMessages[eventName],
    severity: severity ?? Severity.INFO,
  };

  if (prompt !== undefined && event['kavia.prompt'] === undefined) {
    event['kavia.prompt'] = prompt;
  }
  if (response !== undefined && event['kavia.response'] === undefined) {
    event['kavia.response'] = response;
  }

  for (const key of kaviaKeys) {
    if (fields[key] !== undefined) {
      event[key] = fields[key];
    }
  }

  return event;
}

/**
 * Build an AI request (prompt-side) event correlated by `kavia.request.id`.
 */
export function buildAiRequestEvent(fields: AiEventFields = {}): Partial<LogEvent> {
  return buildAiEvent('AI_PROMPT_SUBMITTED', {
    message: fields.message ?? 'AI request submitted',
    ...fields,
  });
}

/**
 * Build an AI response event correlated by `kavia.request.id`.
 */
export function buildAiResponseEvent(fields: AiEventFields = {}): Partial<LogEvent> {
  return buildAiEvent('AI_RESPONSE_RECEIVED', {
    message: fields.message ?? 'AI response received',
    ...fields,
  });
}
