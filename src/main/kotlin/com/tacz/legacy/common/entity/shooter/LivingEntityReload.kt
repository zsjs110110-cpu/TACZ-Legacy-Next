package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.entity.ReloadState
import com.tacz.legacy.api.event.GunReloadEvent
import com.tacz.legacy.common.application.refit.LegacyGunRefitRuntime
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageReload
import com.tacz.legacy.common.resource.BoltType
import com.tacz.legacy.common.resource.GunCombatData
import net.minecraft.entity.EntityLivingBase
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.relauncher.Side
import org.luaj.vm2.lib.jse.CoerceJavaToLua


/**
 * 服务端换弹逻辑。与上游 TACZ LivingEntityReload 行为一致。
 * 支持脚本 hook：start_reload / tick_reload / interrupt_reload。
 */
public class LivingEntityReload(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
    private val draw: LivingEntityDrawGun,
) {
    /**
     * 发起换弹操作。
     */
    public fun reload() {
        val held = data.heldGun() ?: return
        val currentGunItem = held.stack
        val iGun = held.iGun
        val gunData = held.gunData

        // 已经在换弹中
        if (data.reloadStateType.isReloading()) return
        // 还没有完成切枪
        if (draw.getDrawCoolDown() != 0L) return
        // 在拉栓
        if (data.isBolting) return

        val currentAmmo = iGun.getCurrentAmmoCount(currentGunItem)
        val hasBulletInBarrel = iGun.hasBulletInBarrel(currentGunItem)

        // 满弹判定
        val maxAmmo = LegacyGunRefitRuntime.computeAmmoCapacity(currentGunItem)
        val isBarrelFull = hasBulletInBarrel || gunData.boltType == BoltType.OPEN_BOLT
        if (currentAmmo >= maxAmmo && isBarrelFull) return

        val logicalSide = if (shooter.world.isRemote) Side.CLIENT else Side.SERVER
        val reloadEvent = GunReloadEvent(shooter, currentGunItem, logicalSide)
        if (MinecraftForge.EVENT_BUS.post(reloadEvent)) return

        // 弹药来源检查
        val needCheck = !gunData.isReloadInfinite && IGunOperator.fromLivingEntity(shooter).needCheckAmmo()
        if (needCheck && !iGun.hasInventoryAmmo(shooter, currentGunItem, needCheck)) return

        if (!shooter.world.isRemote) {
            TACZNetworkHandler.sendToTrackingEntity(ServerMessageReload(shooter.entityId, currentGunItem), shooter)
        }

        // 判定空仓换弹 vs 战术换弹
        val ammoCount = currentAmmo + if (hasBulletInBarrel && gunData.boltType != BoltType.OPEN_BOLT) 1 else 0
        if (ammoCount <= 0) {
            data.reloadStateType = ReloadState.StateType.EMPTY_RELOAD_FEEDING
        } else {
            data.reloadStateType = ReloadState.StateType.TACTICAL_RELOAD_FEEDING
        }
        data.reloadTimestamp = System.currentTimeMillis()

        // 脚本 hook: start_reload（脚本出错时按默认逻辑允许换弹）
        val startFunc = GunScriptHooks.find(gunData, "start_reload")
        if (startFunc != null) {
            val api = TACZGunScriptAPI.create(shooter, data, currentGunItem)
            val shouldProceed = GunScriptHooks.run(gunData, "start_reload", { true }) {
                startFunc.call(CoerceJavaToLua.coerce(api)).checkboolean()
            }
            if (!shouldProceed) {
                data.reloadStateType = ReloadState.StateType.NOT_RELOADING
                data.reloadTimestamp = -1L
            }
        }
        // 无脚本时，默认允许换弹（startReload 等价 return true）
    }

    /**
     * 服务端 tick：检查换弹阶段转移和完成。
     * 如果脚本定义了 tick_reload，用脚本返回的 (stateType, countDown) 推进状态机。
     * 否则用默认 timer 逻辑。
     */
    public fun tickReload() {
        if (data.reloadTimestamp == -1L) return
        val held = data.heldGun() ?: return
        val gunData = held.gunData
        val api = TACZGunScriptAPI.create(shooter, data, held.stack)

        val tickFunc = GunScriptHooks.find(gunData, "tick_reload")
        val result: ReloadState = if (tickFunc != null) {
            GunScriptHooks.run(gunData, "tick_reload", { defaultTickReload(api, gunData) }) {
                val varargs = tickFunc.invoke(CoerceJavaToLua.coerce(api))
                val typeOrdinal = varargs.arg(1).checkint()
                val countDown = varargs.arg(2).checklong()
                // 脚本返回非法的状态序号时视为结束换弹，而不是抛出越界异常
                val stateType = ReloadState.StateType.values().getOrElse(typeOrdinal) { ReloadState.StateType.NOT_RELOADING }
                ReloadState(stateType, countDown)
            }
        } else {
            defaultTickReload(api, gunData)
        }

        data.reloadStateType = result.stateType
        if (!result.stateType.isReloading()) {
            data.reloadTimestamp = -1L
        }
    }

    /**
     * 取消换弹。
     */
    public fun cancelReload(): Boolean {
        if (!data.reloadStateType.isReloading()) return false
        if (data.currentGunItem == null) return false

        // 脚本 hook: interrupt_reload
        val held = data.heldGun()
        if (held != null) {
            val interruptFunc = GunScriptHooks.find(held.gunData, "interrupt_reload")
            if (interruptFunc != null) {
                val api = TACZGunScriptAPI.create(shooter, data, held.stack)
                GunScriptHooks.run(held.gunData, "interrupt_reload", {}) {
                    interruptFunc.call(CoerceJavaToLua.coerce(api))
                }
            }
        }

        data.reloadStateType = ReloadState.StateType.NOT_RELOADING
        data.reloadTimestamp = -1L
        return true
    }

    public fun getReloadState(): ReloadState {
        val countDown = if (data.reloadTimestamp > 0) {
            data.reloadTimestamp + getExpectedReloadLength() - System.currentTimeMillis()
        } else {
            ReloadState.NOT_RELOADING_COUNTDOWN
        }
        return ReloadState(data.reloadStateType, countDown.coerceAtLeast(ReloadState.NOT_RELOADING_COUNTDOWN))
    }

    /**
     * 换弹总时长。与 [defaultTickReload] 一致：cooldown（finishing）时间是从换弹开始计算的累计时间，
     * 而不是 feeding 之后的额外时间，因此总时长取两者较大值（原实现将两者相加，倒计时偏长）。
     */
    private fun getExpectedReloadLength(): Long {
        val gunData = data.heldGun()?.gunData ?: return 0L
        val seconds = when (data.reloadStateType) {
            ReloadState.StateType.EMPTY_RELOAD_FEEDING,
            ReloadState.StateType.EMPTY_RELOAD_FINISHING ->
                maxOf(gunData.emptyReloadFeedingTimeS, gunData.emptyReloadFinishingTimeS)
            ReloadState.StateType.TACTICAL_RELOAD_FEEDING,
            ReloadState.StateType.TACTICAL_RELOAD_FINISHING ->
                maxOf(gunData.reloadFeedingTimeS, gunData.reloadFinishingTimeS)
            else -> 0f
        }
        return (seconds * 1000).toLong()
    }

    // =====================================================================
    // 默认换弹逻辑（无脚本时使用）— 与上游 defaultTickReload 对齐
    // =====================================================================

    private fun defaultTickReload(api: TACZGunScriptAPI, gunData: GunCombatData): ReloadState {
        val oldStateType = ReloadState.StateType.values()[api.getReloadStateType()]
        val progressTime = api.getReloadTime()
        var stateType: ReloadState.StateType
        var countDown: Long

        if (oldStateType.isReloadingEmpty()) {
            val feedTime = (gunData.emptyReloadFeedingTimeS * 1000).toLong()
            val finishingTime = (gunData.emptyReloadFinishingTimeS * 1000).toLong()
            if (progressTime < feedTime) {
                stateType = ReloadState.StateType.EMPTY_RELOAD_FEEDING
                countDown = feedTime - progressTime
            } else if (progressTime < finishingTime) {
                stateType = ReloadState.StateType.EMPTY_RELOAD_FINISHING
                countDown = finishingTime - progressTime
            } else {
                stateType = ReloadState.StateType.NOT_RELOADING
                countDown = ReloadState.NOT_RELOADING_COUNTDOWN
            }
        } else if (oldStateType.isReloadingTactical()) {
            val feedTime = (gunData.reloadFeedingTimeS * 1000).toLong()
            val finishingTime = (gunData.reloadFinishingTimeS * 1000).toLong()
            if (progressTime < feedTime) {
                stateType = ReloadState.StateType.TACTICAL_RELOAD_FEEDING
                countDown = feedTime - progressTime
            } else if (progressTime < finishingTime) {
                stateType = ReloadState.StateType.TACTICAL_RELOAD_FINISHING
                countDown = finishingTime - progressTime
            } else {
                stateType = ReloadState.StateType.NOT_RELOADING
                countDown = ReloadState.NOT_RELOADING_COUNTDOWN
            }
        } else {
            stateType = ReloadState.StateType.NOT_RELOADING
            countDown = ReloadState.NOT_RELOADING_COUNTDOWN
        }

        // feeding -> finishing 阶段转换时执行补弹
        if (oldStateType == ReloadState.StateType.EMPTY_RELOAD_FEEDING && oldStateType != stateType) {
            defaultReloadFinishing(api, gunData, isTactical = false)
        }
        if (oldStateType == ReloadState.StateType.TACTICAL_RELOAD_FEEDING && oldStateType != stateType) {
            defaultReloadFinishing(api, gunData, isTactical = true)
        }

        return ReloadState(stateType, countDown)
    }

    /**
     * 默认补弹逻辑 — 与上游 defaultReloadFinishing 对齐。
     * 在 feeding → finishing 转换时调用：消耗背包弹药并注入弹匣。
     */
    private fun defaultReloadFinishing(api: TACZGunScriptAPI, gunData: GunCombatData, isTactical: Boolean) {
        val needAmmoCount = api.getNeededAmmoAmount()
        // 无限装填不消耗背包弹药，直接补满；仅需要消耗且非无限时才从背包扣除
        val needConsumeAmmo = !gunData.isReloadInfinite && api.isReloadingNeedConsumeAmmo()

        // 根据弹药类型补弹（上游 MAGAZINE / FUEL 两种路径，ModernKineticGunItem#defaultReloadFinishing）：
        // - magazine：按需要量逐发消耗背包弹药，消耗多少补多少；
        // - fuel：只消耗 1 个供弹物品，成功消耗（返回 1）就把弹匣补满（needAmmoCount * consumed）。
        // manual/inventory 保持原有 magazine 行为（inventory 枪用 useInventoryAmmo 直读，不依赖本分支）。
        if (gunData.reloadType.equals("fuel", ignoreCase = true)) {
            if (needConsumeAmmo) {
                val consumed = api.consumeAmmoFromPlayer(1)
                api.putAmmoInMagazine(needAmmoCount * consumed)
            } else {
                api.putAmmoInMagazine(needAmmoCount)
            }
        } else {
            if (needConsumeAmmo) {
                val consumed = api.consumeAmmoFromPlayer(needAmmoCount)
                api.putAmmoInMagazine(consumed)
            } else {
                api.putAmmoInMagazine(needAmmoCount)
            }
        }

        // 非战术换弹：从弹匣取 1 颗放入枪膛
        val boltType = gunData.boltType
        if (!isTactical && (boltType == BoltType.MANUAL_ACTION || boltType == BoltType.CLOSED_BOLT)) {
            val removed = api.removeAmmoFromMagazine(1)
            if (removed != 0) {
                api.setAmmoInBarrel(true)
            }
        }
    }
}
