package dev.bisz.watcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

record ContentManifest(String release, Map<String, Map<String, Artifact>> artifacts) {
    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern EXTENSION = Pattern.compile("(?:\\.[A-Za-z0-9_-]+)?");
    private static final Pattern RELEASE = Pattern.compile("assemble_1\\.20\\.1_\\d{14}");

    static ContentManifest parse(String json, URI manifestUri, Path runtimeRoot) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Content manifest is not valid JSON.", error);
        }
        String release = requiredString(root, "release");
        if (!RELEASE.matcher(release).matches()) {
            throw new IOException("Invalid content release identifier: " + release);
        }
        if (!"SHA-256".equals(requiredString(root, "algorithm"))) {
            throw new IOException("Unsupported content hash algorithm.");
        }
        if (!root.has("artifacts") || !root.get("artifacts").isJsonObject()) {
            throw new IOException("Content manifest is missing artifacts.");
        }

        Map<String, String> modIdHashes = new HashMap<>();
        Map<String, Map<String, Artifact>> directories = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> directoryEntry : root.getAsJsonObject("artifacts").entrySet()) {
            String directoryKey = directoryEntry.getKey();
            Path directory = ContentPaths.resolveRuntimeDirectory(runtimeRoot, directoryKey);
            if (!directoryEntry.getValue().isJsonObject()) {
                throw new IOException("Artifacts for " + directoryKey + " must be an object.");
            }
            Map<String, Artifact> directoryArtifacts = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> artifactEntry : directoryEntry.getValue().getAsJsonObject().entrySet()) {
                String hash = artifactEntry.getKey();
                if (!HASH.matcher(hash).matches() || !artifactEntry.getValue().isJsonObject()) {
                    throw new IOException("Invalid artifact hash in " + directoryKey + ": " + hash);
                }
                JsonObject artifactJson = artifactEntry.getValue().getAsJsonObject();
                String extension = stringAllowingEmpty(artifactJson, "extension");
                if (!EXTENSION.matcher(extension).matches()) {
                    throw new IOException("Invalid artifact extension for " + hash);
                }
                URI downloadUri;
                try {
                    downloadUri = manifestUri.resolve(requiredString(artifactJson, "downloadUrl"));
                } catch (IllegalArgumentException error) {
                    throw new IOException("Invalid artifact URL for " + hash, error);
                }
                if (!("http".equalsIgnoreCase(downloadUri.getScheme()) || "https".equalsIgnoreCase(downloadUri.getScheme()))
                        || downloadUri.getHost() == null) {
                    throw new IOException("Artifact URL must use HTTP or HTTPS: " + downloadUri);
                }

                List<String> modIds = new ArrayList<>();
                if (artifactJson.has("modIds")) {
                    if (!artifactJson.get("modIds").isJsonArray()) {
                        throw new IOException("modIds must be an array for " + hash);
                    }
                    artifactJson.getAsJsonArray("modIds").forEach(value -> modIds.add(value.getAsString()));
                    for (String modId : modIds) {
                        String previous = modIdHashes.putIfAbsent(modId, hash);
                        if (previous != null && !previous.equals(hash)) {
                            throw new IOException("Mod ID " + modId + " maps to multiple hashes.");
                        }
                    }
                }
                directoryArtifacts.put(hash, new Artifact(directoryKey, directory, hash, extension,
                        List.copyOf(modIds), downloadUri));
            }
            directories.put(directoryKey, Collections.unmodifiableMap(directoryArtifacts));
        }
        return new ContentManifest(release, Collections.unmodifiableMap(directories));
    }

    List<Artifact> flattenedArtifacts() {
        return artifacts.values().stream().flatMap(directory -> directory.values().stream()).toList();
    }

    private static String requiredString(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IOException("Missing manifest string: " + key);
        }
        String value = object.get(key).getAsString();
        if (value.isBlank()) {
            throw new IOException("Manifest string may not be blank: " + key);
        }
        return value;
    }

    private static String stringAllowingEmpty(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IOException("Missing manifest string: " + key);
        }
        return object.get(key).getAsString();
    }

    record Artifact(String directoryKey, Path directory, String hash, String extension,
                    List<String> modIds, URI downloadUri) {
        String installedFileName() {
            return hash + extension;
        }
    }
}
