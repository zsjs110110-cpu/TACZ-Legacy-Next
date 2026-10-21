package com.tacz.legacy.common.network.message.event

import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * S2C: 实体“升级”事件（等级变化展示）。
 *
 * 预留给后续功能，当前无发送方。字段保持最小：实体 id + 新等级；
 * 未来接入等级/经验系统时由服务端构造并发送。
 *
 * handler 在客户端主线程执行，只做实体存在性检查；当前没有等级显示消费方，
 * 因此不做任何状态修改，即使实体已卸载或 entityId 非法也不会抛异常。
 */
public class ServerMessageLevelUp() : IMessage {
    private var entityId: Int = 0
    private var level: Int = 0

    public constructor(entityId: Int, level: Int) : this() {
        this.entityId = entityId
        this.level = level
    }

    override fun fromBytes(buf: ByteBuf) {
        entityId = buf.readInt()
        level = buf.readInt()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(entityId)
        buf.writeInt(level)
    }

    public class Handler : IMessageHandler<ServerMessageLevelUp, IMessage?> {
        override fun onMessage(message: ServerMessageLevelUp, ctx: MessageContext): IMessage? {
            val mc = Minecraft.getMinecraft()
            mc.addScheduledTask {
                val world = mc.world ?: return@addScheduledTask
                if (world.getEntityByID(message.entityId) == null) {
                    return@addScheduledTask
                }
                // 预留给后续功能，当前无发送方：等级展示/音效尚未接入。
            }
            return null
        }
    }
}
