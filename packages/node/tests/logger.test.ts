import { readFileSync, rmSync } from 'node:fs';
import { createServer, IncomingMessage, Server } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import {
  createLogger,
  LogEventValidator,
  MaskingEngine,
  ValidationError,
  ValidationMode,
} from '../src';

const baseConfig = (
  validationMode: ValidationMode | string = ValidationMode.LENIENT,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> => ({
  'service.name': 'test-service',
  'service.version': '1.0.0',
  'deployment.environment': 'test',
  exporter: 'stdout',
  'validation.mode': validationMode,
  ...overrides,
});

const readExportedEvent = (logSpy: jest.SpyInstance): Record<string, unknown> => {
  expect(logSpy).toHaveBeenCalledTimes(1);
  return JSON.parse(logSpy.mock.calls[0][0] as string) as Record<string, unknown>;
};

const createTemporaryLogPath = (): string =>
  join(tmpdir(), `dt3-node-file-transport-${Date.now()}-${Math.random().toString(16).slice(2)}.jsonl`);

const startHttpServer = async (
  handler: (request: IncomingMessage, body: string) => void,
): Promise<{ server: Server; endpoint: string }> => {
  const server = createServer((request, response) => {
    const chunks: Buffer[] = [];
    request.on('data', (chunk: Buffer) => chunks.push(chunk));
    request.on('end', () => {
      handler(request, Buffer.concat(chunks).toString('utf8'));
      response.writeHead(202);
      response.end();
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('Unable to determine HTTP test server address');
  }

  return { server, endpoint: `http://127.0.0.1:${address.port}/v1/events` };
};

describe('MaskingEngine', () => {
  it('masks default sensitive fields recursively without mutating source input', () => {
    const source = {
      password: 'root-secret',
      nested: { Token: 'nested-secret' },
      users: [{ email: 'person@example.test' }],
    };

    const result = new MaskingEngine({ trackMaskedFields: true }).mask(source);

    expect(result.data).toEqual({
      password: '[REDACTED]',
      nested: { Token: '[REDACTED]' },
      users: [{ email: '[REDACTED]' }],
    });
    expect(result.maskedFields).toEqual(['password', 'nested.Token', 'users[0].email']);
    expect(source).toEqual({
      password: 'root-secret',
      nested: { Token: 'nested-secret' },
      users: [{ email: 'person@example.test' }],
    });
  });

  it('supports custom fields, replacement values, and disabled masking', () => {
    const custom = new MaskingEngine({
      sensitiveFields: ['credential'],
      replacementValue: 'MASKED',
      trackMaskedFields: true,
    }).mask({ credential: 'private', password: 'private-password' });

    expect(custom.data).toEqual({ credential: 'MASKED', password: 'MASKED' });
    expect(custom.maskedFields).toEqual(['credential', 'password']);

    const disabled = new MaskingEngine({ enabled: false }).mask({ password: 'unchanged' });
    expect(disabled.data).toEqual({ password: 'unchanged' });
    expect(disabled.maskedFields).toEqual([]);
  });
});

describe('LogEventValidator', () => {
  const validEvent = (): Record<string, unknown> => ({
    timestamp: '2026-08-13T12:00:00.000Z',
    severity: 'ERROR',
    message: 'Request failed',
    'event.name': 'REQUEST_FAILED',
    'schema.version': '1.0.0',
    'sdk.name': '@digitalt3/commons',
    'sdk.version': '0.1.0',
    'service.name': 'test-service',
    'service.version': '1.0.0',
    'deployment.environment': 'test',
    'tenant.id': 'tenant-42',
    'tenant.region': 'us-east',
    'error.type': 'Error',
    'error.message': 'operation failed',
    'error.retryable': false,
    attributes: { request: { attempt: 2 } },
  });

  it('accepts canonical events containing tenant, error, and nested attribute fields', () => {
    const result = new LogEventValidator().validate(validEvent());

    expect(result).toEqual({ valid: true, errors: [], mode: ValidationMode.LENIENT });
  });

  it('reports missing required properties and schema rules without exposing caller values', () => {
    const invalid = validEvent();
    delete invalid['deployment.environment'];
    invalid.message = 'contains-secret-value';
    invalid['duration.ms'] = -1;
    invalid['error.retryable'] = 'yes';
    invalid.attributes = ['not-an-object'];
    invalid.severity = 'TRACE';

    const result = new LogEventValidator().validate(invalid);

    expect(result.valid).toBe(false);
    expect(result.errors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ field: 'deployment.environment', rule: 'required' }),
        expect.objectContaining({ field: 'duration.ms', rule: 'minimum' }),
        expect.objectContaining({ field: 'error.retryable', rule: 'type' }),
        expect.objectContaining({ field: 'attributes', rule: 'type' }),
        expect.objectContaining({ field: 'severity', rule: 'enum' }),
      ]),
    );
    expect(result.errors.every(({ field, message, rule }) =>
      typeof field === 'string' && typeof message === 'string' && typeof rule === 'string',
    )).toBe(true);
    expect(JSON.stringify(result.errors)).not.toContain('contains-secret-value');
  });

  it('reports malformed timestamps using the date-time format rule when validation is enabled', () => {
    const invalid = validEvent();
    invalid.timestamp = 'not-a-date-time';

    const lenientResult = new LogEventValidator().validate(invalid, ValidationMode.LENIENT);
    const strictResult = new LogEventValidator().validate(invalid, ValidationMode.STRICT);

    expect(lenientResult).toMatchObject({
      valid: false,
      mode: ValidationMode.LENIENT,
      errors: [expect.objectContaining({ field: 'timestamp', rule: 'format' })],
    });
    expect(strictResult).toMatchObject({
      valid: false,
      mode: ValidationMode.STRICT,
      errors: [expect.objectContaining({ field: 'timestamp', rule: 'format' })],
    });
  });

  it('skips validation in OFF mode', () => {
    const result = new LogEventValidator().validate(
      { severity: 'TRACE', message: '', 'event.name': 'not-valid' },
      ValidationMode.OFF,
    );

    expect(result).toEqual({ valid: true, errors: [], mode: ValidationMode.OFF });
  });
});

describe('LoggerImpl behavior', () => {
  let logSpy: jest.SpyInstance;

  beforeEach(() => {
    logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    logSpy.mockRestore();
  });

  it.each(['DEBUG', 'INFO', 'WARN'] as const)('exports structured %s events with standard metadata and context', (severity) => {
    const logger = createLogger(baseConfig());

    if (severity === 'DEBUG') {
      logger.debug('test message', { 'event.name': 'TEST_EVENT', requestId: 'request-1' });
    } else if (severity === 'INFO') {
      logger.info('test message', { 'event.name': 'TEST_EVENT', requestId: 'request-1' });
    } else {
      logger.warn('test message', { 'event.name': 'TEST_EVENT', requestId: 'request-1' });
    }

    const event = readExportedEvent(logSpy);
    expect(event).toMatchObject({
      severity,
      message: 'test message',
      'event.name': 'TEST_EVENT',
      'service.name': 'test-service',
      'service.version': '1.0.0',
      'deployment.environment': 'test',
      requestId: 'request-1',
    });
    expect(typeof event.timestamp).toBe('string');
  });

  it('keeps logger-method severity when caller context attempts to override it', () => {
    const logger = createLogger(baseConfig());

    logger.warn('Reserved severity', {
      'event.name': 'RESERVED_SEVERITY',
      severity: 'ERROR',
    });

    expect(readExportedEvent(logSpy).severity).toBe('WARN');
  });

  it('exports error detail, stack, tenant fields, and structured attributes', () => {
    const logger = createLogger(baseConfig());
    const error = new Error('database unavailable');

    logger.error('Database request failed', error, {
      'event.name': 'DATABASE_REQUEST_FAILED',
      'tenant.id': 'tenant-42',
      'tenant.environment': 'production',
      attributes: { operation: 'read' },
    });

    const event = readExportedEvent(logSpy);
    expect(event).toMatchObject({
      severity: 'ERROR',
      'tenant.id': 'tenant-42',
      'tenant.environment': 'production',
      'error.type': 'Error',
      'error.message': 'database unavailable',
      attributes: { operation: 'read' },
    });
    expect(typeof event['error.stack']).toBe('string');
  });

  it('runs masking before LENIENT validation and preserves the caller context', () => {
    const logger = createLogger(baseConfig(ValidationMode.LENIENT, {
      'masking.track_masked_fields': true,
    }));
    const context = {
      'event.name': 'invalid_event',
      attributes: {
        credentials: { token: 'sensitive-token' },
        request: { id: 'request-1' },
      },
    };

    logger.info('Validation with masked context', context);

    const event = readExportedEvent(logSpy);
    expect((event.attributes as Record<string, unknown>).credentials).toEqual({ token: '[REDACTED]' });
    expect(event['dt3.security.masked_fields']).toEqual(['attributes.credentials.token']);
    expect(event['dt3.validation.errors']).toEqual(
      expect.arrayContaining([expect.objectContaining({ field: 'event.name', rule: 'pattern' })]),
    );
    expect(JSON.stringify(event['dt3.validation.errors'])).not.toContain('sensitive-token');
    expect(context.attributes.credentials.token).toBe('sensitive-token');
  });

  it('throws in STRICT mode and does not export an invalid event', () => {
    const logger = createLogger(baseConfig(ValidationMode.STRICT));

    expect(() => logger.info('Invalid event', { 'event.name': 'not-valid' })).toThrow(ValidationError);
    expect(logSpy).not.toHaveBeenCalled();
  });

  it('reports missing deployment metadata in LENIENT mode without synthesizing an unknown value', () => {
    const logger = createLogger(
      baseConfig(ValidationMode.LENIENT, { 'deployment.environment': undefined }),
    );

    logger.info('Missing deployment environment', { 'event.name': 'MISSING_DEPLOYMENT_ENVIRONMENT' });

    const event = readExportedEvent(logSpy);
    expect(event['deployment.environment']).toBeUndefined();
    expect(event['dt3.validation.errors']).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ field: 'deployment.environment', rule: 'required' }),
      ]),
    );
  });

  it('rejects missing deployment metadata in STRICT mode and does not export the event', () => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, { 'deployment.environment': undefined }),
    );

    expect(() =>
      logger.info('Missing deployment environment', { 'event.name': 'MISSING_DEPLOYMENT_ENVIRONMENT' }),
    ).toThrow(ValidationError);
    expect(logSpy).not.toHaveBeenCalled();
  });

  it('exports invalid events without validation metadata in OFF mode', () => {
    const logger = createLogger(baseConfig(ValidationMode.OFF));

    logger.info('Invalid event', {
      timestamp: 'not-a-date-time',
      'event.name': 'not-valid',
      'duration.ms': -1,
    });

    const event = readExportedEvent(logSpy);
    expect(event['event.name']).toBe('not-valid');
    expect(typeof event.timestamp).toBe('string');
    expect(event.timestamp).not.toBe('not-a-date-time');
    expect(event['dt3.validation.errors']).toBeUndefined();
  });

  it('defaults to LENIENT validation and rejects unknown validation modes during initialization', () => {
    const defaultLogger = createLogger(baseConfig(undefined));
    defaultLogger.info('Invalid event', { 'event.name': 'not-valid' });

    expect(readExportedEvent(logSpy)['dt3.validation.errors']).toEqual(
      expect.arrayContaining([expect.objectContaining({ field: 'event.name', rule: 'pattern' })]),
    );

    expect(() => createLogger(baseConfig('UNSUPPORTED'))).toThrow(
      'validation.mode must be one of STRICT, LENIENT, or OFF',
    );
  });
});

describe('File transport', () => {
  it('writes a canonical structured record through the existing logger API', () => {
    const filePath = createTemporaryLogPath();
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        exporter: 'file',
        'exporter.file.path': filePath,
      }),
    );

    try {
      logger.info('File transport started', { 'event.name': 'FILE_TRANSPORT_STARTED' });
      logger.flush();

      const lines = readFileSync(filePath, 'utf8').trim().split('\n');
      expect(lines).toHaveLength(1);

      const event = JSON.parse(lines[0]) as Record<string, unknown>;
      expect(event).toMatchObject({
        severity: 'INFO',
        message: 'File transport started',
        'event.name': 'FILE_TRANSPORT_STARTED',
        'schema.version': '1.1.0',
        'sdk.name': '@digitalt3/commons',
        'sdk.version': '0.1.0',
        'service.name': 'test-service',
        'service.version': '1.0.0',
        'deployment.environment': 'test',
      });
      expect(typeof event.timestamp).toBe('string');
    } finally {
      rmSync(filePath, { force: true });
    }
  });

  it('requires a configured file path for the file exporter', () => {
    expect(() => createLogger(baseConfig(ValidationMode.LENIENT, { exporter: 'file' }))).toThrow(
      'exporter.file.path must be configured for the file exporter',
    );
  });

  it('swallows file export failures when fail_open is enabled', () => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        exporter: 'file',
        'exporter.file.path': join(tmpdir(), 'dt3-node-fail-open', 'transport.jsonl'),
      }),
    );
    const transport = (logger as unknown as { fileTransport: { export: (event: unknown) => void } })
      .fileTransport;
    const exportError = new Error('file export failed');
    const exportSpy = jest.spyOn(transport, 'export').mockImplementation(() => {
      throw exportError;
    });

    try {
      expect(() =>
        logger.info('Continue after export failure', { 'event.name': 'FILE_EXPORT_FAILED' }),
      ).not.toThrow();
      expect(exportSpy).toHaveBeenCalledTimes(1);
    } finally {
      exportSpy.mockRestore();
    }
  });

  it('propagates file export failures when fail_open is disabled', () => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        exporter: 'file',
        fail_open: false,
        'exporter.file.path': join(tmpdir(), 'dt3-node-fail-closed', 'transport.jsonl'),
      }),
    );
    const transport = (logger as unknown as { fileTransport: { export: (event: unknown) => void } })
      .fileTransport;
    const exportError = new Error('file export failed');
    const exportSpy = jest.spyOn(transport, 'export').mockImplementation(() => {
      throw exportError;
    });

    try {
      expect(() =>
        logger.info('Propagate export failure', { 'event.name': 'FILE_EXPORT_FAILED' }),
      ).toThrow(exportError);
      expect(exportSpy).toHaveBeenCalledTimes(1);
    } finally {
      exportSpy.mockRestore();
    }
  });
});

describe('HTTP transport', () => {
  it('POSTs the final canonical event as application/json through the logger pipeline', async () => {
    let capturedMethod: string | undefined;
    let capturedContentType: string | undefined;
    let capturedEvent: Record<string, unknown> | undefined;
    let resolveRequest: (() => void) | undefined;
    const received = new Promise<void>((resolve) => {
      resolveRequest = resolve;
    });
    const { server, endpoint } = await startHttpServer((request, body) => {
      capturedMethod = request.method;
      capturedContentType = request.headers['content-type'];
      capturedEvent = JSON.parse(body) as Record<string, unknown>;
      resolveRequest?.();
    });

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.STRICT, {
          exporter: 'http',
          'exporter.http.endpoint': endpoint,
          'exporter.http.timeout_ms': 1000,
        }),
      );

      logger.info('HTTP transport started', {
        'event.name': 'HTTP_TRANSPORT_STARTED',
        attributes: { region: 'us-east' },
      });
      await received;

      expect(capturedMethod).toBe('POST');
      expect(capturedContentType).toBe('application/json');
      expect(capturedEvent).toMatchObject({
        severity: 'INFO',
        message: 'HTTP transport started',
        'event.name': 'HTTP_TRANSPORT_STARTED',
        'schema.version': '1.1.0',
        'sdk.name': '@digitalt3/commons',
        'sdk.version': '0.1.0',
        'service.name': 'test-service',
        'service.version': '1.0.0',
        'deployment.environment': 'test',
        attributes: { region: 'us-east' },
      });
      expect(typeof capturedEvent?.timestamp).toBe('string');
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });

  it('prefers canonical exporter.http.timeout over the deprecated _ms alias', async () => {
    const { server, endpoint } = await startHttpServer(() => undefined);

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.STRICT, {
          exporter: 'http',
          'exporter.http.endpoint': endpoint,
          'exporter.http.timeout': 1000,
          'exporter.http.timeout_ms': 0,
        }),
      );

      logger.info('Canonical timeout', { 'event.name': 'CANONICAL_TIMEOUT' });
      await expect(logger.flush()).resolves.toBeUndefined();
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });

  it('masks sensitive fields before sending an HTTP request', async () => {
    let capturedPayload = '';
    let resolveRequest: (() => void) | undefined;
    const received = new Promise<void>((resolve) => {
      resolveRequest = resolve;
    });
    const { server, endpoint } = await startHttpServer((_, body) => {
      capturedPayload = body;
      resolveRequest?.();
    });

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.LENIENT, {
          exporter: 'http',
          'exporter.http.endpoint': endpoint,
          'masking.track_masked_fields': true,
        }),
      );

      logger.info('Sensitive HTTP event', {
        'event.name': 'SENSITIVE_HTTP_EVENT',
        password: 'do-not-export',
        attributes: { token: 'nested-secret' },
      });
      await received;

      const event = JSON.parse(capturedPayload) as Record<string, unknown>;
      expect(capturedPayload).not.toContain('do-not-export');
      expect(capturedPayload).not.toContain('nested-secret');
      expect(event.password).toBe('[REDACTED]');
      expect(event['dt3.security.masked_fields']).toEqual(['password', 'attributes.token']);
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });

  it('does not initiate an HTTP request when STRICT validation rejects an event', () => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        exporter: 'http',
        'exporter.http.endpoint': 'http://127.0.0.1:65534/v1/events',
      }),
    );
    const transport = (logger as unknown as { httpTransport: { export: (event: unknown) => void } })
      .httpTransport;
    const exportSpy = jest.spyOn(transport, 'export');

    try {
      expect(() => logger.info('Invalid event', { 'event.name': 'invalid-name' })).toThrow(ValidationError);
      expect(exportSpy).not.toHaveBeenCalled();
    } finally {
      exportSpy.mockRestore();
    }
  });

  it.each([
    [true, false],
    [false, true],
  ])('uses fail_open=%s for synchronous transport failures', (failOpen, shouldThrow) => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        exporter: 'http',
        fail_open: failOpen,
        'exporter.http.endpoint': 'http://127.0.0.1:65534/v1/events',
      }),
    );
    const transport = (logger as unknown as { httpTransport: { export: (event: unknown) => void } })
      .httpTransport;
    const exportError = new Error('HTTP export failed');
    const exportSpy = jest.spyOn(transport, 'export').mockImplementation(() => {
      throw exportError;
    });

    try {
      const operation = () => logger.info('HTTP export failure', { 'event.name': 'HTTP_EXPORT_FAILED' });
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

  it('makes a non-2xx HTTP response observable through fail-closed flush', async () => {
    const server = createServer((request, response) => {
      request.resume();
      request.on('end', () => {
        response.writeHead(500);
        response.end();
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const address = server.address();
    if (address === null || typeof address === 'string') {
      throw new Error('Unable to determine HTTP test server address');
    }

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.STRICT, {
          exporter: 'http',
          fail_open: false,
          'exporter.http.endpoint': `http://127.0.0.1:${address.port}/v1/events`,
        }),
      );

      logger.info('HTTP failure', { 'event.name': 'HTTP_EXPORT_FAILED' });
      await expect(logger.flush()).rejects.toThrow('HTTP export failed with status 500');
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });

  it('swallows asynchronous HTTP delivery failures when fail_open is enabled', async () => {
    const logger = createLogger(
      baseConfig(ValidationMode.STRICT, {
        exporter: 'http',
        fail_open: true,
        'exporter.http.endpoint': 'http://127.0.0.1:65534/v1/events',
      }),
    );

    logger.info('HTTP unavailable', { 'event.name': 'HTTP_EXPORT_FAILED' });
    await expect(logger.flush()).resolves.toBeUndefined();
  });

  it('rejects unsafe generic HTTP headers during initialization', () => {
    expect(() =>
      createLogger(
        baseConfig(ValidationMode.STRICT, {
          exporter: 'http',
          'exporter.http.endpoint': 'http://collector.example.test/v1/events',
          'exporter.http.headers': { 'X-Unsafe': 'value\r\nInjected: true' },
        }),
      )
    ).toThrow('exporter.http.headers must be a mapping of safe string header names to string values');
  });

  it('closes idempotently and rejects subsequent logging and flush operations', async () => {
    const logger = createLogger(baseConfig());

    logger.close();
    logger.close();

    expect(() => logger.info('Closed', { 'event.name': 'CLOSED_LOGGER' })).toThrow('Logger is closed');
    await expect(logger.flush()).rejects.toThrow('Logger is closed');
  });

  it('sends LENIENT validation diagnostics to HTTP', async () => {
    let capturedEvent: Record<string, unknown> | undefined;
    let resolveRequest: (() => void) | undefined;
    const received = new Promise<void>((resolve) => {
      resolveRequest = resolve;
    });
    const { server, endpoint } = await startHttpServer((_, body) => {
      capturedEvent = JSON.parse(body) as Record<string, unknown>;
      resolveRequest?.();
    });

    try {
      const logger = createLogger(
        baseConfig(ValidationMode.LENIENT, {
          exporter: 'http',
          'exporter.http.endpoint': endpoint,
        }),
      );

      logger.info('Invalid event', { 'event.name': 'invalid-name' });
      await received;

      expect(capturedEvent?.['dt3.validation.errors']).toEqual(
        expect.arrayContaining([expect.objectContaining({ field: 'event.name', rule: 'pattern' })]),
      );
    } finally {
      await new Promise<void>((resolve, reject) => server.close((error) => (error ? reject(error) : resolve())));
    }
  });
});
