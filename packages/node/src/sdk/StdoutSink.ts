import { EventSink } from '../api/EventSink';
import { LogEvent } from '../api/types';

/**
 * Event sink that writes each canonical event as a JSON line to stdout.
 */
export class StdoutSink implements EventSink {
  /**
   * Serialize and write one event to stdout.
   *
   * @param event - Final pipeline event ready for export.
   */
  public export(event: LogEvent): void {
    console.log(JSON.stringify(event));
  }

  /**
   * Flush stdout sink state. Console writes are immediate, so this is a no-op.
   */
  public flush(): void {
    // No-op: console.log completes each write before returning.
  }

  /**
   * Close the stdout sink. Idempotent no-op.
   */
  public close(): void {
    // No-op: stdout does not own closable resources.
  }
}
