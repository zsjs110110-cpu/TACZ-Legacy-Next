package com.tacz.legacy.common.network.message.client

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.application.refit.LegacyGunRefitRuntime
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageRefreshRefitScreen
import io.netty.buffer.ByteBuf
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraftforge.fml.common.network.simpleimpl.IMessage
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext
import net.minecraftforge.items.ItemHandlerHelper

public class ClientMessageRefitGun() : IMessage, IMessageHandler<ClientMessageRefitGun, IMessage?> {
    private var attachmentSlotIndex: Int = -1
    private var gunSlotIndex: Int = -1
    private var attachmentType: AttachmentType = AttachmentType.NONE

    public constructor(attachmentSlotIndex: Int, gunSlotIndex: Int, attachmentType: AttachmentType) : this() {
        this.attachmentSlotIndex = attachmentSlotIndex
        this.gunSlotIndex = gunSlotIndex
        this.attachmentType = attachmentType
    }

    override fun fromBytes(buf: ByteBuf) {
        attachmentSlotIndex = buf.readInt()
        gunSlotIndex = buf.readInt()
        attachmentType = AttachmentType.values().getOrElse(buf.readInt()) { AttachmentType.NONE }
    }

    override fun toBytes(buf: ByteBuf) {
        buf.writeInt(attachmentSlotIndex)
        buf.writeInt(gunSlotIndex)
        buf.writeInt(attachmentType.ordinal)
    }

    override fun onMessage(message: ClientMessageRefitGun, ctx: MessageContext): IMessage? {
        val player: EntityPlayerMP = ctx.serverHandler.player
        player.serverWorld.addScheduledTask {
            // 兼容层 ③ D-4：失败必须回原因码。
            // 原本四条失败分支都是裸 return，玩家侧表现为"拖上去没反应"，
            // 与"远程客户端数据没同步到"完全同形，无法排查。
            // 服务端同时留一条 WARN：原因码是给玩家看的，日志才是给排查用的。
            fun reject(reason: Int) {
                TACZLegacy.logger.warn(
                    "[GunPackRuntime] Refit rejected: reason={} gunSlot={} attachmentSlot={} type={} player={}.",
                    reason, message.gunSlotIndex, message.attachmentSlotIndex, message.attachmentType, player.name,
                )
                TACZNetworkHandler.sendToPlayer(
                    ServerMessageRefreshRefitScreen().apply { this.reason = reason },
                    player,
                )
            }
            if (message.attachmentSlotIndex !in 0 until player.inventory.sizeInventory) {
                return@addScheduledTask reject(ServerMessageRefreshRefitScreen.REASON_SLOT)
            }
            if (message.gunSlotIndex !in 0 until player.inventory.sizeInventory) {
                return@addScheduledTask reject(ServerMessageRefreshRefitScreen.REASON_SLOT)
            }
            if (message.attachmentSlotIndex == message.gunSlotIndex) {
                return@addScheduledTask reject(ServerMessageRefreshRefitScreen.REASON_SLOT)
            }
            val gunStack = player.inventory.getStackInSlot(message.gunSlotIndex)
            val attachmentStack = player.inventory.getStackInSlot(message.attachmentSlotIndex)
            if (gunStack.isEmpty || attachmentStack.isEmpty) {
                return@addScheduledTask reject(ServerMessageRefreshRefitScreen.REASON_NOT_FOUND)
            }
            val gunItem = gunStack.item as? com.tacz.legacy.api.item.IGun
            if (gunItem != null && gunItem.hasAttachmentLock(gunStack)) {
                return@addScheduledTask reject(ServerMessageRefreshRefitScreen.REASON_LOCKED)
            }
            // 返回 null 的语义是"这次换装不成立"：要么类型不被枪接受，要么装备后的 id 校验没通过。
            // 两者都归到 INCOMPATIBLE，具体原因看上面那条 WARN。
            val swapResult = LegacyGunRefitRuntime.swapAttachment(gunStack, attachmentStack, message.attachmentType)
                ?: return@addScheduledTask reject(ServerMessageRefreshRefitScreen.REASON_INCOMPATIBLE)
            // 只消耗一个配件；若槽位里还有剩余，则把卸下的旧配件另行返还给玩家，避免整组被覆盖丢失
            val remaining = attachmentStack.copy().apply { shrink(1) }
            if (remaining.isEmpty) {
                player.inventory.setInventorySlotContents(message.attachmentSlotIndex, swapResult.previousAttachment)
            } else {
                player.inventory.setInventorySlotContents(message.attachmentSlotIndex, remaining)
                if (!swapResult.previousAttachment.isEmpty) {
                    ItemHandlerHelper.giveItemToPlayer(player, swapResult.previousAttachment)
                }
            }
            if (swapResult.requiresAmmoRefund) {
                LegacyGunRefitRuntime.refundLoadedAmmo(gunStack, player.capabilities.isCreativeMode).refundStacks.forEach { stack ->
                    ItemHandlerHelper.giveItemToPlayer(player, stack)
                }
            }
            player.inventory.markDirty()
            player.inventoryContainer.detectAndSendChanges()
            TACZNetworkHandler.sendToPlayer(ServerMessageRefreshRefitScreen(), player)
        }
        return null
    }
}
