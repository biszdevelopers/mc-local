package dev.bisz.watcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class ContentUpdaterLauncher {
    private static final String UPDATER_RESOURCE = "/devwatcher/updater.jar";

    private ContentUpdaterLauncher() {
    }

    static Process launch(Path runtimeRoot, Path planFile) throws IOException {
        Path workingRoot = runtimeRoot.resolve(".devwatcher");
        Files.createDirectories(workingRoot);
        Path updater = workingRoot.resolve("updater.jar");
        Path temporary = workingRoot.resolve("updater.jar.tmp");
        try (InputStream input = ContentUpdaterLauncher.class.getResourceAsStream(UPDATER_RESOURCE)) {
            if (input == null) {
                throw new IOException("Packaged content updater is missing: " + UPDATER_RESOURCE);
            }
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, updater, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, updater, StandardCopyOption.REPLACE_EXISTING);
        }

        String javaExecutable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaExecutable);
        if (!Files.isRegularFile(java)) {
            throw new IOException("Could not locate the Java executable: " + java);
        }
        Path log = workingRoot.resolve("updater.log");
        return new ProcessBuilder(
                java.toString(),
                "-jar",
                updater.toString(),
                Long.toString(ProcessHandle.current().pid()),
                planFile.toAbsolutePath().normalize().toString()
        )
                .directory(runtimeRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }
}
