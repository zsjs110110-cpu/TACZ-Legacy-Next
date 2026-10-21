package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.item.IGun;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "clickMouse", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunClickMouse(CallbackInfo ci) {
        if (tacz$shouldSuppressGunLeftClick()) {
            ci.cancel();
        }
    }

    @Inject(method = "sendClickBlockToController", at = @At("HEAD"), cancellable = true)
    private void tacz$cancelGunBlockDamage(boolean leftClick, CallbackInfo ci) {
        if (!leftClick || !tacz$shouldSuppressGunLeftClick()) {
            return;
        }
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.playerController != null) {
            minecraft.playerController.resetBlockRemoving();
        }
        ci.cancel();
    }

    @Unique
    private boolean tacz$shouldSuppressGunLeftClick() {
        Minecraft minecraft = (Minecraft) (Object) this;
        EntityPlayerSP player = minecraft.player;
        if (player == null || minecraft.currentScreen != null || !minecraft.inGameHasFocus) {
            return false;
        }
        ItemStack stack = player.getHeldItemMainhand();
        return !stack.isEmpty() && stack.getItem() instanceof IGun;
    }
}