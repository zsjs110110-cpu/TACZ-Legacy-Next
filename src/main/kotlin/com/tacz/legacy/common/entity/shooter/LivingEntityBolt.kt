package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.common.resource.BoltType
import com.tacz.legacy.common.resource.GunCombatData
import net.minecraft.entity.EntityLivingBase
import org.luaj.vm2.lib.jse.CoerceJavaToLua

/**
 * 服务端拉栓逻辑。与上游 TACZ LivingEntityBolt 行为一致。
 * 支持脚本 hook：start_bolt / tick_bolt。
 */
public class LivingEntityBolt(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
    private val draw: LivingEntityDrawGun,
    private val shoot: LivingEntityShoot,
) {
    /**
     * 执行拉栓操作。
     *
     * 前置判断的顺序与上游 `LivingEntityBolt.bolt()` 逐条对齐：
     * 射击冷却 → 正在换弹 → 正在切枪 → 已在拉栓 → bolt 类型 → 膛内已有弹 → 无弹可上。
     * 其中「射击冷却」是 X-09 补上的：上游 `:31` 判 `shoot.getShootCoolDown() != 0`
     * 就返回，否则连发枪刚打完还能立刻再拉一次栓，把膛内弹和弹匣计数打乱。
     */
    public fun bolt() {
        val held = data.heldGun() ?: return
        val currentGunItem = held.stack
        val iGun = held.iGun
        val gunData = held.gunData

        // 正在射击冷却
        if (shoot.getShootCoolDown() != 0L) return
        // 正在换弹
        if (data.reloadStateType.isReloading()) return
        // 正在切枪
        if (draw.getDrawCoolDown() != 0L) return
        // 已在拉栓
        if (data.isBolting) return

        // 开膛/闭膛枪不需要手动拉栓
        if (gunData.boltType != BoltType.MANUAL_ACTION) return
        // 已有膛内弹
        if (iGun.hasBulletInBarrel(currentGunItem)) return
        // 没弹药可上：背包直读枪查背包，弹匣枪查弹匣（对齐上游 noAmmo 的两分支）
        val useInventoryAmmo = iGun.useInventoryAmmo(currentGunItem)
        val noAmmo = if (useInventoryAmmo) {
            !iGun.hasInventoryAmmo(shooter, currentGunItem, IGunOperator.fromLivingEntity(shooter).needCheckAmmo())
        } else {
            iGun.getCurrentAmmoCount(currentGunItem) < 1
        }
        if (noAmmo) return

        data.boltTimestamp = System.currentTimeMillis()

        // 脚本 hook: start_bolt → 返回 boolean（是否开始拉栓）
        val startFunc = GunScriptHooks.find(gunData, "start_bolt")
        if (startFunc != null) {
            val api = TACZGunScriptAPI.create(shooter, data, currentGunItem)
            data.isBolting = GunScriptHooks.run(gunData, "start_bolt", { true }) {
                startFunc.call(CoerceJavaToLua.coerce(api)).checkboolean()
            }
        } else {
            data.isBolting = true
        }
    }

    /**
     * 每 tick 检查拉栓是否完成。
     */
    public fun tickBolt() {
        if (!data.isBolting) return
        val held = data.heldGun() ?: run { data.isBolting = false; return }
        val gunData = held.gunData
        val api = TACZGunScriptAPI.create(shooter, data, held.stack)

        val tickFunc = GunScriptHooks.find(gunData, "tick_bolt")
        data.isBolting = if (tickFunc != null) {
            GunScriptHooks.run(gunData, "tick_bolt", { defaultTickBolt(api, gunData) }) {
                tickFunc.call(CoerceJavaToLua.coerce(api)).checkboolean()
            }
        } else {
            defaultTickBolt(api, gunData)
        }
    }

    /**
     * 默认拉栓逻辑 — 与上游 defaultTickBolt 对齐。
     */
    private fun defaultTickBolt(api: TACZGunScriptAPI, gunData: GunCombatData): Boolean {
        val boltActionTime = (gunData.boltTimeS * 1000).toLong()
        val rawFeedTime = gunData.boltFeedTimeS
        val boltFeedTime = if (rawFeedTime < 0) boltActionTime else (rawFeedTime * 1000).toLong()

        if (api.getBoltTime() < boltFeedTime) {
            return true
        }

        // feed 时间已到：如果膛内无弹，从弹匣/背包取 1 颗上膛
        if (!api.hasAmmoInBarrel()) {
            if (api.useInventoryAmmo()) {
                if (api.consumeAmmoFromPlayer(1) == 1) {
                    api.setAmmoInBarrel(true)
                }
            } else if (api.removeAmmoFromMagazine(1) != 0) {
                api.setAmmoInBarrel(true)
            }
        }

        return api.getBoltTime() < boltActionTime
    }
}
