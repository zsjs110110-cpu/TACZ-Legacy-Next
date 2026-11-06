package com.tacz.legacy.client.compat.playeranimator;

import com.tacz.legacy.TACZLegacy;
import com.tacz.legacy.client.compat.playeranimator.animation.AnimationDataRegisterFactory;
import com.tacz.legacy.client.compat.playeranimator.animation.AnimationManager;
import com.tacz.legacy.client.compat.playeranimator.animation.PlayerAnimatorAssetManager;
import com.tacz.legacy.client.compat.playeranimator.animation.PlayerAnimatorLoader;
import com.tacz.legacy.client.compat.playeranimator.animation.RotationAdjustmentModifier;
import com.tacz.legacy.client.resource.GunDisplayInstance;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;

import java.io.File;

/**
 * playerAnimator 兼容层入口。
 *
 * <p>1.12.2 的 playerAnimator（{@code dev.kosmx.playerAnim}）是纯客户端库，
 * 本类把 TACZ 上游的 7 个兼容文件机械降级（类型替换 + Java 8 语法）后接回 Legacy：
 * <ul>
 *   <li>每帧根据 display 的 {@code player_animator_3rd} 选择 clip，驱动四个动画层；</li>
 *   <li>枪包加载时把 {@code assets/<ns>/player_animator/*.json} 灌进资产索引；</li>
 *   <li>库不在场时所有入口都短路，Legacy 继续走原来的静态持枪姿势。</li>
 * </ul>
 */
public class PlayerAnimatorCompat {
    public static final ResourceLocation LOWER_ANIMATION = new ResourceLocation(TACZLegacy.MOD_ID, "lower_animation");
    public static final ResourceLocation LOOP_UPPER_ANIMATION = new ResourceLocation(TACZLegacy.MOD_ID, "loop_upper_animation");
    public static final ResourceLocation ONCE_UPPER_ANIMATION = new ResourceLocation(TACZLegacy.MOD_ID, "once_upper_animation");
    public static final ResourceLocation ROTATION_ANIMATION = new ResourceLocation(TACZLegacy.MOD_ID, "rotation");

    private static final String MOD_ID = "playeranimator";
    private static boolean INSTALLED = false;

    public static void init() {
        INSTALLED = Loader.isModLoaded(MOD_ID);
        if (INSTALLED) {
            AnimationDataRegisterFactory.registerData();
            MinecraftForge.EVENT_BUS.register(new AnimationManager());
            TACZLegacy.logger.info("[PlayerAnimator] Compatibility layer enabled");
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    /**
     * 扫描一个枪包（目录或 zip），把里面的 player_animator 动画注册进 [PlayerAnimatorAssetManager]。
     */
    public static void reloadPackAssets(File packFile) {
        if (!INSTALLED || packFile == null || !packFile.exists()) {
            return;
        }
        try {
            if (packFile.isDirectory()) {
                PlayerAnimatorLoader.load(packFile);
            } else if (packFile.getName().toLowerCase().endsWith(".zip")) {
                PlayerAnimatorLoader.loadZip(packFile);
            }
        } catch (Exception exception) {
            TACZLegacy.logger.warn("[PlayerAnimator] Failed to scan pack {} for animations", packFile.getName(), exception);
        }
    }

    public static void clearAllAnimationCache() {
        if (INSTALLED) {
            PlayerAnimatorAssetManager.get().clearAll();
        }
    }

    public static boolean hasPlayerAnimator3rd(net.minecraft.entity.EntityLivingBase livingEntity, GunDisplayInstance display) {
        if (INSTALLED && livingEntity instanceof net.minecraft.client.entity.AbstractClientPlayer) {
            return AnimationManager.hasPlayerAnimator3rd(display);
        }
        return false;
    }

    public static void stopAllAnimation(EntityLivingBase livingEntity) {
        if (INSTALLED && livingEntity instanceof net.minecraft.client.entity.AbstractClientPlayer) {
            net.minecraft.client.entity.AbstractClientPlayer player = (net.minecraft.client.entity.AbstractClientPlayer) livingEntity;
            RotationAdjustmentModifier.setEnabled(player, false);
            AnimationManager.stopAllAnimation(player);
        }
    }

    public static void stopAllAnimation(EntityLivingBase livingEntity, int fadeTime) {
        if (INSTALLED && livingEntity instanceof net.minecraft.client.entity.AbstractClientPlayer) {
            net.minecraft.client.entity.AbstractClientPlayer player = (net.minecraft.client.entity.AbstractClientPlayer) livingEntity;
            RotationAdjustmentModifier.setEnabled(player, false);
            AnimationManager.stopAllAnimation(player, fadeTime);
        }
    }

    /** 该枪没有 {@code player_animator_3rd}：回到原版第三人称朝向。 */
    public static void stopRotationAdjustment(EntityLivingBase livingEntity) {
        if (INSTALLED && livingEntity instanceof net.minecraft.client.entity.AbstractClientPlayer) {
            RotationAdjustmentModifier.setEnabled(
                    (net.minecraft.client.entity.AbstractClientPlayer) livingEntity, false);
        }
    }

    public static void playAnimation(EntityLivingBase livingEntity, GunDisplayInstance display, float limbSwingAmount) {
        if (INSTALLED && livingEntity instanceof net.minecraft.client.entity.AbstractClientPlayer) {
            net.minecraft.client.entity.AbstractClientPlayer player = (net.minecraft.client.entity.AbstractClientPlayer) livingEntity;
            RotationAdjustmentModifier.setEnabled(player, true);
            AnimationManager.playLowerAnimation(player, display, limbSwingAmount);
            AnimationManager.playLoopUpperAnimation(player, display, limbSwingAmount);
        }
    }
}
