package dev.bisz.watcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.electronwill.nightconfig.core.file.FileConfig;
import net.minecraftforge.fml.loading.FMLPaths;

record ServerSettings(String name, String address, String contentServer, String sessionServer) {
    private static final String CONFIG_FILE_NAME = "relizc-watcher-server.toml";
    private static final String DEFAULT_CONFIG_RESOURCE = "/" + CONFIG_FILE_NAME;

    static Optional<ServerSettings> load() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE_NAME);
        if (!ensureConfigExists(configFile)) {
            return Optional.empty();
        }

        try (FileConfig config = FileConfig.builder(configFile).sync().build()) {
            config.load();
            String name = config.getOrElse("server.name", "Relizc SMP");
            String address = config.getOrElse("server.address", "");
            String cont = config.getOrElse("server.content_server", "");
            String sess = config.getOrElse("server.session_server", "");
            if (address.isBlank()) {
                Watcher.LOGGER.error("No server address is configured in {}", configFile);
                return Optional.empty();
            }
            return Optional.of(new ServerSettings(name, address.trim(), cont, sess));
        } catch (RuntimeException exception) {
            Watcher.LOGGER.error("Could not load server settings from {}", configFile, exception);
            return Optional.empty();
        }
    }

    private static boolean ensureConfigExists(Path configFile) {
        if (Files.isRegularFile(configFile)) {
            return true;
        }

        try {
            Files.createDirectories(configFile.getParent());
            try (InputStream defaults = ServerSettings.class.getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
                if (defaults == null) {
                    Watcher.LOGGER.error("Missing packaged server configuration {}", DEFAULT_CONFIG_RESOURCE);
                    return false;
                }
                Files.copy(defaults, configFile);
            }
            Watcher.LOGGER.info("Created server configuration at {}", configFile);
            return true;
        } catch (IOException exception) {
            Watcher.LOGGER.error("Could not create server configuration at {}", configFile, exception);
            return false;
        }
    }
}
