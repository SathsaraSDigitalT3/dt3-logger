import { createLogger } from '../src';

const config = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  'service.name': 'batching-test-service',
  'service.version': '1.0.0',
  'deployment.environment': 'test',
  exporter: 'stdout',
  'validation.mode': 'STRICT',
  'batching.enabled': true,
  'batching.max_size': 3,
  'batching.flush_interval_ms': 1000,
  ...overrides,
});

const exportedEvents = (logSpy: jest.SpyInstance): Record<string, unknown>[] =>
  logSpy.mock.calls.map(([payload]) => JSON.parse(payload as string) as Record<string, unknown>);

describe('Node logger batching', () => {
  let logSpy: jest.SpyInstance;

  beforeEach(() => {
    jest.useFakeTimers();
    logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    jest.useRealTimers();
    logSpy.mockRestore();
  });

  it('keeps immediate single-event delivery when batching is disabled by default', () => {
    const logger = createLogger({
      ...config(),
      'batching.enabled': false,
    });

    logger.info('Immediate', { 'event.name': 'IMMEDIATE_EVENT' });

    expect(exportedEvents(logSpy).map((event) => event['event.name'])).toEqual(['IMMEDIATE_EVENT']);
    logger.close();
  });

  it('accumulates under-sized batches until explicitly flushed', async () => {
    const logger = createLogger(config());

    logger.info('Buffered', { 'event.name': 'BUFFERED_EVENT' });
    expect(logSpy).not.toHaveBeenCalled();

    await logger.flush();
    expect(exportedEvents(logSpy).map((event) => event['event.name'])).toEqual(['BUFFERED_EVENT']);
    logger.close();
  });

  it('flushes exactly at the configured size and preserves event order across batches', () => {
    const logger = createLogger(config({ 'batching.max_size': 2 }));

    for (let sequence = 0; sequence < 5; sequence += 1) {
      logger.info(`Event ${sequence}`, { 'event.name': 'BATCH_SIZE_EVENT', sequence });
    }

    expect(exportedEvents(logSpy).map((event) => event.sequence)).toEqual([0, 1, 2, 3]);
    logger.close();
    expect(exportedEvents(logSpy).map((event) => event.sequence)).toEqual([0, 1, 2, 3, 4]);
  });

  it('flushes a pending batch when the configured timeout expires', () => {
    const logger = createLogger(config({ 'batching.flush_interval_ms': 25 }));

    logger.info('Timed', { 'event.name': 'TIMED_EVENT' });
    jest.advanceTimersByTime(24);
    expect(logSpy).not.toHaveBeenCalled();

    jest.advanceTimersByTime(1);
    expect(exportedEvents(logSpy).map((event) => event['event.name'])).toEqual(['TIMED_EVENT']);
    logger.close();
  });

  it('does not publish empty batches and resets the timer after a size flush', () => {
    const logger = createLogger(config({ 'batching.max_size': 2, 'batching.flush_interval_ms': 25 }));

    logger.info('One', { 'event.name': 'ONE' });
    logger.info('Two', { 'event.name': 'TWO' });
    jest.advanceTimersByTime(25);
    expect(exportedEvents(logSpy).map((event) => event['event.name'])).toEqual(['ONE', 'TWO']);

    logger.info('Three', { 'event.name': 'THREE' });
    jest.advanceTimersByTime(25);
    expect(exportedEvents(logSpy).map((event) => event['event.name'])).toEqual(['ONE', 'TWO', 'THREE']);
    logger.close();
  });

  it('flushes a partial batch during close and does not leave an active timer', () => {
    const logger = createLogger(config());

    logger.info('Closing', { 'event.name': 'CLOSE_EVENT' });
    logger.close();
    jest.runOnlyPendingTimers();

    expect(exportedEvents(logSpy).map((event) => event['event.name'])).toEqual(['CLOSE_EVENT']);
  });

  it.each([
    [true, false],
    [false, true],
  ])('applies fail_open=%s to a batch delivery failure', (failOpen, shouldThrow) => {
    const logger = createLogger(
      config({
        exporter: 'file',
        fail_open: failOpen,
        'batching.max_size': 1,
        'exporter.file.path': 'batching-failure.jsonl',
      }),
    );
    const transport = (
      logger as unknown as { fileTransport: { export: (event: unknown) => void } }
    ).fileTransport;
    const deliveryError = new Error('batch delivery failed');
    jest.spyOn(transport, 'export').mockImplementation(() => {
      throw deliveryError;
    });

    const operation = () => logger.info('Failure', { 'event.name': 'BATCH_FAILURE' });
    if (shouldThrow) {
      expect(operation).toThrow(deliveryError);
    } else {
      expect(operation).not.toThrow();
    }

    logger.close();
  });

  it('does not retry failed or unattempted events after a fail-closed batch failure', async () => {
    const logger = createLogger(
      config({
        exporter: 'file',
        fail_open: false,
        'batching.max_size': 3,
        'exporter.file.path': 'batching-abort.jsonl',
      }),
    );
    const transport = (
      logger as unknown as { fileTransport: { export: (event: Record<string, unknown>) => void } }
    ).fileTransport;
    const attempts: string[] = [];
    jest.spyOn(transport, 'export').mockImplementation((event) => {
      attempts.push(event['event.name'] as string);
      if (event['event.name'] === 'SECOND_EVENT') {
        throw new Error('collector unavailable');
      }
    });

    logger.info('First', { 'event.name': 'FIRST_EVENT' });
    logger.info('Second', { 'event.name': 'SECOND_EVENT' });
    expect(() => logger.info('Third', { 'event.name': 'THIRD_EVENT' })).toThrow('collector unavailable');

    await logger.flush();
    logger.info('After abort', { 'event.name': 'AFTER_ABORT_EVENT' });
    await logger.flush();
    logger.close();

    expect(attempts).toEqual(['FIRST_EVENT', 'SECOND_EVENT']);
  });

  it.each([
    ['batching.enabled', 'yes', 'batching.enabled'],
    ['batching.max_size', 0, 'batching.max_size'],
    ['batching.flush_interval_ms', 0, 'batching.flush_interval_ms'],
  ])('validates %s during initialization', (key, value, message) => {
    expect(() => createLogger(config({ [key]: value }))).toThrow(message);
  });

  it('uses the Python-compatible batching defaults when enabled', () => {
    const logger = createLogger(config({
      'batching.max_size': undefined,
      'batching.flush_interval_ms': undefined,
    }));

    for (let sequence = 0; sequence < 99; sequence += 1) {
      logger.info(`Event ${sequence}`, { 'event.name': 'DEFAULT_BATCH_EVENT', sequence });
    }
    expect(logSpy).not.toHaveBeenCalled();

    logger.info('Event 99', { 'event.name': 'DEFAULT_BATCH_EVENT', sequence: 99 });
    expect(logSpy).toHaveBeenCalledTimes(100);
    logger.close();
  });
});
