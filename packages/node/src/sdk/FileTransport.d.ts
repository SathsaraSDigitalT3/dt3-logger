import { LogEvent } from '../api/types';
/**
 * Append final DT3 structured log events to a configured UTF-8 JSON Lines file.
 */
export declare class FileTransport {
    private readonly filePath;
    /**
     * Create a file transport for the configured destination.
     *
     * @param filePath - Destination path for append-only JSON Lines output.
     * @throws Error if the destination path is empty.
     */
    constructor(filePath: string);
    /**
     * Serialize and append one final DT3 event as a JSON Lines record.
     *
     * @param event - The already-masked and validation-processed canonical log event.
     */
    export(event: LogEvent): void;
    /**
     * Flush output written by this transport.
     *
     * Synchronous append operations are immediately committed, so no additional
     * work is required.
     */
    flush(): void;
}
