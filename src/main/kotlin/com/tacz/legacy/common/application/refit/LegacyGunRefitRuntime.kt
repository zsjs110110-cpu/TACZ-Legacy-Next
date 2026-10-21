package com.tacz.legacy.common.application.refit

import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import net.minecraft.item.ItemStack

internal data class LegacyRefitInventorySlot(
    val slotIndex: Int,
    val stack: ItemStack,
)

internal data class LegacyAttachmentSwapResult(
    val previousAttachment: ItemStack,
    val requiresAmmoRefund: Boolean,
)

internal data class LegacyAttachmentUnloadResult(
    val removedAttachment: ItemStack,
    val requiresAmmoRefund: Boolean,
)

internal data class LegacyLaserColorPayload(
    val attachmentColors: Map<AttachmentType, Int>,
    val gunColor: Int?,
)

internal data class LegacyAmmoRefundResult(
    val refundStacks: List<ItemStack>,
)

internal object LegacyGunRefitRuntime {
    fun canOpenRefit(gunStack: ItemStack): Boolean {
        if (gunStack.isEmpty) {
            return false
        }
        val iGun = IGun.getIGunOrNull(gunStack) ?: return false
        return !iGun.hasAttachmentLock(gunStack)
    }

    fun compatibleInventorySlots(
        gunStack: ItemStack,
        selectedType: AttachmentType,
        slots: Iterable<LegacyRefitInventorySlot>,
    ): List<LegacyRefitInventorySlot> {
        if (selectedType == AttachmentType.NONE) {
            return emptyList()
        }
        val iGun = IGun.getIGunOrNull(gunStack) ?: return emptyList()
        return slots.filter { slot ->
            val attachment = IAttachment.getIAttachmentOrNull(slot.stack) ?: return@filter false
            attachment.getType(slot.stack) == selectedType && iGun.allowAttachment(gunStack, slot.stack)
        }
    }

    fun compatibleCreativeAttachments(
        gunStack: ItemStack,
        selectedType: AttachmentType,
    ): List<ItemStack> {
        if (selectedType == AttachmentType.NONE) {
            return emptyList()
        }
        val iGun = IGun.getIGunOrNull(gunStack) ?: return emptyList()
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        return TACZGunPackPresentation.sortedAttachments(snapshot)
            .asSequence()
            .filter { attachment -> AttachmentType.fromSerializedName(attachment.index.type) == selectedType }
            .map { attachment ->
                ItemStack(LegacyItems.ATTACHMENT).apply {
                    LegacyItems.ATTACHMENT.setAttachmentId(this, attachment.id)
                }
            }
            .filter { attachmentStack -> iGun.allowAttachment(gunStack, attachmentStack) }
            .toList()
    }

    fun displayedAttachment(gunStack: ItemStack, type: AttachmentType): ItemStack {
        val iGun = IGun.getIGunOrNull(gunStack) ?: return ItemStack.EMPTY
        val installed = iGun.getAttachment(gunStack, type)
        if (!installed.isEmpty) {
            return installed
        }
        return iGun.getBuiltinAttachment(gunStack, type)
    }

    fun swapAttachment(
        gunStack: ItemStack,
        attachmentStack: ItemStack,
        expectedType: AttachmentType,
    ): LegacyAttachmentSwapResult? {
        if (expectedType == AttachmentType.NONE) {
            return null
        }
        val iGun = IGun.getIGunOrNull(gunStack) ?: return null
        // 配件锁定的枪不允许改装（客户端也会检查，但服务端必须再次校验）
        if (iGun.hasAttachmentLock(gunStack)) {
            return null
        }
        val iAttachment = IAttachment.getIAttachmentOrNull(attachmentStack) ?: return null
        if (iAttachment.getType(attachmentStack) != expectedType) {
            return null
        }
        if (!iGun.allowAttachment(gunStack, attachmentStack)) {
            return null
        }
        val previousAttachment = iGun.getAttachment(gunStack, expectedType)
        val incomingId = iAttachment.getAttachmentId(attachmentStack)
        iGun.installAttachment(gunStack, attachmentStack.copy().apply { count = 1 })
        if (iGun.getAttachmentId(gunStack, expectedType) != incomingId) {
            return null
        }
        return LegacyAttachmentSwapResult(
            previousAttachment = previousAttachment,
            requiresAmmoRefund = expectedType == AttachmentType.EXTENDED_MAG,
        )
    }

    fun unloadAttachment(gunStack: ItemStack, type: AttachmentType): LegacyAttachmentUnloadResult? {
        if (type == AttachmentType.NONE) {
            return null
        }
        val iGun = IGun.getIGunOrNull(gunStack) ?: return null
        if (iGun.hasAttachmentLock(gunStack)) {
            return null
        }
        val removed = iGun.getAttachment(gunStack, type)
        if (removed.isEmpty) {
            return null
        }
        iGun.unloadAttachment(gunStack, type)
        return LegacyAttachmentUnloadResult(
            removedAttachment = removed,
            requiresAmmoRefund = type == AttachmentType.EXTENDED_MAG,
        )
    }

    fun createLaserPayload(gunStack: ItemStack): LegacyLaserColorPayload {
        val iGun = IGun.getIGunOrNull(gunStack) ?: return LegacyLaserColorPayload(emptyMap(), null)
        val attachmentColors = linkedMapOf<AttachmentType, Int>()
        AttachmentType.values().filterNot { it == AttachmentType.NONE }.forEach { type ->
            val attachmentStack = iGun.getAttachment(gunStack, type)
            val iAttachment = IAttachment.getIAttachmentOrNull(attachmentStack) ?: return@forEach
            if (iAttachment.hasCustomLaserColor(attachmentStack)) {
                attachmentColors[type] = iAttachment.getLaserColor(attachmentStack)
            }
        }
        val gunColor = if (iGun.hasCustomLaserColor(gunStack)) iGun.getLaserColor(gunStack) else null
        return LegacyLaserColorPayload(attachmentColors = attachmentColors, gunColor = gunColor)
    }

    fun applyLaserPayload(gunStack: ItemStack, payload: LegacyLaserColorPayload) {
        val iGun = IGun.getIGunOrNull(gunStack) ?: return
        payload.attachmentColors.forEach { (type, color) ->
            val attachmentTag = iGun.getAttachmentTag(gunStack, type) ?: return@forEach
            attachmentTag.setInteger("LaserColor", color)
        }
        payload.gunColor?.let { color ->
            iGun.setLaserColor(gunStack, color)
        }
    }

    fun computeAmmoCapacity(gunStack: ItemStack): Int {
        val iGun = IGun.getIGunOrNull(gunStack) ?: return 0
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunEntry = snapshot.guns[iGun.getGunId(gunStack)] ?: return 0
        val baseAmmoAmount = gunEntry.data.ammoAmount.coerceAtLeast(0)
        val extendedMagAmmoAmount = gunEntry.data.extendedMagAmmoAmount ?: return baseAmmoAmount
        val magAttachmentId = iGun.getAttachmentId(gunStack, AttachmentType.EXTENDED_MAG)
        // 兼容层 ③ C10：exclusive_attachments 命中时用独占数据的 extended_mag_level
        // （对齐上游 AttachmentDataUtils#getMagExtendLevel 的整份替换语义）
        val magData = TACZGunPropertyResolver.attachmentDataFor(
            snapshot, magAttachmentId, gunEntry.data.exclusiveAttachments,
        ) ?: return baseAmmoAmount
        val extendedMagLevel = magData.extendedMagLevel.coerceIn(0, 3)
        if (extendedMagLevel <= 0) {
            return baseAmmoAmount
        }
        return extendedMagAmmoAmount.getOrNull(extendedMagLevel - 1)?.coerceAtLeast(0) ?: baseAmmoAmount
    }

    fun refundLoadedAmmo(gunStack: ItemStack, creativeMode: Boolean): LegacyAmmoRefundResult {
        val iGun = IGun.getIGunOrNull(gunStack) ?: return LegacyAmmoRefundResult(emptyList())
        if (iGun.useInventoryAmmo(gunStack)) {
            return LegacyAmmoRefundResult(emptyList())
        }
        val currentAmmo = iGun.getCurrentAmmoCount(gunStack)
        if (currentAmmo <= 0) {
            return LegacyAmmoRefundResult(emptyList())
        }
        val gunData = GunDataAccessor.getGunData(iGun.getGunId(gunStack)) ?: return LegacyAmmoRefundResult(emptyList())
        if (iGun.useDummyAmmo(gunStack)) {
            iGun.setCurrentAmmoCount(gunStack, 0)
            if (!gunData.reloadType.equals("fuel", ignoreCase = true)) {
                iGun.addDummyAmmoAmount(gunStack, currentAmmo)
            }
            return LegacyAmmoRefundResult(emptyList())
        }
        if (creativeMode) {
            iGun.setCurrentAmmoCount(gunStack, computeAmmoCapacity(gunStack))
            return LegacyAmmoRefundResult(emptyList())
        }
        if (gunData.reloadType.equals("fuel", ignoreCase = true)) {
            iGun.setCurrentAmmoCount(gunStack, 0)
            return LegacyAmmoRefundResult(emptyList())
        }
        val ammoId = gunData.ammoId ?: run {
            iGun.setCurrentAmmoCount(gunStack, 0)
            return LegacyAmmoRefundResult(emptyList())
        }
        val stackSize = TACZGunPackRuntimeRegistry.getSnapshot().ammos[ammoId]?.stackSize?.coerceAtLeast(1) ?: 1
        val refundStacks = mutableListOf<ItemStack>()
        var remaining = currentAmmo
        while (remaining > 0) {
            val count = minOf(remaining, stackSize)
            val ammoStack = ItemStack(LegacyItems.AMMO, count)
            LegacyItems.AMMO.setAmmoId(ammoStack, ammoId)
            refundStacks += ammoStack
            remaining -= count
        }
        iGun.setCurrentAmmoCount(gunStack, 0)
        return LegacyAmmoRefundResult(refundStacks = refundStacks)
    }
}
