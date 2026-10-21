package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.application.refit.LegacyGunRefitRuntime
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageRefreshRefitScreen
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

public class ClientMessageRefitGunCreative() : IMessage, IMessageHandler<ClientMessageRefitGunCreative, IMessage?> {
    private var attachmentIdRaw: String = ""
    private var gunSlotIndex: Int = -1
    private var attachmentType: AttachmentType = AttachmentType.NONE

    public constructor(attachmentId: ResourceLocation, gunSlotIndex: Int, attachmentType: AttachmentType) : this() {
        this.attachmentIdRaw = attachmentId.toString()
        this.gunSlotIndex = gunSlotIndex
        this.attachmentType = attachmentType
    }

    override fun fromBytes(buf: ByteBuf) {
        attachmentIdRaw = readUtf(buf)
        gunSlotIndex = buf.readInt()
        attachmentType = AttachmentType.values().getOrElse(buf.readInt()) { AttachmentType.NONE }
    }

    override fun toBytes(buf: ByteBuf) {
        writeUtf(buf, attachmentIdRaw)
        buf.writeInt(gunSlotIndex)
        buf.writeInt(attachmentType.ordinal)
    }

    override fun onMessage(message: ClientMessageRefitGunCreative, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        player.serverWorld.addScheduledTask {
            if (!player.capabilities.isCreativeMode) {
                return@addScheduledTask
            }
            if (message.gunSlotIndex !in 0 until player.inventory.sizeInventory) {
                return@addScheduledTask
            }
            val attachmentId = runCatching { ResourceLocation(message.attachmentIdRaw) }.getOrNull() ?: return@addScheduledTask
            val gunStack = player.inventory.getStackInSlot(message.gunSlotIndex)
            val attachmentStack = ItemStack(LegacyItems.ATTACHMENT).apply {
                LegacyItems.ATTACHMENT.setAttachmentId(this, attachmentId)
            }
            val swapResult = LegacyGunRefitRuntime.swapAttachment(gunStack, attachmentStack, message.attachmentType) ?: return@addScheduledTask
            if (swapResult.requiresAmmoRefund) {
                LegacyGunRefitRuntime.refundLoadedAmmo(gunStack, creativeMode = true)
            }
            player.inventory.markDirty()
            player.inventoryContainer.detectAndSendChanges()
            TACZNetworkHandler.sendToPlayer(ServerMessageRefreshRefitScreen(), player)
        }
        return null
    }

    private fun readUtf(buf: ByteBuf): String {
        val length = buf.readInt()
        // 来自客户端的长度不可信：限制长度，防止超大分配导致服务端内存溢出
        require(length in 0..minOf(MAX_STRING_BYTES, buf.readableBytes())) { "Invalid string length: $length" }
        val bytes = ByteArray(length)
        buf.readBytes(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeUtf(buf: ByteBuf, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        buf.writeInt(bytes.size)
        buf.writeBytes(bytes)
    }

    private companion object {
        const val MAX_STRING_BYTES: Int = 32767
    }
}
