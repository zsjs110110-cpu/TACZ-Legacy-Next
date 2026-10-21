package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.common.resource.TACZAttachmentModifierRegistry
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import net.minecraft.entity.EntityLivingBase

/**
 * 服务端瞄准逻辑。与上游 TACZ LivingEntityAim 行为一致。
 */
public class LivingEntityAim(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
) {
    /**
     * 切换瞄准状态。
     *
     * 对齐上游 `LivingEntityAim.aim(boolean)`：只写 `isAiming`，不做任何前置拦截。
     * X-37：旧实现在这里额外拦了「没持枪 / 切枪中 / 拉栓中」，上游三者都不拦 ——
     * 拉栓动画期间照样可以开镜，进度由 `tickAimingProgress` 按 aimTime 平滑推进。
     * `aimingTimestamp` 仍然在这里刷新，等价于上游每 tick 末尾的赋值。
     */
    public fun aim(isAiming: Boolean) {
        data.isAiming = isAiming
        data.aimingTimestamp = System.currentTimeMillis()
    }

    /**
     * 每 tick 更新瞄准进度（0.0 ~ 1.0）。
     */
    public fun tickAimingProgress() {
        val held = data.heldGun()
        if (held == null) {
            data.aimingProgress = 0f
            return
        }

        // 兼容层 ③ C1-4：配件 ads modifier 修正开镜时长（对齐上游 LocalPlayerAim.java:89-90 读缓存后的 aimTime）
        val adsModifiers = TACZGunPropertyResolver.collectGunNumericModifiers(held.stack, held.iGun, "ads")
        val aimTimeS = if (adsModifiers.isEmpty()) {
            held.gunData.aimTimeS
        } else {
            TACZAttachmentModifierRegistry.evalNumeric(adsModifiers, held.gunData.aimTimeS.toDouble()).toFloat()
        }
        val aimTimeMs = (aimTimeS * 1000).toLong()
        if (aimTimeMs <= 0) {
            data.aimingProgress = if (data.isAiming) 1f else 0f
            return
        }

        val tickDeltaMs = 50L // 50ms per tick
        val progressPerTick = tickDeltaMs.toFloat() / aimTimeMs
        if (data.isAiming) {
            data.aimingProgress = (data.aimingProgress + progressPerTick).coerceIn(0f, 1f)
        } else {
            data.aimingProgress = (data.aimingProgress - progressPerTick).coerceIn(0f, 1f)
        }
    }
}
