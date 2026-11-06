package com.tacz.legacy.client.animation.third;

import com.tacz.legacy.api.client.other.IThirdPersonAnimation;
import com.tacz.legacy.api.client.other.ThirdPersonManager;
import com.tacz.legacy.api.entity.IGunOperator;
import com.tacz.legacy.api.item.IGun;
import com.tacz.legacy.client.compat.playeranimator.PlayerAnimatorCompat;
import com.tacz.legacy.client.resource.GunDisplayInstance;
import com.tacz.legacy.client.resource.TACZClientAssetManager;
import com.tacz.legacy.common.resource.TACZGunPackPresentation;
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

/**
 * 第三人称持枪姿势的分发点。
 *
 * <p>移植自上游 {@code com.tacz.guns.client.animation.third.InnerThirdPersonManager}：
 * 有 {@code player_animator_3rd} 且 playerAnimator 库在场时交给动画状态机，否则回落到
 * "直接摆原版骨骼姿势"；两条路径在同一次调用内互斥。
 *
 * <p>调用方是 {@code ModelBipedGunPoseMixin}，注入在 {@code ModelBiped.setRotationAngles} 的 TAIL：
 * 原版先把手臂摆成正常姿势，这里再覆盖成持枪姿势。
 */
public final class InnerThirdPersonManager {

    private InnerThirdPersonManager() {
    }

    /**
     * 在主手持有枪械时改写双臂（以及机枪姿势下的躯干）的旋转角。
     *
     * @param entityIn 正在被渲染的实体
     * @param rightArm 右手模型
     * @param leftArm  左手模型
     * @param body     躯干模型
     * @param head     头部模型
     */
    public static void setRotationAnglesHead(EntityLivingBase entityIn, ModelRenderer rightArm,
                                             ModelRenderer leftArm, ModelRenderer body, ModelRenderer head,
                                             float limbSwingAmount) {
        // 暂停时不算，否则标题界面的预览实体会一直重算（上游同款保护）
        // 1.12.2 的 Minecraft.isGamePaused 字段是 private，公开访问器是同名方法。
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null || minecraft.isGamePaused()) {
            return;
        }

        ItemStack mainHandItem = entityIn.getHeldItemMainhand();
        IGun iGun = IGun.getIGunOrNull(mainHandItem);
        if (iGun == null) {
            PlayerAnimatorCompat.stopAllAnimation(entityIn);
            return;
        }

        // 睡觉、爬梯、游泳、鞘翅飞行时不摆枪姿（与上游一致）
        if (entityIn.isPlayerSleeping() || entityIn.isOnLadder() || entityIn.isInWater()
                || entityIn.isElytraFlying()) {
            PlayerAnimatorCompat.stopAllAnimation(entityIn);
            return;
        }

        GunDisplayInstance display = resolveDisplay(iGun, mainHandItem);
        if (display == null) {
            PlayerAnimatorCompat.stopRotationAdjustment(entityIn);
            return;
        }

        // playerAnimator 在场且该枪声明了 player_animator_3rd：交给动画状态机，
        // 与静态姿势互斥（不依赖两个 TAIL mixin 的先后顺序）。
        if (PlayerAnimatorCompat.hasPlayerAnimator3rd(entityIn, display)) {
            PlayerAnimatorCompat.playAnimation(entityIn, display, limbSwingAmount);
            return;
        }

        // 没有 player_animator_3rd：走静态姿势，同时关掉动画层的朝向修正。
        PlayerAnimatorCompat.stopRotationAdjustment(entityIn);

        IThirdPersonAnimation animation = ThirdPersonManager.getAnimation(display.getThirdPersonAnimation());
        float aimingProgress = IGunOperator.fromLivingEntity(entityIn).getSynAimingProgress();
        if (aimingProgress <= 0) {
            animation.animateGunHold(entityIn, rightArm, leftArm, body, head);
        } else {
            animation.animateGunAim(entityIn, rightArm, leftArm, body, head, aimingProgress);
        }
    }

    /**
     * 枪械 id → display id → 已加载的 display 实例。
     * display 没加载出来时（枪包被换成不认识的、或者资源还在建）直接放弃，不要抛异常 ——
     * 这里是渲染热路径，每帧都会走。
     */
    private static GunDisplayInstance resolveDisplay(IGun iGun, ItemStack stack) {
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
}
