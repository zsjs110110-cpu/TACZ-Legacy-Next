package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.resource.GunCombatData
import com.tacz.legacy.common.resource.GunDataAccessor
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaFunction
import java.util.concurrent.ConcurrentHashMap

/**
 * 射手当前手持枪械的解析结果。
 * 用于替代各 LivingEntity* 子系统中重复的 “supplier → stack → IGun → gunData” 样板代码。
 */
internal class HeldGun(
    val stack: ItemStack,
    val iGun: IGun,
    val gunId: ResourceLocation,
    val gunData: GunCombatData,
)

/** 解析当前 draw 的枪械；未持枪、物品不是枪或枪械数据不存在时返回 null。 */
internal fun ShooterDataHolder.heldGun(): HeldGun? {
    val stack = currentGunItem?.get() ?: return null
    val iGun = stack.item as? IGun ?: return null
    val gunId = iGun.getGunId(stack)
    val gunData = GunDataAccessor.getGunData(gunId) ?: return null
    return HeldGun(stack, iGun, gunId, gunData)
}

/**
 * 枪包数据脚本 hook 的安全调用入口。
 *
 * 脚本由第三方枪包提供，脚本中的错误（LuaError、类型错误等）若直接抛出，会沿着实体 tick
 * 传播并导致服务端崩溃。这里捕获异常、每个 (脚本, hook) 只记录一次日志，并回退到默认逻辑。
 */
internal object GunScriptHooks {
    private val reportedFailures: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 查找脚本中的 hook 函数；脚本不存在、未定义或定义错误时返回 null。 */
    fun find(gunData: GunCombatData, hookName: String): LuaFunction? {
        return try {
            val script = TACZGunScriptAPI.resolveScript(gunData) ?: return null
            TACZGunScriptAPI.checkFunction(script, hookName)
        } catch (e: LuaError) {
            report(gunData, hookName, e)
            null
        }
    }

    /** 执行 hook；出错时记录日志并返回 [fallback] 的结果。 */
    inline fun <T> run(gunData: GunCombatData, hookName: String, fallback: () -> T, block: () -> T): T {
        return try {
            block()
        } catch (e: LuaError) {
            report(gunData, hookName, e)
            fallback()
        } catch (e: RuntimeException) {
            report(gunData, hookName, e)
            fallback()
        }
    }

    fun report(gunData: GunCombatData, hookName: String, error: Throwable) {
        val key = "${gunData.scriptId}#$hookName"
        if (reportedFailures.add(key)) {
            TACZLegacy.logger.error("Gun data script {} failed in hook '{}', falling back to default logic", gunData.scriptId, hookName, error)
        }
    }
}
