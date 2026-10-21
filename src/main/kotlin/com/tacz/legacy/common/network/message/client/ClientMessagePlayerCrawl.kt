package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.api.entity.IGunOperator
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

/**
 * C2S: 趴下状态切换请求。
 *
 * 协议携带一个 boolean（客户端期望的趴下状态），而不是空包。原因是服务端的
 * [IGunOperator.crawl] 目前是 **toggle** 语义：如果客户端本地已经翻转一次、包又重复
 * 到达，空包会把状态翻回去。因此这里传“期望状态”，由 handler 与
 * [com.tacz.legacy.common.entity.shooter.ShooterDataHolder.isCrawling] 比较后，
 * 只在两者不一致时调用一次 `crawl()`。
 *
 * 注意：发送方为 [com.tacz.legacy.common.entity.shooter.LivingEntityCrawl.crawl] 的客户端分支
 * （本地预测翻转后立即 sendToServer）。按键层接入后同样调用该方法即可，无需另加发包点。
 */
public class ClientMessagePlayerCrawl() : IMessage, IMessageHandler<ClientMessagePlayerCrawl, IMessage?> {
    private var crawling: Boolean = false

    public constructor(crawling: Boolean) : this() {
        this.crawling = crawling
    }

    override fun fromBytes(buf: ByteBuf) {
        crawling = buf.readBoolean()
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeBoolean(crawling)
    }

    override fun onMessage(message: ClientMessagePlayerCrawl, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        // 实体状态只能在服务端主线程读写；网络回调线程直接改 ShooterDataHolder 会有竞态。
        ctx.serverHandler.player.serverWorld.addScheduledTask {
            val operator = IGunOperator.fromLivingEntity(player)
            // crawl() 是 toggle：只有期望状态与当前状态不一致时才调用一次，
            // 避免“客户端已本地翻转 + 服务端再翻转”或重复包导致状态来回跳。
            // 当前枪械 can_crawl=false 时 crawl() 内部会强制站起，这也是期望行为。
            if (operator.getDataHolder().isCrawling != message.crawling) {
                operator.crawl()
            }
        }
        return null
    }
}
