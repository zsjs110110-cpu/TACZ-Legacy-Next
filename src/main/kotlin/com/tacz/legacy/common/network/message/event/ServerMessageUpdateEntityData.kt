package com.tacz.legacy.common.network.message.event

import com.tacz.legacy.api.entity.IGunOperator
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraft.entity.EntityLivingBase
import net.minecraft.nbt.NBTTagCompound
import net.minecraftforge.fml.common.network.ByteBufUtils
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * S2C: 同步某个实体的通用附加数据（NBT）。
 *
 * 上游用该包同步实体的瞄准/趴下/换弹等表现状态。线格式用 NBT 作载体，
 * 避免以后每加一种状态就再改一次协议（注册顺序即 packetId，本包 = 31，只能追加不可插队）。
 *
 * 审计 B6-1：当前承载 `isCrawling`。趴下状态的三个消费点里，镜头后座
 * （`TACZCameraRecoilHandler`）与动画状态机（`GunAnimationStateContext`）读的是**客户端**
 * 实体 holder，而状态只在服务端翻转，因此必须由本包回传，否则那两处恒为 false。
 */
public class ServerMessageUpdateEntityData() : IMessage {
    private var entityId: Int = 0
    private var data: NBTTagCompound = NBTTagCompound()

    public constructor(entityId: Int, data: NBTTagCompound) : this() {
        this.entityId = entityId
        this.data = data
    }

    override fun fromBytes(buf: ByteBuf) {
        entityId = buf.readInt()
        data = ByteBufUtils.readTag(buf) ?: NBTTagCompound()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(entityId)
        ByteBufUtils.writeTag(buf, data)
    }

    public class Handler : IMessageHandler<ServerMessageUpdateEntityData, IMessage?> {
        override fun onMessage(message: ServerMessageUpdateEntityData, ctx: MessageContext): IMessage? {
            val mc = Minecraft.getMinecraft()
            mc.addScheduledTask {
                // 实体状态只能在客户端主线程读写；实体不存在或已换成非生物都安全返回。
                val world = mc.world ?: return@addScheduledTask
                val entity = world.getEntityByID(message.entityId) as? EntityLivingBase ?: return@addScheduledTask
                val data = message.data
                if (data.hasKey(TAG_CRAWLING, NBT_TYPE_BYTE)) {
                    // 服务端是权威值：本地预测可以先置位，收到本包后以服务端状态纠正
                    // （例如 can_crawl=false 的枪，服务端会强制站起并把 false 回传）。
                    IGunOperator.fromLivingEntity(entity).getDataHolder().isCrawling = data.getBoolean(TAG_CRAWLING)
                }
            }
            return null
        }
    }

    public companion object {
        /** NBT 键：趴下状态 */
        public const val TAG_CRAWLING: String = "IsCrawling"
        private const val NBT_TYPE_BYTE: Int = 1
    }
}
