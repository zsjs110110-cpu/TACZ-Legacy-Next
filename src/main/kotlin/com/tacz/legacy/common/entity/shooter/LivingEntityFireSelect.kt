package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.item.gun.FireMode
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageGunFireSelect
import net.minecraft.entity.EntityLivingBase

/**
 * 服务端射击模式切换逻辑。与上游 TACZ LivingEntityFireSelect 行为一致。
 */
public class LivingEntityFireSelect(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
) {
    /**
     * 循环切换射击模式。
     */
    public fun fireSelect() {
        val held = data.heldGun() ?: return
        val currentGunItem = held.stack
        val iGun = held.iGun
        val gunData = held.gunData

        val fireModes = gunData.fireModesSet
        if (fireModes.isEmpty()) return

        val currentMode = iGun.getFireMode(currentGunItem)
        val currentIndex = fireModes.indexOfFirst { it.equals(currentMode.name, ignoreCase = true) }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % fireModes.size
        val nextModeName = fireModes[nextIndex]
        // 无法识别的模式名直接跳过，避免把枪切到无法射击的 UNKNOWN 模式
        val nextMode = runCatching { FireMode.valueOf(nextModeName.uppercase()) }.getOrNull() ?: return
        iGun.setFireMode(currentGunItem, nextMode)
        if (!shooter.world.isRemote && currentMode != nextMode) {
            TACZNetworkHandler.sendToTrackingEntity(
                ServerMessageGunFireSelect(shooter.entityId, currentGunItem, nextMode),
                shooter,
            )
        }
    }
}
