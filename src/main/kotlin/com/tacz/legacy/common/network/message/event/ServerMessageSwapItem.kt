package com.tacz.legacy.common.network.message.event

import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import net.minecraftforge.fml.common.network.ByteBufUtils
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * S2C: 通知客户端某实体切换了手持物品。
 *
 * 预留给后续功能，当前无发送方。字段保持最小：实体 id + 新的手持物品；
 * 未来接入“换物品”行为时由服务端构造并发送。
 *
 * handler 在客户端主线程执行，只做实体存在性检查，不直接修改客户端背包/手持物，
 * 避免与服务器槽位同步互相覆盖。当前即使收到非法 entityId 或空物品也安全返回。
 */
public class ServerMessageSwapItem() : IMessage {
    private var entityId: Int = 0
    private var itemStack: ItemStack = ItemStack.EMPTY

    public constructor(entityId: Int, itemStack: ItemStack) : this() {
        this.entityId = entityId
        this.itemStack = itemStack
    }

    override fun fromBytes(buf: ByteBuf) {
        entityId = buf.readInt()
        itemStack = ByteBufUtils.readItemStack(buf)
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(entityId)
        ByteBufUtils.writeItemStack(buf, itemStack)
    }

    public class Handler : IMessageHandler<ServerMessageSwapItem, IMessage?> {
        override fun onMessage(message: ServerMessageSwapItem, ctx: MessageContext): IMessage? {
            val mc = Minecraft.getMinecraft()
            mc.addScheduledTask {
                val world = mc.world ?: return@addScheduledTask
                val entity = world.getEntityByID(message.entityId)
                if (entity !is EntityLivingBase) {
                    return@addScheduledTask
                }
                // 预留给后续功能，当前无发送方：暂不落地物品替换，
                // 防止在没有任何消费方时改动客户端实体状态。
            }
            return null
        }
    }
}
