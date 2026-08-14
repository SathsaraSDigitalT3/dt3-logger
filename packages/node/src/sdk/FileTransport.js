"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.FileTransport = void 0;
const node_fs_1 = require("node:fs");
const node_path_1 = require("node:path");
/**
 * Append final DT3 structured log events to a configured UTF-8 JSON Lines file.
 */
class FileTransport {
    filePath;
    /**
     * Create a file transport for the configured destination.
     *
     * @param filePath - Destination path for append-only JSON Lines output.
     * @throws Error if the destination path is empty.
     */
    constructor(filePath) {
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
    export(event) {
        (0, node_fs_1.mkdirSync)((0, node_path_1.dirname)(this.filePath), { recursive: true });
        (0, node_fs_1.appendFileSync)(this.filePath, `${JSON.stringify(event)}\n`, { encoding: 'utf8' });
    }
    /**
     * Flush output written by this transport.
     *
     * Synchronous append operations are immediately committed, so no additional
     * work is required.
     */
    flush() {
        // No-op: appendFileSync completes each write before returning.
    }
}
exports.FileTransport = FileTransport;
