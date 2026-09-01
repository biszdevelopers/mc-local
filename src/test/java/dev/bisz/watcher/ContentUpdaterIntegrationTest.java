package dev.bisz.watcher;

import dev.bisz.watcher.updater.ContentUpdater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentUpdaterIntegrationTest {
    @TempDir
    Path runtimeRoot;

    @Test
    void quarantinesPromotesVerifiesAndCommitsState() throws Exception {
        String release = "assemble_1.20.1_20260901010203";
        Path oldFile = runtimeRoot.resolve("mods/old.jar");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "old");

        Path stagingRoot = runtimeRoot.resolve(".devwatcher/staging").resolve(release);
        Path stagedFile = stagingRoot.resolve("mods/new.jar");
        Files.createDirectories(stagedFile.getParent());
        Files.writeString(stagedFile, "new-content");
        String expectedHash = sha256(stagedFile);
        Path stagedState = stagingRoot.resolve("state.json");
        Files.writeString(stagedState, "{\"release\":\"test\"}");

        Properties plan = new Properties();
        plan.setProperty("runtimeRoot", runtimeRoot.toString());
        plan.setProperty("release", release);
        plan.setProperty("stagingRoot", runtimeRoot.relativize(stagingRoot).toString());
        plan.setProperty("quarantine.count", "1");
        plan.setProperty("quarantine.0", "mods/old.jar");
        plan.setProperty("install.count", "1");
        plan.setProperty("install.0.source", "mods/new.jar");
        plan.setProperty("install.0.target", "mods/" + expectedHash + ".jar");
        plan.setProperty("install.0.hash", expectedHash);
        plan.setProperty("verify.count", "1");
        plan.setProperty("verify.0.target", "mods/" + expectedHash + ".jar");
        plan.setProperty("verify.0.hash", expectedHash);
        plan.setProperty("state.source", "state.json");
        plan.setProperty("state.target", ".devwatcher/state.json");
        Path planFile = stagingRoot.resolve("update.properties");
        try (OutputStream output = Files.newOutputStream(planFile)) {
            plan.store(output, "test");
        }

        ContentUpdater.main(new String[]{Long.toString(Long.MAX_VALUE), planFile.toString()});

        assertFalse(Files.exists(oldFile));
        assertTrue(Files.isRegularFile(runtimeRoot.resolve(".devwatcher/quarantine").resolve(release).resolve("mods/old.jar")));
        Path installed = runtimeRoot.resolve("mods/" + expectedHash + ".jar");
        assertEquals(expectedHash, sha256(installed));
        assertEquals("{\"release\":\"test\"}", Files.readString(runtimeRoot.resolve(".devwatcher/state.json")));
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
