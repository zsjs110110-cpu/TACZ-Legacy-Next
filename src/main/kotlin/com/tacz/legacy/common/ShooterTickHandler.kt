package com.tacz.legacy.common

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageSyncBaseTimestamp
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayerMP
import com.tacz.legacy.common.entity.shooter.BurstFireTaskScheduler
import com.tacz.legacy.common.entity.shooter.LivingEntityDrawGun
import com.tacz.legacy.common.entity.shooter.LivingEntitySpeedModifier
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.entity.living.LivingEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.util.function.Supplier

/**
 * 每 tick 驱动所有 EntityLivingBase 上的枪械子系统更新。
 * 注册到 Forge EVENT_BUS。
 */
internal object ShooterTickHandler {

    private var cleanupCounter = 0

    /**
     * 每个服务端 tick 驱动 BurstFireTaskScheduler 中的周期任务。
     * 与上游 TACZ ServerTickEvent → CycleTaskHelper.tick() 行为一致。
     */
    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        BurstFireTaskScheduler.tick()
        // 每 600 tick（约 30 秒）清理一次位置记录，防止已移除实体的条目堆积
        if (event.phase == TickEvent.Phase.END && cleanupCounter++ % 600 == 0) {
            TACZGunPropertyResolver.clearAllTickPositions()
        }
    }

    /**
     * 玩家加入世界时发起 baseTimestamp 同步握手。
     * 与上游 TACZ SyncBaseTimestamp.onPlayerJoinWorld 行为一致。
     */
    @SubscribeEvent
    fun onEntityJoinWorld(event: EntityJoinWorldEvent) {
        val entity = event.entity
        if (entity is EntityPlayerMP && !event.world.isRemote) {
            TACZNetworkHandler.sendToPlayer(ServerMessageSyncBaseTimestamp(), entity)
        }
    }

    @SubscribeEvent
    fun onLivingTick(event: LivingEvent.LivingUpdateEvent) {
        val entity: EntityLivingBase = event.entityLiving ?: return
        val mainHand = entity.heldItemMainhand
        val isHoldingGun = !mainHand.isEmpty && mainHand.item is IGun

        // 兼容层 ③ C6 修复（审计05 T12）：客户端也需要 updateSpeedModifier
        // 1.12.2 玩家位移由客户端 EntityPlayerSP 预测驱动，服务端 applyModifier 不会自动同步到客户端
        if (isHoldingGun) {
            val gun = mainHand.item as IGun
            val gunData = GunDataAccessor.getGunData(gun.getGunId(mainHand))
            LivingEntitySpeedModifier.updateSpeedModifier(entity, gunData)
        } else {
            LivingEntitySpeedModifier.updateSpeedModifier(entity, null)
        }

        // 以下逻辑只在服务端执行
        if (entity.world.isRemote) return

        val operator = IGunOperator.fromLivingEntity(entity)
        val holder = operator.getDataHolder()

        // 仅在手持枪械时 tick 子系统
        if (!isHoldingGun) {
            // 非持枪：如果之前有持枪状态需要清理
            if (holder.currentGunItem != null) {
                operator.initialData()
                holder.currentGunItem = null
                holder.drawnGunSignature = null
            }
            // updateSpeedModifier 已在上方调用
            // 仍需记录位置（供下一 tick isMoving 使用）
            TACZGunPropertyResolver.recordTickPosition(entity)
            return
        }

        val signature = LivingEntityDrawGun.gunSignature(entity, mainHand)
        if (holder.currentGunItem == null || holder.drawnGunSignature != signature) {
            operator.draw(Supplier { entity.heldItemMainhand })
        }

        operator.tick()

        // updateSpeedModifier 已在上方调用
        // 在 tick 之后记录位置：射击发生在 operator.tick() 内或 network tick，
        // 此时 lastTickPositions 仍是上一 tick 的值，isMoving 做差值才能检测到移动
        TACZGunPropertyResolver.recordTickPosition(entity)
    }
}
