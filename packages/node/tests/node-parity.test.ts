import {
  createLogger,
  extract,
  inject,
  LogEvent,
  Severity,
  ValidationError,
  ValidationMode,
} from '../src';

const baseConfig = (
  overrides: Record<string, unknown> = {},
): Record<string, unknown> => ({
  'service.name': 'node-parity-service',
  'service.version': '1.0.0',
  'deployment.environment': 'test',
  'validation.mode': ValidationMode.STRICT,
  exporter: 'stdout',
  ...overrides,
});

const eventFromSpy = (spy: jest.SpyInstance, index = 0): Record<string, unknown> =>
  JSON.parse(spy.mock.calls[index][0] as string) as Record<string, unknown>;

describe('Node SDK parity APIs', () => {
  let logSpy: jest.SpyInstance;

  beforeEach(() => {
    logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    logSpy.mockRestore();
  });

  it('exports canonical FATAL events through the normal pipeline', () => {
    const logger = createLogger(baseConfig());

    logger.fatal('Unrecoverable failure', {
      'event.name': 'UNRECOVERABLE_FAILURE',
      password: 'secret',
    });

    const emitted = eventFromSpy(logSpy);
    expect(emitted).toMatchObject({
      severity: Severity.FATAL,
      message: 'Unrecoverable failure',
      'event.name': 'UNRECOVERABLE_FAILURE',
      password: '[REDACTED]',
    });
  });

  it('processes valid canonical events and preserves compatible supplied fields', () => {
    const logger = createLogger(baseConfig());
    const event: LogEvent = {
      timestamp: '2020-01-01T00:00:00.000Z',
      severity: Severity.WARN,
      message: 'Canonical event',
      'event.name': 'CANONICAL_EVENT',
      'schema.version': '1.0.0',
      'sdk.name': '@custom/sdk',
      'sdk.version': '1.0.0',
      'service.name': 'caller-service',
      'service.version': '1.0.0',
      'deployment.environment': 'caller',
      'tenant.id': 'tenant-1',
      attributes: { requestId: 'request-1' },
    };

    logger.event(event);

    const emitted = eventFromSpy(logSpy);
    expect(emitted).toMatchObject({
      severity: Severity.WARN,
      message: 'Canonical event',
      'event.name': 'CANONICAL_EVENT',
      'tenant.id': 'tenant-1',
      attributes: { requestId: 'request-1' },
      'service.name': 'node-parity-service',
      'deployment.environment': 'test',
    });
    expect(event.timestamp).toBe('2020-01-01T00:00:00.000Z');
  });

  it('preserves validation behavior for direct canonical events', () => {
    const strictLogger = createLogger(baseConfig());
    expect(() =>
      strictLogger.event({
        timestamp: new Date().toISOString(),
        severity: Severity.INFO,
        message: 'Invalid direct event',
        'event.name': 'invalid',
        'schema.version': '1.0.0',
        'sdk.name': '@digitalt3/commons',
        'sdk.version': '0.1.0',
        'service.name': 'node-parity-service',
        'service.version': '1.0.0',
        'deployment.environment': 'test',
      }),
    ).toThrow(ValidationError);

    const lenientLogger = createLogger(baseConfig({ 'validation.mode': ValidationMode.LENIENT }));
    lenientLogger.event({
      timestamp: new Date().toISOString(),
      severity: Severity.INFO,
      message: 'Lenient direct event',
      'event.name': 'invalid',
      'schema.version': '1.0.0',
      'sdk.name': '@digitalt3/commons',
      'sdk.version': '0.1.0',
      'service.name': 'node-parity-service',
      'service.version': '1.0.0',
      'deployment.environment': 'test',
    });
    expect(eventFromSpy(logSpy)['dt3.validation.errors']).toEqual(
      expect.arrayContaining([expect.objectContaining({ field: 'event.name' })]),
    );
  });

  it('injects and extracts W3C, correlation, and tenant headers', () => {
    const headers: Record<string, string> = {};
    inject(
      {
        traceId: '4bf92f3577b34da6a3ce929d0e0e4736',
        spanId: '00f067aa0ba902b7',
        'trace.flags': '01',
        tracestate: 'vendor=value',
        correlationId: 'request-123',
        tenantId: 'tenant-42',
        tenantRegion: 'us-east',
        tenantEnvironment: 'production',
      },
      headers,
    );

    expect(headers).toEqual({
      traceparent: '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01',
      tracestate: 'vendor=value',
      'x-correlation-id': 'request-123',
      'x-tenant-id': 'tenant-42',
      'x-tenant-region': 'us-east',
      'x-tenant-environment': 'production',
    });

    expect(extract(headers)).toMatchObject({
      'trace.id': '4bf92f3577b34da6a3ce929d0e0e4736',
      'span.id': '00f067aa0ba902b7',
      'trace.flags': '01',
      tracestate: 'vendor=value',
      'correlation.id': 'request-123',
      'tenant.id': 'tenant-42',
      'tenant.region': 'us-east',
      'tenant.environment': 'production',
    });
  });

  it('ignores malformed traceparents without dropping valid tenant or correlation headers', () => {
    expect(
      extract({
        TraceParent: 'malformed',
        'X-Correlation-Id': 'correlation-1',
        'X-Tenant-Id': 'tenant-1',
      }),
    ).toEqual({
      'correlation.id': 'correlation-1',
      'tenant.id': 'tenant-1',
    });
  });

  it('enriches events with extracted tenant context and preserves async isolation', async () => {
    const logger = createLogger(baseConfig());
    const first = extract({
      'x-tenant-id': 'tenant-a',
      'x-correlation-id': 'correlation-a',
    });
    const second = extract({
      'x-tenant-id': 'tenant-b',
      'x-correlation-id': 'correlation-b',
    });

    await Promise.all([
      logger.withContext(first, async () => {
        await Promise.resolve();
        logger.info('First request', { 'event.name': 'FIRST_REQUEST' });
      }),
      logger.withContext(second, async () => {
        await Promise.resolve();
        logger.info('Second request', { 'event.name': 'SECOND_REQUEST' });
      }),
    ]);

    const events = logSpy.mock.calls.map((_, index) => eventFromSpy(logSpy, index));
    expect(events).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ 'tenant.id': 'tenant-a', 'correlation.id': 'correlation-a' }),
        expect.objectContaining({ 'tenant.id': 'tenant-b', 'correlation.id': 'correlation-b' }),
      ]),
    );
  });

  it('generates a scoped correlation ID only when configured and absent', () => {
    const disabled = createLogger(baseConfig());
    disabled.info('Disabled generation', { 'event.name': 'DISABLED_GENERATION' });
    expect(eventFromSpy(logSpy)['correlation.id']).toBeUndefined();

    const enabled = createLogger(baseConfig({ 'tracing.auto_generate_correlation_id': true }));
    enabled.withContext({}, () => {
      enabled.info('Generated one', { 'event.name': 'GENERATED_ONE' });
      enabled.info('Generated two', { 'event.name': 'GENERATED_TWO' });
    });

    const generatedOne = eventFromSpy(logSpy, 1)['correlation.id'];
    const generatedTwo = eventFromSpy(logSpy, 2)['correlation.id'];
    expect(generatedOne).toMatch(/^[0-9a-f-]{36}$/i);
    expect(generatedTwo).toBe(generatedOne);

    enabled.withContext({ correlationId: 'provided-correlation' }, () => {
      enabled.info('Existing value', { 'event.name': 'EXISTING_CORRELATION' });
    });
    expect(eventFromSpy(logSpy, 3)['correlation.id']).toBe('provided-correlation');
  });
});
