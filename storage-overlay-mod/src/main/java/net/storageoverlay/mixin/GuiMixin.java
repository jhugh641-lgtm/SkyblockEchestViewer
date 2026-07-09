package net.storageoverlay.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Confirmed by decompiling the real 26.2 client jar: the "current screen" state moved off
 * Minecraft entirely and onto the new Gui class (Minecraft.gui is now a public field of type
 * Gui, and Gui holds its own private "screen" field plus public screen()/setScreen() methods).
 * This mixin targets Gui directly (not Minecraft) to null the field out without going through
 * setScreen()'s normal onClose()-triggering path.
 */
@Mixin(Gui.class)
public interface GuiMixin {
    @Accessor("screen")
    void setCurrentScreen(Screen screen);
}
