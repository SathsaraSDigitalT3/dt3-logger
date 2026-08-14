package com.digitalt3.commons.sdk;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

/**
 * White-box regression tests for package-private FileTransport serialization safeguards.
 */
public class FileTransportTest {

    @Test
    public void rejectsSerializedPayloadsWithRawLineBreaks() throws IOException {
        Path logFile = temporaryLogFile();
        FileTransport transport = new FileTransport(logFile.toString());

        assertThrows(
            IllegalArgumentException.class,
            () -> transport.writeJson("{\"message\":\"first\"}\n{\"message\":\"second\"}")
        );

        assertFalse(Files.exists(logFile));
    }

    private Path temporaryLogFile() throws IOException {
        Path directory = Files.createTempDirectory("dt3-file-transport");
        return directory.resolve("events.jsonl");
    }
}
