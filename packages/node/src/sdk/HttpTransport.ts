import { request } from 'node:http';
import { request as requestSecurely } from 'node:https';

import { Headers, LogEvent } from '../api/types';
import { Dt3ErrorCode, Dt3TransportError } from './errors';

/**
 * Raised when an HTTP export cannot complete successfully.
 */
export class HttpTransportError extends Dt3TransportError {
  /**
   * Create an HTTP export error.
   *
   * @param message - A sanitized description of the transport failure.
   * @param options - Specific canonical transport classification.
   */
  constructor(
    message: string,
    options: {
      code?: Dt3ErrorCode;
      retryable?: boolean;
      cause?: unknown;
    } = {},
  ) {
    super(message, options);
    this.name = 'HttpTransportError';
    Object.setPrototypeOf(this, new.target.prototype);
  }
}

/**
 * Export final DT3 structured events to a generic HTTP endpoint.
 *
 * Requests are settled asynchronously and captured by `flush`, while `export`
 * remains synchronous so existing logger method signatures are preserved.
 */
export class HttpTransport {
  private readonly endpoint: URL;
  private readonly timeoutMs: number;
  private readonly headers: Headers;
  private readonly inFlight = new Set<Promise<void>>();
  private readonly failedDeliveries = new Map<Promise<void>, unknown>();
  private readonly onDeliveryFailure?: (error: unknown) => void;
  private readonly retainFailedDeliveries: boolean;
  private closed = false;

  /**
   * Create an HTTP transport.
   *
   * @param endpoint - Destination URL that receives JSON log events.
   * @param timeoutMs - Maximum request duration in milliseconds.
   * @param headers - Optional request headers merged with the JSON content type.
   * @param onDeliveryFailure - Optional out-of-band async failure observer.
   * @param retainFailedDeliveries - Whether failures must be retained for a
   * later fail-closed `flush()` boundary.
   * @throws Error if the endpoint, timeout, or headers are invalid.
   */
  constructor(
    endpoint: string,
    timeoutMs = 5000,
    headers?: Headers,
    onDeliveryFailure?: (error: unknown) => void,
    retainFailedDeliveries = true,
  ) {
    if (typeof endpoint !== 'string' || endpoint.trim().length === 0) {
      throw new Error('exporter.http.endpoint must be configured for the HTTP exporter');
    }
    if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
      throw new Error('exporter.http.timeout must be greater than zero');
    }
    if (headers !== undefined && !HttpTransport.areValidHeaders(headers)) {
      throw new Error('exporter.http.headers must be a mapping of safe string header names to string values');
    }

    try {
      this.endpoint = new URL(endpoint);
    } catch {
      throw new Error('exporter.http.endpoint must be a valid HTTP or HTTPS URL');
    }

    if (this.endpoint.protocol !== 'http:' && this.endpoint.protocol !== 'https:') {
      throw new Error('exporter.http.endpoint must use the HTTP or HTTPS protocol');
    }

    this.timeoutMs = timeoutMs;
    this.headers = { ...(headers ?? {}) };
    this.onDeliveryFailure = onDeliveryFailure;
    this.retainFailedDeliveries = retainFailedDeliveries;
  }

  /**
   * Start export of one final DT3 event as an application/json payload.
   *
   * @param event - The already-masked and validation-processed canonical log event.
   * @throws HttpTransportError if the transport is closed or initialization fails.
   */
  public export(event: LogEvent): void {
    if (this.closed) {
      throw new HttpTransportError('HTTP transport is closed', {
        code: Dt3ErrorCode.TransportClosed,
        retryable: false,
      });
    }

    const payload = JSON.stringify(event);
    const headers = Object.fromEntries(
      Object.entries(this.headers).filter(([name]) => name.toLowerCase() !== 'content-type'),
    );
    headers['Content-Type'] = 'application/json';

    let resolveDelivery!: () => void;
    let rejectDelivery!: (error: HttpTransportError) => void;
    const delivery = new Promise<void>((resolve, reject) => {
      resolveDelivery = resolve;
      rejectDelivery = reject;
    });
    this.inFlight.add(delivery);

    // A fail-closed logger needs a settled rejection available to a later
    // flush boundary. Fail-open delivery failures are reported immediately,
    // then released so a long-lived logger cannot retain failed requests.
    void delivery
      .catch((deliveryError: unknown) => {
        if (this.retainFailedDeliveries) {
          this.failedDeliveries.set(delivery, deliveryError);
        }
        try {
          this.onDeliveryFailure?.(deliveryError);
        } catch {
          // An observer must never turn an async delivery error into an
          // unhandled rejection or recursively enter the logger.
        }
      });
    void delivery.finally(() => this.inFlight.delete(delivery)).catch(() => undefined);

    try {
      const requestFactory = this.endpoint.protocol === 'https:' ? requestSecurely : request;
      const outgoingRequest = requestFactory(
        this.endpoint,
        {
          method: 'POST',
          headers,
          timeout: this.timeoutMs,
        },
        (response) => {
          response.resume();

          if (response.statusCode === undefined || response.statusCode < 200 || response.statusCode >= 300) {
            rejectDelivery(
              new HttpTransportError(`HTTP export failed with status ${response.statusCode ?? 'unknown'}`, {
                code: Dt3ErrorCode.TransportRejected,
                retryable: (response.statusCode ?? 0) >= 500,
              }),
            );
            return;
          }

          resolveDelivery();
        },
      );

      outgoingRequest.once('timeout', () => {
        outgoingRequest.destroy(
          new HttpTransportError('HTTP export request timed out', {
            code: Dt3ErrorCode.TransportTimeout,
            retryable: true,
          }),
        );
      });
      outgoingRequest.once('error', (error: Error) => {
        rejectDelivery(
          error instanceof HttpTransportError
            ? error
            : new HttpTransportError('HTTP export request failed', {
                code: Dt3ErrorCode.TransportUnavailable,
                retryable: true,
                cause: error,
              }),
        );
      });
      outgoingRequest.write(payload, 'utf8');
      outgoingRequest.end();
    } catch {
      rejectDelivery(
        new HttpTransportError('HTTP export request could not be initiated', {
          code: Dt3ErrorCode.TransportUnavailable,
          retryable: true,
        }),
      );
    }
  }

  /**
   * Settle requests started before this flush boundary and consume previously
   * settled delivery failures that have not yet been observed by a flush.
   *
   * @returns A promise that resolves when captured requests succeed or rejects
   * with the first sanitized delivery failure.
   * @throws HttpTransportError if the transport is closed.
   */
  public async flush(): Promise<void> {
    if (this.closed) {
      throw new HttpTransportError('HTTP transport is closed', {
        code: Dt3ErrorCode.TransportClosed,
        retryable: false,
      });
    }

    const pending = [...this.inFlight];
    const retainedFailures = [...this.failedDeliveries.entries()];
    if (pending.length === 0 && retainedFailures.length === 0) {
      return;
    }

    const results = await Promise.allSettled(pending);
    const failure = results.find(
      (result): result is PromiseRejectedResult => result.status === 'rejected',
    );
    const retainedFailure = retainedFailures[0]?.[1];

    // Every request captured at this boundary, including one that rejected
    // while awaiting `allSettled`, has now been observed by this flush.
    for (const [delivery] of retainedFailures) {
      this.failedDeliveries.delete(delivery);
    }
    for (const delivery of pending) {
      this.failedDeliveries.delete(delivery);
    }

    if (retainedFailure !== undefined) {
      throw retainedFailure;
    }
    if (failure) {
      throw failure.reason;
    }
  }

  /**
   * Enter the terminal transport state.
   *
   * The generic HTTP transport owns no persistent sockets, so close only
   * prevents future exports and flush operations.
   */
  public close(): void {
    this.closed = true;
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
