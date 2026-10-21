package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.api.entity.IGunOperator
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * C2S: 瞄具倍率切换请求。
 *
 * 空包：与服务端 `zoom()` 的无参动作语义一致，收到一次就推进一档倍率。
 *
 * 倍率保存在瞄具 [net.minecraft.item.ItemStack] 的 NBT 中，服务端修改后不会自动
 * 同步给客户端；handler 在调用 `zoom()` 后主动执行
 * `player.inventoryContainer.detectAndSendChanges()`，由原版槽位同步把新的 NBT
 * 写回客户端，避免两端倍率不一致。这里不需要额外的专用 S2C 包。
 *
 * 注意：客户端按键层尚未接入发送方（预留给后续功能），当前无发送方。
 */
public class ClientMessagePlayerZoom() : IMessage, IMessageHandler<ClientMessagePlayerZoom, IMessage?> {

    override fun fromBytes(buf: ByteBuf) {}

    override fun toBytes(buf: ByteBuf) {}

    override fun onMessage(message: ClientMessagePlayerZoom, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        // zoom() 会读写手持物品/瞄具的 NBT，必须在服务端主线程执行。
        ctx.serverHandler.player.serverWorld.addScheduledTask {
            IGunOperator.fromLivingEntity(player).zoom()
            // zoom() 只改 NBT，不触发容器同步；主动检测变更把新档位写回客户端，
            // 否则客户端仍按旧倍率渲染，和服务端的瞄准/后坐力计算不一致。
            player.inventoryContainer.detectAndSendChanges()
        }
        return null
    }
}
