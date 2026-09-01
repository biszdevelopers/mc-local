package dev.bisz.watcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.minecraftforge.fml.loading.FMLPaths;

final class ConnectionPreference {
    private static final String PREFERENCE_FILE = "relizc-watcher.properties";
    // Deliberately distinct from the former anti-cheat consent key so existing users see the content-sync disclosure.
    private static final String STARTUP_CONSENT_KEY = "contentSyncConsentAccepted";
    private static final String AUTOMATIC_CONNECTION_KEY = "automaticallyConnectToRelizcSmp";

    private ConnectionPreference() {
    }

    static boolean hasAcceptedStartupConsent() {
        return hasAccepted(STARTUP_CONSENT_KEY);
    }

    static void saveAcceptedStartupConsent() {
        saveAccepted(STARTUP_CONSENT_KEY, "startup consent");
    }

    static boolean hasAcceptedAutomaticConnection() {
        return hasAccepted(AUTOMATIC_CONNECTION_KEY);
    }

    static void saveAcceptedAutomaticConnection() {
        saveAccepted(AUTOMATIC_CONNECTION_KEY, "connection choice");
    }

    private static boolean hasAccepted(String key) {
        Path preferenceFile = preferenceFile();
        if (!Files.isRegularFile(preferenceFile)) {
            return false;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(preferenceFile)) {
            properties.load(input);
        } catch (IOException exception) {
            Watcher.LOGGER.warn("Could not read remembered connection choice from {}", preferenceFile, exception);
            return false;
        }

        String value = properties.getProperty(key);
        return "true".equalsIgnoreCase(value);
    }

    private static void saveAccepted(String key, String description) {
        Path preferenceFile = preferenceFile();
        Properties properties = new Properties();

        if (Files.isRegularFile(preferenceFile)) {
            try (InputStream input = Files.newInputStream(preferenceFile)) {
                properties.load(input);
            } catch (IOException exception) {
                Watcher.LOGGER.warn("Could not preserve existing preferences from {}", preferenceFile, exception);
            }
        }

        properties.setProperty(key, Boolean.TRUE.toString());

        try {
            Files.createDirectories(preferenceFile.getParent());
            try (OutputStream output = Files.newOutputStream(preferenceFile)) {
                properties.store(output, "DevWatcher Minecraft Content Sync preferences");
            }
        } catch (IOException exception) {
            Watcher.LOGGER.warn("Could not save {} to {}", description, preferenceFile, exception);
        }
    }

    private static Path preferenceFile() {
        return FMLPaths.CONFIGDIR.get().resolve(PREFERENCE_FILE);
    }
}
