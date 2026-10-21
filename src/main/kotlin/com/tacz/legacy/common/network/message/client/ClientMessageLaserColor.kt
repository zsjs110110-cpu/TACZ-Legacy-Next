package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.application.refit.LegacyGunRefitRuntime
import com.tacz.legacy.common.application.refit.LegacyLaserColorPayload
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.item.ItemStack
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext

public class ClientMessageLaserColor() : IMessage, IMessageHandler<ClientMessageLaserColor, IMessage?> {
    private var attachmentColors: LinkedHashMap<AttachmentType, Int> = linkedMapOf()
    private var gunColor: Int? = null
    private var gunSlotIndex: Int = -1

    public constructor(gunStack: ItemStack, gunSlotIndex: Int) : this() {
        val payload = LegacyGunRefitRuntime.createLaserPayload(gunStack)
        this.attachmentColors.putAll(payload.attachmentColors)
        this.gunColor = payload.gunColor
        this.gunSlotIndex = gunSlotIndex
    }

    override fun fromBytes(buf: ByteBuf) {
        gunSlotIndex = buf.readInt()
        val colorSize = buf.readInt()
        attachmentColors = linkedMapOf()
        repeat(colorSize) {
            val type = AttachmentType.values().getOrElse(buf.readInt()) { AttachmentType.NONE }
            attachmentColors[type] = buf.readInt()
        }
        gunColor = if (buf.readBoolean()) buf.readInt() else null
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(gunSlotIndex)
        buf.writeInt(attachmentColors.size)
        attachmentColors.forEach { (type, color) ->
            buf.writeInt(type.ordinal)
            buf.writeInt(color)
        }
        val color = gunColor
        buf.writeBoolean(color != null)
        if (color != null) {
            buf.writeInt(color)
        }
    }

    override fun onMessage(message: ClientMessageLaserColor, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        player.serverWorld.addScheduledTask {
            if (message.gunSlotIndex !in 0 until player.inventory.sizeInventory) {
                return@addScheduledTask
            }
            val gunStack = player.inventory.getStackInSlot(message.gunSlotIndex)
            if (IGun.getIGunOrNull(gunStack) == null) {
                return@addScheduledTask
            }
            LegacyGunRefitRuntime.applyLaserPayload(
                gunStack,
                LegacyLaserColorPayload(
                    attachmentColors = message.attachmentColors.filterKeys { type -> type != AttachmentType.NONE },
                    gunColor = message.gunColor,
                ),
            )
            player.inventory.markDirty()
            player.inventoryContainer.detectAndSendChanges()
        }
        return null
    }
}
