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
    expect(result.errors.some((error) => error.includes('deployment.environment'))).toBe(true);
    expect(result.errors.some((error) => error.includes('duration.ms'))).toBe(true);
    expect(result.errors.some((error) => error.includes('error.retryable'))).toBe(true);
    expect(result.errors.some((error) => error.includes('attributes'))).toBe(true);
    expect(result.errors.some((error) => error.includes('severity'))).toBe(true);
    expect(result.errors.every((error) => !error.includes('contains-secret-value'))).toBe(true);
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
      expect.arrayContaining([expect.stringContaining('event.name')]),
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
      expect.arrayContaining([expect.stringContaining('deployment.environment')]),
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

    logger.info('Invalid event', { 'event.name': 'not-valid', 'duration.ms': -1 });

    const event = readExportedEvent(logSpy);
    expect(event['event.name']).toBe('not-valid');
    expect(event['dt3.validation.errors']).toBeUndefined();
  });

  it('defaults to LENIENT validation and rejects unknown validation modes during initialization', () => {
    const defaultLogger = createLogger(baseConfig(undefined));
    defaultLogger.info('Invalid event', { 'event.name': 'not-valid' });

    expect(readExportedEvent(logSpy)['dt3.validation.errors']).toEqual(
      expect.arrayContaining([expect.stringContaining('event.name')]),
    );

    expect(() => createLogger(baseConfig('UNSUPPORTED'))).toThrow(
      'validation.mode must be one of STRICT, LENIENT, or OFF',
    );
  });
});
