package net.storageoverlay;

import net.minecraft.client.Minecraft;
import net.storageoverlay.data.StorageData;

import java.nio.file.Path;

public class StorageDataManager {

    private static StorageDataManager instance;
    private final Path dataDir;
    private StorageData currentData;
    private String currentProfile = null;

    private StorageDataManager(Path dataDir) { this.dataDir = dataDir; }

    public static void init(Path configDir) {
        instance = new StorageDataManager(configDir.resolve("storage-data"));
    }

    public static StorageDataManager get() { return instance; }

    public StorageData getData() {
        if (currentData == null) currentData = new StorageData();
        return currentData;
    }

    public void onLogin(String profileId) {
        if (profileId.equals(currentProfile)) return;
        save();
        currentProfile = profileId;
        load();
    }

    public void onLogout() {
        save();
        currentData = null;
        currentProfile = null;
    }

    public void markDirty() {}

    public void save() {
        if (currentData == null || currentProfile == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        currentData.saveTo(dataDir.resolve(sanitize(currentProfile) + ".json"), mc.level.registryAccess());
    }

    private void load() {
        if (currentProfile == null) { currentData = new StorageData(); return; }
        Minecraft mc = Minecraft.getInstance();
        Path file = dataDir.resolve(sanitize(currentProfile) + ".json");
        currentData = mc.level != null
                ? StorageData.loadFrom(file, mc.level.registryAccess())
                : new StorageData();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
