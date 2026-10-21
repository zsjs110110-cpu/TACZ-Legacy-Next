package com.tacz.legacy.mixin.minecraft;

import com.tacz.legacy.common.item.AmmoBoxInventoryInteractions;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Container.class)
public abstract class ContainerMixin {
    @Inject(method = "slotClick", at = @At("HEAD"), cancellable = true)
    private void tacz$handleAmmoBoxSlotClick(
            int slotId,
            int dragType,
            ClickType clickType,
            EntityPlayer player,
            CallbackInfoReturnable<ItemStack> cir
    ) {
        if (AmmoBoxInventoryInteractions.tryHandleSlotClick((Container) (Object) this, slotId, dragType, clickType, player)) {
            cir.setReturnValue(player.inventory.getItemStack());
        }
    }
}