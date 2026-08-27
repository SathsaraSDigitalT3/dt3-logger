package com.digitalt3.commons.sdk;

import com.digitalt3.commons.api.LogEvent;
import com.digitalt3.commons.api.LogTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Synchronously appends final DT3 structured events to a UTF-8 JSON Lines file.
 *
 * <p>This transport accepts events after the logger has completed masking and
 * validation. It intentionally does not duplicate pipeline processing.</p>
 *
 * @since 0.1.0
 */
public final class FileTransport implements LogTransport {

    private final Path filePath;

    /**
     * Create an append-only file transport.
     *
     * @param filePath destination path for JSON Lines output
     * @throws IllegalArgumentException if the path is null or blank
     */
    public FileTransport(String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException(
                "exporter.file.path must be configured for the file exporter"
            );
        }

        this.filePath = Path.of(filePath);
    }

    // PUBLIC_INTERFACE
    /**
     * Serialize and append one final canonical structured event as a JSON line.
     *
     * @param logEvent already-masked and validation-processed event
     * @throws Dt3SdkException if the destination cannot be created or written
     */
    @Override
    public synchronized void write(LogEvent logEvent) {
        Objects.requireNonNull(logEvent, "logEvent must not be null");
        writeJson(StdoutLogger.toJson(logEvent.toMap()));
    }

    /**
     * Append a serialized final event produced by the logger pipeline.
     *
     * @param serializedEvent canonical JSON event without a trailing line separator
     * @throws Dt3SdkException if the destination cannot be created or written
     */
    synchronized void writeJson(String serializedEvent) {
        Objects.requireNonNull(serializedEvent, "serializedEvent must not be null");
        if (serializedEvent.indexOf('\r') >= 0 || serializedEvent.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(
                "serializedEvent must not contain carriage-return or line-feed characters"
            );
        }

        try {
            Path parent = filePath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                filePath,
                serializedEvent + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new Dt3SdkException(
                "Unable to write log event to file exporter destination: " + filePath,
                exception,
                Dt3ErrorCode.FILE_WRITE_FAILED,
                false,
                Dt3ErrorPhase.DELIVERY
            );
        }
    }

    // PUBLIC_INTERFACE
    /**
     * Flush file output.
     *
     * <p>Each synchronous append is committed before {@link #write(LogEvent)}
     * returns, so no additional flush work is required.</p>
     */
    @Override
    public void flush() {
        // No-op: Files.writeString is synchronous.
    }

    // PUBLIC_INTERFACE
    /**
     * Shut down the transport.
     *
     * <p>The transport has no persistent resources because every write opens,
     * appends, and closes the destination atomically.</p>
     */
    @Override
    public void shutdown() {
        // No-op: this transport owns no persistent file handle.
    }
}
