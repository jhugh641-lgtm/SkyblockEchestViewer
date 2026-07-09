package net.storageoverlay.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.components.EditBox;
// Renamed from Yarn's "KeyInput" — confirmed via the 26.1 migration primer that
// net.minecraft.client.input.CharacterEvent exists with this shape; KeyEvent is the sibling
// type for key (non-character) input and is a reasonable-confidence match, but wasn't
// directly confirmed the way CharacterEvent was — check here first if this doesn't compile.
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.network.chat.Component;
import net.storageoverlay.StorageBackingHandle;
import net.storageoverlay.StorageConfig;
import net.storageoverlay.StorageDataManager;
import net.storageoverlay.StorageOverlayHandler;
import net.storageoverlay.data.StorageData;
import net.storageoverlay.data.StoragePageSlot;
import net.storageoverlay.data.VirtualInventory;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.Map;

public class StorageOverviewScreen extends Screen {

    private static final int SLOT_SIZE     = 18;
    private static final int SLOTS_W       = 9;
    private static final int PAGE_W        = SLOTS_W * SLOT_SIZE + 8;
    private int PLAYER_INV_H = 100; // computed in init()
    private static final int PLAYER_INV_W  = 9 * SLOT_SIZE + 8;
    private static final int BAR_H         = 22; // height of the bottom bar row
    private static final int EDIT_BTN_W    = 80;
    private static final int SCROLL_W      = 12;

    // MC GUI colours
    private static final int GUI_BG       = 0xFF_C6C6C6;
    private static final int SLOT_BG      = 0xFF_8B8B8B;
    private static final int BORDER_DARK  = 0xFF_555555;
    private static final int BORDER_LITE  = 0xFF_FFFFFF;
    private static final int ACTIVE_TXT   = 0xFFFF55;
    private static final int SCROLLBAR_BG = 0xFF_8B8B8B;
    private static final int SCROLL_KNOB  = 0xFF_555555;

    private static float scroll = 0F;
    private static String savedSearchText = "";

    // Per-page searchable text index (title + item names + tooltip/lore lines, all lowercased).
    // Built lazily ONCE per page and reused across every keystroke — this is what's expensive
    // (getTooltipLines does enchantment/attribute formatting + translation lookups), not the
    // actual string match. Only cleared via invalidateSearchCache() when the underlying storage
    // data changes, NOT when the search query changes, so typing never re-scans items.
    private final java.util.Map<StoragePageSlot, List<String>> searchIndex = new java.util.HashMap<>();
    private static int lastContentHeight = 0;

    private final ContainerScreen backingScreen;
    private final StorageBackingHandle handle;

    // Layout
    private int panelX, panelY, panelW, panelH;
    private int scrollX, scrollY, scrollH;
    private int barX, barY, barW;          // bottom bar between panel and inventory
    private int editBtnX, editBtnY;
    private int searchFieldX, searchFieldY, searchFieldW;
    private int playerInvX, playerInvY;
    private int pageWidthCount;

    private boolean wasLeftDown = false;
    private boolean knobGrabbed = false;
    public boolean isExiting    = false;
    private StoragePageSlot activePage = null;

    // Search widget — shared between overview and page overlay
    private EditBox searchField;

    public StorageOverviewScreen(Minecraft client, ContainerScreen backingScreen,
                                  StorageBackingHandle handle) {
        super(Component.literal("Storage"));
        this.backingScreen = backingScreen;
        this.handle = handle;
        if (handle instanceof StorageBackingHandle.Page p) activePage = p.storagePageSlot();
    }

    // ---- Layout & init ----

    @Override
    protected void init() {
        StorageConfig cfg = StorageConfig.get();
        pageWidthCount = Math.max(1, Math.min(cfg.columns, (width - 20) / (PAGE_W + cfg.padding)));

        int contentW = pageWidthCount * PAGE_W + (pageWidthCount - 1) * cfg.padding;
        panelW = contentW + 8 + SCROLL_W + 4;
        panelH = Math.min(height - PLAYER_INV_H - BAR_H - 30, cfg.height);
        panelX = width / 2 - panelW / 2;
        panelY = Math.max(4, (height - (panelH + BAR_H + PLAYER_INV_H + 10)) / 2);

        scrollX = panelX + panelW - SCROLL_W - 2;
        scrollY = panelY + 4;
        scrollH = panelH - 8;

        // Bottom bar — fills gap between storage panel and player inventory
        barX = panelX;
        barY = panelY + panelH + 2;
        barW = panelW;

        editBtnX = barX + 2;
        editBtnY = barY + (BAR_H - 16) / 2;

        searchFieldX = editBtnX + EDIT_BTN_W + 4;
        searchFieldY = barY + (BAR_H - 18) / 2;
        searchFieldW = barW - EDIT_BTN_W - 8;

        // Player inventory below the bar
        playerInvX = panelX + (panelW - PLAYER_INV_W) / 2;
        playerInvY = barY + BAR_H + 2;

        coerceScroll(0F);

        // Compute inventory panel height now that font is available:
        // title + 3 rows main inv + gap + hotbar row + bottom padding
        PLAYER_INV_H = font.lineHeight + 5 + 3 * SLOT_SIZE + 4 + SLOT_SIZE + 6;

        // Create (or re-create) the search text field
        searchField = new EditBox(font, searchFieldX, searchFieldY,
                searchFieldW, 16, Component.literal("Search..."));
        searchField.setMaxLength(64);
        searchField.setHint(Component.literal("Search..."));
        searchField.setResponder(text -> savedSearchText = text);
        searchField.setValue(savedSearchText); // restore text across reinits
        searchField.setFocused(true);
        addRenderableWidget(searchField);
        setFocused(searchField);
    }

    @Override
    public void onClose() {
        if (!StorageConfig.get().retainScroll) scroll = 0F;
        savedSearchText = "";

        // This screen extends plain Screen, not AbstractContainerScreen, so it never gets
        // AbstractContainerScreen#onClose's automatic "close out the real container" cleanup
        // (player.closeContainer() + ServerboundContainerClosePacket). Do it ourselves here,
        // before super.onClose(), and only when there's actually a container open to close —
        // this screen can also be constructed standalone (backingScreen/handle == null), in
        // which case the player's containerMenu is already just their inventory menu.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }

        super.onClose();
        StorageOverlayHandler.handleOverlayClosed(); // shared stateId-resync trick
    }

    // ---- Rendering ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        long wh = Minecraft.getInstance().getWindow().handle();
        boolean leftDown = GLFW.glfwGetMouseButton(wh, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (leftDown && !wasLeftDown) handleClick(mouseX, mouseY);
        if (!leftDown) knobGrabbed = false;
        if (leftDown && knobGrabbed) updateScrollFromMouse(mouseY);
        wasLeftDown = leftDown;

        drawBackgrounds(ctx);
        drawContentArea(ctx, mouseX, mouseY, null);
        drawScrollBar(ctx);
        drawBottomBar(ctx, mouseX, mouseY);
        drawPlayerInventory(ctx, playerInvX, playerInvY, mouseX, mouseY);

        super.extractRenderState(ctx, mouseX, mouseY, delta); // draws the search field widget
    }

    // ---- Public draw methods (called by StoragePageOverlayScreen) ----

    public void drawBackgrounds(GuiGraphicsExtractor ctx) {
        ctx.fill(0, 0, width, height, 0x80_000000);
        drawPanel(ctx, panelX, panelY, panelW, panelH);
    }

    public void drawScrollBarPublic(GuiGraphicsExtractor ctx) { drawScrollBar(ctx); }

    public void drawPlayerInventoryPublic(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        drawPlayerInventory(ctx, playerInvX, playerInvY, mouseX, mouseY);
    }

    public void drawBottomBarPublic(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        drawBottomBar(ctx, mouseX, mouseY);
    }

    public void drawPagesWithLiveSlots(GuiGraphicsExtractor ctx, int mouseX, int mouseY,
                                        StoragePageSlot active, List<Slot> liveSlots) {
        this.activePage = active;
        int cx = panelX + 4, cy = panelY + 4;
        int cw = panelW - SCROLL_W - 8, ch = panelH - 8;
        ctx.enableScissor(cx, cy, cx + cw, cy + ch);
        drawAllPages(ctx, cx, cy, cw, mouseX, mouseY, liveSlots);
        ctx.disableScissor();
    }

    // ---- Content rendering ----

    private void drawContentArea(GuiGraphicsExtractor ctx, int mouseX, int mouseY, List<Slot> liveSlots) {
        int cx = panelX + 4, cy = panelY + 4;
        int cw = panelW - SCROLL_W - 8, ch = panelH - 8;
        ctx.enableScissor(cx, cy, cx + cw, cy + ch);
        drawAllPages(ctx, cx, cy, cw, mouseX, mouseY, liveSlots);
        ctx.disableScissor();
    }

    private void drawAllPages(GuiGraphicsExtractor ctx, int contentX, int contentY, int contentW,
                               int mouseX, int mouseY, List<Slot> liveSlots) {
        StorageConfig cfg = StorageConfig.get();
        StorageData data = StorageDataManager.get().getData();
        int col = 0, rowY = contentY - Math.round(scroll), rowH = 0;
        for (Map.Entry<StoragePageSlot, StorageData.StorageInventory> e : data.storageInventories.entrySet()) {
            StorageData.StorageInventory inv = e.getValue();
            if (!matchesSearch(inv)) continue;
            int ph = pageHeight(inv, liveSlots != null && e.getKey().equals(activePage) ? liveSlots : null);
            int px = contentX + col * (PAGE_W + cfg.padding);
            drawPageBox(ctx, px, rowY, inv, e.getKey(), mouseX, mouseY,
                    e.getKey().equals(activePage) ? liveSlots : null);
            rowH = Math.max(rowH, ph + cfg.padding);
            col++;
            if (col >= pageWidthCount) { col = 0; rowY += rowH; rowH = 0; }
        }
        lastContentHeight = (rowY + rowH) - (contentY - Math.round(scroll));
    }

    private void drawPageBox(GuiGraphicsExtractor ctx, int x, int y, StorageData.StorageInventory inv,
                              StoragePageSlot slot, int mouseX, int mouseY, List<Slot> liveSlots) {
        final int numSlots, rows;
        if (liveSlots != null) {
            numSlots = liveSlots.size();
            rows     = Math.max(1, numSlots / SLOTS_W);
        } else if (inv.inventory != null) {
            rows     = inv.inventory.rows;
            numSlots = rows * SLOTS_W;
        } else {
            rows = 3; numSlots = rows * SLOTS_W;
        }

        int ph = font.lineHeight + 5 + rows * SLOT_SIZE + 4;
        drawPanel(ctx, x, y, PAGE_W, ph);

        boolean isActive = slot.equals(activePage);
        ctx.text(font, inv.title, x + 4, y + 3, isActive ? ACTIVE_TXT : 0xFF_404040, false);

        int slotsTop = y + font.lineHeight + 5;
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < SLOTS_W; c++)
                drawSlotBg(ctx, x + 4 + c * SLOT_SIZE, slotsTop + r * SLOT_SIZE);

        if (liveSlots == null && inv.inventory == null) {
            ctx.text(font, "Not loaded", x + 4, slotsTop + 2, 0xFF_AA5500, false);
            return;
        }

        for (int i = 0; i < numSlots; i++) {
            int sx = x + 4 + (i % SLOTS_W) * SLOT_SIZE;
            int sy = slotsTop + (i / SLOTS_W) * SLOT_SIZE;
            ItemStack stack;
            if (liveSlots != null) {
                stack = liveSlots.get(i).getItem();
            } else {
                List<ItemStack> cached = inv.inventory.getStacks();
                stack = i < cached.size() ? cached.get(i) : ItemStack.EMPTY;
            }
            if (!stack.isEmpty()) {
                ctx.item(stack, sx + 1, sy + 1);
                ctx.itemDecorations(font, stack, sx + 1, sy + 1, null);
            }
        }
    }

    // ---- Bottom bar ----

    private void drawBottomBar(GuiGraphicsExtractor ctx, int mouseX, int mouseY) {
        // Background strip
        ctx.fill(barX, barY, barX + barW, barY + BAR_H, GUI_BG);
        ctx.fill(barX, barY, barX + barW, barY + 1, BORDER_DARK);
        ctx.fill(barX, barY + BAR_H - 1, barX + barW, barY + BAR_H, BORDER_LITE);

        // Edit Pages button
        boolean hoverEdit = mouseX >= editBtnX && mouseX < editBtnX + EDIT_BTN_W
                         && mouseY >= editBtnY && mouseY < editBtnY + 16;
        drawButton(ctx, editBtnX, editBtnY, EDIT_BTN_W, 16, "Edit Pages", hoverEdit);
    }

    private void drawButton(GuiGraphicsExtractor ctx, int x, int y, int w, int h, String label, boolean hovered) {
        ctx.fill(x, y, x + w, y + h, hovered ? 0xFF_A0A0A0 : 0xFF_8B8B8B);
        ctx.fill(x, y, x + w, y + 1, BORDER_LITE);
        ctx.fill(x, y + h - 1, x + w, y + h, BORDER_DARK);
        ctx.fill(x, y, x + 1, y + h, BORDER_LITE);
        ctx.fill(x + w - 1, y, x + w, y + h, BORDER_DARK);
        ctx.centeredText(font, Component.literal(label), x + w / 2, y + (h - font.lineHeight) / 2, 0xFF_404040);
    }

    // ---- Player inventory ----

    private void drawPlayerInventory(GuiGraphicsExtractor ctx, int x, int y, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Inventory inv = mc.player.getInventory();

        drawPanel(ctx, x, y, PLAYER_INV_W, PLAYER_INV_H);
        ctx.text(font, "Inventory", x + 4, y + 3, 0xFF_404040, false);

        // Clip contents to inside the panel
        ctx.enableScissor(x + 2, y + 2, x + PLAYER_INV_W - 2, y + PLAYER_INV_H - 2);

        int slotsTop = y + font.lineHeight + 5;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                int sx = x + 4 + col * SLOT_SIZE, sy = slotsTop + row * SLOT_SIZE;
                drawSlotBg(ctx, sx, sy);
                ItemStack stack = inv.getItem(9 + row * 9 + col);
                if (!stack.isEmpty()) { ctx.item(stack, sx+1, sy+1); ctx.itemDecorations(font, stack, sx+1, sy+1, null); }
            }
        }
        int hotbarY = slotsTop + 3 * SLOT_SIZE + 4;
        for (int col = 0; col < 9; col++) {
            int sx = x + 4 + col * SLOT_SIZE;
            drawSlotBg(ctx, sx, hotbarY);
            int selected = inv.getSelectedSlot();
            if (col == selected) ctx.fill(sx+1, hotbarY+1, sx+SLOT_SIZE-1, hotbarY+SLOT_SIZE-1, 0x60_FFFFFF);
            ItemStack stack = inv.getItem(col);
            if (!stack.isEmpty()) { ctx.item(stack, sx+1, hotbarY+1); ctx.itemDecorations(font, stack, sx+1, hotbarY+1, null); }
        }

        ctx.disableScissor();
    }

    // ---- MC draw helpers ----

    private void drawPanel(GuiGraphicsExtractor ctx, int x, int y, int w, int h) {
        ctx.fill(x, y, x+w, y+h, GUI_BG);
        ctx.fill(x, y, x+w, y+1, BORDER_DARK); ctx.fill(x, y+h-1, x+w, y+h, BORDER_LITE);
        ctx.fill(x, y, x+1, y+h, BORDER_DARK); ctx.fill(x+w-1, y, x+w, y+h, BORDER_LITE);
        ctx.fill(x+1, y+1, x+w-1, y+2, BORDER_LITE); ctx.fill(x+1, y+h-2, x+w-1, y+h-1, BORDER_DARK);
        ctx.fill(x+1, y+1, x+2, y+h-1, BORDER_LITE); ctx.fill(x+w-2, y+1, x+w-1, y+h-1, BORDER_DARK);
    }

    private void drawSlotBg(GuiGraphicsExtractor ctx, int x, int y) {
        ctx.fill(x, y, x+SLOT_SIZE, y+SLOT_SIZE, SLOT_BG);
        ctx.fill(x, y, x+SLOT_SIZE, y+1, BORDER_DARK); ctx.fill(x, y, x+1, y+SLOT_SIZE, BORDER_DARK);
        ctx.fill(x, y+SLOT_SIZE-1, x+SLOT_SIZE, y+SLOT_SIZE, BORDER_LITE); ctx.fill(x+SLOT_SIZE-1, y, x+SLOT_SIZE, y+SLOT_SIZE, BORDER_LITE);
    }

    private void drawScrollBar(GuiGraphicsExtractor ctx) {
        ctx.fill(scrollX, scrollY, scrollX+SCROLL_W, scrollY+scrollH, SCROLLBAR_BG);
        float max = getMaxScroll();
        if (max > 0) {
            int knobH = Math.max(20, (int)((float)scrollH*scrollH/(lastContentHeight+scrollH)));
            int knobY = scrollY + (int)(scroll/max*(scrollH-knobH));
            ctx.fill(scrollX+1, knobY+1, scrollX+SCROLL_W-1, knobY+knobH-1, SCROLL_KNOB);
            ctx.fill(scrollX+1, knobY, scrollX+SCROLL_W-1, knobY+1, BORDER_LITE);
            ctx.fill(scrollX+1, knobY+knobH-1, scrollX+SCROLL_W-1, knobY+knobH, BORDER_DARK);
        }
    }

    // ---- Page height ----

    private int pageHeight(StorageData.StorageInventory inv, List<Slot> live) {
        int rows = live != null ? Math.max(1, live.size()/SLOTS_W)
                 : inv.inventory != null ? inv.inventory.rows : 3;
        return font.lineHeight + 5 + rows * SLOT_SIZE + 4;
    }

    private int pageHeight(StorageData.StorageInventory inv) { return pageHeight(inv, null); }

    // ---- Search ----

    private boolean matchesSearch(StorageData.StorageInventory inv) {
        String q = searchField != null ? searchField.getValue().toLowerCase().trim() : "";
        if (q.isEmpty()) return true;

        // Build (or reuse) this page's searchable text once — cheap after the first time,
        // regardless of how many keystrokes the query goes through.
        List<String> index = searchIndex.computeIfAbsent(inv.slot, k -> buildSearchIndex(inv));

        for (String s : index) {
            if (s.contains(q)) return true;
        }
        return false;
    }

    // Extracts all searchable text for a page ONE TIME. This is the expensive part
    // (getTooltipLines does enchantment/attribute-modifier formatting + translation lookups),
    // so it must never run per-keystroke — only when the page is first seen or after
    // invalidateSearchCache() following a real data refresh.
    private List<String> buildSearchIndex(StorageData.StorageInventory inv) {
        List<String> index = new java.util.ArrayList<>();
        index.add(inv.title.toLowerCase());
        if (inv.inventory == null) return index;

        Minecraft mc = Minecraft.getInstance();
        for (ItemStack stack : inv.inventory.getStacks()) {
            if (stack.isEmpty()) continue;
            index.add(stack.getHoverName().getString().toLowerCase());
            try {
                // Confirmed via Forge/NeoForge javadoc for this API shape: the method is
                // ItemStack#getTooltipLines(TooltipContext, Player, TooltipFlag), and the
                // no-registries constant is Item.TooltipContext.EMPTY (not DEFAULT).
                var lines = stack.getTooltipLines(
                    net.minecraft.world.item.Item.TooltipContext.EMPTY,
                    mc.player,
                    net.minecraft.world.item.TooltipFlag.NORMAL
                );
                for (var line : lines) {
                    index.add(line.getString().toLowerCase());
                }
            } catch (Exception ignored) {}
        }
        return index;
    }

    // ---- Scroll ----

    private float getMaxScroll() { return Math.max(0, lastContentHeight - (panelH - 8)); }
    public void coerceScroll(float delta) { scroll = Math.min(getMaxScroll(), Math.max(0F, scroll + delta)); }
    private void updateScrollFromMouse(int my) { scroll=(float)(my-scrollY)/scrollH*getMaxScroll(); coerceScroll(0F); }

    // ---- Click handling ----

    private void handleClick(int mx, int my) {
        // Edit Pages button
        if (mx >= editBtnX && mx < editBtnX + EDIT_BTN_W && my >= editBtnY && my < editBtnY + 16) {
            activateEditMode(); return;
        }
        // Scrollbar
        if (mx >= scrollX && mx < scrollX+SCROLL_W && my >= scrollY && my < scrollY+scrollH) {
            knobGrabbed = true; updateScrollFromMouse(my); return;
        }
        // Page click → navigate
        layoutedForEach((rect, slot, inv) -> {
            if (rect.contains(mx, my)) { isExiting = true; slot.navigateTo(); }
        });
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hA, double vA) {
        coerceScroll((float) StorageConfig.get().adjustScrollSpeed(vA));
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        // Forward directly to the search field rather than relying on Screen's default
        // focus-based dispatch — that dispatch produced a mismatch between charTyped()
        // (routed via this screen's own focus) and keyPressed() (routed through a
        // different screen instance in the page-overlay case), which is why typing
        // worked but Backspace/Delete did not.
        if (searchField != null && searchField.keyPressed(input)) return true;
        return super.keyPressed(input);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (searchField != null && searchField.charTyped(event)) return true;
        return super.charTyped(event);
    }

    // ---- Edit Pages ----

    public void activateEditMode() {
        net.storageoverlay.StorageOverlayHandler.editMode = true;
        isExiting = true;
        Minecraft mc = Minecraft.getInstance();
        if (backingScreen != null) {
            // Same trick used everywhere else screens are switched: null the current screen
            // first so setScreen() skips calling this screen's onClose() — without this,
            // onClose() sends ServerboundContainerClosePacket for the shared syncId, and the
            // server closes the container out from under the backing screen we're switching to.
            ((net.storageoverlay.mixin.GuiMixin)(Object) mc.gui).setCurrentScreen(null);
            mc.gui.setScreen(backingScreen);
        } else {
            onClose();
        }
    }

    /** Returns true if the click was on the Edit Pages button (and handles it). */
    public boolean isEditOrSearchClick(int mx, int my) {
        if (mx >= editBtnX && mx < editBtnX + EDIT_BTN_W && my >= editBtnY && my < editBtnY + 16) {
            activateEditMode(); return true;
        }
        return false;
    }

    /** Navigate to a different page without closing the overlay. */
    public void handlePageClick(int mouseX, int mouseY, StoragePageSlot currentPage) {
        layoutedForEach((rect, slot, inv) -> {
            if (!slot.equals(currentPage) && rect.contains(mouseX, mouseY)) slot.navigateTo();
        });
    }

    // ---- Layout iterator ----

    @FunctionalInterface interface PageIterator { void accept(IntRect r, StoragePageSlot s, StorageData.StorageInventory i); }
    record IntRect(int x, int y, int w, int h) { boolean contains(int px, int py) { return px>=x&&px<x+w&&py>=y&&py<y+h; } }

    private void layoutedForEach(PageIterator fn) {
        StorageConfig cfg = StorageConfig.get();
        StorageData data = StorageDataManager.get().getData();
        int cx = panelX+4, cy = panelY+4;
        int col = 0, rowY = cy - Math.round(scroll), rowH = 0;
        for (Map.Entry<StoragePageSlot, StorageData.StorageInventory> e : data.storageInventories.entrySet()) {
            if (!matchesSearch(e.getValue())) continue;
            int ph = pageHeight(e.getValue());
            fn.accept(new IntRect(cx + col*(PAGE_W+cfg.padding), rowY, PAGE_W, ph), e.getKey(), e.getValue());
            rowH = Math.max(rowH, ph+cfg.padding);
            col++;
            if (col >= pageWidthCount) { col=0; rowY+=rowH; rowH=0; }
        }
    }

    // ---- Origin getters (for StoragePageOverlayScreen hit detection) ----

    public int[] getActivePageSlotOrigin() {
        if (activePage == null) return null;
        final int[][] result = {null};
        layoutedForEach((rect, slot, inv) -> {
            if (slot.equals(activePage))
                result[0] = new int[]{rect.x()+4, rect.y()+font.lineHeight+5};
        });
        return result[0];
    }

    public int[] getPlayerInvOrigin() {
        return new int[]{playerInvX+4, playerInvY+font.lineHeight+5};
    }

    public void invalidateSearchCache() {
        searchIndex.clear();
    }

    public EditBox getSearchField() { return searchField; }

    public void initForSize(int w, int h) { this.width = w; this.height = h; init(); }
}
