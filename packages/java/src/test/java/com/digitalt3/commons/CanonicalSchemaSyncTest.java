package com.digitalt3.commons;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Keeps the JAR-embedded schema in sync with the monorepo canonical file.
 * Skips when the repository {@code schemas/} tree is absent (consumer installs).
 */
public class CanonicalSchemaSyncTest {

    @Test
    public void packagedSchemaMatchesRepositoryCanonical() throws IOException {
        Path basedir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path canonical = basedir.resolve("../../schemas/log-event.schema.json").normalize();
        Path packaged = basedir.resolve("src/main/resources/schemas/log-event.schema.json").normalize();

        assumeTrue(
                "Monorepo canonical schema not present; skipping sync check",
                Files.isRegularFile(canonical));
        assertTrue("Packaged schema missing: " + packaged, Files.isRegularFile(packaged));

        String canonicalJson = Files.readString(canonical, StandardCharsets.UTF_8).trim();
        String packagedJson = Files.readString(packaged, StandardCharsets.UTF_8).trim();
        assertEquals(
                "Java packaged schema differs from schemas/log-event.schema.json. "
                        + "Copy the canonical schema into src/main/resources/schemas/ before releasing.",
                canonicalJson,
                packagedJson);
    }
}
