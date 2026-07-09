package net.storageoverlay.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftScreenMixin {
    // Pre-26.2: the currently-open screen is a private field directly on Minecraft (it only
    // moved to the separate Gui class in 26.2). The Mojang-mapped name for this field is
    // "screen" (not Yarn's old "currentScreen") — Fabric's own custom-screens doc still shows
    // "currentScreen" but that appears to be stale/unmigrated wording, since it doesn't compile
    // against the real 26.1.2 mappings. Confirm against a genSources output if this is wrong.
    @Accessor("screen")
    void setCurrentScreen(Screen screen);

    @Accessor("screen")
    Screen getCurrentScreen();
}
