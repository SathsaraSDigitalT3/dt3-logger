import { createServer, IncomingMessage, Server } from 'node:http';

import { createLogger, OtlpTransport, ValidationError, ValidationMode } from '../src';

const baseConfig = (
  validationMode: ValidationMode | string = ValidationMode.LENIENT,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> => ({
  'service.name': 'otlp-test-service',
  'service.version': '1.2.3',
  'deployment.environment': 'test',
  exporter: 'otlp',
  'otlp.endpoint': 'http://127.0.0.1:65534/v1/logs',
  'otlp.timeout': 1000,
  'validation.mode': validationMode,
  ...overrides,
});

const startOtlpServer = async (
  handler: (request: IncomingMessage, body: string, response: import('node:http').ServerResponse) => void,
): Promise<{ server: Server; endpoint: string }> => {
  const server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on('data', (chunk: Buffer) => chunks.push(chunk));
    request.on('end', () => {
      handler(request, Buffer.concat(chunks).toString('utf8'), response);
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('Unable to determine OTLP server address');
  }

  return { server, endpoint: `http://127.0.0.1:${address.port}/v1/logs` };
};

const recordFrom = (payload: Record<string, unknown>): Record<string, unknown> =>
  (((payload.resourceLogs as Record<string, unknown>[])[0].scopeLogs as Record<string, unknown>[])[0]
    .logRecords as Record<string, unknown>[])[0];

const attributesFrom = (entries: Record<string, unknown>[]): Record<string, Record<string, unknown>> =>
  Object.fromEntries(entries.map((entry) => [entry.key as string, entry.value as Record<string, unknown>]));

describe('OTLP transport', () => {
  it('exports a final canonical event as OTLP Logs JSON with configured headers', async () => {
    let capturedMethod: string | undefined;
    let capturedContentType: string | undefined;
    let capturedAuthorization: string | undefined;
    let capturedPayload: Record<string, unknown> | undefined;
    let resolveRequest: (() => void) | undefined;
    const received = new Promise<void>((resolve) => {
      resolveRequest = resolve;
    });
    const { server, endpoint } = await startOtlpServer((request, body, response) => {
      capturedMethod = request.method;
      capturedContentType = request.headers['content-type'];
      capturedAuthorization = request.headers.authorization;
      capturedPayload = JSON.parse(body) as Record<string, unknown>;
      resolveRequest?.();
      response.writeHead(202);
      response.end();
    });

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.STRICT, {
          'otlp.endpoint': endpoint,
          'otlp.headers': { Authorization: 'Bearer integration-token' },
        }),
      );

      logger.info('Connected', {
        'event.name': 'OTLP_EXPORT_STARTED',
        'tenant.id': 'tenant-17',
        attributes: { region: 'east' },
      });
      await received;

      expect(capturedMethod).toBe('POST');
      expect(capturedContentType).toBe('application/json');
      expect(capturedAuthorization).toBe('Bearer integration-token');

      const record = recordFrom(capturedPayload as Record<string, unknown>);
      const resourceLogs = (capturedPayload?.resourceLogs as Record<string, unknown>[])[0];
      const resourceAttributes = attributesFrom(
        (resourceLogs.resource as Record<string, unknown>).attributes as Record<string, unknown>[],
      );
      const logAttributes = attributesFrom(record.attributes as Record<string, unknown>[]);

      expect(record).toMatchObject({
        severityText: 'INFO',
        severityNumber: 9,
        body: { stringValue: 'Connected' },
      });
      expect(typeof record.timeUnixNano).toBe('string');
      expect(resourceAttributes['service.name']).toEqual({ stringValue: 'otlp-test-service' });
      expect(resourceAttributes['service.version']).toEqual({ stringValue: '1.2.3' });
      expect(resourceAttributes['deployment.environment']).toEqual({ stringValue: 'test' });
      expect(resourceAttributes['tenant.id']).toEqual({ stringValue: 'tenant-17' });
      expect(logAttributes['event.name']).toEqual({ stringValue: 'OTLP_EXPORT_STARTED' });
      expect(logAttributes.attributes).toEqual({
        kvlistValue: {
          values: [{ key: 'region', value: { stringValue: 'east' } }],
        },
      });
    } finally {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    }
  });

  it.each([
    ['DEBUG', 5],
    ['INFO', 9],
    ['WARN', 13],
    ['ERROR', 17],
  ])('maps %s severity and timestamps to OTLP fields', (severity, severityNumber) => {
    const payload = OtlpTransport.toOtlpPayload({
      timestamp: '1970-01-01T00:00:01.000Z',
      severity,
      message: 'Test event',
      'event.name': 'OTLP_TEST_EVENT',
      'schema.version': '1.0.0',
      'sdk.name': '@digitalt3/commons',
      'sdk.version': '0.1.0',
      'service.name': 'test-service',
      'service.version': '1.0.0',
      'deployment.environment': 'test',
    });

    expect(recordFrom(payload)).toMatchObject({
      timeUnixNano: '1000000000',
      severityText: severity,
      severityNumber,
      body: { stringValue: 'Test event' },
    });
  });

  it('exports only masked data and preserves LENIENT validation diagnostics', async () => {
    let capturedPayload = '';
    let resolveRequest: (() => void) | undefined;
    const received = new Promise<void>((resolve) => {
      resolveRequest = resolve;
    });
    const { server, endpoint } = await startOtlpServer((_, body, response) => {
      capturedPayload = body;
      resolveRequest?.();
      response.writeHead(202);
      response.end();
    });

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.LENIENT, {
          'otlp.endpoint': endpoint,
          'masking.track_masked_fields': true,
        }),
      );

      logger.info('Masked event', {
        'event.name': 'invalid-name',
        password: 'do-not-export',
        attributes: { token: 'nested-secret' },
      });
      await received;

      expect(capturedPayload).not.toContain('do-not-export');
      expect(capturedPayload).not.toContain('nested-secret');

      const record = recordFrom(JSON.parse(capturedPayload) as Record<string, unknown>);
      const attributes = attributesFrom(record.attributes as Record<string, unknown>[]);
      expect(attributes.password).toEqual({ stringValue: '[REDACTED]' });
      expect(attributes['dt3.security.masked_fields']).toEqual({
        arrayValue: {
          values: [{ stringValue: 'password' }, { stringValue: 'attributes.token' }],
        },
      });
      expect(attributes['dt3.validation.errors']).toEqual(
        expect.objectContaining({ arrayValue: expect.any(Object) }),
      );
    } finally {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    }
  });

  it('does not export when STRICT validation rejects the event', () => {
    const logger = createLogger(baseConfig(ValidationMode.STRICT));
    const transport = (logger as unknown as { otlpTransport: { export: (event: unknown) => void } })
      .otlpTransport;
    const exportSpy = jest.spyOn(transport, 'export');

    try {
      expect(() => logger.info('Invalid', { 'event.name': 'invalid-name' })).toThrow(ValidationError);
      expect(exportSpy).not.toHaveBeenCalled();
    } finally {
      exportSpy.mockRestore();
    }
  });

  it.each([
    [true, false],
    [false, true],
  ])('uses fail_open=%s for synchronous OTLP failures', (failOpen, shouldThrow) => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        fail_open: failOpen,
      }),
    );
    const transport = (logger as unknown as { otlpTransport: { export: (event: unknown) => void } })
      .otlpTransport;
    const exportError = new Error('OTLP export failed');
    const exportSpy = jest.spyOn(transport, 'export').mockImplementation(() => {
      throw exportError;
    });

    try {
      const operation = () => logger.info('OTLP failure', { 'event.name': 'OTLP_EXPORT_FAILED' });
      if (shouldThrow) {
        expect(operation).toThrow(exportError);
      } else {
        expect(operation).not.toThrow();
      }
      expect(exportSpy).toHaveBeenCalledTimes(1);
    } finally {
      exportSpy.mockRestore();
    }
  });

  it('makes a non-2xx OTLP response observable through fail-closed flush', async () => {
    const { server, endpoint } = await startOtlpServer((_, __, response) => {
      response.writeHead(500);
      response.end();
    });

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.STRICT, { 'otlp.endpoint': endpoint, fail_open: false }),
      );

      logger.info('Collector failure', { 'event.name': 'OTLP_EXPORT_FAILED' });
      await expect(logger.flush()).rejects.toThrow('OTLP export failed with status 500');
    } finally {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    }
  });

  it('keeps delivery failures fail-open when configured and leaves no rejected flush', async () => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        fail_open: true,
        'otlp.endpoint': 'http://127.0.0.1:65534/v1/logs',
      }),
    );

    logger.info('Collector unavailable', { 'event.name': 'OTLP_EXPORT_FAILED' });
    await expect(logger.flush()).resolves.toBeUndefined();
  });

  it('reports OTLP timeout failures through fail-closed flush', async () => {
    const { server, endpoint } = await startOtlpServer((_, __, response) => {
      setTimeout(() => {
        response.writeHead(202);
        response.end();
      }, 100);
    });

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.STRICT, {
          'otlp.endpoint': endpoint,
          'otlp.timeout': 20,
          fail_open: false,
        }),
      );

      logger.info('Slow collector', { 'event.name': 'OTLP_EXPORT_TIMEOUT' });
      await expect(logger.flush()).rejects.toThrow('OTLP export request timed out');
    } finally {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    }
  });

  it('waits for a delayed in-flight OTLP request before flush resolves', async () => {
    let finishResponse!: () => void;
    const responseCanFinish = new Promise<void>((resolve) => {
      finishResponse = resolve;
    });
    const { server, endpoint } = await startOtlpServer((_, __, response) => {
      finishResponse = () => {
        response.writeHead(202);
        response.end();
      };
    });

    try {
      const logger = createLogger(baseConfig(ValidationMode.STRICT, { 'otlp.endpoint': endpoint }));
      logger.info('Await transport', { 'event.name': 'OTLP_EXPORT_STARTED' });
      const flushing = logger.flush();
      let resolved = false;
      void flushing.then(() => {
        resolved = true;
      });

      await Promise.resolve();
      expect(resolved).toBe(false);
      finishResponse();
      await responseCanFinish;
      await expect(flushing).resolves.toBeUndefined();
    } finally {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    }
  });

  it('rejects invalid OTLP endpoint, timeout, and header configuration', () => {
    expect(() => createLogger(baseConfig(ValidationMode.LENIENT, { 'otlp.endpoint': '' }))).toThrow(
      'otlp.endpoint must be configured for the OTLP exporter',
    );
    expect(() => new OtlpTransport('http://collector.example.test/v1/logs', 0)).toThrow(
      'otlp.timeout must be greater than zero',
    );
    expect(() => new OtlpTransport('http://collector.example.test/v1/logs', 1000, {
      'X-Unsafe': 'value\r\nInjected: true',
    })).toThrow('otlp.headers must be a mapping of string header names to string values');
  });
});
