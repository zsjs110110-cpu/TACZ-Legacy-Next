package com.tacz.legacy.client.compat.playeranimator.animation;

import com.tacz.legacy.client.compat.playeranimator.PlayerAnimatorCompat;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;

/**
 * 注册 TACZ 使用的四个动画层，优先级与上游一致（93/94/95/96）。
 */
public class AnimationDataRegisterFactory {
    public static void registerData() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(PlayerAnimatorCompat.LOWER_ANIMATION, 93,
                player -> new ModifierLayer<IAnimation>());
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(PlayerAnimatorCompat.LOOP_UPPER_ANIMATION, 94,
                player -> new ModifierLayer<IAnimation>());
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(PlayerAnimatorCompat.ONCE_UPPER_ANIMATION, 95,
                player -> new ModifierLayer<IAnimation>());
        // 旋转修正常驻：不再依赖 empty clip 的存活，避免每 tick 丢一帧修正导致全身抽动。
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(PlayerAnimatorCompat.ROTATION_ANIMATION, 96,
                player -> new ModifierLayer<IAnimation>(null, RotationAdjustmentModifier.get(player)));
    }
}
