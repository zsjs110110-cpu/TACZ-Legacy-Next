package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.client.other.KeepingItemRenderer;
import com.tacz.legacy.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents the equip animation (hand bobbing down and up) when switching between guns.
 * Port of upstream TACZ ItemInHandRendererMixin, adapted for 1.12.2 ItemRenderer.
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin implements KeepingItemRenderer {
    @Shadow
    private float equippedProgressMainHand;
    @Shadow
    private float prevEquippedProgressMainHand;
    @Shadow
    private ItemStack itemStackMainHand;
    @Unique
    private ItemStack tacz$keepItem = ItemStack.EMPTY;
    @Unique
    private long tacz$keepTimeMs;
    @Unique
    private long tacz$keepTimestamp = -1L;

    @Inject(method = "updateEquippedItem", at = @At("TAIL"))
    private void cancelGunEquipAnimation(CallbackInfo ci) {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null) {
            return;
        }
        if (!tacz$keepItem.isEmpty()) {
            long elapsed = System.currentTimeMillis() - tacz$keepTimestamp;
            if (elapsed < tacz$keepTimeMs) {
                equippedProgressMainHand = 1.0f;
                prevEquippedProgressMainHand = 1.0f;
                itemStackMainHand = tacz$keepItem;
                return;
            }
            tacz$keepItem = ItemStack.EMPTY;
        }
        ItemStack itemStack = player.getHeldItemMainhand();
        if (itemStack.getItem() instanceof IGun) {
            equippedProgressMainHand = 1.0f;
            prevEquippedProgressMainHand = 1.0f;
            itemStackMainHand = itemStack;
        }
    }

    @Override
    public void keep(ItemStack itemStack, long timeMs) {
        if (itemStack == null || itemStack.isEmpty()) {
            return;
        }
        long elapsed = System.currentTimeMillis() - tacz$keepTimestamp;
        if (!tacz$keepItem.isEmpty() && elapsed < tacz$keepTimeMs) {
            return;
        }
        this.tacz$keepItem = itemStack.copy();
        this.tacz$keepTimeMs = Math.max(0L, timeMs);
        this.tacz$keepTimestamp = System.currentTimeMillis();
        this.itemStackMainHand = this.tacz$keepItem;
    }

    @Override
    public ItemStack getCurrentItem() {
        if (!tacz$keepItem.isEmpty()) {
            long elapsed = System.currentTimeMillis() - tacz$keepTimestamp;
            if (elapsed < tacz$keepTimeMs) {
                return tacz$keepItem;
            }
            tacz$keepItem = ItemStack.EMPTY;
        }
        return itemStackMainHand;
    }
}
