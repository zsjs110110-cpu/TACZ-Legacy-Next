package com.tacz.legacy.common.network.message.event

import com.tacz.legacy.client.gui.GunRefitScreen
import io.netty.buffer.ByteBuf
import net.minecraft.client.Minecraft
import net.minecraft.util.text.TextComponentTranslation
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * 服务端 → 客户端：改装界面该重算了（**载荷不含数据**，客户端用本地快照重算）。
 *
 * 兼容层 ③ D-4：顺带带回一个**失败原因码**。改装失败原本在服务端静默 `return`，
 * 玩家的体验是"拖上去没反应"，而远程客户端的数据缺失会表现成同一现象 ——
 * 没有原因码就无法区分"操作不对"和"数据没同步到"。
 */
public class ServerMessageRefreshRefitScreen() : IMessage {

    public var reason: Int = REASON_NONE

    override fun fromBytes(buf: ByteBuf) {
        // 旧格式（空包）保持可读：没有剩余字节时按"成功"处理
        reason = if (buf.readableBytes() >= Int.SIZE_BYTES) buf.readInt() else REASON_NONE
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(reason)
    }

    public class Handler : IMessageHandler<ServerMessageRefreshRefitScreen, IMessage?> {
        override fun onMessage(message: ServerMessageRefreshRefitScreen, ctx: MessageContext): IMessage? {
            val mc = Minecraft.getMinecraft()
            mc.addScheduledTask {
                (mc.currentScreen as? GunRefitScreen)?.refreshFromLocalSnapshot()
                if (message.reason != REASON_NONE) {
                    mc.player?.sendStatusMessage(
                        TextComponentTranslation(reasonLangKey(message.reason)),
                        true,
                    )
                }
            }
            return null
        }
    }

    public companion object {
        public const val REASON_NONE: Int = 0

        /** 槽位下标非法/两槽相同。 */
        public const val REASON_SLOT: Int = 1

        /** 枪或配件在槽位里查不到。 */
        public const val REASON_NOT_FOUND: Int = 2

        /** 类型不匹配：枪的 `allow_attachment_types` 不允许该配件。 */
        public const val REASON_INCOMPATIBLE: Int = 3

        /** 该枪的配件槽被锁定（`hasAttachmentLock`），服务端拒绝改装。 */
        public const val REASON_LOCKED: Int = 4

        public fun reasonLangKey(reason: Int): String = when (reason) {
            REASON_SLOT -> "tacz.refit.rejected.slot"
            REASON_NOT_FOUND -> "tacz.refit.rejected.not_found"
            REASON_INCOMPATIBLE -> "tacz.refit.rejected.incompatible"
            REASON_LOCKED -> "tacz.refit.rejected.locked"
            else -> "tacz.refit.rejected.unknown"
        }
    }
}
