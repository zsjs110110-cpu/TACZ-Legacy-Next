package com.tacz.legacy.common.entity.shooter

import com.tacz.legacy.api.event.GunMeleeEvent
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageMelee
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.GunMeleeCombatData
import com.tacz.legacy.common.resource.MeleeEffectCombatData
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.entity.ai.attributes.AttributeModifier
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.potion.Potion
import net.minecraft.potion.PotionEffect
import net.minecraft.util.DamageSource
import net.minecraft.util.math.Vec3d
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.relauncher.Side
import java.util.UUID

/**
 * 服务端近战逻辑。与上游 TACZ LivingEntityMelee 行为一致。
 */
public class LivingEntityMelee(
    private val shooter: EntityLivingBase,
    private val data: ShooterDataHolder,
    private val draw: LivingEntityDrawGun,
) {
    /**
     * 一次近战使用的解析结果。
     * 对齐上游三级优先：muzzle（枪口/刺刀） → stock（枪托） → 枪械 melee.default。
     */
    private class ResolvedMeleeData(
        /** 枪本体近战距离（上游 doMelee 的 gunDistance） */
        val gunDistance: Float,
        /** 配件/默认数据的附加距离，与 gunDistance 相加得到总攻击距离 */
        val distance: Float,
        val rangeAngle: Float,
        val knockback: Float,
        val damage: Float,
        val prepTime: Float,
        /** 附加冷却，与枪械 melee.cooldown 相加 */
        val extraCooldown: Float,
        val effects: List<MeleeEffectCombatData>,
    )

    /**
     * 发起近战攻击。
     */
    public fun melee() {
        if (shooter.world.isRemote) return
        val currentGunItem = data.currentGunItem?.get() ?: return

        // 对齐上游拦截顺序：切枪 → 拉栓 → 冷却（冷却判断用 != 0）
        if (draw.getDrawCoolDown() != 0L) return
        if (data.isBolting) return
        if (getMeleeCoolDown() != 0L) return

        // 发布服务端 Forge 事件
        val meleeEvent = GunMeleeEvent(shooter, currentGunItem, Side.SERVER)
        if (MinecraftForge.EVENT_BUS.post(meleeEvent)) return

        // 广播近战开始（对齐上游：先发包，再记录时间戳）
        TACZNetworkHandler.sendToTrackingEntity(ServerMessageMelee(shooter.entityId, currentGunItem), shooter)

        // 只有持枪且枪械数据可用时才进入前摇
        val held = data.heldGun() ?: return
        data.meleeTimestamp = System.currentTimeMillis()

        // 对齐上游 LivingEntityMelee#melee：
        // meleePrepTickCount = (int) Math.max(0, prepTime * 20)
        // 即使 prep <= 0 也会推迟到下一个 tick 执行（由 tickMelee 处理）。
        // 上游在 defaultMeleeData == null 时只发事件/发包，不进入前摇
        val prepTime = resolveMeleeData(held)?.prepTime ?: return
        data.meleePrepTickCount = (prepTime * 20f).toInt().coerceAtLeast(0)
    }

    /**
     * 每 tick 检查近战 prep 阶段是否需要执行伤害。
     * 对齐上游 scheduleTickMelee：>0 递减；==0 当 tick 执行并置 -1；<0 空闲。
     */
    public fun tickMelee() {
        if (data.meleePrepTickCount > 0) {
            data.meleePrepTickCount--
            return
        }
        if (data.meleePrepTickCount == 0) {
            data.meleePrepTickCount = -1
            // 执行时重新解析当前枪械/配件，避免切枪后沿用旧数据
            val held = data.heldGun() ?: return
            executeMelee(held)
        }
    }

    /**
     * 当前近战剩余冷却（毫秒）。
     * 对齐上游：muzzle → stock → default 取附加冷却，与枪械 cooldown 相加后再减 5 ms 窗口。
     */
    public fun getMeleeCoolDown(): Long {
        val currentGunItem = data.currentGunItem?.get() ?: return 0
        val iGun = currentGunItem.item as? IGun ?: return 0
        val gunData = GunDataAccessor.getGunData(iGun.getGunId(currentGunItem)) ?: return -1

        val meleeData = gunData.meleeData ?: GunMeleeCombatData()
        val muzzleData = GunDataAccessor.getAttachmentMeleeData(
            currentGunItem, iGun.getAttachmentId(currentGunItem, AttachmentType.MUZZLE)
        )
        // 仅当枪口没有近战数据时才检查枪托，保持与三级优先一致的取值
        val stockData = if (muzzleData == null) {
            GunDataAccessor.getAttachmentMeleeData(
                currentGunItem, iGun.getAttachmentId(currentGunItem, AttachmentType.STOCK)
            )
        } else {
            null
        }
        val extraCooldown = muzzleData?.cooldown ?: stockData?.cooldown
            ?: meleeData.defaultMeleeData?.cooldown ?: 0f

        val totalCooldownTime = meleeData.cooldown + extraCooldown
        var coolDown = (totalCooldownTime * 1000f).toLong() - (System.currentTimeMillis() - data.meleeTimestamp)
        // 给 5 ms 的窗口时间，以平衡延迟；减完后保证下限为 0
        coolDown -= 5L
        return if (coolDown < 0L) 0L else coolDown
    }

    /**
     * 按 muzzle → stock → default 的顺序解析本次近战数据。
     * 上游 `melee` 缺省时用 new GunMeleeData()，但 `default` 本身是 @Nullable；
     * 三层都没有时返回 null，调用方跳过伤害结算（事件/发包/冷却仍已执行）。
     */
    private fun resolveMeleeData(held: HeldGun): ResolvedMeleeData? {
        val gunMelee = held.gunData.meleeData ?: GunMeleeCombatData()

        // 一级：枪口（刺刀）
        val muzzleData = GunDataAccessor.getAttachmentMeleeData(
            held.stack, held.iGun.getAttachmentId(held.stack, AttachmentType.MUZZLE)
        )
        if (muzzleData != null) {
            return ResolvedMeleeData(
                gunDistance = gunMelee.distance,
                distance = muzzleData.distance,
                rangeAngle = muzzleData.rangeAngle,
                knockback = muzzleData.knockback,
                damage = muzzleData.damage,
                prepTime = muzzleData.prepTime,
                extraCooldown = muzzleData.cooldown,
                effects = muzzleData.effects,
            )
        }

        // 二级：枪托
        val stockData = GunDataAccessor.getAttachmentMeleeData(
            held.stack, held.iGun.getAttachmentId(held.stack, AttachmentType.STOCK)
        )
        if (stockData != null) {
            return ResolvedMeleeData(
                gunDistance = gunMelee.distance,
                distance = stockData.distance,
                rangeAngle = stockData.rangeAngle,
                knockback = stockData.knockback,
                damage = stockData.damage,
                prepTime = stockData.prepTime,
                extraCooldown = stockData.cooldown,
                effects = stockData.effects,
            )
        }

        // 三级：枪械默认数据（上游 defaultMeleeData 可空；缺省时不结算伤害）
        val defaultData = gunMelee.defaultMeleeData ?: return null
        return ResolvedMeleeData(
            gunDistance = gunMelee.distance,
            distance = defaultData.distance,
            rangeAngle = defaultData.rangeAngle,
            knockback = defaultData.knockback,
            damage = defaultData.damage,
            prepTime = defaultData.prepTime,
            extraCooldown = defaultData.cooldown,
            effects = emptyList(),
        )
    }

    private fun executeMelee(held: HeldGun) {
        val mData = resolveMeleeData(held) ?: return
        // 枪长 + 刺刀长 = 总攻击距离
        val distance = mData.gunDistance + mData.distance

        // 视角方向向量，缩放至总距离
        val eyeVec = shooter.lookVec.normalize().scale(distance.toDouble())
        // 眼睛位置
        val eyePos = Vec3d(shooter.posX, shooter.posY + shooter.eyeHeight, shooter.posZ)
        // 球心在玩家眼睛后方 distance 处（锥形判定原点）
        val centrePos = eyePos.subtract(eyeVec)

        // 以玩家 boundingBox 向外扩展 distance 搜索候选实体
        val candidates = shooter.world.getEntitiesWithinAABBExcludingEntity(
            shooter, shooter.entityBoundingBox.grow(distance.toDouble())
        )

        // 近战诊断日志（-Dtacz.spreadDebug=true 复用同一开关）
        if (System.getProperty("tacz.spreadDebug") == "true") {
            println(
                "[tacz-melee] candidates=${candidates.size} eyeVec=(${eyeVec.x},${eyeVec.y},${eyeVec.z}) " +
                    "eye=(${eyePos.x},${eyePos.y},${eyePos.z}) centre=(${centrePos.x},${centrePos.y},${centrePos.z}) " +
                    "totalDist=$distance gunDist=${mData.gunDistance} meleeDist=${mData.distance} " +
                    "rangeAngle=${mData.rangeAngle} damage=${mData.damage} prep=${mData.prepTime} " +
                    "effects=${mData.effects.size}",
            )
        }

        // 伤害源：玩家 → playerDamage，非玩家（NPC/怪物） → mobDamage
        val damageSource = if (shooter is EntityPlayer) {
            DamageSource.causePlayerDamage(shooter)
        } else {
            DamageSource.causeMobDamage(shooter)
        }

        // 对齐上游：伤害走 ATTACK_DAMAGE 属性（先 setBaseValue(0)，再加 ADDITION 修饰符），
        // 力量/虚弱等属性修饰符会参与最终伤害计算。
        // 与上游 Suppliers.memoize 一致：命中第一个目标时才计算，且整次近战只算一次。
        val resolvedDamage = lazy(LazyThreadSafetyMode.NONE) { resolveAttackDamage(mData.damage) }

        // 击退方向只依赖射手朝向，循环外算一次
        val knockbackX = Math.sin(Math.toRadians(shooter.rotationYaw.toDouble()))
        val knockbackZ = -Math.cos(Math.toRadians(shooter.rotationYaw.toDouble()))

        for (entity in candidates) {
            if (entity !is EntityLivingBase) continue
            if (entity === shooter) continue

            // 球心 → 目标眼睛的向量
            val targetVec = Vec3d(
                entity.posX, entity.posY + entity.eyeHeight, entity.posZ
            ).subtract(centrePos)
            // 目标到球心的距离
            val targetLength = targetVec.length()
            // 距离小于总距离 → 在玩家背后，跳过
            if (targetLength < distance) continue

            // 计算 eyeVec 与 targetVec 的夹角（度）
            val degree = Math.toDegrees(
                Math.acos(
                    (targetVec.dotProduct(eyeVec) / (targetLength * distance)).coerceIn(-1.0, 1.0)
                )
            )

            if (System.getProperty("tacz.spreadDebug") == "true") {
                println("[tacz-melee] candidate=${entity.name} targetLength=$targetLength degree=$degree")
            }

            // 夹角在半角范围内，且视线无阻挡 → 造成伤害并击退
            if (degree < mData.rangeAngle / 2.0 && shooter.canEntityBeSeen(entity)) {
                // 对齐上游顺序：先击退
                entity.knockBack(shooter, mData.knockback, knockbackX, knockbackZ)
                // 后伤害
                entity.attackEntityFrom(damageSource, resolvedDamage.value)
                // 对齐上游 doEnchantDamageEffects（1.12.2 无该公开方法，使用等价的附魔后处理）
                EnchantmentHelper.applyThornEnchantments(entity, shooter)
                EnchantmentHelper.applyArthropodEnchantments(shooter, entity)
                // 目标已死亡：短路 effects
                if (!entity.isEntityAlive) continue
                applyMeleeEffects(entity, mData.effects)
            }
        }

        // 玩家扣饱食度
        if (shooter is EntityPlayer) {
            shooter.addExhaustion(0.1f)
        }
    }

    /**
     * 通过 ATTACK_DAMAGE 属性计算实际伤害（对齐上游 doMelee 的 realDamage 逻辑）。
     * 临时把基础值设为 0 并挂一个 ADDITION 修饰符，读取 getValue() 后还原，
     * 这样力量（MULTIPLY_TOTAL）、虚弱（ADDITION）等属性修饰符都会生效。
     */
    private fun resolveAttackDamage(baseDamage: Float): Float {
        val attribute = shooter.getEntityAttribute(SharedMonsterAttributes.ATTACK_DAMAGE) ?: return baseDamage
        val oldBaseValue = attribute.baseValue
        val modifier = AttributeModifier(UUID.randomUUID(), "TACZ Melee Damage", baseDamage.toDouble(), 0)
        return try {
            attribute.baseValue = 0.0
            attribute.applyModifier(modifier)
            attribute.attributeValue.toFloat()
        } finally {
            attribute.baseValue = oldBaseValue
            attribute.removeModifier(modifier)
        }
    }

    /**
     * 逐个施加 melee.effects（对齐上游 doPerLivingHurt 中的效果处理）。
     * effectId 无效时跳过；时长秒转 tick，amplifier 下限 0。
     */
    private fun applyMeleeEffects(target: EntityLivingBase, effects: List<MeleeEffectCombatData>) {
        for (effect in effects) {
            val effectId = effect.effectId ?: continue
            val potion = Potion.getPotionFromResourceLocation(effectId.toString()) ?: continue
            val duration = (effect.durationSeconds * 20f).toInt().coerceAtLeast(0)
            val amplifier = effect.amplifier.coerceAtLeast(0)
            target.addPotionEffect(
                PotionEffect(potion, duration, amplifier, false, effect.hideParticles)
            )
        }
    }
}
