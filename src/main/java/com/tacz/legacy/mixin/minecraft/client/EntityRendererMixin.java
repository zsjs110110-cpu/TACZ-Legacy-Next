package com.tacz.legacy.mixin.minecraft.client;

import com.tacz.legacy.api.client.other.KeepingItemRenderer;
import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.client.event.FirstPersonFovHooks;
import com.tacz.legacy.client.renderer.other.GunHurtBobTweak;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Matches upstream TACZ item-in-hand bob suppression for first-person gun rendering.
 *
 * Vanilla 1.12 applies hand bobbing inside EntityRenderer#renderHand before ItemRenderer.
 * Legacy's custom gun renderer caches muzzle offsets from that hand render, while tracer
 * reconstruction only replays camera yaw/pitch. Skipping the vanilla bob on gun hand renders
 * keeps the cached muzzle offset in the same camera space expected by the tracer renderer.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Shadow
    private void applyBobbing(float partialTicks) {
    }

    @Redirect(
            method = "renderHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;applyBobbing(F)V",
                    ordinal = 0
            )
    )
    private void tacz$skipGunItemHandBobbing(EntityRenderer instance, float partialTicks) {
        if (!tacz$shouldSkipHandBobbing()) {
            applyBobbing(partialTicks);
        }
    }

    /**
     * renderHand 里 applyBobbing 被调用两次（手部之前一次、renderOverlays 之后一次）。
     * 只替换 ordinal 0 会让叠层那一次继续晃，枪口缓存偏移与相机就错开了。
     */
    @Redirect(
            method = "renderHand",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/EntityRenderer;applyBobbing(F)V",
                    ordinal = 1
            )
    )
    private void tacz$skipGunItemOverlayBobbing(EntityRenderer instance, float partialTicks) {
        if (!tacz$shouldSkipHandBobbing()) {
            applyBobbing(partialTicks);
        }
    }

    @Inject(method = "getFOVModifier", at = @At("RETURN"), cancellable = true)
    private void tacz$applyGunScopeFov(float partialTicks, boolean useFOVSetting, CallbackInfoReturnable<Float> cir) {
        float resolved = FirstPersonFovHooks.applyFovModifier(cir.getReturnValue(), partialTicks, useFOVSetting);
        cir.setReturnValue(resolved);
    }

    /**
     * 抑制原版的受伤晃动（L1/D-01），随后由 CameraSetup 事件按枪包倍率重新叠加。
     *
     * 1.12.2 里读取 hurtTime 做晃动的只有 hurtCameraEffect，它被
     * setupCameraTransform 与 renderHand（renderItemInFirstPerson 之前、
     * renderOverlays 之后各一次）三处调用；只拦其中一处会漏掉手部与叠层晃动。
     * 在方法入口 cancel 一次覆盖三处，且不改动 hurtTime 本身
     * （HUD 闪红、模型受伤变色都不受影响）。
     */
    @Inject(method = "hurtCameraEffect", at = @At("HEAD"), cancellable = true)
    private void tacz$suppressVanillaHurtBob(float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        // 原版的门是 `getRenderViewEntity() instanceof EntityLivingBase`，不是 `== player`：
        // 旁观 / 自由视角模组会把相机挂到别的活体上，那种情况下补偿不会叠加
        // （TACZCameraRecoilHandler 只在 event.entity == player 时加 roll），
        // 只 suppress 不补偿就把那个实体连原版晃动也删了。
        if (mc.getRenderViewEntity() != mc.player) {
            return;
        }
        EntityPlayerSP player = mc.player;
        if (player == null || player.hurtTime <= 0 || player.getHealth() <= 0.0F) {
            return;
        }
        // 只有"被枪击中"的受伤才由本模组接管；摔落、近战等保持原版晃动
        if (GunHurtBobTweak.isActive()) {
            ci.cancel();
        }
    }

    private boolean tacz$shouldSkipHandBobbing() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.player;
        if (player == null || mc.gameSettings.thirdPersonView != 0) {
            return false;
        }
        ItemStack renderedMainItem = KeepingItemRenderer.getRenderer() != null
                ? KeepingItemRenderer.getRenderer().getCurrentItem()
                : player.getHeldItemMainhand();
        return renderedMainItem.getItem() instanceof IGun;
    }
}