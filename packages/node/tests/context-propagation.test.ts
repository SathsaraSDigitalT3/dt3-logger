import { readFileSync, rmSync } from 'node:fs';
import { createServer, IncomingMessage, Server } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

import { createLogger, ValidationMode } from '../src';

const baseConfig = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  'service.name': 'context-test-service',
  'service.version': '1.0.0',
  'deployment.environment': 'test',
  'validation.mode': ValidationMode.STRICT,
  exporter: 'stdout',
  'tracing.auto_generate_ids': false,
  ...overrides,
});

const eventFromSpy = (spy: jest.SpyInstance, index = 0): Record<string, unknown> =>
  JSON.parse(spy.mock.calls[index][0] as string) as Record<string, unknown>;

const delay = (milliseconds: number): Promise<void> =>
  new Promise((resolve) => setTimeout(resolve, milliseconds));

const startServer = async (): Promise<{
  endpoint: string;
  events: Record<string, unknown>[];
  server: Server;
}> => {
  const events: Record<string, unknown>[] = [];
  const server = createServer((request: IncomingMessage, response) => {
    const chunks: Buffer[] = [];
    request.on('data', (chunk: Buffer) => chunks.push(chunk));
    request.on('end', () => {
      events.push(JSON.parse(Buffer.concat(chunks).toString('utf8')) as Record<string, unknown>);
      response.writeHead(202);
      response.end();
    });
  });

  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('Unable to determine test server address');
  }

  return { endpoint: `http://127.0.0.1:${address.port}/v1/events`, events, server };
};

describe('execution-scoped context propagation', () => {
  let logSpy: jest.SpyInstance;

  beforeEach(() => {
    logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    logSpy.mockRestore();
  });

  it('attaches canonical context fields to multiple log calls in a scope', () => {
    const logger = createLogger(baseConfig());

    logger.withContext(
      {
        traceId: '11111111111111111111111111111111',
        spanId: '1111111111111111',
        parentSpanId: '2222222222222222',
        correlationId: 'correlation-1',
      },
      () => {
        logger.info('Started', { 'event.name': 'REQUEST_STARTED' });
        logger.info('Completed', { 'event.name': 'REQUEST_COMPLETED' });
      },
    );

    expect(eventFromSpy(logSpy, 0)).toMatchObject({
      'trace.id': '11111111111111111111111111111111',
      'span.id': '1111111111111111',
      'parent.span.id': '2222222222222222',
      'correlation.id': 'correlation-1',
    });
    expect(eventFromSpy(logSpy, 1)).toMatchObject({
      'trace.id': '11111111111111111111111111111111',
      'span.id': '1111111111111111',
      'parent.span.id': '2222222222222222',
      'correlation.id': 'correlation-1',
    });
  });

  it('restores the parent scope after a nested context completes', () => {
    const logger = createLogger(baseConfig());

    logger.withContext({ traceId: '33333333333333333333333333333333', correlationId: 'parent-correlation' }, () => {
      logger.info('Parent before', { 'event.name': 'PARENT_BEFORE' });
      logger.withContext({ spanId: '3333333333333333', correlationId: 'child-correlation' }, () => {
        logger.info('Child', { 'event.name': 'CHILD' });
      });
      logger.info('Parent after', { 'event.name': 'PARENT_AFTER' });
    });
    logger.info('Outside', { 'event.name': 'OUTSIDE' });

    expect(eventFromSpy(logSpy, 0)).toMatchObject({
      'trace.id': '33333333333333333333333333333333',
      'correlation.id': 'parent-correlation',
    });
    expect(eventFromSpy(logSpy, 1)).toMatchObject({
      'trace.id': '33333333333333333333333333333333',
      'span.id': '3333333333333333',
      'correlation.id': 'child-correlation',
    });
    expect(eventFromSpy(logSpy, 2)).toMatchObject({
      'trace.id': '33333333333333333333333333333333',
      'correlation.id': 'parent-correlation',
    });
    expect(eventFromSpy(logSpy, 2)['span.id']).toBeUndefined();
    expect(eventFromSpy(logSpy, 3)['trace.id']).toBeUndefined();
  });

  it('keeps explicit event values ahead of active scoped values and logger-owned fields ahead of both', () => {
    const logger = createLogger(baseConfig());

    logger.withContext({ traceId: '44444444444444444444444444444444' }, () => {
      logger.warn('Override context', {
        'event.name': 'OVERRIDE_CONTEXT',
        'trace.id': '55555555555555555555555555555555',
        severity: 'ERROR',
        'service.name': 'caller-service',
      });
    });

    const event = eventFromSpy(logSpy);
    expect(event['trace.id']).toBe('55555555555555555555555555555555');
    expect(event.severity).toBe('WARN');
    expect(event['service.name']).toBe('context-test-service');
  });

  it('propagates through promises and async/await while concurrent scopes remain isolated', async () => {
    const logger = createLogger(baseConfig());

    await Promise.all([
      logger.withContext({ traceId: 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }, async () => {
        await delay(20);
        await Promise.resolve();
        logger.info('Request A', { 'event.name': 'REQUEST_A' });
      }),
      logger.withContext({ traceId: 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' }, async () => {
        await delay(5);
        await Promise.resolve();
        logger.info('Request B', { 'event.name': 'REQUEST_B' });
      }),
    ]);

    const events = logSpy.mock.calls.map((_, index) => eventFromSpy(logSpy, index));
    expect(events).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ 'event.name': 'REQUEST_A', 'trace.id': 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' }),
        expect.objectContaining({ 'event.name': 'REQUEST_B', 'trace.id': 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' }),
      ]),
    );
  });

  it('preserves existing behavior when no context is configured', () => {
    const logger = createLogger(baseConfig());

    logger.info('No context', { 'event.name': 'NO_CONTEXT' });

    const event = eventFromSpy(logSpy);
    expect(event['trace.id']).toBeUndefined();
    expect(event['span.id']).toBeUndefined();
    expect(event['parent.span.id']).toBeUndefined();
    expect(event['correlation.id']).toBeUndefined();
  });

  it('includes scoped context in file transport output', () => {
    const filePath = join(tmpdir(), `dt3-context-${Date.now()}.jsonl`);
    const logger = createLogger(
      baseConfig({ exporter: 'file', 'exporter.file.path': filePath }),
    );

    try {
      logger.withContext({ traceId: 'cccccccccccccccccccccccccccccccc' }, () => {
        logger.info('File context', { 'event.name': 'FILE_CONTEXT' });
      });

      const event = JSON.parse(readFileSync(filePath, 'utf8').trim()) as Record<string, unknown>;
      expect(event['trace.id']).toBe('cccccccccccccccccccccccccccccccc');
    } finally {
      rmSync(filePath, { force: true });
    }
  });

  it('includes scoped context in HTTP transport output', async () => {
    const { endpoint, events, server } = await startServer();
    const logger = createLogger(
      baseConfig({ exporter: 'http', 'exporter.http.endpoint': endpoint }),
    );

    try {
      logger.withContext({ traceId: 'dddddddddddddddddddddddddddddddd' }, () => {
        logger.info('HTTP context', { 'event.name': 'HTTP_CONTEXT' });
      });
      await logger.flush();

      expect(events).toHaveLength(1);
      expect(events[0]['trace.id']).toBe('dddddddddddddddddddddddddddddddd');
    } finally {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    }
  });

  it('includes scoped context in OTLP transport output', async () => {
    const { endpoint, events, server } = await startServer();
    const logger = createLogger(
      baseConfig({
        exporter: 'otlp',
        'otlp.endpoint': endpoint,
      }),
    );

    try {
      logger.withContext({ traceId: 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' }, () => {
        logger.info('OTLP context', { 'event.name': 'OTLP_CONTEXT' });
      });
      await logger.flush();

      const payload = events[0];
      const record = (((payload.resourceLogs as Record<string, unknown>[])[0]
        .scopeLogs as Record<string, unknown>[])[0].logRecords as Record<string, unknown>[])[0];
      const attributes = Object.fromEntries(
        (record.attributes as Array<Record<string, unknown>>).map((attribute) => [
          attribute.key,
          attribute.value,
        ]),
      );
      expect(attributes['trace.id']).toEqual({ stringValue: 'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee' });
    } finally {
      await new Promise<void>((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    }
  });
});
