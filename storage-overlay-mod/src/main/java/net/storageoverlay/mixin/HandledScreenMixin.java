package net.storageoverlay.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(AbstractContainerScreen.class)
public interface HandledScreenMixin {
    // "onMouseClick" was Yarn's name; confirmed via the migration primer that the real method
    // is "slotClicked" and ClickType was renamed straight across to ContainerInput (same
    // constants: PICKUP, QUICK_MOVE, etc.) — the parameter order below should be accurate.
    @Invoker("slotClicked")
    void invokeOnMouseClick(Slot slot, int slotId, int button, ContainerInput actionType);

    /**
     * Allows updating the internal handler so the container id stays current.
     * Confirmed via decompiling the real 26.2 jar: the field is "menu" — but it's declared
     * `protected final T menu`, so @Mutable is required to write to it outside the constructor
     * (this was the cause of the runtime IllegalAccessError: "Update to non-static final field").
     */
    @Mutable
    @Accessor("menu")
    void setScreenHandler(AbstractContainerMenu handler);
}
