package dev.bisz.watcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

record ManagedContentState(String release, Map<String, Map<String, String>> artifacts) {
    static ManagedContentState empty() {
        return new ManagedContentState("", Map.of());
    }

    static ManagedContentState load(Path stateFile) {
        if (!Files.isRegularFile(stateFile)) {
            return empty();
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            String release = root.has("release") ? root.get("release").getAsString() : "";
            Map<String, Map<String, String>> directories = new LinkedHashMap<>();
            if (root.has("artifacts") && root.get("artifacts").isJsonObject()) {
                for (Map.Entry<String, JsonElement> directory : root.getAsJsonObject("artifacts").entrySet()) {
                    if (!directory.getValue().isJsonObject()) {
                        continue;
                    }
                    Map<String, String> files = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> file : directory.getValue().getAsJsonObject().entrySet()) {
                        if (file.getValue().isJsonPrimitive()) {
                            files.put(file.getKey(), file.getValue().getAsString());
                        }
                    }
                    directories.put(directory.getKey(), Collections.unmodifiableMap(files));
                }
            }
            return new ManagedContentState(release, Collections.unmodifiableMap(directories));
        } catch (Exception error) {
            Watcher.LOGGER.warn("Could not read managed content state from {}; starting with an empty state",
                    stateFile, error);
            return empty();
        }
    }

    static void write(Path target, String release, Map<String, Map<String, String>> artifacts) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("release", release);
        JsonObject directories = new JsonObject();
        artifacts.forEach((directory, files) -> {
            JsonObject entries = new JsonObject();
            files.forEach(entries::addProperty);
            directories.add(directory, entries);
        });
        root.add("artifacts", directories);

        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, root.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
