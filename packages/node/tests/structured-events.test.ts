import { createLogger, EventEmitter, MaskingEngine, buildApiEvent, buildAiEvent } from '../src';
import { EventSink } from '../src/api/EventSink';
import { LogEvent } from '../src/api/types';

class CapturingSink implements EventSink {
  events: LogEvent[] = [];

  export(event: LogEvent): void {
    this.events.push({ ...event });
  }

  flush(): void {}
  close(): void {}
}

class FailingSink implements EventSink {
  export(): void {
    throw new Error('sink failed');
  }
  flush(): void {}
  close(): void {}
}

const baseConfig = (overrides: Record<string, unknown> = {}) => ({
  'service.name': 'test-service',
  'service.version': '1.0.0',
  'deployment.environment': 'test',
  'validation.mode': 'OFF',
  exporter: 'stdout',
  ...overrides,
});

describe('structured event framework', () => {
  it('auto-generates event.id and defaults schema.version to 1.1.0', () => {
    const sink = new CapturingSink();
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const logger = createLogger(
      baseConfig({
        sinks: [sink],
        'component.name': 'orders-api',
      }),
    );

    logger.info('hello', { 'event.name': 'TEST_EVENT' });

    expect(sink.events).toHaveLength(1);
    expect(sink.events[0]['event.id']).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
    );
    expect(sink.events[0]['schema.version']).toBe('1.1.0');
    expect(sink.events[0]['component.name']).toBe('orders-api');
    logSpy.mockRestore();
    logger.close();
  });

  it('fans out to registered sinks with failure isolation', () => {
    const good = new CapturingSink();
    const logger = createLogger(baseConfig({ fail_open: true }));
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);

    logger.registerSink?.(new FailingSink(), 'bad');
    logger.registerSink?.(good, 'good');
    logger.info('fan', { 'event.name': 'FANOUT' });

    expect(good.events).toHaveLength(1);
    expect(good.events[0]['event.name']).toBe('FANOUT');
    logSpy.mockRestore();
    logger.close();
  });

  it('emits typed API and AI events via EventEmitter', () => {
    const sink = new CapturingSink();
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const logger = createLogger(baseConfig({ sinks: [sink] }));
    const emitter = new EventEmitter(logger);

    emitter.emit(buildApiEvent('INCOMING_HTTP', {
      message: 'GET /users',
      'http.request.method': 'GET',
      'http.route': '/users',
      'http.response.status_code': 200,
      'duration.ms': 11,
    }));
    emitter.emitAi('AI_RESPONSE_RECEIVED', {
      message: 'done',
      'kavia.provider': 'openai',
      'kavia.model': 'gpt-4o',
      'kavia.request.id': 'req-1',
      'kavia.tokens.prompt': 10,
      'kavia.tokens.completion': 5,
      'kavia.tokens.total': 15,
    });

    expect(sink.events.length).toBeGreaterThanOrEqual(2);
    const api = sink.events.find((e) => e['event.name'] === 'INCOMING_HTTP');
    const ai = sink.events.find((e) => e['event.name'] === 'AI_RESPONSE_RECEIVED');
    expect(api?.['http.request.method']).toBe('GET');
    expect(ai?.['kavia.tokens.prompt']).toBe(10);
    logSpy.mockRestore();
    logger.close();
  });

  it('masks kavia.prompt but not kavia.tokens.prompt', () => {
    const engine = new MaskingEngine({ trackMaskedFields: true });
    const { data } = engine.mask({
      'kavia.prompt': 'secret prompt',
      'kavia.tokens.prompt': 42,
      'kavia.response': 'secret response',
    });
    expect(data['kavia.prompt']).toBe('[REDACTED]');
    expect(data['kavia.response']).toBe('[REDACTED]');
    expect(data['kavia.tokens.prompt']).toBe(42);
  });

  it('creates nested spans with parent.span.id', () => {
    const sink = new CapturingSink();
    const logSpy = jest.spyOn(console, 'log').mockImplementation(() => undefined);
    const logger = createLogger(
      baseConfig({
        sinks: [sink],
        'tracing.span_events.enabled': true,
      }),
    );
    const tracer = logger.createTracer!();

    tracer.withSpan('outer', (outer) => {
      expect(outer.traceId).toMatch(/^[a-f0-9]{32}$/);
      expect(outer.spanId).toMatch(/^[a-f0-9]{16}$/);
      tracer.withSpan('inner', (inner) => {
        expect(inner.traceId).toBe(outer.traceId);
        expect(inner.parentSpanId).toBe(outer.spanId);
        logger.info('inside', { 'event.name': 'INSIDE_SPAN' });
      });
    });

    const inside = sink.events.find((e) => e['event.name'] === 'INSIDE_SPAN');
    expect(inside?.['trace.id']).toMatch(/^[a-f0-9]{32}$/);
    expect(inside?.['span.id']).toMatch(/^[a-f0-9]{16}$/);
    expect(inside?.['parent.span.id']).toMatch(/^[a-f0-9]{16}$/);
    logSpy.mockRestore();
    logger.close();
  });
});
