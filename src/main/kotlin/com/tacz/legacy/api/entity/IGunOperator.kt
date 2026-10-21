package com.tacz.legacy.api.entity

import com.tacz.legacy.common.entity.shooter.ShooterDataHolder
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import java.util.function.Supplier

/**
 * 枪械操作者接口，由 LivingEntity 通过 Mixin 实现。
 * 与上游 TACZ IGunOperator 行为保持一致。
 */
public interface IGunOperator {
    public fun getSynShootCoolDown(): Long
    public fun getSynMeleeCoolDown(): Long
    public fun getSynDrawCoolDown(): Long
    public fun getSynIsBolting(): Boolean
    public fun getSynReloadState(): ReloadState
    public fun getSynAimingProgress(): Float
    public fun getSynIsAiming(): Boolean
    public fun getSynSprintTime(): Float

    public fun initialData()
    public fun draw(itemStackSupplier: Supplier<ItemStack>)
    public fun bolt()
    public fun reload()
    public fun cancelReload()
    public fun fireSelect()
    public fun melee()

    /**
     * 切换当前瞄具的倍率档位。
     *
     * 上游语义推断：TACZ 的倍镜切换是一个无参、无返回值的动作方法，按下倍镜键时
     * 把当前瞄具的倍率推进一档；倍率值保存在瞄具物品 NBT 中。
     * 本移植同样由 `IAttachment#getZoomNumber` / `IAttachment#setZoomNumber` 读写该 NBT，
     * 渲染与后坐力计算（FirstPersonFovHooks / TACZGunPropertyResolver）都从该 NBT 取值，
     * 因此采用 void 无参签名最贴近现有实现，也不破坏调用方。
     */
    public fun zoom()

    /**
     * 切换趴下状态。
     *
     * 上游语义推断：无参、无返回值的切换动作；实际状态保存在
     * [ShooterDataHolder.isCrawling]，是否允许趴下由枪械数据 can_crawl 决定。
     * 查询当前状态请读取 `getDataHolder().isCrawling`。
     *
     * 审计 B6-1：切换发生在服务端，因此 [com.tacz.legacy.common.entity.shooter.LivingEntityCrawl]
     * 会把权威值经 `ServerMessageUpdateEntityData`（S2C ID 31）下发到各客户端的 holder，
     * 镜头后座（TACZCameraRecoilHandler）与动画状态机（GunAnimationStateContext）读的正是客户端侧。
     * 注意：**按键层尚未接入**；当前的触发方是 [com.tacz.legacy.common.entity.shooter.LivingEntityCrawl.crawl]
     * —— 客户端调用时本地预测翻转后立刻 `sendToServer(ClientMessagePlayerCrawl)`，服务端比对后再回同步。
     */
    public fun crawl()

    public fun shoot(pitch: Supplier<Float>, yaw: Supplier<Float>, timestamp: Long, chargeProgress: Float = 0f): ShootResult

    public fun needCheckAmmo(): Boolean
    public fun consumesAmmoOrNot(): Boolean
    public fun getProcessedSprintStatus(): Float
    public fun aim(isAim: Boolean)

    public fun getDataHolder(): ShooterDataHolder

    /**
     * 推进并返回“下一发是否为曳光弹”。
     *
     * 上游语义推断：该方法无参，内部把 [ShooterDataHolder.shootCount] 加一，
     * 再按弹药数据的 tracer_count_interval 取模判断本发是否曳光弹
     * （与现有 LivingEntityShoot 私有实现一致）。每调用一次视为消耗一次发射计数。
     */
    public fun nextBulletIsTracer(): Boolean

    /**
     * 服务端每 tick 调用，驱动所有枪械子系统更新。
     */
    public fun tick()

    public companion object {
        @JvmStatic
        public fun fromLivingEntity(entity: EntityLivingBase): IGunOperator = entity as IGunOperator
    }
}
