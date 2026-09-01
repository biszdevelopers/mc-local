package dev.bisz.watcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ContentPaths {
    private ContentPaths() {
    }

    static Path resolveRuntimeDirectory(Path runtimeRoot, String manifestDirectory) throws IOException {
        if (manifestDirectory == null || !manifestDirectory.startsWith("@/") || manifestDirectory.indexOf('\\') >= 0) {
            throw new IOException("Invalid runtime directory: " + manifestDirectory);
        }
        String relativeValue = manifestDirectory.substring(2);
        if (relativeValue.isBlank()) {
            throw new IOException("Runtime directory may not be the runtime root itself.");
        }
        Path relative = Path.of(relativeValue);
        if (relative.isAbsolute()) {
            throw new IOException("Runtime directory must be relative: " + manifestDirectory);
        }
        Path normalizedRoot = runtimeRoot.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Runtime directory escapes the runtime root: " + manifestDirectory);
        }
        return resolved;
    }

    static String runtimeRelative(Path runtimeRoot, Path path) throws IOException {
        Path normalizedRoot = runtimeRoot.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(normalizedRoot)) {
            throw new IOException("Path escapes the runtime root: " + path);
        }
        return normalizedRoot.relativize(normalizedPath).toString().replace('\\', '/');
    }

    static void ensureNoSymlinkEscape(Path runtimeRoot, Path directory) throws IOException {
        Path normalizedRoot = runtimeRoot.toAbsolutePath().normalize();
        Path realRoot = Files.exists(normalizedRoot) ? normalizedRoot.toRealPath() : normalizedRoot;
        Path current = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(directory.toAbsolutePath().normalize())) {
            current = current.resolve(segment);
            if (Files.exists(current) && !current.toRealPath().startsWith(realRoot)) {
                throw new IOException("Runtime path escapes through a symbolic link: " + current);
            }
        }
    }
}
