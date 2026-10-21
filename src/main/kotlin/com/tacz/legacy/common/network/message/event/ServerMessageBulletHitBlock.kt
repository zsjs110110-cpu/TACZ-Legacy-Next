package com.tacz.legacy.common.network.message.event

import com.tacz.legacy.client.particle.LegacyBulletClientEffects
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraft.util.EnumFacing
import net.minecraft.util.math.BlockPos
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * 子弹击中方块时由服务端广播，客户端据此生成弹孔、方块碎屑粒子与击中音效。
 * 对应上游 TACZ 在 onHitBlock 中发送的 BulletHoleOption 粒子。
 */
public class ServerMessageBulletHitBlock() : IMessage {
    private var blockPos: BlockPos = BlockPos.ORIGIN
    private var sideIndex: Int = 0
    private var hitX: Double = 0.0
    private var hitY: Double = 0.0
    private var hitZ: Double = 0.0

    public constructor(blockPos: BlockPos, side: EnumFacing, hitX: Double, hitY: Double, hitZ: Double) : this() {
        this.blockPos = blockPos
        this.sideIndex = side.index
        this.hitX = hitX
        this.hitY = hitY
        this.hitZ = hitZ
    }

    override fun fromBytes(buf: ByteBuf) {
        // 消息体固定 33 字节：long(8) + byte(1) + double(8)*3。解码前校验长度，拒绝畸形消息。
        require(buf.readableBytes() >= 33) { "ServerMessageBulletHitBlock 消息体长度不足: ${buf.readableBytes()}" }
        val pos = BlockPos.fromLong(buf.readLong())
        val side = buf.readByte().toInt()
        val x = buf.readDouble()
        val y = buf.readDouble()
        val z = buf.readDouble()
        // EnumFacing.byIndex 不是合法性检查，这里显式限制面索引范围并拒绝非有限坐标
        require(side in 0..5) { "ServerMessageBulletHitBlock 非法面索引: $side" }
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "ServerMessageBulletHitBlock 击中坐标非有限值" }
        blockPos = pos
        sideIndex = side
        hitX = x
        hitY = y
        hitZ = z
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeLong(blockPos.toLong())
        buf.writeByte(sideIndex)
        buf.writeDouble(hitX)
        buf.writeDouble(hitY)
        buf.writeDouble(hitZ)
    }

    public class Handler : IMessageHandler<ServerMessageBulletHitBlock, IMessage?> {
        override fun onMessage(message: ServerMessageBulletHitBlock, ctx: MessageContext): IMessage? {
            Minecraft.getMinecraft().addScheduledTask {
                LegacyBulletClientEffects.onBulletHitBlock(
                    message.blockPos,
                    EnumFacing.byIndex(message.sideIndex),
                    message.hitX,
                    message.hitY,
                    message.hitZ,
                )
            }
            return null
        }
    }
}
