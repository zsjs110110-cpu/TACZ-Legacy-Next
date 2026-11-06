package com.tacz.legacy.client.compat.playeranimator.animation;

import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 常驻的第三人称旋转修正。
 *
 * <p>上游把修正挂在 rotation 层的 {@code empty} clip 上：clip 结束 → 层失活 → 修正消失。
 * 1.12.2 下 {@code empty} 没有 {@code animation_length}，GeckoLib 解析成
 * {@code endTick = stopTick = 1}，每个 tick 都会 stop 一次，于是：
 * <ul>
 *   <li>clip 死掉的那几帧，走廊层失活，{@code -yaw} 修正整帧丢失；</li>
 *   <li>重启后 {@code AdjustmentModifier} 的 fadeIn/fadeOut 又按 clip 进度再算一遍。</li>
 * </ul>
 * 两者叠加的结果是「整个人」的朝向每个 tick 被拉回身体朝向一次（幅度等于头身夹角，
 * 转动视角时可达 75°），表现为高频抽动。
 *
 * <p>这里让修正层常驻（不依赖 clip 存活），并把淡入淡出固定为 1。
 */
public class RotationAdjustmentModifier extends AdjustmentModifier {

    /** 每个玩家一个实例：工厂建一次，状态机开关也改这一个。 */
    private static final Map<EntityPlayer, RotationAdjustmentModifier> INSTANCES = new WeakHashMap<>();

    public RotationAdjustmentModifier(AdjustmentYRotModifier source) {
        super(source);
    }

    public static RotationAdjustmentModifier get(EntityPlayer player) {
        RotationAdjustmentModifier modifier = INSTANCES.get(player);
        if (modifier == null) {
            modifier = new RotationAdjustmentModifier(new AdjustmentYRotModifier(player));
            INSTANCES.put(player, modifier);
        }
        return modifier;
    }

    /**
     * 只有当前确实在播放 playerAnimator 持枪动画时才启用；
     * 空手 / 无 player_animator_3rd 的枪仍然沿用原版第三人称朝向。
     */
    public static void setEnabled(EntityPlayer player, boolean enabled) {
        get(player).enabled = enabled;
    }

    @Override
    public boolean isActive() {
        return enabled;
    }

    @Override
    protected float getFadeIn(float delta) {
        return 1f;
    }

    @Override
    protected float getFadeOut(float delta) {
        return 1f;
    }
}
