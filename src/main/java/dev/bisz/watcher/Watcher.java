package dev.bisz.watcher;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(Watcher.MOD_ID)
public final class Watcher {
    public static final String MOD_ID = ModIdentity.MOD_ID;
    public static final Logger LOGGER = LogUtils.getLogger();

    public Watcher() {
        LOGGER.info("DevWatcher Minecraft Content Syncer loaded");
        if (FMLEnvironment.dist == Dist.CLIENT) {
            WatcherClient.initialize();
        }
    }
}
