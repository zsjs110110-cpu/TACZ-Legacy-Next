package com.tacz.legacy.client.compat.playeranimator.animation;

import com.tacz.legacy.api.entity.IGunOperator;
import com.tacz.legacy.api.event.GunDrawEvent;
import com.tacz.legacy.api.event.GunMeleeEvent;
import com.tacz.legacy.api.event.GunReloadEvent;
import com.tacz.legacy.api.event.GunShootEvent;
import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.TACZLegacy;
import com.tacz.legacy.client.compat.playeranimator.AnimationName;
import com.tacz.legacy.client.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.legacy.client.resource.GunDisplayInstance;
import com.tacz.legacy.client.resource.TACZClientAssetManager;
import com.tacz.legacy.common.entity.shooter.ShooterDataHolder;
import com.tacz.legacy.common.resource.TACZGunPackPresentation;
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 第三人称动画状态机（上游 AnimationManager 的 1.12.2 版）。
 *
 * <p>降级口径：只做类型替换（AbstractClientPlayer→同类型、Pose→isSneaking/isCrawling、
 * Mth→1.12.2 MathHelper 等价实现、Java 17 模式匹配/switch 表达式→Java 8 写法），
 * 状态机分支与 clip 选择顺序保持不变。
 */
public class AnimationManager {

    public static boolean hasPlayerAnimator3rd(GunDisplayInstance display) {
        ResourceLocation location = display.getPlayerAnimator3rd();
        if (location == null) {
            return false;
        }
        boolean loaded = PlayerAnimatorAssetManager.get().containsKey(location);
        if (loaded && LOGGED_DISPLAYS.add(location)) {
            TACZLegacy.logger.info("[PlayerAnimator] Using 3rd-person clips {} for this display", location);
        }
        return loaded;
    }

    private static final java.util.Set<ResourceLocation> LOGGED_DISPLAYS = java.util.Collections.newSetFromMap(new java.util.HashMap<>());

    public static boolean isFlying(AbstractClientPlayer player) {
        return !player.onGround && player.capabilities.isFlying;
    }

    public static void playLowerAnimation(AbstractClientPlayer player, GunDisplayInstance display, float limbSwingAmount) {
        // 如果玩家趴下，不播放下半身动画
        if (isPlayerLie(player)) {
            return;
        }
        // 如果玩家骑乘
        if (player.getRidingEntity() != null) {
            playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.RIDE_LOWER);
            return;
        }
        // 如果玩家在天上，下半身动画就是站立动画
        if (isFlying(player)) {
            playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.HOLD_LOWER);
            return;
        }
        if (player.isSprinting()) {
            if (player.isSneaking()) {
                playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.CROUCH_WALK_LOWER);
            } else {
                playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.RUN_LOWER);
            }
            return;
        }
        if (limbSwingAmount > 0.05) {
            if (player.isSneaking()) {
                playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.CROUCH_WALK_LOWER);
            } else {
                playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.WALK_LOWER);
            }
            return;
        }
        if (player.isSneaking()) {
            playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.CROUCH_LOWER);
        } else {
            playLoopAnimation(player, display, PlayerAnimatorCompat.LOWER_ANIMATION, AnimationName.HOLD_LOWER);
        }
    }

    public static void playLoopUpperAnimation(AbstractClientPlayer player, GunDisplayInstance display, float limbSwingAmount) {
        IGunOperator operator = IGunOperator.fromLivingEntity(player);
        float aimingProgress = operator.getSynAimingProgress();
        if (aimingProgress <= 0) {
            // 疾跑时播放的动画
            if (!isFlying(player) && player.isSprinting()) {
                if (isPlayerLie(player)) {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.LIE_MOVE);
                } else if (player.isSneaking()) {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.CROUCH_WALK_UPPER);
                } else {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.RUN_UPPER);
                }
                return;
            }

            // 行走时的动画
            if (!isFlying(player) && limbSwingAmount > 0.05) {
                if (isPlayerLie(player)) {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.LIE_MOVE);
                } else if (player.isSneaking()) {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.CROUCH_WALK_UPPER);
                } else {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.WALK_UPPER);
                }
                return;
            }

            if (isPlayerLie(player)) {
                // 趴下时的动画
                playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.LIE);
            } else {
                // 普通待命
                playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.HOLD_UPPER);
            }
        } else {
            if (isPlayerLie(player)) {
                // 趴下时瞄准
                if (!isFlying(player) && limbSwingAmount > 0.05) {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.LIE_MOVE);
                } else {
                    playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.LIE_AIM);
                }
            } else {
                // 普通瞄准
                playLoopAnimation(player, display, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, AnimationName.AIM_UPPER);
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static void playLoopAnimation(AbstractClientPlayer player, GunDisplayInstance display, ResourceLocation dataId, String animationName) {
        ResourceLocation animator3rd = display.getPlayerAnimator3rd();
        if (animator3rd == null) {
            return;
        }
        if (!PlayerAnimatorAssetManager.get().containsKey(animator3rd)) {
            return;
        }
        PlayerAnimatorAssetManager.get().getAnimations(animator3rd, animationName).ifPresent(keyframeAnimation -> {
            PlayerAnimationAccess.PlayerAssociatedAnimationData associatedData = PlayerAnimationAccess.getPlayerAssociatedData(player);
            ModifierLayer<IAnimation> modifierLayer = (ModifierLayer<IAnimation>) associatedData.get(dataId);
            if (modifierLayer == null) {
                return;
            }
            if (modifierLayer.getAnimation() instanceof KeyframeAnimationPlayer) {
                KeyframeAnimationPlayer animationPlayer = (KeyframeAnimationPlayer) modifierLayer.getAnimation();
                if (animationPlayer.isActive()) {
                    Object extraDataName = animationPlayer.getData().extraData.get("name");
                    if (extraDataName instanceof String && !animationName.equals(extraDataName)) {
                        AbstractFadeModifier fadeModifier = AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE);
                        modifierLayer.replaceAnimationWithFade(fadeModifier, new KeyframeAnimationPlayer(keyframeAnimation));
                    }
                    return;
                }
            }
            AbstractFadeModifier fadeModifier = AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE);
            modifierLayer.replaceAnimationWithFade(fadeModifier, new KeyframeAnimationPlayer(keyframeAnimation));
        });
    }

    @SuppressWarnings("unchecked")
    public static void playOnceAnimation(AbstractClientPlayer player, GunDisplayInstance display, ResourceLocation dataId, String animationName) {
        ResourceLocation animator3rd = display.getPlayerAnimator3rd();
        if (animator3rd == null) {
            return;
        }
        if (!PlayerAnimatorAssetManager.get().containsKey(animator3rd)) {
            return;
        }
        PlayerAnimatorAssetManager.get().getAnimations(animator3rd, animationName).ifPresent(keyframeAnimation -> {
            PlayerAnimationAccess.PlayerAssociatedAnimationData associatedData = PlayerAnimationAccess.getPlayerAssociatedData(player);
            ModifierLayer<IAnimation> modifierLayer = (ModifierLayer<IAnimation>) associatedData.get(dataId);
            if (modifierLayer == null) {
                return;
            }
            IAnimation animation = modifierLayer.getAnimation();
            if (animation == null || !animation.isActive()) {
                AbstractFadeModifier fadeModifier = AbstractFadeModifier.standardFadeIn(8, Ease.INOUTSINE);
                modifierLayer.replaceAnimationWithFade(fadeModifier, new KeyframeAnimationPlayer(keyframeAnimation));
            }
        });
    }

    public static void stopAllAnimation(AbstractClientPlayer player) {
        stopAllAnimation(player, 8);
    }

    public static void stopAllAnimation(AbstractClientPlayer player, int fadeTime) {
        stopAnimation(player, PlayerAnimatorCompat.LOWER_ANIMATION, fadeTime);
        stopAnimation(player, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, fadeTime);
        stopAnimation(player, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, fadeTime);
        stopAnimation(player, PlayerAnimatorCompat.ROTATION_ANIMATION, fadeTime);
    }

    @SuppressWarnings("unchecked")
    private static void stopAnimation(AbstractClientPlayer player, ResourceLocation dataId, int fadeTime) {
        PlayerAnimationAccess.PlayerAssociatedAnimationData associatedData = PlayerAnimationAccess.getPlayerAssociatedData(player);
        ModifierLayer<IAnimation> modifierLayer = (ModifierLayer<IAnimation>) associatedData.get(dataId);
        if (modifierLayer != null && modifierLayer.isActive()) {
            AbstractFadeModifier fadeModifier = AbstractFadeModifier.standardFadeIn(fadeTime, Ease.INOUTSINE);
            modifierLayer.replaceAnimationWithFade(fadeModifier, null);
        }
    }

    /**
     * 1.12.2 没有 Pose.SWIMMING 的 TACZ 趴下语义，趴下状态由 ShooterDataHolder.isCrawling 表达。
     */
    private static boolean isPlayerLie(AbstractClientPlayer player) {
        ShooterDataHolder dataHolder = IGunOperator.fromLivingEntity(player).getDataHolder();
        return dataHolder != null && dataHolder.isCrawling;
    }

    /**
     * 枪械 id → display id → 已加载的 display 实例（与 InnerThirdPersonManager 的解析保持一致）。
     */
    private static GunDisplayInstance resolveDisplay(ItemStack stack) {
        IGun iGun = IGun.getIGunOrNull(stack);
        if (iGun == null) {
            return null;
        }
        ResourceLocation gunId = iGun.getGunId(stack);
        if (gunId == null) {
            return null;
        }
        ResourceLocation displayId = TACZGunPackPresentation.INSTANCE.resolveGunDisplayId(
                TACZGunPackRuntimeRegistry.currentSnapshotForJava(), gunId);
        if (displayId == null) {
            return null;
        }
        return TACZClientAssetManager.INSTANCE.getGunDisplayInstance(displayId);
    }

    private static boolean isLocalPlayer(AbstractClientPlayer player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft.player != null && minecraft.player == player;
    }

    private static boolean isFirstPerson() {
        return Minecraft.getMinecraft().gameSettings.thirdPersonView == 0;
    }

    @SubscribeEvent
    public void onFire(GunShootEvent event) {
        if (event.getLogicalSide().isServer()) {
            return;
        }
        EntityLivingBase shooter = event.getShooter();
        if (!(shooter instanceof AbstractClientPlayer)) {
            return;
        }
        AbstractClientPlayer player = (AbstractClientPlayer) shooter;
        if (isLocalPlayer(player) && isFirstPerson()) return;
        ItemStack gunItemStack = event.getGunItemStack();
        IGun iGun = IGun.getIGunOrNull(gunItemStack);
        if (iGun == null) {
            return;
        }
        GunDisplayInstance display = resolveDisplay(gunItemStack);
        if (display == null) {
            return;
        }
        IGunOperator operator = IGunOperator.fromLivingEntity(player);
        float aimingProgress = operator.getSynAimingProgress();
        if (aimingProgress <= 0) {
            if (isPlayerLie(player)) {
                playOnceAnimation(player, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, AnimationName.LIE_NORMAL_FIRE);
            } else {
                playOnceAnimation(player, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, AnimationName.NORMAL_FIRE_UPPER);
            }
        } else {
            if (isPlayerLie(player)) {
                playOnceAnimation(player, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, AnimationName.LIE_AIM_FIRE);
            } else {
                playOnceAnimation(player, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, AnimationName.AIM_FIRE_UPPER);
            }
        }
    }

    @SubscribeEvent
    public void onReload(GunReloadEvent event) {
        if (event.getLogicalSide().isServer()) {
            return;
        }
        EntityLivingBase shooter = event.getEntity();
        if (!(shooter instanceof AbstractClientPlayer)) {
            return;
        }
        AbstractClientPlayer player = (AbstractClientPlayer) shooter;
        if (isLocalPlayer(player) && isFirstPerson()) return;
        ItemStack gunItemStack = event.getGunItemStack();
        IGun iGun = IGun.getIGunOrNull(gunItemStack);
        if (iGun == null) {
            return;
        }
        GunDisplayInstance display = resolveDisplay(gunItemStack);
        if (display == null) {
            return;
        }
        if (isPlayerLie(player)) {
            playOnceAnimation(player, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, AnimationName.LIE_RELOAD);
        } else {
            playOnceAnimation(player, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, AnimationName.RELOAD_UPPER);
        }
    }

    @SubscribeEvent
    public void onMelee(GunMeleeEvent event) {
        if (event.getLogicalSide().isServer()) {
            return;
        }
        EntityLivingBase shooter = event.getShooter();
        if (!(shooter instanceof AbstractClientPlayer)) {
            return;
        }
        AbstractClientPlayer player = (AbstractClientPlayer) shooter;
        if (isLocalPlayer(player) && isFirstPerson()) return;
        ItemStack gunItemStack = event.getGunItemStack();
        IGun iGun = IGun.getIGunOrNull(gunItemStack);
        if (iGun == null) {
            return;
        }
        GunDisplayInstance display = resolveDisplay(gunItemStack);
        if (display == null) {
            return;
        }
        int randomIndex = shooter.getRNG().nextInt(3);
        String animationName;
        switch (randomIndex) {
            case 0:
                animationName = AnimationName.MELEE_UPPER;
                break;
            case 1:
                animationName = AnimationName.MELEE_2_UPPER;
                break;
            default:
                animationName = AnimationName.MELEE_3_UPPER;
                break;
        }
        playOnceAnimation(player, display, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, animationName);
    }

    @SubscribeEvent
    public void onDraw(GunDrawEvent event) {
        if (event.getLogicalSide().isServer()) {
            return;
        }
        EntityLivingBase entity = event.getEntity();
        if (!(entity instanceof AbstractClientPlayer)) {
            return;
        }
        AbstractClientPlayer player = (AbstractClientPlayer) entity;
        if (isLocalPlayer(player) && isFirstPerson()) return;
        ItemStack currentGunItem = event.getCurrentGunItem();
        ItemStack previousGunItem = event.getPreviousGunItem();
        // 在切枪时，重置上半身动画
        if (currentGunItem.getItem() instanceof IGun && previousGunItem.getItem() instanceof IGun) {
            stopAnimation(player, PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, 8);
            stopAnimation(player, PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, 8);
            stopAnimation(player, PlayerAnimatorCompat.LOWER_ANIMATION, 8);
        }
    }
}
