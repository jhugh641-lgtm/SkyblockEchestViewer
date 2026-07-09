package net.storageoverlay.data;

import net.minecraft.client.Minecraft;

public final class StoragePageSlot implements Comparable<StoragePageSlot> {

    private final int index;

    public StoragePageSlot(int index) {
        if (index < 0 || index >= 27)
            throw new IllegalArgumentException("StoragePageSlot index out of range: " + index);
        this.index = index;
    }

    public int getIndex() { return index; }
    public boolean isEnderChest() { return index < 9; }
    public boolean isBackPack() { return !isEnderChest(); }

    public String defaultName() {
        return isEnderChest() ? "Ender Chest #" + (index + 1) : "Backpack #" + (index - 9 + 1);
    }

    public void navigateTo() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (isBackPack()) {
            mc.player.connection.sendCommand("backpack " + (index - 9 + 1));
        } else {
            mc.player.connection.sendCommand("enderchest " + (index + 1));
        }
    }

    public static StoragePageSlot fromOverviewSlotIndex(int slot) {
        if (slot >= 9 && slot < 18) return new StoragePageSlot(slot - 9);
        if (slot >= 27 && slot < 45) return new StoragePageSlot(slot - 27 + 9);
        return null;
    }

    public static StoragePageSlot ofEnderChestPage(int slot) { return new StoragePageSlot(slot - 1); }
    public static StoragePageSlot ofBackPackPage(int slot) { return new StoragePageSlot(slot - 1 + 9); }

    @Override public int compareTo(StoragePageSlot o) { return Integer.compare(index, o.index); }
    @Override public boolean equals(Object o) { return o instanceof StoragePageSlot s && s.index == index; }
    @Override public int hashCode() { return index; }
    @Override public String toString() { return "StoragePageSlot(" + index + ")"; }
}
