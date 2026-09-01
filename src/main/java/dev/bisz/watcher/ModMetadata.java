package dev.bisz.watcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ModMetadata {
    private static final Pattern MOD_BLOCK = Pattern.compile("(?ms)^\\s*\\[\\[mods\\]\\]\\s*(.*?)(?=^\\s*\\[\\[|\\z)");
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");

    private ModMetadata() {
    }

    static List<String> readModIds(Path jar) throws IOException {
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            ZipEntry tomlEntry = archive.getEntry("META-INF/mods.toml");
            if (tomlEntry == null) {
                tomlEntry = archive.getEntry("META-INF/neoforge.mods.toml");
            }
            if (tomlEntry != null) {
                String toml;
                try (InputStream input = archive.getInputStream(tomlEntry)) {
                    toml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                Set<String> modIds = new LinkedHashSet<>();
                Matcher blocks = MOD_BLOCK.matcher(toml);
                while (blocks.find()) {
                    Matcher id = MOD_ID.matcher(blocks.group(1));
                    if (id.find()) {
                        modIds.add(id.group(1));
                    }
                }
                return new ArrayList<>(modIds);
            }

            ZipEntry fabricEntry = archive.getEntry("fabric.mod.json");
            if (fabricEntry != null) {
                try (InputStream input = archive.getInputStream(fabricEntry)) {
                    JsonObject metadata = JsonParser.parseReader(
                            new java.io.InputStreamReader(input, StandardCharsets.UTF_8)
                    ).getAsJsonObject();
                    return metadata.has("id") ? List.of(metadata.get("id").getAsString()) : List.of();
                }
            }
            return List.of();
        }
    }
}
