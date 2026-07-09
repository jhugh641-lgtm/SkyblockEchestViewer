package net.storageoverlay;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.Slot;
import net.storageoverlay.data.StorageData;
import net.storageoverlay.data.StoragePageSlot;
import net.storageoverlay.data.VirtualInventory;
import net.storageoverlay.screen.StorageOverviewScreen;
import net.storageoverlay.screen.StoragePageOverlayScreen;

import java.util.ArrayList;
import java.util.List;

public class StorageOverlayHandler {

    private static final List<Item> EMPTY_SLOT_ITEMS = List.of(
            Items.RED_STAINED_GLASS_PANE,
            Items.BROWN_STAINED_GLASS_PANE,
            Items.GRAY_DYE
    );

    private static StorageBackingHandle currentHandle = null;
    private static StorageOverviewScreen pendingOverview = null;
    private static boolean isExiting = false;

    public static void register() {
        ScreenEvents.AFTER_INIT.register(StorageOverlayHandler::onScreenInit);
    }

    private static void onScreenInit(Minecraft client, Screen screen, int w, int h) {
        if (editMode) { editMode = false; return; }
        // Don't wrap our own overlay — it extends ContainerScreen so would trigger again
        if (screen instanceof StoragePageOverlayScreen) return;
        if (screen instanceof StorageOverviewScreen) return;
        if (!(screen instanceof ContainerScreen gcs)) return;

        StorageBackingHandle handle = StorageBackingHandle.fromScreen(gcs);
        if (handle == null) return;

        StorageBackingHandle previousHandle = currentHandle;
        currentHandle = handle;
        rememberContent(previousHandle);

        ScreenEvents.afterTick(screen).register(s -> rememberContent(currentHandle));

        if (handle instanceof StorageBackingHandle.Overview) {
            if (StorageConfig.get().alwaysReplace) {
                StorageOverviewScreen overlay = new StorageOverviewScreen(client, gcs, handle);
                pendingOverview = overlay;
                double[] mx = new double[1], my = new double[1];
                org.lwjgl.glfw.GLFW.glfwGetCursorPos(client.getWindow().handle(), mx, my);
                client.execute(() -> {
                    // Null currentScreen first so setScreen() skips calling gcs.onClose() —
                    // without this, gcs.onClose() sends ServerboundContainerClosePacket and the
                    // server closes the container (syncId mismatch on all future clicks).
                    // Pre-26.2: this field is private directly on Minecraft (it only moved to the
                    // new Gui class in 26.2), so we go through the accessor mixin to null it.
                    ((net.storageoverlay.mixin.MinecraftScreenMixin)(Object) client).setCurrentScreen(null);
                    client.setScreen(overlay);
                    org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().handle(), mx[0], my[0]);
                });
            }
        } else if (handle instanceof StorageBackingHandle.Page page) {
            StorageOverviewScreen overview = pendingOverview != null
                    ? pendingOverview
                    : (StorageConfig.get().alwaysReplace ? new StorageOverviewScreen(client, null, null) : null);
            if (overview != null) {
                Screen current = ((net.storageoverlay.mixin.MinecraftScreenMixin)(Object) client).getCurrentScreen();
                if (current instanceof StoragePageOverlayScreen existing) {
                    existing.updateHandle(gcs, page);
                } else {
                    StoragePageOverlayScreen pageOverlay = new StoragePageOverlayScreen(client, gcs, handle, overview);
                    double[] mx = new double[1], my = new double[1];
                    org.lwjgl.glfw.GLFW.glfwGetCursorPos(client.getWindow().handle(), mx, my);
                    client.execute(() -> {
                        // Null currentScreen first so setScreen() skips calling gcs.onClose() —
                        // without this, gcs.onClose() sends ServerboundContainerClosePacket and the
                        // server closes the container (syncId mismatch on all future clicks).
                        // Pre-26.2: this field is private directly on Minecraft (it only moved to
                        // the new Gui class in 26.2), so we go through the accessor mixin to null it.
                        ((net.storageoverlay.mixin.MinecraftScreenMixin)(Object) client).setCurrentScreen(null);
                        client.setScreen(pageOverlay);
                        org.lwjgl.glfw.GLFW.glfwSetCursorPos(client.getWindow().handle(), mx[0], my[0]);
                    });
                }
            }
        }
    }

    public static void rememberContent(StorageBackingHandle handle) {
        if (handle == null) return;
        StorageData data = StorageDataManager.get().getData();
        if (handle instanceof StorageBackingHandle.Overview ov) rememberOverview(ov, data);
        else if (handle instanceof StorageBackingHandle.Page pg) rememberPage(pg, data);
        StorageDataManager.get().markDirty();
        // Invalidate search cache so newly loaded pages show correctly
        if (pendingOverview != null) pendingOverview.invalidateSearchCache();
    }

    private static void rememberOverview(StorageBackingHandle.Overview handle, StorageData data) {
        List<Slot> slots = handle.handler().slots;
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            if (slot.getItem().isEmpty()) continue;
            StoragePageSlot pageSlot = StoragePageSlot.fromOverviewSlotIndex(i);
            if (pageSlot == null) continue;
            boolean isEmpty = EMPTY_SLOT_ITEMS.contains(slot.getItem().getItem());
            if (data.storageInventories.containsKey(pageSlot)) {
                if (isEmpty) data.storageInventories.remove(pageSlot);
            } else if (!isEmpty) {
                data.storageInventories.put(pageSlot,
                        new StorageData.StorageInventory(pageSlot.defaultName(), pageSlot, null));
            }
        }
    }

    private static void rememberPage(StorageBackingHandle.Page handle, StorageData data) {
        List<Slot> slots = handle.handler().slots;
        // Confirmed: ChestMenu's row-count accessor is getRowCount() (was getRows() in Yarn).
        int rowCount = handle.handler().getRowCount();
        List<net.minecraft.world.item.ItemStack> stacks = new ArrayList<>();
        for (int i = 9; i < rowCount * 9; i++) {
            stacks.add(slots.get(i).getItem().copy());
        }
        if (stacks.isEmpty() || stacks.size() % 9 != 0) return;
        VirtualInventory newInv = new VirtualInventory(stacks);
        StoragePageSlot pageSlot = handle.storagePageSlot();
        data.storageInventories.compute(pageSlot, (slot, existing) -> {
            if (existing == null) existing = new StorageData.StorageInventory(slot.defaultName(), slot, null);
            existing.inventory = newInv;
            return existing;
        });
    }

    public static StorageBackingHandle getCurrentHandle() { return currentHandle; }
    public static void setExiting(boolean val) { isExiting = val; }
    public static boolean editMode = false;
    public static void setPendingOverview(StorageOverviewScreen s) { pendingOverview = s; }
    public static StorageOverviewScreen getPendingOverview() { return pendingOverview; }

    /**
     * Shared cleanup run when either overlay screen (StorageOverviewScreen or
     * StoragePageOverlayScreen) closes. Forces a stateId resync for the player inventory.
     * After moving items via the storage overlay, the server updates the stateId. If the
     * player opens their inventory before the server's update packets arrive, the first
     * click fails due to stateId mismatch. Sending a PICKUP_ALL with slot=-999 (outside
     * inventory) triggers the server to immediately send a full inventory resync
     * (ClientboundContainerSetContentPacket), so the stateId is up to date before the
     * player's next interaction.
     */
    public static void handleOverlayClosed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        mc.execute(() -> {
            mc.player.containerMenu = mc.player.inventoryMenu;
            mc.gameMode.handleContainerInput(
                    mc.player.containerMenu.containerId,
                    -999, 0,
                    net.minecraft.world.inventory.ContainerInput.PICKUP,
                    mc.player
            );
        });
    }
}
