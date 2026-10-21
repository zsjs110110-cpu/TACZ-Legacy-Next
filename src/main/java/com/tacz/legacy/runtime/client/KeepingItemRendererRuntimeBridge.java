package com.tacz.legacy.runtime.client;

import com.tacz.legacy.api.client.other.KeepingItemRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class KeepingItemRendererRuntimeBridge {
    private static final KeepingItemRenderer RENDERER = new FallbackKeepingItemRenderer();

    private KeepingItemRendererRuntimeBridge() {
    }

    public static KeepingItemRenderer getRenderer() {
        return RENDERER;
    }

    private static final class FallbackKeepingItemRenderer implements KeepingItemRenderer {
        private ItemStack keepItem = ItemStack.EMPTY;
        private long keepTimeMs;
        private long keepTimestamp = -1L;

        @Override
        public void keep(ItemStack itemStack, long timeMs) {
            this.keepItem = itemStack.copy();
            this.keepTimeMs = Math.max(0L, timeMs);
            this.keepTimestamp = System.currentTimeMillis();
        }

        @Override
        public ItemStack getCurrentItem() {
            if (!this.keepItem.isEmpty()) {
                long elapsed = System.currentTimeMillis() - this.keepTimestamp;
                if (elapsed < this.keepTimeMs) {
                    return this.keepItem;
                }
                this.keepItem = ItemStack.EMPTY;
            }
            if (Minecraft.getMinecraft().player == null) {
                return ItemStack.EMPTY;
            }
            return Minecraft.getMinecraft().player.getHeldItemMainhand();
        }
    }
}