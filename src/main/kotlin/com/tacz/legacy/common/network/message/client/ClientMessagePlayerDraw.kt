package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.common.entity.shooter.LivingEntityDrawGun
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.item.ItemStack
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext
import java.util.function.Supplier

/**
 * C2S: 切枪/拔枪请求。
 */
public class ClientMessagePlayerDraw() : IMessage, IMessageHandler<ClientMessagePlayerDraw, IMessage?> {

    override fun fromBytes(buf: ByteBuf) {}
    override fun toBytes(buf: ByteBuf) {}

    override fun onMessage(message: ClientMessagePlayerDraw, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        ctx.serverHandler.player.serverWorld.addScheduledTask {
            val mainHand = player.heldItemMainhand
            if (mainHand.item is IGun) {
                val operator = IGunOperator.fromLivingEntity(player)
                val holder = operator.getDataHolder()
                // 服务端 tick 可能已经针对同一把枪执行过 draw，避免重复广播切枪动画
                if (holder.currentGunItem != null &&
                    holder.drawnGunSignature == LivingEntityDrawGun.gunSignature(player, mainHand)
                ) {
                    return@addScheduledTask
                }
                // 必须实时读取主手物品：捕获当时的 ItemStack 实例会在物品被移动/替换后指向旧的枪
                operator.draw(Supplier { player.heldItemMainhand })
            }
        }
        return null
    }
}
