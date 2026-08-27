/**
 * Messaging-transport event envelope (snake_case wrapper).
 *
 * This shape matches `schemas/event-envelope.schema.json` and is intended for
 * async/messaging adapters. It is not consumed by the logger pipeline.
 */
export interface EventEnvelope {
  event_type: string;
  event_version: string;
  timestamp: string;
  payload: Record<string, unknown>;
  source?: string;
  correlation_id?: string;
  tenant_id?: string;
  metadata?: Record<string, unknown>;
}

/**
 * Fields accepted when building a messaging transport envelope.
 */
export interface EventEnvelopeFields {
  event_type: string;
  event_version?: string;
  timestamp?: string;
  payload: Record<string, unknown>;
  source?: string;
  correlation_id?: string;
  tenant_id?: string;
  metadata?: Record<string, unknown>;
}

/**
 * Build a snake_case messaging transport envelope.
 *
 * @param fields - Envelope discriminator, payload, and optional correlation metadata.
 * @returns An envelope suitable for async/messaging transports.
 */
export function buildEventEnvelope(fields: EventEnvelopeFields): EventEnvelope {
  const envelope: EventEnvelope = {
    event_type: fields.event_type,
    event_version: fields.event_version ?? '1.0.0',
    timestamp: fields.timestamp ?? new Date().toISOString(),
    payload: fields.payload,
  };

  if (fields.source !== undefined) {
    envelope.source = fields.source;
  }
  if (fields.correlation_id !== undefined) {
    envelope.correlation_id = fields.correlation_id;
  }
  if (fields.tenant_id !== undefined) {
    envelope.tenant_id = fields.tenant_id;
  }
  if (fields.metadata !== undefined) {
    envelope.metadata = fields.metadata;
  }

  return envelope;
}
