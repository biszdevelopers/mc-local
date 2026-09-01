package dev.bisz.watcher;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;

final class WatcherClient {
    private static ContentSyncCoordinator contentSyncCoordinator;

    private WatcherClient() {
    }

    static void initialize() {
        if (!ConnectionPreference.hasAcceptedStartupConsent()) {
            if (!StartupDialog.awaitConsent()) {
                Watcher.LOGGER.info("Startup consent was declined; terminating Minecraft");
                System.exit(0);
                return;
            }

            ConnectionPreference.saveAcceptedStartupConsent();
        }

        boolean automaticallyConnect = ConnectionPreference.hasAcceptedAutomaticConnection();
        if (automaticallyConnect) {
            Watcher.LOGGER.info("Using the previously accepted Relizc SMP connection choice");
        }

        ServerSettings.load().ifPresent(settings -> {
            AutoConnector connector = new AutoConnector(settings, automaticallyConnect);
            MinecraftForge.EVENT_BUS.addListener(connector::onClientTick);
        });

        MinecraftLogWindow.open();
        contentSyncCoordinator = new ContentSyncCoordinator();
        contentSyncCoordinator.start();
        contentSyncCoordinator.awaitContinue();
        MinecraftForge.EVENT_BUS.addListener(WatcherClient::onGameShuttingDown);
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        if (contentSyncCoordinator != null) {
            contentSyncCoordinator.stop();
            contentSyncCoordinator = null;
        }
        MinecraftLogWindow.close();
    }

}
