package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.foundation.FocusedSmokeRuntime
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext
import java.util.function.Supplier

/**
 * C2S: 射击请求。
 */
public class ClientMessagePlayerShoot() : IMessage, IMessageHandler<ClientMessagePlayerShoot, IMessage?> {
    private var pitch: Float = 0f
    private var yaw: Float = 0f
    private var timestamp: Long = 0L
    private var chargeProgress: Float = 0f

    public constructor(pitch: Float, yaw: Float, timestamp: Long, chargeProgress: Float = 0f) : this() {
        this.pitch = pitch
        this.yaw = yaw
        this.timestamp = timestamp
        this.chargeProgress = chargeProgress
    }

    override fun fromBytes(buf: ByteBuf) {
        pitch = buf.readFloat()
        yaw = buf.readFloat()
        timestamp = buf.readLong()
        chargeProgress = buf.readFloat()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeFloat(pitch)
        buf.writeFloat(yaw)
        buf.writeLong(timestamp)
        buf.writeFloat(chargeProgress)
    }

    override fun onMessage(message: ClientMessagePlayerShoot, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        ctx.serverHandler.player.serverWorld.addScheduledTask {
            val operator = IGunOperator.fromLivingEntity(player)
            val heldGunId = (player.heldItemMainhand.item as? IGun)?.getGunId(player.heldItemMainhand)
            val result = operator.shoot(Supplier { message.pitch }, Supplier { message.yaw }, message.timestamp, message.chargeProgress)
            if (FocusedSmokeRuntime.enabled) {
                TACZLegacy.logger.info(
                    "[FocusedSmoke] SERVER_SHOOT_RESULT gun={} result={} timestamp={} baseTimestamp={} currentGunPresent={}",
                    heldGunId,
                    result,
                    message.timestamp,
                    operator.getDataHolder().baseTimestamp,
                    operator.getDataHolder().currentGunItem != null,
                )
            }
            if (result != com.tacz.legacy.api.entity.ShootResult.SUCCESS) {
                // If shoot fails on server but client thought it succeeded (e.g. NETWORK_FAIL), 
                // we must force sync the mainhand item to revert the client's local ammo deduction.
                player.sendAllContents(player.openContainer, player.openContainer.inventory)
            }
        }
        return null
    }
}
