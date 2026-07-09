package net.storageoverlay;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public class StorageOverlayMod implements ClientModInitializer {

    public static final String MOD_ID = "storageoverlay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        StorageConfig.init(configDir);
        StorageDataManager.init(configDir);
        StorageOverlayHandler.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                StorageDataManager.get().onLogout());

        ClientLifecycleEvents.CLIENT_STOPPING.register(client ->
                StorageDataManager.get().save());

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // PORT-VERIFY: "getCurrentServerEntry()"/".address" were Yarn's names (returning a
            // ServerInfo-like record). Mojmap's equivalent is commonly known as
            // "getCurrentServer()" returning a ServerData whose host field is "ip" — confirm
            // both the method and field name before relying on this.
            String address = client.getCurrentServer() != null
                    ? client.getCurrentServer().ip
                    : "singleplayer";
            StorageDataManager.get().onLogin(address);
        });

        LOGGER.info("StorageOverlay loaded.");
    }
}
