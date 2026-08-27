import {
  createLogger,
  Dt3Error,
  Dt3ErrorCode,
  Dt3ErrorPhase,
  ErrorHandler,
  EventBatcher,
} from '../src';
import type { Logger } from '../src';
import { createServer } from 'node:http';

const readErrorSnapshot = (logger: Pick<Logger, 'errorSnapshot'>) => {
  if (logger.errorSnapshot === undefined) {
    throw new Error('Logger does not expose diagnostic snapshots');
  }

  return logger.errorSnapshot();
};

const createRejectingEndpoint = async (): Promise<{
  endpoint: string;
  close: () => Promise<void>;
}> => {
  const server = createServer((_request, response) => {
    response.statusCode = 503;
    response.end();
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('Unable to determine rejecting test endpoint address');
  }

  return {
    endpoint: `http://127.0.0.1:${address.port}/logs`,
    close: () =>
      new Promise<void>((resolve, reject) => {
        server.close((error) => (error ? reject(error) : resolve()));
      }),
  };
};

const loggerConfig = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  'service.name': 'error-handler-test-service',
  'service.version': '1.0.0',
  'deployment.environment': 'test',
  exporter: 'stdout',
  'validation.mode': 'STRICT',
  ...overrides,
});

describe('ErrorHandler', () => {
  it.each([
    [
      new Dt3Error('timeout', {
        code: Dt3ErrorCode.TransportTimeout,
        retryable: true,
        phase: Dt3ErrorPhase.Delivery,
      }),
      Dt3ErrorCode.TransportTimeout,
      true,
    ],
    [new TypeError('serialization failed'), Dt3ErrorCode.SerializationFailed, false],
    [new RangeError('maximum call stack size exceeded'), Dt3ErrorCode.MaskingFailed, false],
    [{ code: 'ECONNREFUSED' }, Dt3ErrorCode.TransportUnavailable, true],
    [{ code: 'ETIMEDOUT' }, Dt3ErrorCode.TransportTimeout, true],
    [{ code: 'EACCES' }, Dt3ErrorCode.FileWriteFailed, false],
    [{ code: 'ENOENT' }, Dt3ErrorCode.FileWriteFailed, false],
    [{ code: 'EISDIR' }, Dt3ErrorCode.FileWriteFailed, false],
    [{ code: 'ENOSPC' }, Dt3ErrorCode.FileWriteFailed, false],
    [new Error('unknown'), Dt3ErrorCode.Unknown, false],
  ])('classifies %p as %s with retryable=%s', (error, code, retryable) => {
    const handler = new ErrorHandler({ diagnosticsEnabled: false });

    expect(handler.classify(error)).toEqual({ code, retryable });
  });

  it('reports each error to callbacks while rate limiting diagnostics per error code', () => {
    const diagnostics: string[] = [];
    const reports: Array<{ code: Dt3ErrorCode; type: string }> = [];
    const handler = new ErrorHandler({
      diagnosticsWrite: (line) => diagnostics.push(line),
      rateLimitPerMinute: 1,
      onError: (report) => reports.push({ code: report.code, type: report.type }),
    });

    handler.handle(new Error('sensitive first failure'), Dt3ErrorPhase.Delivery);
    handler.handle(new Error('sensitive second failure'), Dt3ErrorPhase.Delivery);

    expect(diagnostics).toHaveLength(1);
    expect(diagnostics[0]).toContain('code=DT3_UNKNOWN');
    expect(diagnostics[0]).toContain('type=Error');
    expect(diagnostics[0]).not.toContain('sensitive first failure');
    expect(reports).toEqual([
      { code: Dt3ErrorCode.Unknown, type: 'Error' },
      { code: Dt3ErrorCode.Unknown, type: 'Error' },
    ]);
    expect(handler.snapshot()).toEqual({ [Dt3ErrorCode.Unknown]: 2 });
  });

  it('sanitizes diagnostic context labels to prevent structured diagnostic injection', () => {
    const diagnostics: string[] = [];
    const handler = new ErrorHandler({
      diagnosticsWrite: (line) => diagnostics.push(line),
    });

    handler.report(new Error('delivery failed'), Dt3ErrorPhase.Delivery, {
      'request\nid': 'trusted\nvalue=forged',
    });

    expect(diagnostics).toEqual([
      expect.stringContaining('request_id=trusted_value_forged'),
    ]);
    expect(diagnostics[0]).toHaveLength(diagnostics[0].length);
    expect(diagnostics[0]).not.toContain('\nvalue=');
  });

  it('isolates failing diagnostic sinks and application callbacks in fail-open mode', () => {
    const handler = new ErrorHandler({
      diagnosticsWrite: () => {
        throw new Error('stderr unavailable');
      },
      onError: () => {
        throw new Error('callback unavailable');
      },
    });

    expect(() => handler.handle(new Error('delivery failed'), Dt3ErrorPhase.Delivery)).not.toThrow();
  });

  it('rethrows the original error in fail-closed mode after reporting it', () => {
    const reports: Array<{ code: Dt3ErrorCode; type: string }> = [];
    const handler = new ErrorHandler({
      failOpen: false,
      diagnosticsEnabled: false,
      onError: (report) => reports.push({ code: report.code, type: report.type }),
    });
    const failure = new Error('delivery failed');

    expect(() => handler.handle(failure, Dt3ErrorPhase.Delivery)).toThrow(failure);
    expect(reports).toEqual([{ code: Dt3ErrorCode.Unknown, type: 'Error' }]);
  });
});

describe('Logger ErrorHandler integration', () => {
  let logSpy: jest.SpyInstance;
  let stderrSpy: jest.SpyInstance;

  beforeEach(() => {
    logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    stderrSpy = jest.spyOn(process.stderr, 'write').mockImplementation(() => true);
  });

  afterEach(() => {
    logSpy.mockRestore();
    stderrSpy.mockRestore();
  });

  it('accepts a positive integer error-handler rate limit from logger configuration', () => {
    const logger = createLogger(loggerConfig({ 'error.rate_limit_per_minute': 1 }));

    expect(readErrorSnapshot(logger)).toEqual({});
    logger.close();
  });

  it.each([
    ['a string', '20'],
    ['a boolean', false],
    ['an object', {}],
    ['null', null],
    ['a decimal', 1.5],
    ['zero', 0],
    ['a negative integer', -1],
  ])('rejects %s supplied as error.rate_limit_per_minute', (_description, value) => {
    expect(() => createLogger(loggerConfig({ 'error.rate_limit_per_minute': value }))).toThrow(
      'error.rate_limit_per_minute must be a positive integer',
    );
  });

  it('reports constructor configuration failures before rethrowing them', () => {
    const reports: Array<{ code: Dt3ErrorCode; phase: Dt3ErrorPhase }> = [];

    expect(() =>
      createLogger(
        loggerConfig({
          fail_open: 'invalid',
          'error.on_error': (report: { code: Dt3ErrorCode; phase: Dt3ErrorPhase }) => reports.push(report),
        }),
      ),
    ).toThrow('fail_open must be a boolean');

    expect(reports).toEqual([
      expect.objectContaining({
        code: Dt3ErrorCode.ConfigurationInvalid,
        phase: Dt3ErrorPhase.Configuration,
      }),
    ]);
  });

  it.each([
    ['HTTP', 'http', 'exporter.http.endpoint'],
    ['OTLP', 'otlp', 'otlp.endpoint'],
  ])(
    'reports one %s asynchronous delivery failure when flush observes its rejection',
    async (_transportName, exporter, endpointKey) => {
      const endpoint = await createRejectingEndpoint();
      const reports: string[] = [];
      const logger = createLogger(
        loggerConfig({
          exporter,
          [endpointKey]: endpoint.endpoint,
          'error.on_error': (report: { code: string }) => reports.push(report.code),
        }),
      );

      try {
        logger.info('delivery failure', { 'event.name': 'ASYNC_DELIVERY_FAILURE' });

        await expect(logger.flush()).resolves.toBeUndefined();
        expect(reports).toEqual([Dt3ErrorCode.TransportRejected]);
        expect(stderrSpy).toHaveBeenCalledTimes(1);
        expect(readErrorSnapshot(logger)).toEqual({
          [Dt3ErrorCode.TransportRejected]: 1,
        });
      } finally {
        logger.close();
        await endpoint.close();
      }
    },
  );

  it.each([
    ['HTTP', 'http', 'exporter.http.endpoint', 'HttpTransportError'],
    ['OTLP', 'otlp', 'otlp.endpoint', 'OtlpTransportError'],
  ])(
    'retains a settled fail-closed %s delivery failure until a later flush consumes it',
    async (_transportName, exporter, endpointKey, errorName) => {
      const endpoint = await createRejectingEndpoint();
      const reports: string[] = [];
      let resolveReported!: () => void;
      const reported = new Promise<void>((resolve) => {
        resolveReported = resolve;
      });
      const logger = createLogger(
        loggerConfig({
          exporter,
          fail_open: false,
          [endpointKey]: endpoint.endpoint,
          'error.on_error': (report: { code: string }) => {
            reports.push(report.code);
            resolveReported();
          },
        }),
      );

      try {
        logger.info('delivery failure', { 'event.name': 'DELAYED_FLUSH_FAILURE' });

        // Wait until the transport observer has reported the settled rejection,
        // ensuring this flush starts after the promise has left `inFlight`.
        await reported;

        await expect(logger.flush()).rejects.toMatchObject({
          name: errorName,
          code: Dt3ErrorCode.TransportRejected,
        });
        expect(reports).toEqual([Dt3ErrorCode.TransportRejected]);
        expect(stderrSpy).toHaveBeenCalledTimes(1);
        expect(readErrorSnapshot(logger)).toEqual({
          [Dt3ErrorCode.TransportRejected]: 1,
        });

        // The retained failure is consumed by the first flush boundary.
        await expect(logger.flush()).resolves.toBeUndefined();
      } finally {
        logger.close();
        await endpoint.close();
      }
    },
  );

  it.each([
    ['HTTP', 'http', 'exporter.http.endpoint', 'httpTransport'],
    ['OTLP', 'otlp', 'otlp.endpoint', 'otlpTransport'],
  ])(
    'releases repeated fail-open %s delivery failures without waiting for flush',
    async (_transportName, exporter, endpointKey, transportKey) => {
      const endpoint = await createRejectingEndpoint();
      const reports: string[] = [];
      let resolveReports!: () => void;
      const reported = new Promise<void>((resolve) => {
        resolveReports = resolve;
      });
      const logger = createLogger(
        loggerConfig({
          exporter,
          [endpointKey]: endpoint.endpoint,
          'error.on_error': (report: { code: string }) => {
            reports.push(report.code);
            if (reports.length === 2) {
              resolveReports();
            }
          },
        }),
      );

      try {
        logger.info('first delivery failure', { 'event.name': 'FAIL_OPEN_RETENTION_FIRST' });
        logger.info('second delivery failure', { 'event.name': 'FAIL_OPEN_RETENTION_SECOND' });

        // The observer reports each settled failure immediately. No flush is
        // called, so any retained entries would remain reachable indefinitely.
        await reported;

        const transport = (
          logger as unknown as {
            httpTransport?: { failedDeliveries: Map<unknown, unknown> };
            otlpTransport?: { failedDeliveries: Map<unknown, unknown> };
          }
        )[transportKey as 'httpTransport' | 'otlpTransport'];
        expect(transport).toBeDefined();
        expect(transport!.failedDeliveries.size).toBe(0);
        expect(reports).toEqual([
          Dt3ErrorCode.TransportRejected,
          Dt3ErrorCode.TransportRejected,
        ]);
        expect(readErrorSnapshot(logger)).toEqual({
          [Dt3ErrorCode.TransportRejected]: 2,
        });
      } finally {
        logger.close();
        await endpoint.close();
      }
    },
  );

  it('enriches logger.error events with canonical code and retryability', () => {
    const logger = createLogger(loggerConfig());
    const error = new Dt3Error('collector timed out', {
      code: Dt3ErrorCode.TransportTimeout,
      retryable: true,
      phase: Dt3ErrorPhase.Delivery,
    });

    logger.error('delivery failed', error, { 'event.name': 'DELIVERY_FAILED' });

    const event = JSON.parse(logSpy.mock.calls[0][0] as string) as Record<string, unknown>;
    expect(event).toMatchObject({
      'error.type': 'Dt3Error',
      'error.message': 'collector timed out',
      'error.code': Dt3ErrorCode.TransportTimeout,
      'error.retryable': true,
    });
    logger.close();
  });

  it('keeps STRICT validation fail-closed while reporting the canonical validation error', () => {
    const reports: string[] = [];
    const logger = createLogger(
      loggerConfig({
        'error.on_error': (report: { code: string }) => reports.push(report.code),
      }),
    );

    expect(() => logger.info('invalid', { 'event.name': 'invalid-name' })).toThrow();
    expect(reports).toContain(Dt3ErrorCode.ValidationFailed);
    logger.close();
  });

  it('suppresses fail-open delivery failures and exposes their diagnostic snapshot', () => {
    const reports: string[] = [];
    const logger = createLogger(
      loggerConfig({
        exporter: 'file',
        'exporter.file.path': 'error-handler-fail-open.jsonl',
        'error.on_error': (report: { code: string }) => reports.push(report.code),
      }),
    );
    const transport = (
      logger as unknown as { fileTransport: { export: (event: unknown) => void } }
    ).fileTransport;
    jest.spyOn(transport, 'export').mockImplementation(() => {
      throw new Dt3Error('collector unavailable', {
        code: Dt3ErrorCode.TransportUnavailable,
        retryable: true,
      });
    });

    expect(() => logger.info('event', { 'event.name': 'FAIL_OPEN_EVENT' })).not.toThrow();
    expect(reports).toContain(Dt3ErrorCode.TransportUnavailable);
    expect(readErrorSnapshot(logger)).toEqual({
      [Dt3ErrorCode.TransportUnavailable]: 1,
    });
    logger.close();
  });

  it('rethrows normalized delivery failures when fail_open is false', () => {
    const logger = createLogger(
      loggerConfig({
        exporter: 'file',
        fail_open: false,
        'exporter.file.path': 'error-handler-fail-closed.jsonl',
      }),
    );
    const transport = (
      logger as unknown as { fileTransport: { export: (event: unknown) => void } }
    ).fileTransport;
    const failure = new Dt3Error('collector unavailable', {
      code: Dt3ErrorCode.TransportUnavailable,
      retryable: true,
    });
    jest.spyOn(transport, 'export').mockImplementation(() => {
      throw failure;
    });

    expect(() => logger.info('event', { 'event.name': 'FAIL_CLOSED_EVENT' })).toThrow(failure);
    logger.close();
  });

  it('reports and drops masking recursion failures in fail-open mode', () => {
    const reports: string[] = [];
    const logger = createLogger(
      loggerConfig({
        'error.on_error': (report: { code: string }) => reports.push(report.code),
      }),
    );
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;

    expect(() => logger.info('cyclic context', { 'event.name': 'CYCLIC_CONTEXT', cyclic })).not.toThrow();
    expect(reports).toContain(Dt3ErrorCode.MaskingFailed);
    expect(logSpy).not.toHaveBeenCalled();
    logger.close();
  });

  it('reports an aborted batch without retrying discarded events', () => {
    const reports: string[] = [];
    const logger = createLogger(
      loggerConfig({
        exporter: 'file',
        fail_open: false,
        'exporter.file.path': 'error-handler-batch-abort.jsonl',
        'batching.enabled': true,
        'batching.max_size': 1,
        'error.on_error': (report: { code: string }) => reports.push(report.code),
      }),
    );
    const transport = (
      logger as unknown as { fileTransport: { export: (event: unknown) => void } }
    ).fileTransport;
    jest.spyOn(transport, 'export').mockImplementation(() => {
      throw new Error('delivery failure');
    });

    expect(() => logger.info('first', { 'event.name': 'FIRST_EVENT' })).toThrow('delivery failure');
    expect(() => logger.info('discarded', { 'event.name': 'DISCARDED_EVENT' })).not.toThrow();
    expect(reports).toContain(Dt3ErrorCode.BatchAborted);
    logger.close();
  });

  it('reports lifecycle failures caused by logging after close', () => {
    const reports: string[] = [];
    const logger = createLogger(
      loggerConfig({
        'error.on_error': (report: { code: string }) => reports.push(report.code),
      }),
    );

    logger.close();

    expect(() => logger.info('after close', { 'event.name': 'AFTER_CLOSE' })).toThrow('Logger is closed');
    expect(reports).toContain(Dt3ErrorCode.LifecycleClosed);
  });

  it('classifies direct EventBatcher use after close as a lifecycle failure', () => {
    const handler = new ErrorHandler({ diagnosticsEnabled: false });
    const batcher = new EventBatcher(() => undefined, 1, 1000);
    batcher.close();

    let failure: unknown;
    try {
      batcher.add({ message: 'after close' } as never);
    } catch (error) {
      failure = error;
    }

    expect(failure).toBeInstanceOf(Dt3Error);
    expect(handler.classify(failure)).toEqual({
      code: Dt3ErrorCode.LifecycleClosed,
      retryable: false,
    });
    expect((failure as Dt3Error).phase).toBe(Dt3ErrorPhase.Lifecycle);
  });

  it('reports invalid file transport configuration as a configuration failure', () => {
    const reports: Array<{ code: Dt3ErrorCode; phase: Dt3ErrorPhase }> = [];

    expect(() =>
      createLogger(
        loggerConfig({
          exporter: 'file',
          'exporter.file.path': '   ',
          'error.on_error': (report: { code: Dt3ErrorCode; phase: Dt3ErrorPhase }) =>
            reports.push(report),
        }),
      ),
    ).toThrow('exporter.file.path must be configured for the file exporter');

    expect(reports).toEqual([
      expect.objectContaining({
        code: Dt3ErrorCode.ConfigurationInvalid,
        phase: Dt3ErrorPhase.Configuration,
      }),
    ]);
  });
});
