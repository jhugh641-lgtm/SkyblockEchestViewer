package net.storageoverlay;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;

public class StorageConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    private static StorageConfig instance;

    public boolean alwaysReplace       = true;
    public int     columns             = 3;
    public int     height              = 3 * 18 * 6;
    public boolean retainScroll        = true;
    public int     scrollSpeed         = 10;
    public boolean inverseScroll       = false;
    public int     padding             = 5;
    public int     margin              = 20;
    public boolean itemsBlockScrolling = true;

    public static StorageConfig get() {
        if (instance == null) instance = new StorageConfig();
        return instance;
    }

    public static void init(Path configDir) {
        configPath = configDir.resolve("storage-overlay.json");
        if (Files.exists(configPath)) {
            try (Reader r = Files.newBufferedReader(configPath)) {
                instance = GSON.fromJson(r, StorageConfig.class);
            } catch (Exception e) {
                StorageOverlayMod.LOGGER.error("Failed to load config", e);
                instance = new StorageConfig();
            }
        } else {
            instance = new StorageConfig();
            instance.save();
        }
    }

    public void save() {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer w = Files.newBufferedWriter(configPath)) {
                GSON.toJson(this, w);
            }
        } catch (IOException e) {
            StorageOverlayMod.LOGGER.error("Failed to save config", e);
        }
    }

    public double adjustScrollSpeed(double amount) {
        return amount * scrollSpeed * (inverseScroll ? 1 : -1);
    }
}
