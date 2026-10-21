package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.resource.GunCombatData
import com.tacz.legacy.common.resource.TACZAttachmentModifierRegistry
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.ai.attributes.AttributeModifier
import net.minecraft.entity.ai.attributes.IAttributeInstance
import java.util.UUID

/**
 * 持枪移动速度修正（对齐原版 LivingEntitySpeedModifier）。
 *
 * 原版用两套 AttributeModifier：
 * - 重量修正（WeightModifier → MULTIPLY_BASE，兼容层 ③ C6 已接入）
 * - 移动速度修正（ExtraMovementModifier → MULTIPLY_TOTAL）
 *
 * MULTIPLY_BASE(operation=1)：最终速度 += 基础速度 × amount（amount 为负 = 减速）
 * MULTIPLY_TOTAL(operation=2)：最终速度 = 基础速度 × (1 + amount)
 */
internal object LivingEntitySpeedModifier {
    private val SPEED_MODIFIER_UUID = UUID.fromString("4D5696AE-A7C5-C59C-80E9-2A2DC8373C46")
    // 兼容层 ③ C6：weight → 移速惩罚 UUID（对齐上游 LivingEntitySpeedModifier.java:19）
    private val WEIGHT_SPEED_MODIFIER_UUID = UUID.fromString("2CB6F5AD-C6D2-9D29-4E84-0856ACD47CDB")
    private const val MODIFIER_NAME = "tacz:gun_move_speed"
    private const val WEIGHT_MODIFIER_NAME = "tacz:gun_weight_speed"

    /**
     * 每服务端 tick 调用，根据持枪状态更新 MOVEMENT_SPEED 的 AttributeModifier。
     */
    fun updateSpeedModifier(shooter: EntityLivingBase, gunData: GunCombatData?) {
        if (!shooter.isEntityAlive) return

        val speedAttr: IAttributeInstance? = shooter.getEntityAttribute(SharedMonsterAttributes.MOVEMENT_SPEED)
        if (speedAttr == null) return

        if (gunData != null) {
            val operator = IGunOperator.fromLivingEntity(shooter)
            val holder = operator.getDataHolder()

            // 兼容层 ③ C6：weight → 移速惩罚（对齐上游 LivingEntitySpeedModifier.java:36-63）
            // 枪基础重量 + 配件 weight modifier → eval → -weight × WeightSpeedMultiplier → MULTIPLY_BASE
            // 修复（审计 05）：去掉 weightMods.isNotEmpty() 门槛——上游 cache 恒含 gunData.getWeight()，
            // 即使无配件也按枪基础重量减速
            val gunStack = shooter.heldItemMainhand
            val iGun = gunStack?.item as? IGun
            if (iGun != null) {
                val gunId = iGun.getGunId(gunStack)
                val baseWeight = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId]?.data?.weight?.toDouble() ?: 0.0
                val weightMods = TACZGunPropertyResolver.collectGunNumericModifiers(gunStack, iGun, "weight_modifier")
                val totalWeight = TACZAttachmentModifierRegistry.evalNumeric(weightMods, baseWeight)
                val factor = LegacyConfigManager.server.weightSpeedMultiplier
                val weightAmount = -totalWeight * factor
                if (weightAmount != 0.0) {
                    val currentWeight = speedAttr.getModifier(WEIGHT_SPEED_MODIFIER_UUID)
                    if (currentWeight == null || currentWeight.amount != weightAmount) {
                        speedAttr.removeModifier(WEIGHT_SPEED_MODIFIER_UUID)
                        // 1.12.2 operation: 0=ADDITION(直接加amount), 1=MULTIPLY_BASE(base×amount), 2=MULTIPLY_TOTAL
                        // 上游用 MULTIPLY_BASE 语义（base × amount），1.12.2 对应 operation=1
                        // 修复（补测T12）：之前误用 operation=0（ADDITION）导致惩罚强度×10
                        speedAttr.applyModifier(AttributeModifier(WEIGHT_SPEED_MODIFIER_UUID, WEIGHT_MODIFIER_NAME, weightAmount, 1))
                    }
                } else {
                    speedAttr.removeModifier(WEIGHT_SPEED_MODIFIER_UUID)
                }
            } else {
                speedAttr.removeModifier(WEIGHT_SPEED_MODIFIER_UUID)
            }

            // 移动速度修正（原有逻辑，movement_speed base/aim/reload → MULTIPLY_TOTAL）
            var targetSpeed = when {
                holder.reloadStateType.isReloading() -> gunData.moveSpeedReload
                holder.isAiming -> gunData.moveSpeedAim
                else -> gunData.moveSpeedBase
            }
            // 兼容层 ③ C6-ms：movement_speed 配件 modifier 逐分量相加（对齐上游 ExtraMovementModifier.java:42-45）
            // 配件的 movement_speed raw json 的 base/aim/reload 值叠加到枪自身值上
            if (iGun != null) {
                val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
                // C10：movement_speed 同样要遵守 exclusive 整份替换（同文件 weight 路径已经是 exclusive-aware）
                val exclusiveMap = snapshot.guns[iGun.getGunId(gunStack)]?.data?.exclusiveAttachments ?: emptyMap()
                val attachIds = TACZGunPropertyResolver.collectAttachmentIds(gunStack, iGun)
                var dBase = 0f; var dAim = 0f; var dReload = 0f
                for (id in attachIds) {
                    val msObj = TACZGunPropertyResolver.attachmentDataFor(snapshot, id, exclusiveMap)
                        ?.raw?.get("movement_speed")
                        ?.takeIf(com.google.gson.JsonElement::isJsonObject)?.asJsonObject ?: continue
                    dBase += msObj.get("base")?.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asFloat ?: 0f
                    dAim += msObj.get("aim")?.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asFloat ?: 0f
                    dReload += msObj.get("reload")?.takeIf(com.google.gson.JsonElement::isJsonPrimitive)?.asFloat ?: 0f
                }
                targetSpeed += when {
                    holder.reloadStateType.isReloading() -> dReload
                    holder.isAiming -> dAim
                    else -> dBase
                }
                // 钳制（对齐上游 MoveSpeed.java:49-51：max(0, value)）
                targetSpeed = targetSpeed.coerceAtLeast(0f)
            }
            // 无修正时不添加 modifier
            if (targetSpeed == 0f) {
                speedAttr.removeModifier(SPEED_MODIFIER_UUID)
                return
            }
            val current = speedAttr.getModifier(SPEED_MODIFIER_UUID)
            if (current == null || current.amount != targetSpeed.toDouble()) {
                speedAttr.removeModifier(SPEED_MODIFIER_UUID)
                // operation 2 = MULTIPLY_TOTAL
                speedAttr.applyModifier(AttributeModifier(SPEED_MODIFIER_UUID, MODIFIER_NAME, targetSpeed.toDouble(), 2))
            }
        } else {
            // 不持枪：移除两套修正
            speedAttr.removeModifier(SPEED_MODIFIER_UUID)
            speedAttr.removeModifier(WEIGHT_SPEED_MODIFIER_UUID)
        }
    }
}
