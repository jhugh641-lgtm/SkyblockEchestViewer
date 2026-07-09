package net.storageoverlay.data;

import com.google.gson.*;
import net.minecraft.core.HolderLookup;
import net.storageoverlay.StorageOverlayMod;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.TreeMap;

public class StorageData {

    public final TreeMap<StoragePageSlot, StorageInventory> storageInventories = new TreeMap<>();

    public static class StorageInventory {
        public String title;
        public final StoragePageSlot slot;
        public VirtualInventory inventory;

        public StorageInventory(String title, StoragePageSlot slot, VirtualInventory inventory) {
            this.title = title;
            this.slot = slot;
            this.inventory = inventory;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public JsonObject toJson(HolderLookup.Provider registries) {
        JsonObject root = new JsonObject();
        JsonObject pages = new JsonObject();
        for (Map.Entry<StoragePageSlot, StorageInventory> entry : storageInventories.entrySet()) {
            StorageInventory inv = entry.getValue();
            JsonObject pageObj = new JsonObject();
            pageObj.addProperty("title", inv.title);
            if (inv.inventory != null) {
                pageObj.addProperty("inventory", inv.inventory.toBase64(registries));
            } else {
                pageObj.add("inventory", JsonNull.INSTANCE);
            }
            pages.add(String.valueOf(entry.getKey().getIndex()), pageObj);
        }
        root.add("pages", pages);
        return root;
    }

    public static StorageData fromJson(JsonObject root, HolderLookup.Provider registries) {
        StorageData data = new StorageData();
        if (!root.has("pages")) return data;
        JsonObject pages = root.getAsJsonObject("pages");
        for (Map.Entry<String, JsonElement> entry : pages.entrySet()) {
            try {
                int idx = Integer.parseInt(entry.getKey());
                StoragePageSlot slot = new StoragePageSlot(idx);
                JsonObject pageObj = entry.getValue().getAsJsonObject();
                String title = pageObj.has("title") ? pageObj.get("title").getAsString() : slot.defaultName();
                VirtualInventory inv = null;
                if (pageObj.has("inventory") && !pageObj.get("inventory").isJsonNull()) {
                    String b64 = pageObj.get("inventory").getAsString();
                    if (!b64.isEmpty()) inv = VirtualInventory.fromBase64(b64, registries);
                }
                data.storageInventories.put(slot, new StorageInventory(title, slot, inv));
            } catch (Exception e) {
                StorageOverlayMod.LOGGER.error("Failed to load storage page entry", e);
            }
        }
        return data;
    }

    public void saveTo(Path file, HolderLookup.Provider registries) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(toJson(registries), w);
            }
        } catch (IOException e) {
            StorageOverlayMod.LOGGER.error("Failed to save StorageData", e);
        }
    }

    public static StorageData loadFrom(Path file, HolderLookup.Provider registries) {
        if (!Files.exists(file)) return new StorageData();
        try (Reader r = Files.newBufferedReader(file)) {
            JsonObject root = GSON.fromJson(r, JsonObject.class);
            return fromJson(root, registries);
        } catch (Exception e) {
            StorageOverlayMod.LOGGER.error("Failed to load StorageData", e);
            return new StorageData();
        }
    }
}
