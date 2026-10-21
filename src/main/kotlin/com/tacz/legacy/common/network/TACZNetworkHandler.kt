package com.tacz.legacy.common.network

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.network.message.client.*
import com.tacz.legacy.common.network.message.event.*
import net.minecraft.entity.Entity
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.network.NetworkRegistry
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper
import net.minecraftforge.fml.relauncher.Side

/**
 * 基于 1.12.2 SimpleNetworkWrapper 的网络管理器。
 * 注册所有战斗/枪械相关的 C2S 和 S2C 消息。
 */
public object TACZNetworkHandler {

    @JvmField
    public val CHANNEL: SimpleNetworkWrapper = NetworkRegistry.INSTANCE.newSimpleChannel(TACZLegacy.MOD_ID)
    private var packetId: Int = 0

    /**
     * 注册顺序即协议 ID（SimpleNetworkWrapper 按 `registerMessage` 的调用顺序自增 discriminator），
     * **两端必须完全一致**，而本 mod 没有任何版本协商。
     *
     * 因此新增消息时：
     * 1. 只能**追加**到对应分组的末尾；
     * 2. 禁止重排序、禁止删除已有注册（会让后面所有包的 ID 前移一位，混版本必炸）；
     * 3. 修改已有包的 `toBytes` 时，`fromBytes` 必须能读旧格式（参考 `ServerMessageRefreshRefitScreen`
     *    用 `readableBytes() >= 4` 兜住"旧版空包"）。
     */
    public fun init() {
        // Client -> Server
        registerC2S<ClientMessageGunSmithCraft>()
        registerC2S<ClientMessageRefitGun>()
        registerC2S<ClientMessageRefitGunCreative>()
        registerC2S<ClientMessageUnloadAttachment>()
        registerC2S<ClientMessageLaserColor>()
        registerC2S<ClientMessagePlayerShoot>()
        registerC2S<ClientMessagePlayerReload>()
        registerC2S<ClientMessagePlayerCancelReload>()
        registerC2S<ClientMessagePlayerAim>()
        registerC2S<ClientMessagePlayerBolt>()
        registerC2S<ClientMessagePlayerDraw>()
        registerC2S<ClientMessagePlayerMelee>()
        registerC2S<ClientMessagePlayerFireSelect>()
        registerC2S<ClientMessageSyncBaseTimestamp>()

        // 注意：注册顺序即 packetId，新增 C2S 包只能追加在本块末尾，禁止插队/重排/删除，
        // 否则后续所有包 ID 会整体偏移，两端协议不一致时会出现 "unknown packet"。
        // 当前按顺序分配：ClientMessagePlayerCrawl = 14，ClientMessagePlayerZoom = 15。
        registerC2S<ClientMessagePlayerCrawl>()
        registerC2S<ClientMessagePlayerZoom>()

        // Server -> Client (events)
        registerS2C(ServerMessageGunShoot.Handler::class.java, ServerMessageGunShoot::class.java)
        registerS2C(ServerMessageReload.Handler::class.java, ServerMessageReload::class.java)
        registerS2C(ServerMessageMelee.Handler::class.java, ServerMessageMelee::class.java)
        registerS2C(ServerMessageSound.Handler::class.java, ServerMessageSound::class.java)
        registerS2C(ServerMessageGunFire.Handler::class.java, ServerMessageGunFire::class.java)
        registerS2C(ServerMessageGunDraw.Handler::class.java, ServerMessageGunDraw::class.java)
        registerS2C(ServerMessageGunFireSelect.Handler::class.java, ServerMessageGunFireSelect::class.java)
        registerS2C(ServerMessageGunHurt.Handler::class.java, ServerMessageGunHurt::class.java)
        registerS2C(ServerMessageGunKill.Handler::class.java, ServerMessageGunKill::class.java)
        registerS2C(ServerMessageRefreshRefitScreen.Handler::class.java, ServerMessageRefreshRefitScreen::class.java)
        registerS2C(ServerMessageSyncBaseTimestamp.Handler::class.java, ServerMessageSyncBaseTimestamp::class.java)
        registerS2C(ServerMessageBulletHitBlock.Handler::class.java, ServerMessageBulletHitBlock::class.java)
        // 兼容层 ③ C2：枪包数据同步（分片）
        registerS2C(ServerMessageSyncGunPack.Handler::class.java, ServerMessageSyncGunPack::class.java)

        // 注意：注册顺序即 packetId，新增 S2C 包只能追加在本块末尾，禁止插队/重排/删除，
        // 否则后续所有包 ID 会整体偏移，两端协议不一致时会出现 "unknown packet"。
        // 以下三项预留给后续功能，当前均无发送方：
        // ServerMessageSwapItem = 29，ServerMessageLevelUp = 30，ServerMessageUpdateEntityData = 31。
        registerS2C(ServerMessageSwapItem.Handler::class.java, ServerMessageSwapItem::class.java)
        registerS2C(ServerMessageLevelUp.Handler::class.java, ServerMessageLevelUp::class.java)
        registerS2C(ServerMessageUpdateEntityData.Handler::class.java, ServerMessageUpdateEntityData::class.java)
    }

    /**
     * 发送消息到指定玩家。
     */
    @JvmStatic
    public fun sendToPlayer(msg: IMessage, player: EntityPlayerMP) {
        CHANNEL.sendTo(msg, player)
    }

    /**
     * 发送消息到追踪指定实体的所有玩家（不含实体自身如果是玩家）。
     */
    @JvmStatic
    public fun sendToTrackingEntity(msg: IMessage, entity: Entity) {
        val point = NetworkRegistry.TargetPoint(
            entity.world.provider.dimension,
            entity.posX,
            entity.posY,
            entity.posZ,
            128.0,
        )
        CHANNEL.sendToAllAround(msg, point)
    }

    /**
     * 发送消息到指定位置附近的所有客户端。
     */
    @JvmStatic
    public fun sendToAllAround(msg: IMessage, dimension: Int, x: Double, y: Double, z: Double, range: Double) {
        CHANNEL.sendToAllAround(msg, NetworkRegistry.TargetPoint(dimension, x, y, z, range))
    }

    /**
     * 发送消息到指定维度内的所有客户端。
     */
    @JvmStatic
    public fun sendToDimension(msg: IMessage, dimension: Int) {
        CHANNEL.sendToDimension(msg, dimension)
    }

    /**
     * 发送消息到服务端。
     */
    @JvmStatic
    public fun sendToServer(msg: IMessage) {
        CHANNEL.sendToServer(msg)
    }

    private inline fun <reified T> registerC2S() where T : IMessage, T : IMessageHandler<T, IMessage?> {
        CHANNEL.registerMessage(T::class.java, T::class.java, packetId++, Side.SERVER)
    }

    private fun <REQ : IMessage> registerS2C(
        handlerClass: Class<out IMessageHandler<REQ, IMessage?>>,
        messageClass: Class<REQ>,
    ) {
        CHANNEL.registerMessage(handlerClass, messageClass, packetId++, Side.CLIENT)
    }
}
