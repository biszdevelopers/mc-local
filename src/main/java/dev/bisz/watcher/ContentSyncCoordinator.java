package dev.bisz.watcher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ContentSyncCoordinator {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration JSON_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final int DOWNLOAD_ATTEMPTS = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "devwatcher-content-sync");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch continueLatch = new CountDownLatch(1);

    void start() {
        executor.execute(this::synchronize);
    }

    void awaitContinue() {
        try {
            continueLatch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            failAndExit("Content synchronization was interrupted.", error);
        }
    }

    void stop() {
        executor.shutdownNow();
    }

    private void synchronize() {
        try {
            WatcherLog.log(WatcherLog.Level.INFO, "DevWatcher Minecraft Content Syncer v1.0.0");
            ServerSettings settings = ServerSettings.load().orElseThrow(() ->
                    new IOException("Failed to load the server configuration."));
            if (settings.contentServer().isBlank()) {
                throw new IOException("Missing content_server in the server configuration.");
            }

            Path runtimeRoot = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
            Files.createDirectories(runtimeRoot);
            Path workingRoot = runtimeRoot.resolve(".devwatcher");
            Files.createDirectories(workingRoot);
            ContentPaths.ensureNoSymlinkEscape(runtimeRoot, workingRoot);
            logPreviousUpdaterResult(workingRoot.resolve("last-result.properties"));

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            URI configurationUri = contentServerRoot(settings.contentServer());
            WatcherLog.log(WatcherLog.Level.INFO, "Fetching content configuration: " + configurationUri);
            JsonObject configuration = parseObject(fetchJson(http, configurationUri), "content server configuration");
            if (!configuration.has("latest") || !configuration.get("latest").isJsonPrimitive()) {
                throw new IOException("Content server configuration is missing latest.");
            }
            URI manifestUri = configurationUri.resolve(configuration.get("latest").getAsString());
            requireHttpUri(manifestUri, "latest manifest");
            WatcherLog.log(WatcherLog.Level.INFO, "Fetching content manifest: " + manifestUri);
            ContentManifest manifest = ContentManifest.parse(fetchJson(http, manifestUri), manifestUri, runtimeRoot);

            Map<String, Map<String, List<Path>>> currentFiles = scanCurrentFiles(manifest);
            Path stateFile = workingRoot.resolve("state.json");
            ManagedContentState previousState = ManagedContentState.load(stateFile);
            Map<String, Map<String, String>> desiredState = new LinkedHashMap<>();
            List<ContentManifest.Artifact> missing = new ArrayList<>();
            Set<Path> duplicateManagedFiles = new LinkedHashSet<>();

            for (Map.Entry<String, Map<String, ContentManifest.Artifact>> directory : manifest.artifacts().entrySet()) {
                Map<String, String> desiredDirectory = new LinkedHashMap<>();
                Map<String, List<Path>> currentDirectory = currentFiles.getOrDefault(directory.getKey(), Map.of());
                for (ContentManifest.Artifact artifact : directory.getValue().values()) {
                    List<Path> matches = currentDirectory.getOrDefault(artifact.hash(), List.of());
                    matches = matches.stream()
                            .filter(path -> extensionOf(path).equalsIgnoreCase(artifact.extension()))
                            .toList();
                    if (matches.isEmpty()) {
                        missing.add(artifact);
                        Path installed = artifact.directory().resolve(artifact.installedFileName());
                        desiredDirectory.put(artifact.hash(), ContentPaths.runtimeRelative(runtimeRoot, installed));
                    } else {
                        desiredDirectory.put(artifact.hash(), ContentPaths.runtimeRelative(runtimeRoot, matches.get(0)));
                        if (matches.size() > 1) {
                            duplicateManagedFiles.addAll(matches.subList(1, matches.size()));
                        }
                    }
                }
                desiredState.put(directory.getKey(), desiredDirectory);
            }

            Set<Path> quarantine = findManagedRemovals(runtimeRoot, manifest, previousState);
            quarantine.addAll(duplicateManagedFiles);
            quarantine.addAll(findOutdatedModJars(runtimeRoot, manifest, currentFiles));

            if (missing.isEmpty() && quarantine.isEmpty()) {
                ManagedContentState.write(stateFile, manifest.release(), desiredState);
                WatcherLog.log(WatcherLog.Level.INFO,
                        "Runtime content is current for release " + manifest.release());
                MinecraftLogWindow.flushAndWait();
                continueLatch.countDown();
                executor.shutdown();
                return;
            }

            boolean accepted = missing.isEmpty()
                    ? ContentSyncDialogs.confirmCleanup(quarantine.size())
                    : ContentSyncDialogs.confirmDownloads(missing.size());
            if (!accepted) {
                WatcherLog.log(WatcherLog.Level.INFO, "Content synchronization was declined; closing Minecraft");
                MinecraftLogWindow.flushAndWait();
                executor.shutdownNow();
                System.exit(0);
                return;
            }

            Path stagingRoot = workingRoot.resolve("staging").resolve(manifest.release());
            Files.createDirectories(stagingRoot);
            Map<ContentManifest.Artifact, Path> staged = new LinkedHashMap<>();
            for (int artifactIndex = 0; artifactIndex < missing.size(); artifactIndex++) {
                ContentManifest.Artifact artifact = missing.get(artifactIndex);
                Path stagedFile = stageArtifact(http, runtimeRoot, stagingRoot, artifact,
                        artifactIndex + 1, missing.size());
                staged.put(artifact, stagedFile);
            }

            verifyCompleteCoverage(runtimeRoot, manifest, desiredState, staged);
            Path stagedState = stagingRoot.resolve("state.json");
            ManagedContentState.write(stagedState, manifest.release(), desiredState);
            Path planFile = writeUpdatePlan(runtimeRoot, stagingRoot, stateFile, stagedState, manifest,
                    desiredState, quarantine, staged);
            Process updater = ContentUpdaterLauncher.launch(runtimeRoot, planFile);
            if (!updater.isAlive()) {
                throw new IOException("The post-exit content updater did not start.");
            }

            WatcherLog.log(WatcherLog.Level.INFO,
                    "Downloaded and verified " + missing.size() + " content file(s); restart required");
            MinecraftLogWindow.flushAndWait();
            ContentSyncDialogs.showComplete(missing.size());
            executor.shutdownNow();
            System.exit(0);
        } catch (Throwable error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String detail = error.getMessage();
            failAndExit("Content synchronization failed: "
                    + (detail == null || detail.isBlank() ? error.getClass().getSimpleName() : detail), error);
        }
    }

    private static Map<String, Map<String, List<Path>>> scanCurrentFiles(ContentManifest manifest) throws IOException {
        Map<String, Map<String, List<Path>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, ContentManifest.Artifact>> entry : manifest.artifacts().entrySet()) {
            Path directory = entry.getValue().values().stream().findFirst()
                    .map(ContentManifest.Artifact::directory)
                    .orElse(null);
            Map<String, List<Path>> hashes = new HashMap<>();
            if (directory != null && Files.isDirectory(directory)) {
                ContentPaths.ensureNoSymlinkEscape(FMLPaths.GAMEDIR.get(), directory);
                try (var files = Files.list(directory)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        hashes.computeIfAbsent(ContentHashing.sha256(file), ignored -> new ArrayList<>()).add(file);
                    }
                }
            }
            result.put(entry.getKey(), hashes);
        }
        return result;
    }

    private static Set<Path> findManagedRemovals(Path runtimeRoot, ContentManifest manifest,
                                                  ManagedContentState previousState) throws IOException {
        Set<Path> removals = new LinkedHashSet<>();
        for (Map.Entry<String, Map<String, String>> directory : previousState.artifacts().entrySet()) {
            Set<String> desiredHashes = manifest.artifacts().containsKey(directory.getKey())
                    ? manifest.artifacts().get(directory.getKey()).keySet()
                    : Set.of();
            for (Map.Entry<String, String> managed : directory.getValue().entrySet()) {
                if (!desiredHashes.contains(managed.getKey())) {
                    Path path = runtimeRoot.resolve(managed.getValue()).normalize();
                    if (!path.startsWith(runtimeRoot) || path.equals(runtimeRoot)) {
                        throw new IOException("Managed state contains an unsafe path: " + managed.getValue());
                    }
                    if (Files.isRegularFile(path)) {
                        ContentPaths.ensureNoSymlinkEscape(runtimeRoot, path.getParent());
                        if (ContentHashing.sha256(path).equals(managed.getKey())) {
                            removals.add(path);
                        } else {
                            Watcher.LOGGER.warn(
                                    "Previously managed path {} now contains unrelated content; preserving it", path);
                        }
                    }
                }
            }
        }
        return removals;
    }

    private static Set<Path> findOutdatedModJars(Path runtimeRoot, ContentManifest manifest,
                                                  Map<String, Map<String, List<Path>>> currentFiles) {
        Map<String, ContentManifest.Artifact> requiredByModId = new HashMap<>();
        Map<String, ContentManifest.Artifact> requiredMods = manifest.artifacts().getOrDefault("@/mods", Map.of());
        requiredMods.values().forEach(artifact -> artifact.modIds().forEach(modId -> requiredByModId.put(modId, artifact)));
        if (requiredByModId.isEmpty()) {
            return Set.of();
        }

        Set<String> desiredHashes = requiredMods.keySet();
        Set<Path> outdated = new LinkedHashSet<>();
        Path modsDirectory = runtimeRoot.resolve("mods");
        if (!Files.isDirectory(modsDirectory)) {
            return outdated;
        }
        try (var files = Files.list(modsDirectory)) {
            for (Path jar : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".jar")).toList()) {
                String hash = ContentHashing.sha256(jar);
                if (desiredHashes.contains(hash)) {
                    continue;
                }
                try {
                    if (ModMetadata.readModIds(jar).stream().anyMatch(requiredByModId::containsKey)) {
                        outdated.add(jar);
                    }
                } catch (IOException error) {
                    Watcher.LOGGER.warn("Could not inspect local mod metadata in {}; preserving it", jar, error);
                }
            }
        } catch (IOException error) {
            Watcher.LOGGER.warn("Could not inspect the local mods directory for outdated managed mods", error);
        }
        return outdated;
    }

    private static Path stageArtifact(HttpClient http, Path runtimeRoot, Path stagingRoot,
                                      ContentManifest.Artifact artifact, int artifactNumber,
                                      int artifactCount) throws Exception {
        Path relativeDirectory = runtimeRoot.relativize(artifact.directory());
        Path stagingDirectory = stagingRoot.resolve(relativeDirectory).normalize();
        if (!stagingDirectory.startsWith(stagingRoot)) {
            throw new IOException("Staging path escapes its root for " + artifact.directoryKey());
        }
        Files.createDirectories(stagingDirectory);
        Path target = stagingDirectory.resolve(artifact.installedFileName());
        String progressLabel = "[" + artifactNumber + "/" + artifactCount + "] "
                + artifact.directoryKey() + "/" + artifact.installedFileName();
        if (Files.isRegularFile(target) && ContentHashing.sha256(target).equals(artifact.hash())) {
            validateModIds(target, artifact);
            logDownload(progressLabel + " already exists in staging and passed verification");
            return target;
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS; attempt++) {
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.deleteIfExists(partial);
            try {
                logDownload("Downloading " + progressLabel
                        + " (attempt " + attempt + "/" + DOWNLOAD_ATTEMPTS + ")");
                HttpResponse<InputStream> response = sendDownload(http, artifact.downloadUri());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try (InputStream ignored = response.body()) {
                        throw new IOException("HTTP " + response.statusCode() + " from " + artifact.downloadUri());
                    }
                }
                try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(partial)) {
                    copyWithProgress(input, output,
                            response.headers().firstValueAsLong("Content-Length").orElse(-1), progressLabel);
                }
                if (!ContentHashing.sha256(partial).equals(artifact.hash())) {
                    throw new IOException("Downloaded content hash does not match " + artifact.hash());
                }
                validateModIds(partial, artifact);
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                logDownload(progressLabel + " downloaded and verified");
                return target;
            } catch (InterruptedException error) {
                Files.deleteIfExists(partial);
                Thread.currentThread().interrupt();
                throw error;
            } catch (Exception error) {
                Files.deleteIfExists(partial);
                lastError = error;
                Watcher.LOGGER.warn("Content download attempt {} failed for {}", attempt, artifact.downloadUri(), error);
            }
        }
        throw new IOException("Could not download " + artifact.downloadUri() + " after " + DOWNLOAD_ATTEMPTS
                + " attempts", lastError);
    }

    private static void copyWithProgress(InputStream input, OutputStream output, long contentLength,
                                         String progressLabel) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long downloaded = 0;
        int lastPercentBucket = -1;
        long nextUnknownLengthReport = 5L * 1024 * 1024;
        for (int read; (read = input.read(buffer)) != -1;) {
            output.write(buffer, 0, read);
            downloaded += read;

            if (contentLength > 0) {
                int percent = (int) Math.min(100, downloaded * 100 / contentLength);
                int percentBucket = percent / 10;
                if (percentBucket > lastPercentBucket) {
                    lastPercentBucket = percentBucket;
                    logDownload(progressLabel + " - " + percent + "% ("
                            + humanBytes(downloaded) + "/" + humanBytes(contentLength) + ")");
                }
            } else if (downloaded >= nextUnknownLengthReport) {
                logDownload(progressLabel + " - " + humanBytes(downloaded) + " downloaded");
                nextUnknownLengthReport += 5L * 1024 * 1024;
            }
        }
        if (contentLength <= 0 || lastPercentBucket < 10) {
            logDownload(progressLabel + " - download complete (" + humanBytes(downloaded) + ")");
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kibibytes = bytes / 1024.0;
        if (kibibytes < 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KiB", kibibytes);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MiB", kibibytes / 1024.0);
    }

    private static void logDownload(String message) {
        Watcher.LOGGER.info(message);
        WatcherLog.log(WatcherLog.Level.INFO, message);
    }

    private static void validateModIds(Path file, ContentManifest.Artifact artifact) throws IOException {
        if (artifact.modIds().isEmpty()) {
            return;
        }
        Set<String> expected = new HashSet<>(artifact.modIds());
        Set<String> actual = new HashSet<>(ModMetadata.readModIds(file));
        if (!actual.equals(expected)) {
            throw new IOException("Downloaded mod IDs do not match the manifest for " + artifact.hash());
        }
    }

    private static String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int extensionIndex = name.lastIndexOf('.');
        return extensionIndex > 0 ? name.substring(extensionIndex) : "";
    }

    private static void verifyCompleteCoverage(Path runtimeRoot, ContentManifest manifest,
                                               Map<String, Map<String, String>> desiredState,
                                               Map<ContentManifest.Artifact, Path> staged) throws IOException {
        Map<String, Path> stagedByHashAndDirectory = new HashMap<>();
        staged.forEach((artifact, path) -> stagedByHashAndDirectory.put(artifact.directoryKey() + ':' + artifact.hash(), path));
        for (ContentManifest.Artifact artifact : manifest.flattenedArtifacts()) {
            Path candidate = stagedByHashAndDirectory.get(artifact.directoryKey() + ':' + artifact.hash());
            if (candidate == null) {
                String relative = desiredState.get(artifact.directoryKey()).get(artifact.hash());
                candidate = runtimeRoot.resolve(relative).normalize();
            }
            if (!Files.isRegularFile(candidate) || !ContentHashing.sha256(candidate).equals(artifact.hash())) {
                throw new IOException("Content coverage verification failed for " + artifact.hash());
            }
        }
    }

    private static Path writeUpdatePlan(Path runtimeRoot, Path stagingRoot, Path stateFile, Path stagedState,
                                        ContentManifest manifest, Map<String, Map<String, String>> desiredState,
                                        Set<Path> quarantine,
                                        Map<ContentManifest.Artifact, Path> staged) throws IOException {
        Properties plan = new Properties();
        plan.setProperty("runtimeRoot", runtimeRoot.toString());
        plan.setProperty("release", manifest.release());
        plan.setProperty("stagingRoot", ContentPaths.runtimeRelative(runtimeRoot, stagingRoot));

        int index = 0;
        for (Path path : quarantine) {
            plan.setProperty("quarantine." + index++, ContentPaths.runtimeRelative(runtimeRoot, path));
        }
        plan.setProperty("quarantine.count", Integer.toString(index));

        index = 0;
        for (Map.Entry<ContentManifest.Artifact, Path> install : staged.entrySet()) {
            ContentManifest.Artifact artifact = install.getKey();
            plan.setProperty("install." + index + ".source",
                    stagingRoot.relativize(install.getValue()).toString().replace('\\', '/'));
            plan.setProperty("install." + index + ".target",
                    ContentPaths.runtimeRelative(runtimeRoot, artifact.directory().resolve(artifact.installedFileName())));
            plan.setProperty("install." + index + ".hash", artifact.hash());
            index++;
        }
        plan.setProperty("install.count", Integer.toString(index));

        index = 0;
        for (ContentManifest.Artifact artifact : manifest.flattenedArtifacts()) {
            plan.setProperty("verify." + index + ".target",
                    desiredState.get(artifact.directoryKey()).get(artifact.hash()));
            plan.setProperty("verify." + index + ".hash", artifact.hash());
            index++;
        }
        plan.setProperty("verify.count", Integer.toString(index));
        plan.setProperty("state.source", stagingRoot.relativize(stagedState).toString().replace('\\', '/'));
        plan.setProperty("state.target", ContentPaths.runtimeRelative(runtimeRoot, stateFile));

        Path planFile = stagingRoot.resolve("update.properties");
        try (OutputStream output = Files.newOutputStream(planFile)) {
            plan.store(output, "DevWatcher post-exit content update");
        }
        return planFile;
    }

    private static String fetchJson(HttpClient http, URI uri) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try {
            response = sendJson(http, uri);
        } catch (ConnectException error) {
            URI fallback = ipv4LoopbackFallback(uri);
            if (fallback == null) {
                throw new IOException("Could not connect to " + uri, error);
            }
            WatcherLog.log(WatcherLog.Level.INFO,
                    "Retrying localhost through the IPv4 loopback address: " + fallback);
            try {
                response = sendJson(http, fallback);
            } catch (IOException fallbackError) {
                throw new IOException("Could not connect to " + uri + " or " + fallback, fallbackError);
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri);
        }
        return response.body();
    }

    private static HttpResponse<String> sendJson(HttpClient http, URI uri)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(JSON_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static HttpResponse<InputStream> sendDownload(HttpClient http, URI uri)
            throws IOException, InterruptedException {
        try {
            return sendDownloadRequest(http, uri);
        } catch (ConnectException error) {
            URI fallback = ipv4LoopbackFallback(uri);
            if (fallback == null) {
                throw error;
            }
            WatcherLog.log(WatcherLog.Level.INFO,
                    "Retrying localhost download through the IPv4 loopback address: " + fallback);
            return sendDownloadRequest(http, fallback);
        }
    }

    private static HttpResponse<InputStream> sendDownloadRequest(HttpClient http, URI uri)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private static URI ipv4LoopbackFallback(URI uri) {
        if (!"localhost".equalsIgnoreCase(uri.getHost())) {
            return null;
        }
        try {
            return new URI(uri.getScheme(), uri.getUserInfo(), "127.0.0.1", uri.getPort(),
                    uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException("Could not construct the IPv4 localhost fallback", impossible);
        }
    }

    private static JsonObject parseObject(String json, String description) throws IOException {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new IOException("Invalid " + description + " JSON.", error);
        }
    }

    private static URI contentServerRoot(String configured) throws IOException {
        try {
            String normalized = configured.trim();
            if (!normalized.endsWith("/")) {
                normalized += "/";
            }
            URI uri = URI.create(normalized);
            requireHttpUri(uri, "content server");
            return uri;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid content_server URL.", error);
        }
    }

    private static void requireHttpUri(URI uri, String description) throws IOException {
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            throw new IOException(description + " must be an absolute HTTP or HTTPS URL: " + uri);
        }
    }

    private static void logPreviousUpdaterResult(Path resultFile) {
        if (!Files.isRegularFile(resultFile)) {
            return;
        }
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(resultFile)) {
            result.load(input);
            String status = result.getProperty("status", "unknown");
            String message = result.getProperty("message", "No details available.");
            WatcherLog.log("success".equals(status) ? WatcherLog.Level.INFO : WatcherLog.Level.ERROR,
                    "Previous content updater result: " + status + " - " + message);
        } catch (IOException error) {
            Watcher.LOGGER.warn("Could not read the previous content updater result", error);
        }
    }

    private void failAndExit(String message, Throwable error) {
        Watcher.LOGGER.error(message, error);
        WatcherLog.log(WatcherLog.Level.ERROR, message);
        MinecraftLogWindow.flushAndWait();
        try {
            ContentSyncDialogs.showError(message);
        } finally {
            executor.shutdownNow();
            continueLatch.countDown();
            System.exit(1);
        }
    }
}
