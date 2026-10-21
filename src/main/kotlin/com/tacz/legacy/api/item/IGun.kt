package com.tacz.legacy.api.item

import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.item.gun.FireMode
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.ResourceLocation

/**
 * 枪械 NBT 访问接口。不包含枪械逻辑，仅提供数据访问。
 * 与上游 TACZ IGun 行为保持一致。
 */
public interface IGun {
    public fun getGunId(stack: ItemStack): ResourceLocation
    public fun setGunId(stack: ItemStack, gunId: ResourceLocation?)

    public fun getAimingZoom(stack: ItemStack): Float

    public fun getFireMode(stack: ItemStack): FireMode
    public fun setFireMode(stack: ItemStack, fireMode: FireMode?)

    public fun getCurrentAmmoCount(stack: ItemStack): Int
    public fun setCurrentAmmoCount(stack: ItemStack, ammoCount: Int)
    public fun reduceCurrentAmmoCount(stack: ItemStack)

    public fun hasBulletInBarrel(stack: ItemStack): Boolean
    public fun setBulletInBarrel(stack: ItemStack, bulletInBarrel: Boolean)

    public fun useInventoryAmmo(stack: ItemStack): Boolean
    public fun hasInventoryAmmo(shooter: EntityLivingBase, stack: ItemStack, needCheckAmmo: Boolean): Boolean

    public fun useDummyAmmo(stack: ItemStack): Boolean
    public fun getDummyAmmoAmount(stack: ItemStack): Int
    public fun setDummyAmmoAmount(stack: ItemStack, amount: Int)
    public fun addDummyAmmoAmount(stack: ItemStack, amount: Int)

    // X-48：对齐上游 IGun:104-114 的假弹匣上限三件套（背包直读枪的备弹封顶）
    public fun hasMaxDummyAmmo(stack: ItemStack): Boolean
    public fun getMaxDummyAmmoAmount(stack: ItemStack): Int
    public fun setMaxDummyAmmoAmount(stack: ItemStack, amount: Int)

    public fun getAttachmentTag(stack: ItemStack, type: AttachmentType): NBTTagCompound?
    public fun getAttachment(stack: ItemStack, type: AttachmentType): ItemStack
    public fun getBuiltinAttachment(stack: ItemStack, type: AttachmentType): ItemStack
    public fun getAttachmentId(stack: ItemStack, type: AttachmentType): ResourceLocation
    public fun getBuiltInAttachmentId(stack: ItemStack, type: AttachmentType): ResourceLocation
    public fun installAttachment(gun: ItemStack, attachment: ItemStack)
    public fun unloadAttachment(gun: ItemStack, type: AttachmentType)
    public fun allowAttachment(gun: ItemStack, attachmentItem: ItemStack): Boolean
    public fun allowAttachmentType(gun: ItemStack, type: AttachmentType): Boolean

    public fun hasCustomLaserColor(stack: ItemStack): Boolean
    public fun getLaserColor(stack: ItemStack): Int
    public fun setLaserColor(stack: ItemStack, color: Int)

    public fun hasAttachmentLock(stack: ItemStack): Boolean
    public fun setAttachmentLock(stack: ItemStack, locked: Boolean)

    public fun isOverheatLocked(stack: ItemStack): Boolean
    public fun setOverheatLocked(stack: ItemStack, locked: Boolean)

    public fun getHeatAmount(stack: ItemStack): Float
    public fun setHeatAmount(stack: ItemStack, amount: Float)

    /**
     * 当前射速（发/分）。
     *
     * 对齐上游 `AbstractGunItem#getRPM`：基准值取枪械数据 `rpm`（叠加 `fire_mode_adjust.rpm`，<=0 兜底 300），
     * 有 heat 数据时再乘 [lerpRPM]。
     *
     * 审计 B3-17：本组接口是 **API 面对齐**（供第三方物品/脚本/ Lua 读取），本仓玩法侧没有消费点；
     * 实际开火间隔走 `GunCombatData.getShootIntervalMs`。上游没有射速 setter，故此处也不提供。
     */
    public fun getRPM(stack: ItemStack): Int

    /**
     * heat 对射速的插值倍率：按过热进度在 `min_rpm_mod`~`max_rpm_mod` 之间线性插值。
     *
     * 与上游 `IGun#lerpRPM` 一致：无 heat 数据（或 `max <= 0`）时返回 1.0，冷枪不受影响。
     */
    public fun lerpRPM(stack: ItemStack): Float

    /**
     * heat 对散布的插值倍率：按过热进度在 `min_inaccuracy`~`max_inaccuracy` 之间线性插值。
     *
     * 仅 API 对齐上游 `IGun#lerpInaccuracy`，**不要**把它接进散布路径：
     * 实际散布消费仍走 `TACZGunPropertyResolver#resolveInaccuracy`（内部已用
     * `resolveHeatInaccuracyMultiplier` 缩放一次），再接会重复缩放。
     */
    public fun lerpInaccuracy(stack: ItemStack): Float

    /**
     * 持该枪是否允许趴下射击。对齐上游 `AbstractGunItem#isCanCrawl`：读枪械数据 `can_crawl`，
     * 无枪械数据时返回 false。
     */
    public fun isCanCrawl(stack: ItemStack): Boolean

    /**
     * 该枪是否有 heat 配置。对齐上游 `GunData#hasHeatData` 经由 IGun 的转发。
     */
    public fun hasHeatData(stack: ItemStack): Boolean

    public companion object {
        public const val ATTACHMENT_BASE_TAG: String = "Attachment"
        public const val ATTACHMENT_LOCK_TAG: String = "AttachmentLock"
        public const val DUMMY_AMMO_TAG: String = "DummyAmmo"
        // X-48：对齐上游 GunItemDataAccessor.GUN_MAX_DUMMY_AMMO
        public const val MAX_DUMMY_AMMO_TAG: String = "MaxDummyAmmo"
        public const val GUN_ID_TAG: String = "GunId"
        public const val FIRE_MODE_TAG: String = "GunFireMode"
        public const val AMMO_COUNT_TAG: String = "GunCurrentAmmoCount"
        public const val BULLET_IN_BARREL_TAG: String = "HasBulletInBarrel"
        public const val LASER_COLOR_TAG: String = "LaserColor"
        public const val OVERHEAT_LOCK_TAG: String = "OverHeated"
        public const val HEAT_AMOUNT_TAG: String = "HeatAmount"

        @JvmStatic
        public fun getIGunOrNull(stack: ItemStack): IGun? = stack.item as? IGun

        @JvmStatic
        public fun mainHandHoldGun(entity: EntityLivingBase): Boolean =
            entity.heldItemMainhand.item is IGun

        @JvmStatic
        public fun getMainHandFireMode(entity: EntityLivingBase): FireMode {
            val stack = entity.heldItemMainhand
            val iGun = stack.item as? IGun ?: return FireMode.UNKNOWN
            return iGun.getFireMode(stack)
        }
    }
}
