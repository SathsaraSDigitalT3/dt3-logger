import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';

import { Headers, LogEvent } from '../api/types';
import { EventSink } from '../api/EventSink';
import { Dt3ErrorCode, Dt3TransportError } from './errors';

/**
 * Raised when a Kafka or Event Hub export fails.
 */
export class KafkaTransportError extends Dt3TransportError {
  constructor(
    message: string,
    options: { code?: Dt3ErrorCode; retryable?: boolean; cause?: unknown } = {},
  ) {
    super(message, options);
    this.name = 'KafkaTransportError';
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

function validateHeaders(headers: Headers | undefined, key: string): Headers {
  if (headers === undefined) {
    return {};
  }
  for (const [name, value] of Object.entries(headers)) {
    if (
      typeof name !== 'string' ||
      name.trim().length === 0 ||
      /[\r\n]/.test(name) ||
      typeof value !== 'string' ||
      /[\r\n]/.test(value)
    ) {
      throw new Error(`${key} must be a mapping of safe string header names to string values`);
    }
  }
  return { ...headers };
}

function postJson(
  endpoint: string,
  payload: unknown,
  timeoutMs: number,
  headers: Headers,
  label: string,
): Promise<void> {
  return new Promise((resolve, reject) => {
    let url: URL;
    try {
      url = new URL(endpoint);
    } catch {
      reject(new KafkaTransportError(`${label} endpoint must be a valid HTTP or HTTPS URL`));
      return;
    }

    const body = Buffer.from(JSON.stringify(payload), 'utf8');
    const transport = url.protocol === 'https:' ? httpsRequest : httpRequest;
    const req = transport(
      {
        protocol: url.protocol,
        hostname: url.hostname,
        port: url.port,
        path: `${url.pathname}${url.search}`,
        method: 'POST',
        headers: {
          ...headers,
          'Content-Length': body.byteLength,
        },
        timeout: timeoutMs,
      },
      (response) => {
        response.resume();
        const status = response.statusCode ?? 0;
        if (status >= 200 && status < 300) {
          resolve();
          return;
        }
        reject(
          new KafkaTransportError(`${label} export request failed with status ${status}`, {
            code: Dt3ErrorCode.TransportUnavailable,
            retryable: true,
          }),
        );
      },
    );

    req.on('timeout', () => {
      req.destroy();
      reject(
        new KafkaTransportError(`${label} export request timed out`, {
          code: Dt3ErrorCode.TransportTimeout,
          retryable: true,
        }),
      );
    });
    req.on('error', (error) => {
      reject(
        new KafkaTransportError(`${label} export request failed`, {
          cause: error,
          retryable: true,
        }),
      );
    });
    req.write(body);
    req.end();
  });
}

/**
 * Kafka REST Proxy transport (Confluent-compatible JSON records).
 */
export class KafkaTransport implements EventSink {
  private readonly endpoint: string;
  private readonly timeoutMs: number;
  private readonly headers: Headers;
  private readonly inFlight = new Set<Promise<void>>();
  private closed = false;

  constructor(topic: string, restEndpoint: string, timeoutMs = 10000, headers?: Headers) {
    if (typeof topic !== 'string' || topic.trim().length === 0) {
      throw new Error('exporter.kafka.topic must be configured for the kafka exporter');
    }
    if (typeof restEndpoint !== 'string' || restEndpoint.trim().length === 0) {
      throw new Error('exporter.kafka.rest_endpoint must be configured for the kafka exporter');
    }
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      throw new Error('exporter.kafka.timeout must be greater than zero');
    }

    this.endpoint = `${restEndpoint.replace(/\/$/, '')}/topics/${topic.trim()}`;
    this.timeoutMs = timeoutMs;
    this.headers = {
      'Content-Type': 'application/vnd.kafka.json.v2+json',
      Accept: 'application/vnd.kafka.v2+json, application/vnd.kafka+json, application/json',
      ...validateHeaders(headers, 'exporter.kafka.headers'),
    };
  }

  public export(event: LogEvent): void {
    if (this.closed) {
      throw new Error('Kafka transport is closed');
    }
    const delivery = postJson(
      this.endpoint,
      { records: [{ value: event }] },
      this.timeoutMs,
      this.headers,
      'Kafka',
    );
    this.inFlight.add(delivery);
    void delivery.finally(() => this.inFlight.delete(delivery));
  }

  public async flush(): Promise<void> {
    await Promise.all([...this.inFlight]);
  }

  public async close(): Promise<void> {
    this.closed = true;
    await this.flush();
  }
}

/**
 * Azure Event Hubs HTTPS messages transport.
 */
export class EventHubTransport implements EventSink {
  private readonly endpoint: string;
  private readonly timeoutMs: number;
  private readonly headers: Headers;
  private readonly inFlight = new Set<Promise<void>>();
  private closed = false;

  constructor(endpoint: string, timeoutMs = 10000, headers?: Headers) {
    if (typeof endpoint !== 'string' || endpoint.trim().length === 0) {
      throw new Error('exporter.eventhub.endpoint must be configured for the eventhub exporter');
    }
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      throw new Error('exporter.eventhub.timeout must be greater than zero');
    }

    this.endpoint = endpoint.trim();
    this.timeoutMs = timeoutMs;
    this.headers = {
      'Content-Type': 'application/json',
      ...validateHeaders(headers, 'exporter.eventhub.headers'),
    };
  }

  public export(event: LogEvent): void {
    if (this.closed) {
      throw new Error('Event Hub transport is closed');
    }
    const delivery = postJson(this.endpoint, event, this.timeoutMs, this.headers, 'Event Hub');
    this.inFlight.add(delivery);
    void delivery.finally(() => this.inFlight.delete(delivery));
  }

  public async flush(): Promise<void> {
    await Promise.all([...this.inFlight]);
  }

  public async close(): Promise<void> {
    this.closed = true;
    await this.flush();
  }
}
