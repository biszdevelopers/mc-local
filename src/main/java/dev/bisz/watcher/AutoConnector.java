package dev.bisz.watcher;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraftforge.event.TickEvent;

final class AutoConnector {
    private final ServerSettings settings;
    private final boolean automaticallyConnect;
    private boolean attempted;

    AutoConnector(ServerSettings settings, boolean automaticallyConnect) {
        this.settings = settings;
        this.automaticallyConnect = automaticallyConnect;
    }

    void onClientTick(TickEvent.ClientTickEvent event) {
        if (attempted || event.phase != TickEvent.Phase.END) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof TitleScreen titleScreen)) {
            return;
        }

        attempted = true;
        if (!automaticallyConnect) {
            minecraft.setScreen(new ConfirmScreen(
                    accepted -> handleConnectionChoice(accepted, titleScreen, minecraft),
                    Component.literal("Relizc SMP"),
                    Component.literal("Automatically connect to Relizc SMP?")
            ));
            return;
        }

        connect(titleScreen, minecraft);
    }

    private void handleConnectionChoice(boolean accepted, TitleScreen titleScreen, Minecraft minecraft) {
        if (!accepted) {
            Watcher.LOGGER.info("Automatic Relizc SMP connection was declined");
            minecraft.setScreen(titleScreen);
            return;
        }

        ConnectionPreference.saveAcceptedAutomaticConnection();
        connect(titleScreen, minecraft);
    }

    private void connect(TitleScreen titleScreen, Minecraft minecraft) {
        if (!ServerAddress.isValidAddress(settings.address())) {
            Watcher.LOGGER.error("Invalid Relizc SMP address in config: {}", settings.address());
            minecraft.setScreen(titleScreen);
            return;
        }

        ServerAddress address = ServerAddress.parseString(settings.address());
        ServerData serverData = new ServerData(settings.name(), settings.address(), false);
        Watcher.LOGGER.info("Automatically connecting to {} at {}", settings.name(), settings.address());
        ConnectScreen.startConnecting(titleScreen, minecraft, address, serverData, false);
    }
}
