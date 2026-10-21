package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.entity.EntityKineticBullet
import com.tacz.legacy.common.resource.GunCombatData
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.jse.CoerceJavaToLua

internal object TACZBulletSpreadResolver {
    // 散布量化日志开关（-Dtacz.spreadDebug=true），object 加载时读一次缓存（Qoder 审查建议）
    private val spreadDebug = "true".equals(System.getProperty("tacz.spreadDebug"))

    internal fun resolveBulletAmount(gunItem: net.minecraft.item.ItemStack, iGun: IGun, gunData: GunCombatData): Int {
        return TACZGunPropertyResolver.resolveBulletAmount(gunItem, iGun, gunData)
    }

    internal fun applySpread(
        shooter: net.minecraft.entity.EntityLivingBase,
        dataHolder: ShooterDataHolder,
        gunItem: net.minecraft.item.ItemStack,
        iGun: IGun,
        gunData: GunCombatData,
        bullet: EntityKineticBullet,
        bulletIndex: Int,
        processedSpeed: Float,
        pitch: Float,
        yaw: Float,
        scriptApi: TACZGunScriptAPI? = null,
    ) {
        val inaccuracy = TACZGunPropertyResolver.resolveInaccuracy(shooter, gunItem, iGun, gunData)
        // C 档补诊断：散布量化日志，受 -Dtacz.spreadDebug=true 控制
        if (spreadDebug) {
            println("[tacz-spread] inaccuracy=$inaccuracy bulletIndex=$bulletIndex shooter=${shooter.name}")
        }
        if (applyScriptSpread(scriptApi, shooter, dataHolder, gunItem, gunData, bullet, bulletIndex, processedSpeed, inaccuracy, pitch, yaw)) {
            return
        }
        bullet.shootFromRotation(shooter, pitch, yaw, processedSpeed, inaccuracy)
    }

    private fun applyScriptSpread(
        existingApi: TACZGunScriptAPI?,
        shooter: net.minecraft.entity.EntityLivingBase,
        dataHolder: ShooterDataHolder,
        gunItem: net.minecraft.item.ItemStack,
        gunData: GunCombatData,
        bullet: EntityKineticBullet,
        bulletIndex: Int,
        processedSpeed: Float,
        inaccuracy: Float,
        pitch: Float,
        yaw: Float,
    ): Boolean {
        val function = GunScriptHooks.find(gunData, "calcSpread") ?: return false
        val api = existingApi ?: TACZGunScriptAPI.create(shooter, dataHolder, gunItem)
        // 脚本出错时返回 false，由调用方回退到默认散布
        return GunScriptHooks.run(gunData, "calcSpread", { false }) {
            val luaValue = function.call(
                CoerceJavaToLua.coerce(api),
                LuaValue.valueOf(bulletIndex),
                LuaValue.valueOf(inaccuracy.toDouble()),
            )
            if (!luaValue.istable()) {
                return@run false
            }
            val table = luaValue.checktable()
            val spreadX = table.get(1).checkdouble()
            val spreadY = table.get(2).checkdouble()
            bullet.shootFromRotation(shooter, pitch, yaw, processedSpeed, spreadX, spreadY)
            true
        }
    }
}