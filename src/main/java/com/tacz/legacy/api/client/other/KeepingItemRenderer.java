package com.tacz.legacy.api.client.other;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;

/**
 * 1.12 bridge for upstream TACZ KeepingItemRenderer semantics.
 * Allows first-person rendering to temporarily keep the previous main-hand
 * item visible while its put-away animation finishes.
 */
public interface KeepingItemRenderer {
    void keep(ItemStack itemStack, long timeMs);

    ItemStack getCurrentItem();

    static KeepingItemRenderer getRenderer() {
        ItemRenderer itemRenderer = Minecraft.getMinecraft().getItemRenderer();
        return itemRenderer instanceof KeepingItemRenderer ? (KeepingItemRenderer) itemRenderer : null;
    }
}
