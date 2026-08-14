import { request } from 'node:http';
import { request as requestSecurely } from 'node:https';

import { Headers, LogEvent } from '../api/types';

/**
 * Raised when an OTLP export cannot be initiated successfully.
 */
export class OtlpTransportError extends Error {
  /**
   * Create an OTLP export error with a sanitized failure description.
   *
   * @param message - Safe transport failure description that never includes event data.
   */
  constructor(message: string) {
    super(message);
    this.name = 'OtlpTransportError';
  }
}

/**
 * Synchronously initiates OTLP/HTTP JSON export for final DT3 structured log events.
 *
 * Node's HTTP client sends requests asynchronously. This transport does not buffer
 * events, and logger-level `fail_open` controls handling of synchronous initiation
 * failures without recursively logging transport errors.
 */
export class OtlpTransport {
  private static readonly severityNumbers: Record<string, number> = {
    TRACE: 1,
    DEBUG: 5,
    INFO: 9,
    WARN: 13,
    WARNING: 13,
    ERROR: 17,
    FATAL: 21,
  };

  private readonly endpoint: URL;
  private readonly timeoutMs: number;
  private readonly headers: Headers;
  private readonly inFlight = new Set<Promise<void>>();

  /**
   * Create an OTLP/HTTP JSON transport.
   *
   * @param endpoint - OTLP Logs endpoint, conventionally ending in `/v1/logs`.
   * @param timeoutMs - Maximum request duration in milliseconds.
   * @param headers - Optional request headers merged with the OTLP JSON content type.
   * @throws Error if endpoint, timeout, or headers are invalid.
   */
  constructor(endpoint: string, timeoutMs = 10000, headers?: Headers) {
    if (typeof endpoint !== 'string' || endpoint.trim().length === 0) {
      throw new Error('otlp.endpoint must be configured for the OTLP exporter');
    }
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      throw new Error('otlp.timeout must be greater than zero');
    }
    if (headers !== undefined && !OtlpTransport.areValidHeaders(headers)) {
      throw new Error('otlp.headers must be a mapping of string header names to string values');
    }

    try {
      this.endpoint = new URL(endpoint);
    } catch {
      throw new Error('otlp.endpoint must be a valid HTTP or HTTPS URL');
    }

    if (this.endpoint.protocol !== 'http:' && this.endpoint.protocol !== 'https:') {
      throw new Error('otlp.endpoint must use the HTTP or HTTPS protocol');
    }

    this.timeoutMs = timeoutMs;
    this.headers = { ...(headers ?? {}) };
  }

  /**
   * Export one final DT3 event using the OTLP Logs JSON request format.
   *
   * @param event - Already-masked and validation-processed canonical log event.
   * @throws OtlpTransportError if the request cannot be initiated.
   */
  public export(event: LogEvent): void {
    const payload = JSON.stringify(OtlpTransport.toOtlpPayload(event));
    const headers = Object.fromEntries(
      Object.entries(this.headers).filter(([name]) => name.toLowerCase() !== 'content-type'),
    );
    headers['Content-Type'] = 'application/json';

    const requestFactory = this.endpoint.protocol === 'https:' ? requestSecurely : request;
    let resolveDelivery!: () => void;
    let rejectDelivery!: (error: OtlpTransportError) => void;
    const delivery = new Promise<void>((resolve, reject) => {
      resolveDelivery = resolve;
      rejectDelivery = reject;
    });
    this.inFlight.add(delivery);
    void delivery.finally(() => this.inFlight.delete(delivery)).catch(() => undefined);

    try {
      const outgoingRequest = requestFactory(
        this.endpoint,
        {
          method: 'POST',
          headers,
          timeout: this.timeoutMs,
        },
        (response) => {
          // Drain the response so the connection is not retained unnecessarily.
          response.resume();

          if (response.statusCode === undefined || response.statusCode < 200 || response.statusCode >= 300) {
            rejectDelivery(
              new OtlpTransportError(`OTLP export failed with status ${response.statusCode ?? 'unknown'}`),
            );
            return;
          }

          resolveDelivery();
        },
      );

      outgoingRequest.once('timeout', () => {
        outgoingRequest.destroy(new OtlpTransportError('OTLP export request timed out'));
      });
      outgoingRequest.once('error', (error: Error) => {
        // Transport errors intentionally omit the event payload to avoid exposing data.
        rejectDelivery(
          error instanceof OtlpTransportError
            ? error
            : new OtlpTransportError('OTLP export request failed'),
        );
      });
      outgoingRequest.write(payload, 'utf8');
      outgoingRequest.end();
    } catch {
      rejectDelivery(new OtlpTransportError('OTLP export request could not be initiated'));
    }
  }

  /**
   * Wait for OTLP requests that were in flight when this method was called.
   *
   * @returns A promise resolving after all captured requests settle, rejecting
   * with the first sanitized delivery failure.
   */
  public async flush(): Promise<void> {
    const pending = [...this.inFlight];
    if (pending.length === 0) {
      return;
    }

    const results = await Promise.allSettled(pending);
    const failure = results.find(
      (result): result is PromiseRejectedResult => result.status === 'rejected',
    );
    if (failure) {
      throw failure.reason;
    }
  }

  /**
   * Map a final DT3 event into a standards-shaped OTLP Logs JSON export body.
   *
   * @param event - Final canonical DT3 log event.
   * @returns An OTLP Logs JSON request containing one log record.
   */
  public static toOtlpPayload(event: LogEvent): Record<string, unknown> {
    const eventData = { ...event };
    const severityText = String(eventData.severity ?? 'INFO').toUpperCase();
    const logRecord: Record<string, unknown> = {
      timeUnixNano: String(OtlpTransport.timestampToNanoseconds(eventData.timestamp)),
      severityNumber: OtlpTransport.severityNumbers[severityText] ?? 9,
      severityText,
      body: { stringValue: String(eventData.message ?? '') },
    };

    const logAttributes = OtlpTransport.logAttributes(eventData);
    if (logAttributes.length > 0) {
      logRecord.attributes = logAttributes;
    }

    const scopeAttributes: Record<string, unknown>[] = [];
    if ('sdk.name' in eventData) {
      scopeAttributes.push(OtlpTransport.attribute('dt3.sdk.name', eventData['sdk.name']));
    }
    if ('sdk.version' in eventData) {
      scopeAttributes.push(OtlpTransport.attribute('dt3.sdk.version', eventData['sdk.version']));
    }

    const scopeLog: Record<string, unknown> = { logRecords: [logRecord] };
    if (scopeAttributes.length > 0) {
      scopeLog.scope = { name: 'dt3.logger', attributes: scopeAttributes };
    }

    return {
      resourceLogs: [
        {
          resource: { attributes: OtlpTransport.resourceAttributes(eventData) },
          scopeLogs: [scopeLog],
        },
      ],
    };
  }

  private static resourceAttributes(event: LogEvent): Record<string, unknown>[] {
    const attributes: Record<string, unknown>[] = [];
    for (const key of [
      'service.name',
      'service.version',
      'deployment.environment',
      'tenant.id',
      'tenant.name',
    ]) {
      if (key in event) {
        attributes.push(OtlpTransport.attribute(key, event[key]));
      }
    }
    return attributes;
  }

  private static logAttributes(event: LogEvent): Record<string, unknown>[] {
    const excluded = new Set([
      'timestamp',
      'severity',
      'message',
      'service.name',
      'service.version',
      'deployment.environment',
      'tenant.id',
      'tenant.name',
      'sdk.name',
      'sdk.version',
    ]);

    return Object.entries(event)
      .filter(([key]) => !excluded.has(key))
      .map(([key, value]) => OtlpTransport.attribute(key, value));
  }

  private static attribute(key: string, value: unknown): Record<string, unknown> {
    return { key, value: OtlpTransport.anyValue(value) };
  }

  private static anyValue(value: unknown): Record<string, unknown> {
    if (typeof value === 'boolean') {
      return { boolValue: value };
    }
    if (typeof value === 'number') {
      return Number.isInteger(value) ? { intValue: String(value) } : { doubleValue: value };
    }
    if (typeof value === 'string') {
      return { stringValue: value };
    }
    if (value === null || value === undefined) {
      return { stringValue: 'null' };
    }
    if (Array.isArray(value)) {
      return { arrayValue: { values: value.map((item) => OtlpTransport.anyValue(item)) } };
    }
    if (typeof value === 'object') {
      return {
        kvlistValue: {
          values: Object.entries(value as Record<string, unknown>).map(([key, item]) =>
            OtlpTransport.attribute(key, item),
          ),
        },
      };
    }

    return { stringValue: String(value) };
  }

  private static timestampToNanoseconds(value: unknown): bigint {
    const nowNanoseconds = (): bigint => BigInt(Date.now()) * 1_000_000n;

    if (typeof value !== 'string') {
      return nowNanoseconds();
    }

    const timestamp = Date.parse(value);
    return Number.isNaN(timestamp) ? nowNanoseconds() : BigInt(timestamp) * 1_000_000n;
  }

  private static areValidHeaders(headers: Headers): boolean {
    return Object.entries(headers).every(
      ([name, value]) =>
        typeof name === 'string' &&
        name.trim().length > 0 &&
        !/[\r\n]/.test(name) &&
        typeof value === 'string' &&
        !/[\r\n]/.test(value),
    );
  }
}
