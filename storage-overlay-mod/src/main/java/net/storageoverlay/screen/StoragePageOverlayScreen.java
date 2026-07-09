package net.storageoverlay.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
// Renamed from Yarn's "KeyInput" — confirmed via the 26.1 migration primer that
// net.minecraft.client.input.CharacterEvent exists with this shape; KeyEvent is the sibling
// type for key (non-character) input and is a reasonable-confidence match, but wasn't
// directly confirmed the way CharacterEvent was — check here first if this doesn't compile.
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.network.chat.Component;
import net.storageoverlay.StorageBackingHandle;
import net.storageoverlay.StorageConfig;
import net.storageoverlay.StorageOverlayHandler;
import net.storageoverlay.data.StoragePageSlot;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Extends ContainerScreen so that other mods (SkyHanni etc.) which check
 * `currentScreen instanceof ContainerScreen` can detect the open container.
 * getTitle() returns the real server container title ("Ender Chest #1" etc.)
 */
public class StoragePageOverlayScreen extends ContainerScreen {

    private static final int SLOT_SIZE = 18;
    private static final int SLOTS_W   = 9;

    private ContainerScreen backingScreen;
    private StorageBackingHandle.Page handle;
    private final StorageOverviewScreen overview;

    private boolean wasLeftDown  = false;
    private boolean wasRightDown = false;

    public StoragePageOverlayScreen(Minecraft client, ContainerScreen backingScreen,
                                     StorageBackingHandle handle, StorageOverviewScreen overview) {
        super(
            ((StorageBackingHandle.Page) handle).handler(),
            client.player.getInventory(),
            backingScreen.getTitle()
        );
        this.backingScreen = backingScreen;
        this.handle        = (StorageBackingHandle.Page) handle;
        this.overview      = overview;
    }

    /** Return the real container title so other mods detect the correct container. */
    @Override
    public Component getTitle() {
        return backingScreen != null ? backingScreen.getTitle() : super.getTitle();
    }

    public void updateHandle(ContainerScreen newBacking, StorageBackingHandle.Page newHandle) {
        this.backingScreen = newBacking;
        this.handle        = newHandle;
        // Keep backingScreen's internal handler in sync so invokeOnMouseClick uses the right syncId
        ((net.storageoverlay.mixin.HandledScreenMixin)(Object) backingScreen)
                .setScreenHandler(newHandle.handler());
    }

    // Confirmed via the migration primer: AbstractContainerScreen#renderBg (Yarn's
    // "drawBackground") was removed entirely, replaced by Screen#extractBackground. Since we
    // draw everything ourselves in extractRenderState() and never wanted a vanilla background
    // here anyway, there's simply no override needed anymore.

    // Confirmed via javap on the decompiled 26.2 client jar: AbstractContainerScreen's label
    // step is named extractLabels(GuiGraphicsExtractor, int, int) in this version, consistent
    // with the extract* naming used everywhere else in this port (extractContents, extractSlots,
    // extractTooltip, etc.). We still call super.extractRenderState() below (for SkyHanni's mixin
    // hooks etc.), which normally draws these labels as its last step; no-op'ing this suppresses
    // just that, without losing the rest of what super gives us.
    @Override
    protected void extractLabels(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        // no-op — we draw our own per-page titles in StorageOverviewScreen; the vanilla
        // container's raw title/"Inventory" text has no useful position in our custom layout
        // and was showing through underneath/behind our panels.
    }

    // ---- Init ----

    @Override
    protected void init() {
        super.init();
        overview.initForSize(width, height);
        var sf = overview.getSearchField();
        if (sf != null) setFocused(sf); // render manually, don't add to children()
    }

    // ---- Rendering ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        Minecraft mc = Minecraft.getInstance();
        long wh = mc.getWindow().handle();

        boolean leftDown  = GLFW.glfwGetMouseButton(wh, GLFW.GLFW_MOUSE_BUTTON_LEFT)  == GLFW.GLFW_PRESS;
        boolean rightDown = GLFW.glfwGetMouseButton(wh, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        boolean shift     = GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_LEFT_SHIFT)  == GLFW.GLFW_PRESS
                         || GLFW.glfwGetKey(wh, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;

        if (leftDown  && !wasLeftDown)  handleClick(mouseX, mouseY, 0, shift);
        if (rightDown && !wasRightDown) handleClick(mouseX, mouseY, 1, false);
        wasLeftDown  = leftDown;
        wasRightDown = rightDown;

        // Draw dim + panels
        overview.drawBackgrounds(ctx);

        int rowCount   = handle.handler().getRowCount();
        List<Slot> all = handle.handler().slots;
        List<Slot> pageSlots = all.subList(9, rowCount * 9);

        overview.drawPagesWithLiveSlots(ctx, mouseX, mouseY, handle.storagePageSlot(), pageSlots);
        overview.drawScrollBarPublic(ctx);
        overview.drawBottomBarPublic(ctx, mouseX, mouseY);
        overview.drawPlayerInventoryPublic(ctx, mouseX, mouseY);

        // Cursor / held item
        ItemStack cursor = handle.handler().getCarried();
        if (!cursor.isEmpty()) {
            ctx.item(cursor, mouseX - 8, mouseY - 8);
            ctx.itemDecorations(font, cursor, mouseX - 8, mouseY - 8, null);
        }

        // Hover highlight
        int hovered = getHandlerSlotAtMouse(mouseX, mouseY);
        if (hovered >= 0) {
            int[] pos = getScreenPosForHandlerSlot(hovered);
            if (pos != null)
                ctx.fill(pos[0]+1, pos[1]+1, pos[0]+SLOT_SIZE-1, pos[1]+SLOT_SIZE-1, 0x80_FFFFFF);
        }

        // Draw search field directly (avoid iterating children() which includes slot elements)
        var sf = overview.getSearchField();
        if (sf != null) sf.extractRenderState(ctx, mouseX, mouseY, delta);

        // Tooltips on top
        if (hovered >= 0 && mc.player != null && hovered < handle.handler().slots.size()) {
            ItemStack stack = handle.handler().slots.get(hovered).getItem();
            if (!stack.isEmpty()) ctx.setTooltipForNextFrame(font, stack, mouseX, mouseY);
        }

        // Fire SkyHanni's mixin hooks (ChestGuiOverlayRenderEvent etc.) by calling super.render().
        // Slots are at (-32768, -32768) so vanilla rendering is invisible.
        // Pass real mouse coordinates so AbstractContainerScreen doesn't think mouse is at slot position.
        for (Slot slot : handle.handler().slots) {
            ((net.storageoverlay.mixin.SlotMixin)(Object) slot).setX(-32768);
            ((net.storageoverlay.mixin.SlotMixin)(Object) slot).setY(-32768);
        }
        super.extractRenderState(ctx, mouseX, mouseY, delta);
    }



    // ---- Click handling ----

    private void handleClick(int mouseX, int mouseY, int button, boolean shift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (button == 0 && overview.isEditOrSearchClick(mouseX, mouseY)) return;

        // Sync backingScreen's internal handler to the player's current screen handler.
        // Hypixel opens multiple containers in rapid succession; by click time the player's
        // containerMenu may have advanced past what backingScreen was constructed with,
        // causing "Ignoring click in mismatching container" warnings.
        if (mc.player.containerMenu instanceof net.minecraft.world.inventory.ChestMenu) {
            ((net.storageoverlay.mixin.HandledScreenMixin)(Object) backingScreen)
                    .setScreenHandler(mc.player.containerMenu);
        }

        int handlerSlot = getHandlerSlotAtMouse(mouseX, mouseY);
        if (handlerSlot >= 0 && handlerSlot < mc.player.containerMenu.slots.size()) {
            Slot slot = mc.player.containerMenu.slots.get(handlerSlot);
            ContainerInput action = shift ? ContainerInput.QUICK_MOVE : ContainerInput.PICKUP;
            ((net.storageoverlay.mixin.HandledScreenMixin)(Object) backingScreen)
                    .invokeOnMouseClick(slot, handlerSlot, button, action);
            return;
        }

        overview.handlePageClick(mouseX, mouseY, handle.storagePageSlot());
    }

    // ---- Slot hit detection ----

    private int getHandlerSlotAtMouse(int mx, int my) {
        int rowCount = handle.handler().getRowCount();

        int[] pageOrigin = overview.getActivePageSlotOrigin();
        if (pageOrigin != null) {
            int numContentSlots = (rowCount - 1) * SLOTS_W;
            for (int i = 0; i < numContentSlots; i++) {
                int sx = pageOrigin[0] + (i % SLOTS_W) * SLOT_SIZE;
                int sy = pageOrigin[1] + (i / SLOTS_W) * SLOT_SIZE;
                if (mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE)
                    return i + 9;
            }
        }

        int[] invOrigin = overview.getPlayerInvOrigin();
        if (invOrigin == null) return -1;
        int invX = invOrigin[0], invTop = invOrigin[1];

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < SLOTS_W; col++) {
                int sx = invX + col * SLOT_SIZE, sy = invTop + row * SLOT_SIZE;
                if (mx >= sx && mx < sx + SLOT_SIZE && my >= sy && my < sy + SLOT_SIZE)
                    return rowCount * 9 + row * 9 + col;
            }
        }

        int hotbarY = invTop + 3 * SLOT_SIZE + 4;
        for (int col = 0; col < SLOTS_W; col++) {
            int sx = invX + col * SLOT_SIZE;
            if (mx >= sx && mx < sx + SLOT_SIZE && my >= hotbarY && my < hotbarY + SLOT_SIZE)
                return rowCount * 9 + 27 + col;
        }

        return -1;
    }

    private int[] getScreenPosForHandlerSlot(int handlerSlot) {
        int rowCount = handle.handler().getRowCount();
        if (handlerSlot >= 9 && handlerSlot < rowCount * 9) {
            int[] origin = overview.getActivePageSlotOrigin();
            if (origin == null) return null;
            int i = handlerSlot - 9;
            return new int[]{origin[0] + (i % SLOTS_W) * SLOT_SIZE, origin[1] + (i / SLOTS_W) * SLOT_SIZE};
        }
        int[] invOrigin = overview.getPlayerInvOrigin();
        if (invOrigin == null) return null;
        int offset = handlerSlot - rowCount * 9;
        if (offset < 27)
            return new int[]{invOrigin[0] + (offset % SLOTS_W) * SLOT_SIZE, invOrigin[1] + (offset / SLOTS_W) * SLOT_SIZE};
        if (offset < 36)
            return new int[]{invOrigin[0] + (offset - 27) * SLOT_SIZE, invOrigin[1] + 3 * SLOT_SIZE + 4};
        return null;
    }

    // ---- Input ----

    @Override
    public boolean mouseScrolled(double mx, double my, double hA, double vA) {
        overview.coerceScroll((float) StorageConfig.get().adjustScrollSpeed(vA));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        var sf = overview.getSearchField();
        if (sf != null && sf.keyPressed(input)) return true;
        return overview.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        var sf = overview.getSearchField();
        if (sf != null && sf.charTyped(event)) return true;
        return super.charTyped(event);
    }

    @Override
    public void onClose() {
        StorageOverlayHandler.setExiting(true);
        super.onClose(); // AbstractContainerScreen#onClose: sends ServerboundContainerClosePacket,
                          // calls player.closeContainer(), resets containerMenu
        StorageOverlayHandler.handleOverlayClosed(); // shared stateId-resync trick
    }
}
