package net.storageoverlay;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.storageoverlay.data.StoragePageSlot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public sealed interface StorageBackingHandle {

    // Records must explicitly implement the interface method
    ChestMenu getHandler();

    record Overview(ChestMenu handler) implements StorageBackingHandle {
        public ChestMenu getHandler() { return handler; }
    }

    record Page(ChestMenu handler, StoragePageSlot storagePageSlot) implements StorageBackingHandle {
        public ChestMenu getHandler() { return handler; }
    }

    Pattern ENDER_CHEST_PATTERN = Pattern.compile("^Ender Chest (?:✦ )?\\(([1-9])/[1-9]\\)$");
    Pattern BACKPACK_PATTERN    = Pattern.compile("^.+Backpack (?:✦ )?\\(Slot #([0-9]+)\\)$");

    static StorageBackingHandle fromScreen(Screen screen) {
        if (screen == null) return null;
        if (!(screen instanceof ContainerScreen gcs)) return null;
        if (!(gcs.getMenu() instanceof ChestMenu handler)) return null;

        String title = screen.getTitle().getString().replaceAll("§[0-9a-fk-or]", "");

        if (title.equals("Storage")) return new Overview(handler);

        Matcher ec = ENDER_CHEST_PATTERN.matcher(title);
        if (ec.matches()) return new Page(handler, StoragePageSlot.ofEnderChestPage(Integer.parseInt(ec.group(1))));

        Matcher bp = BACKPACK_PATTERN.matcher(title);
        if (bp.matches()) return new Page(handler, StoragePageSlot.ofBackPackPage(Integer.parseInt(bp.group(1))));

        return null;
    }
}
