package net.storageoverlay.data;

import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.core.HolderLookup;
import net.storageoverlay.StorageOverlayMod;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

public final class VirtualInventory {

    private static final String INVENTORY_KEY = "INVENTORY";
    private final List<ItemStack> stacks;
    public final int rows;

    public VirtualInventory(List<ItemStack> stacks) {
        if (stacks.size() % 9 != 0 || stacks.size() / 9 < 1 || stacks.size() / 9 > 6)
            throw new IllegalArgumentException("Invalid size: " + stacks.size());
        this.stacks = Collections.unmodifiableList(new ArrayList<>(stacks));
        this.rows = stacks.size() / 9;
    }

    public List<ItemStack> getStacks() { return stacks; }

    public String toBase64(HolderLookup.Provider registries) {
        try {
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            ListTag list = new ListTag();
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) {
                    list.add(new CompoundTag());
                } else {
                    Tag encoded = ItemStack.CODEC.encode(stack, ops, new CompoundTag()).getOrThrow();
                    list.add(encoded);
                }
            }
            CompoundTag root = new CompoundTag();
            root.put(INVENTORY_KEY, list);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            StorageOverlayMod.LOGGER.error("Failed to serialize VirtualInventory", e);
            return "";
        }
    }

    public static VirtualInventory fromBase64(String base64, HolderLookup.Provider registries) {
        try {
            var ops = registries.createSerializationContext(NbtOps.INSTANCE);
            byte[] bytes = Base64.getDecoder().decode(base64);
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
            ListTag list = root.getList(INVENTORY_KEY).orElse(new ListTag());
            List<ItemStack> stacks = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i).orElse(new CompoundTag());
                if (tag.isEmpty()) {
                    stacks.add(ItemStack.EMPTY);
                } else {
                    stacks.add(ItemStack.CODEC.parse(ops, tag).getOrThrow());
                }
            }
            return new VirtualInventory(stacks);
        } catch (Exception e) {
            StorageOverlayMod.LOGGER.error("Failed to deserialize VirtualInventory", e);
            return null;
        }
    }
}
