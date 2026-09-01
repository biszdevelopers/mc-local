package dev.bisz.watcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentManifestTest {
    @TempDir
    Path runtimeRoot;

    @Test
    void parsesRuntimeDirectoriesAndRelativeDownloads() throws Exception {
        String hash = "a".repeat(64);
        String json = """
                {
                  "release": "assemble_1.20.1_20260901010203",
                  "algorithm": "SHA-256",
                  "artifacts": {
                    "@/tacz/gunpacks": {
                      "%s": {
                        "extension": ".zip",
                        "downloadUrl": "/assemble_1.20.1_20260901010203/tacz/gunpacks/%s.zip"
                      }
                    }
                  }
                }
                """.formatted(hash, hash);

        ContentManifest manifest = ContentManifest.parse(
                json,
                URI.create("http://localhost:4748/build/deploy/release/manifest.json"),
                runtimeRoot
        );
        ContentManifest.Artifact artifact = manifest.flattenedArtifacts().get(0);

        assertEquals(runtimeRoot.resolve("tacz/gunpacks").toAbsolutePath().normalize(), artifact.directory());
        assertEquals(hash + ".zip", artifact.installedFileName());
        assertEquals("http://localhost:4748/assemble_1.20.1_20260901010203/tacz/gunpacks/" + hash + ".zip",
                artifact.downloadUri().toString());
    }

    @Test
    void rejectsPathsThatEscapeTheRuntimeRoot() {
        String json = """
                {
                  "release": "assemble_1.20.1_20260901010203",
                  "algorithm": "SHA-256",
                  "artifacts": { "@/../outside": {} }
                }
                """;

        assertThrows(IOException.class, () -> ContentManifest.parse(
                json,
                URI.create("http://localhost:4748/manifest.json"),
                runtimeRoot
        ));
    }

    @Test
    void rejectsDuplicateModIdsWithDifferentHashes() {
        String first = "a".repeat(64);
        String second = "b".repeat(64);
        String json = """
                {
                  "release": "assemble_1.20.1_20260901010203",
                  "algorithm": "SHA-256",
                  "artifacts": {
                    "@/mods": {
                      "%s": {"extension": ".jar", "modIds": ["duplicate"], "downloadUrl": "http://localhost/a.jar"},
                      "%s": {"extension": ".jar", "modIds": ["duplicate"], "downloadUrl": "http://localhost/b.jar"}
                    }
                  }
                }
                """.formatted(first, second);

        assertThrows(IOException.class, () -> ContentManifest.parse(
                json,
                URI.create("http://localhost:4748/manifest.json"),
                runtimeRoot
        ));
    }
}
