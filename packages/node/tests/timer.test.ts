import { createLogger, Logger, Timer, TimerImpl, ValidationMode } from '../src';

const baseConfig = (overrides: Record<string, unknown> = {}): Record<string, unknown> => ({
  'service.name': 'timer-test-service',
  'service.version': '1.0.0',
  'deployment.environment': 'test',
  'validation.mode': ValidationMode.STRICT,
  exporter: 'stdout',
  ...overrides,
});

const eventFromSpy = (spy: jest.SpyInstance, index = 0): Record<string, unknown> =>
  JSON.parse(spy.mock.calls[index][0] as string) as Record<string, unknown>;

describe('Timer API', () => {
  let logSpy: jest.SpyInstance;

  beforeEach(() => {
    logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
  });

  afterEach(() => {
    logSpy.mockRestore();
  });

  it('is available from the public package entry point and creates an unstarted timer', () => {
    const logger = createLogger(baseConfig());

    const timer: Timer = logger.createTimer('TIMER_STARTED');

    expect(timer).toBeInstanceOf(TimerImpl);
    expect(typeof (logger as Logger).createTimer).toBe('function');
    expect(typeof timer.start).toBe('function');
    expect(typeof timer.stop).toBe('function');
    expect(typeof timer.finish).toBe('function');
    expect(logSpy).not.toHaveBeenCalled();
  });

  it('starts, stops, returns a non-negative duration, and emits through the normal pipeline', () => {
    const logger = createLogger(baseConfig());
    const timer = logger.createTimer('ORDER_PROCESSING', { 'order.id': 'order-42' });

    expect(timer.start()).toBe(timer);
    const elapsedMs = timer.stop();

    expect(elapsedMs).toBeGreaterThanOrEqual(0);
    expect(logSpy).toHaveBeenCalledTimes(1);
    expect(eventFromSpy(logSpy)).toMatchObject({
      severity: 'INFO',
      message: 'ORDER_PROCESSING completed',
      'event.name': 'ORDER_PROCESSING',
      'order.id': 'order-42',
      'service.name': 'timer-test-service',
      'service.version': '1.0.0',
      'deployment.environment': 'test',
    });
    expect(eventFromSpy(logSpy)['duration.ms']).toBe(elapsedMs);
  });

  it('supports finish as a stop alias and retains active execution-scoped context', () => {
    const logger = createLogger(baseConfig());

    logger.withContext(
      {
        traceId: '11111111111111111111111111111111',
        correlationId: 'request-7',
      },
      () => {
        const elapsedMs = logger.createTimer('REQUEST_DURATION').start().finish();
        expect(elapsedMs).toBeGreaterThanOrEqual(0);
      },
    );

    expect(eventFromSpy(logSpy)).toMatchObject({
      'event.name': 'REQUEST_DURATION',
      'trace.id': '11111111111111111111111111111111',
      'correlation.id': 'request-7',
    });
  });

  it('keeps independent timers isolated and emits one event for each', () => {
    const logger = createLogger(baseConfig());

    logger.createTimer('FIRST_TIMER').start().stop();
    logger.createTimer('SECOND_TIMER').start().stop();

    expect(logSpy).toHaveBeenCalledTimes(2);
    expect(eventFromSpy(logSpy, 0)['event.name']).toBe('FIRST_TIMER');
    expect(eventFromSpy(logSpy, 1)['event.name']).toBe('SECOND_TIMER');
    expect(eventFromSpy(logSpy, 0)['duration.ms']).toEqual(expect.any(Number));
    expect(eventFromSpy(logSpy, 1)['duration.ms']).toEqual(expect.any(Number));
  });

  it('rejects invalid timer lifecycle transitions and emits only once', () => {
    const logger = createLogger(baseConfig());
    const timer = logger.createTimer('LIFECYCLE_TIMER');

    expect(() => timer.stop()).toThrow('Timer has not been started');
    timer.start();
    expect(() => timer.start()).toThrow('Timer has already been started');
    timer.stop();
    expect(() => timer.stop()).toThrow('Timer has already been stopped');
    expect(logSpy).toHaveBeenCalledTimes(1);
  });

  it('rejects blank timer names', () => {
    const logger = createLogger(baseConfig());

    expect(() => logger.createTimer('')).toThrow('name must not be blank');
    expect(() => logger.createTimer('   ')).toThrow('name must not be blank');
  });

  it('follows closed logger lifecycle behavior without emitting a completion event', () => {
    const logger = createLogger(baseConfig());
    const timer = logger.createTimer('CLOSED_TIMER').start();

    logger.close();

    expect(() => logger.createTimer('ANOTHER_TIMER')).toThrow('Logger is closed');
    expect(() => timer.stop()).toThrow('Logger is closed');
    expect(logSpy).not.toHaveBeenCalled();
  });
});
