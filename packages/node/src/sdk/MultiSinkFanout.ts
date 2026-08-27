import { EventSink } from '../api/EventSink';
import { LogEvent } from '../api/types';

/**
 * Named sink registration used by {@link MultiSinkFanout}.
 */
export interface NamedEventSink {
  /** Optional diagnostic label for failure isolation. */
  name?: string;
  /** Destination that receives exported events. */
  sink: EventSink;
}

/**
 * Options controlling fan-out failure behavior.
 */
export interface MultiSinkFanoutOptions {
  /**
   * Invoked for each sink failure. Should record diagnostics without throwing
   * when isolation across remaining sinks is required.
   */
  onSinkError?: (error: unknown, sinkName: string) => void;

  /**
   * When true, rethrow the first sink failure after all sinks have been
   * attempted. Defaults to `false`.
   */
  rethrowFirstError?: boolean;
}

/**
 * Fans a single processed event out to one or more sinks with per-sink isolation.
 */
export class MultiSinkFanout implements EventSink {
  private readonly sinks: Array<{ name: string; sink: EventSink }> = [];
  private readonly onSinkError?: (error: unknown, sinkName: string) => void;
  private readonly rethrowFirstError: boolean;
  private closed = false;

  /**
   * Create a fan-out sink from an initial sink list.
   *
   * @param sinks - Initial sinks, optionally named for diagnostics.
   * @param options - Failure isolation and rethrow policy.
   */
  constructor(sinks: Array<EventSink | NamedEventSink> = [], options: MultiSinkFanoutOptions = {}) {
    this.onSinkError = options.onSinkError;
    this.rethrowFirstError = options.rethrowFirstError === true;

    for (const entry of sinks) {
      if (this.isNamedEntry(entry)) {
        this.register(entry.sink, entry.name);
      } else {
        this.register(entry);
      }
    }
  }

  /**
   * Register an additional sink for subsequent exports.
   *
   * @param sink - Destination to append.
   * @param name - Optional diagnostic label.
   */
  public register(sink: EventSink, name?: string): void {
    this.sinks.push({
      name: name && name.trim().length > 0 ? name : `sink-${this.sinks.length}`,
      sink,
    });
  }

  /**
   * Export one event to every registered sink.
   *
   * Sink failures are isolated: remaining sinks still receive the event.
   *
   * @param event - Final pipeline event ready for export.
   */
  public export(event: LogEvent): void {
    let firstError: unknown;

    for (const { name, sink } of this.sinks) {
      try {
        sink.export(event);
      } catch (error) {
        this.onSinkError?.(error, name);
        if (firstError === undefined) {
          firstError = error;
        }
      }
    }

    if (firstError !== undefined && this.rethrowFirstError) {
      throw firstError;
    }
  }

  /**
   * Flush every registered sink.
   */
  public async flush(): Promise<void> {
    let firstError: unknown;

    for (const { name, sink } of this.sinks) {
      try {
        await sink.flush();
      } catch (error) {
        this.onSinkError?.(error, name);
        if (firstError === undefined) {
          firstError = error;
        }
      }
    }

    if (firstError !== undefined && this.rethrowFirstError) {
      throw firstError;
    }
  }

  /**
   * Close every registered sink. Idempotent.
   */
  public async close(): Promise<void> {
    if (this.closed) {
      return;
    }

    this.closed = true;
    let firstError: unknown;

    for (const { name, sink } of this.sinks) {
      try {
        await sink.close();
      } catch (error) {
        this.onSinkError?.(error, name);
        if (firstError === undefined) {
          firstError = error;
        }
      }
    }

    if (firstError !== undefined && this.rethrowFirstError) {
      throw firstError;
    }
  }

  private isNamedEntry(entry: EventSink | NamedEventSink): entry is NamedEventSink {
    return (
      entry !== null &&
      typeof entry === 'object' &&
      'sink' in entry &&
      (entry as NamedEventSink).sink !== undefined
    );
  }
}
