package dev.bisz.watcher.updater;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

public final class ContentUpdater {
    private ContentUpdater() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            System.err.println("Usage: java -jar updater.jar <minecraft-pid> <update-plan>");
            System.exit(2);
        }

        Path planFile = Path.of(arguments[1]).toAbsolutePath().normalize();
        Properties plan = new Properties();
        try (InputStream input = Files.newInputStream(planFile)) {
            plan.load(input);
        } catch (Exception error) {
            error.printStackTrace(System.err);
            System.exit(2);
            return;
        }

        Path runtimeRoot = Path.of(required(plan, "runtimeRoot")).toAbsolutePath().normalize();
        Path workingRoot = runtimeRoot.resolve(".devwatcher").normalize();
        Path resultFile = workingRoot.resolve("last-result.properties");
        String release = required(plan, "release");
        List<Move> quarantined = new ArrayList<>();
        List<Path> installed = new ArrayList<>();

        try {
            long minecraftPid = Long.parseLong(arguments[0]);
            ProcessHandle.of(minecraftPid).ifPresent(process -> {
                try {
                    process.onExit().get();
                } catch (Exception error) {
                    throw new WaitFailure(error);
                }
            });

            Path stagingRoot = safeResolve(runtimeRoot, required(plan, "stagingRoot"));
            Path quarantineRoot = workingRoot.resolve("quarantine").resolve(release).normalize();
            ensureSafeParent(runtimeRoot, workingRoot);
            ensureSafeParent(runtimeRoot, stagingRoot);
            ensureSafeParent(runtimeRoot, quarantineRoot);
            Files.createDirectories(quarantineRoot);

            int quarantineCount = Integer.parseInt(plan.getProperty("quarantine.count", "0"));
            for (int index = 0; index < quarantineCount; index++) {
                Path original = safeResolve(runtimeRoot, required(plan, "quarantine." + index));
                if (!Files.exists(original)) {
                    continue;
                }
                ensureSafeParent(runtimeRoot, original.getParent());
                Path relative = runtimeRoot.relativize(original);
                Path backup = uniquePath(quarantineRoot.resolve(relative));
                Files.createDirectories(backup.getParent());
                move(original, backup, false);
                quarantined.add(new Move(backup, original));
            }

            int installCount = Integer.parseInt(plan.getProperty("install.count", "0"));
            for (int index = 0; index < installCount; index++) {
                Path source = safeResolve(stagingRoot, required(plan, "install." + index + ".source"));
                Path target = safeResolve(runtimeRoot, required(plan, "install." + index + ".target"));
                String expectedHash = required(plan, "install." + index + ".hash");
                if (!sha256(source).equals(expectedHash)) {
                    throw new IOException("Staged content failed verification: " + source);
                }
                ensureSafeParent(runtimeRoot, target.getParent());
                Files.createDirectories(target.getParent());
                if (Files.exists(target)) {
                    if (sha256(target).equals(expectedHash)) {
                        Files.delete(source);
                        continue;
                    }
                    throw new IOException("Install target already exists with different content: " + target);
                }
                move(source, target, false);
                installed.add(target);
            }

            int verifyCount = Integer.parseInt(plan.getProperty("verify.count", "0"));
            for (int index = 0; index < verifyCount; index++) {
                Path target = safeResolve(runtimeRoot, required(plan, "verify." + index + ".target"));
                String expectedHash = required(plan, "verify." + index + ".hash");
                if (!Files.isRegularFile(target) || !sha256(target).equals(expectedHash)) {
                    throw new IOException("Installed content failed verification: " + target);
                }
            }

            Path stateSource = safeResolve(stagingRoot, required(plan, "state.source"));
            Path stateTarget = safeResolve(runtimeRoot, required(plan, "state.target"));
            ensureSafeParent(runtimeRoot, stateTarget.getParent());
            Files.createDirectories(stateTarget.getParent());
            move(stateSource, stateTarget, true);
            writeResult(resultFile, "success", release, "Content update installed successfully.");
        } catch (Throwable error) {
            for (int index = installed.size() - 1; index >= 0; index--) {
                try {
                    Files.deleteIfExists(installed.get(index));
                } catch (IOException ignored) {
                }
            }
            for (int index = quarantined.size() - 1; index >= 0; index--) {
                Move rollback = quarantined.get(index);
                try {
                    Files.createDirectories(rollback.target().getParent());
                    move(rollback.source(), rollback.target(), false);
                } catch (IOException ignored) {
                }
            }
            Throwable cause = error instanceof WaitFailure && error.getCause() != null ? error.getCause() : error;
            try {
                writeResult(resultFile, "failed", release,
                        cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            } catch (IOException ignored) {
            }
            cause.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing update-plan value: " + key);
        }
        return value;
    }

    private static Path safeResolve(Path root, String relativeValue) throws IOException {
        Path relative = Path.of(relativeValue);
        if (relative.isAbsolute()) {
            throw new IOException("Update path must be relative: " + relativeValue);
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException("Update path escapes its root: " + relativeValue);
        }
        return resolved;
    }

    private static void ensureSafeParent(Path runtimeRoot, Path parent) throws IOException {
        Path current = runtimeRoot;
        Path realRoot = Files.exists(runtimeRoot) ? runtimeRoot.toRealPath() : runtimeRoot;
        for (Path segment : runtimeRoot.relativize(parent)) {
            current = current.resolve(segment);
            if (Files.exists(current) && !current.toRealPath().startsWith(realRoot)) {
                throw new IOException("Install path escapes the runtime root through a symbolic link: " + current);
            }
        }
    }

    private static Path uniquePath(Path preferred) {
        if (!Files.exists(preferred)) {
            return preferred;
        }
        int suffix = 1;
        while (Files.exists(Path.of(preferred + "." + suffix))) {
            suffix++;
        }
        return Path.of(preferred + "." + suffix);
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        try {
            if (replace) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException error) {
            if (replace) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target);
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void writeResult(Path resultFile, String status, String release, String message) throws IOException {
        Properties result = new Properties();
        result.setProperty("status", status);
        result.setProperty("release", release);
        result.setProperty("message", message);
        result.setProperty("timestamp", Instant.now().toString());
        Files.createDirectories(resultFile.getParent());
        try (OutputStream output = Files.newOutputStream(resultFile)) {
            result.store(output, "DevWatcher content updater result");
        }
    }

    private record Move(Path source, Path target) {
    }

    private static final class WaitFailure extends RuntimeException {
        private WaitFailure(Throwable cause) {
            super(cause);
        }
    }
}
