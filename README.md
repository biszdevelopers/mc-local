# DevWatcher Minecraft Content Sync

DevWatcher is a Forge 1.20.1 client mod that synchronizes selected directories beneath the active Minecraft runtime root before play. It obtains that root from Forge at runtime, so launcher-specific game directories work without assuming `%APPDATA%/.minecraft`.

The repository's `.minecraft` directory is the publishing source. `deploy_content_roots` in `gradle.properties` initially publishes `mods` and `tacz`; add roots such as `resourcepacks` deliberately when they become managed.

## Build and publish

Run these commands from PowerShell:

```powershell
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat deployBuild
```

`deployBuild` recursively hashes configured source roots with SHA-256 and creates an immutable snapshot at:

```text
build/deploy/assemble_1.20.1_yyyyMMddHHmmss/
```

The snapshot contains `manifest.json` and files distributed as `<full-sha256><original-extension>` beneath their runtime-relative directories. A successful deployment atomically updates `build/deploy/latest.json`; a failed deployment leaves the previous pointer intact.

The development download host defaults to `127.0.0.1:4748`. The numeric loopback address avoids IPv4/IPv6 `localhost` resolution differences. Override it for one build with:

```powershell
.\gradlew.bat deployBuild -PdeployHost=https://downloads.example.com
```

## Content server

Install and run the Express server:

```powershell
cd mods-content
npm install
npm test
npm start
```

It listens on `127.0.0.1:4748` by default. Configure another environment with `HOST`, `PORT`, or `DEPLOY_ROOT`. The API provides:

- `GET /` — server configuration with the latest manifest URL
- `GET /healthz` — health status
- `GET /build/deploy/<release>/manifest.json` — immutable release manifest
- `GET /<release>/<runtime-directory>/<sha256>.<extension>` — immutable content, including range requests

Only validated deployment content is exposed; the repository itself is not served.

## Client synchronization

At startup DevWatcher:

1. Resolves the active game directory through `FMLPaths.GAMEDIR`.
2. Fetches the content server root configuration and latest manifest.
3. Hashes files in each manifest directory and compares them by directory and SHA-256.
4. Preserves unrelated files, while tracking content it manages in `<game-directory>/.devwatcher/state.json`.
5. Asks before downloading missing content, verifies every download, and starts a standalone updater.
6. Closes Minecraft after confirmation so the updater can quarantine outdated managed files and install staged files safely after Forge releases its JAR locks.

Downloaded files use their full SHA-256 as the basename. The original extension is preserved because loaders require suffixes such as `.jar` and `.zip`. Fixed-name configuration files are not currently suitable managed content.

The packaged development server settings are in `src/main/resources/relizc-watcher-server.toml`. The generated runtime copy is `config/relizc-watcher-server.toml`.

## Development

Launch Forge development runs with:

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
.\gradlew.bat runData
```

Reset remembered startup and automatic-connection preferences with:

```powershell
.\gradlew.bat resetConfig
```

The dedicated content-sync log window remains responsive while the mod constructor waits for synchronization. When the runtime already matches the manifest, startup proceeds immediately. Network, manifest, path, download, verification, or updater failures are fatal and are shown in a dialog.
