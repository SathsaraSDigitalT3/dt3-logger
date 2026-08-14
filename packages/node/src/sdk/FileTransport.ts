import { appendFileSync, mkdirSync } from 'node:fs';
import { dirname } from 'node:path';

import { LogEvent } from '../api/types';

/**
 * Append final DT3 structured log events to a configured UTF-8 JSON Lines file.
 */
export class FileTransport {
  private readonly filePath: string;

  /**
   * Create a file transport for the configured destination.
   *
   * @param filePath - Destination path for append-only JSON Lines output.
   * @throws Error if the destination path is empty.
   */
  constructor(filePath: string) {
    if (filePath.trim().length === 0) {
      throw new Error('exporter.file.path must be configured for the file exporter');
    }

    this.filePath = filePath;
  }

  /**
   * Serialize and append one final DT3 event as a JSON Lines record.
   *
   * @param event - The already-masked and validation-processed canonical log event.
   */
  public export(event: LogEvent): void {
    mkdirSync(dirname(this.filePath), { recursive: true });
    appendFileSync(this.filePath, `${JSON.stringify(event)}\n`, { encoding: 'utf8' });
  }

  /**
   * Flush output written by this transport.
   *
   * Synchronous append operations are immediately committed, so no additional
   * work is required.
   */
  public flush(): void {
    // No-op: appendFileSync completes each write before returning.
  }
}
