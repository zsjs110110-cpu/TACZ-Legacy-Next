package com.tacz.legacy.common.item

import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.SoundEvents
import net.minecraft.inventory.ClickType
import net.minecraft.inventory.Container
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemStack
import net.minecraft.util.SoundCategory
import net.minecraftforge.items.IItemHandlerModifiable
import net.minecraftforge.items.SlotItemHandler
import kotlin.math.min

/**
 * 弹药盒与背包槽位之间的交互。统一遵循「读副本 → 校验 → 提交 → 回调」流程：
 * - 读副本：修改前复制受影响的物品栈，避免直接改动 [Slot.stack] / handler 返回栈的 NBT；
 * - 校验：按操作方向区分权限（放入走 isItemValid/getItemStackLimit，取出走 canTakeStack）；
 * - 提交：通过 [Slot.putStack] / [Slot.decrStackSize] 显式写回；无法写回的槽位不报告成功；
 * - 回调：提交后调用 onSlotChanged 与取出时的 onTake，并播放音效。
 */
internal object AmmoBoxInventoryInteractions {
    @JvmStatic
    fun tryHandleSlotClick(
        container: Container,
        slotId: Int,
        dragType: Int,
        clickType: ClickType,
        player: EntityPlayer,
    ): Boolean {
        if (clickType != ClickType.PICKUP || dragType != 1 || slotId < 0 || slotId >= container.inventorySlots.size) {
            return false
        }
        val slot = container.getSlot(slotId) ?: return false

        val cursor = player.inventory.itemStack
        val slotStack = slot.stack

        if (!cursor.isEmpty && cursor.item is IAmmoBox) {
            val boxItem = cursor.item as IAmmoBox
            // 光标持有弹药盒：目标槽位为空时向其装入弹药（放入方向），否则从槽位取出弹药入盒（取出方向）
            return if (slotStack.isEmpty) {
                withdrawAmmo(cursor, boxItem, slot, player)
            } else {
                depositAmmo(cursor, boxItem, slot, slotStack, player)
            }
        }

        if (!slotStack.isEmpty && slotStack.item is IAmmoBox) {
            val boxItem = slotStack.item as IAmmoBox
            // 槽位持有弹药盒：需写回槽位中的盒子副本，后端不可写时直接放弃自定义操作
            if (!canWriteSlot(slot)) {
                return false
            }
            return if (cursor.isEmpty) {
                withdrawAmmoIntoCursor(slotStack, boxItem, slot, player)
            } else {
                depositAmmoFromCursor(slotStack, boxItem, cursor, slot, player)
            }
        }

        return false
    }

    /**
     * 槽位是否可通过 putStack 写入：普通槽直接可写；
     * SlotItemHandler 仅当后端实现 IItemHandlerModifiable 时允许，否则 putStack 会抛 ClassCastException。
     */
    private fun canWriteSlot(slot: Slot): Boolean =
        slot !is SlotItemHandler || slot.itemHandler is IItemHandlerModifiable

    /** 把弹药盒副本提交回槽位并触发回调 */
    private fun commitBoxToSlot(slot: Slot, boxCopy: ItemStack) {
        slot.putStack(boxCopy)
        slot.onSlotChanged()
    }

    /** 光标持有弹药盒、目标槽位为空 → 向槽位装入弹药 */
    private fun withdrawAmmo(
        ammoBox: ItemStack,
        boxItem: IAmmoBox,
        slot: Slot,
        player: EntityPlayer,
    ): Boolean {
        if (boxItem.isAllTypeCreative(ammoBox) || boxItem.isCreative(ammoBox)) {
            return false
        }
        val boxAmmoId = boxItem.getAmmoId(ammoBox)
        if (boxAmmoId == DefaultAssets.EMPTY_AMMO_ID) {
            return false
        }
        val boxAmmoCount = boxItem.getAmmoCount(ammoBox)
        if (boxAmmoCount <= 0) {
            return false
        }
        val ammoDef = TACZGunPackRuntimeRegistry.getSnapshot().ammos[boxAmmoId] ?: return false
        // 放入方向：槽位必须可写，且需通过准入与容量校验（含弹药物品自身最大堆叠数）
        if (!canWriteSlot(slot)) {
            return false
        }
        val template = ItemStack(LegacyItems.AMMO)
        (LegacyItems.AMMO as IAmmo).setAmmoId(template, boxAmmoId)
        if (!slot.isItemValid(template)) {
            return false
        }
        val takeCount = min(ammoDef.stackSize, boxAmmoCount).coerceAtMost(template.maxStackSize).coerceAtMost(slot.getItemStackLimit(template))
        if (takeCount <= 0) {
            return false
        }
        val takeAmmo = ItemStack(LegacyItems.AMMO, takeCount)
        (LegacyItems.AMMO as IAmmo).setAmmoId(takeAmmo, boxAmmoId)
        // 读副本并扣减弹药盒，提交后再写回光标
        val boxCopy = ammoBox.copy()
        val remainCount = boxAmmoCount - takeCount
        boxItem.setAmmoCount(boxCopy, remainCount)
        if (remainCount <= 0) {
            boxItem.setAmmoId(boxCopy, DefaultAssets.EMPTY_AMMO_ID)
        }
        // 提交
        slot.putStack(takeAmmo)
        player.inventory.itemStack = boxCopy
        // 回调
        slot.onSlotChanged()
        playRemoveSound(player)
        return true
    }

    /** 光标持有弹药盒、槽位持有弹药 → 从槽位取出弹药入盒 */
    private fun depositAmmo(
        ammoBox: ItemStack,
        boxItem: IAmmoBox,
        slot: Slot,
        slotStack: ItemStack,
        player: EntityPlayer,
    ): Boolean {
        if (boxItem.isAllTypeCreative(ammoBox)) {
            return false
        }
        val slotAmmo = slotStack.item as? IAmmo ?: return false
        val slotAmmoId = slotAmmo.getAmmoId(slotStack)
        if (slotAmmoId == DefaultAssets.EMPTY_AMMO_ID) {
            return false
        }
        val boxAmmoId = boxItem.getAmmoId(ammoBox)
        if (boxAmmoId != DefaultAssets.EMPTY_AMMO_ID && slotAmmoId != boxAmmoId) {
            return false
        }
        // 先确认弹药定义存在再写入弹药盒类型，避免失败时空弹药盒被错误绑定类型
        val ammoDef = TACZGunPackRuntimeRegistry.getSnapshot().ammos[slotAmmoId] ?: return false
        // 取出方向：校验玩家可从此槽取出
        if (!slot.canTakeStack(player)) {
            return false
        }
        val boxCopy = ammoBox.copy()
        if (boxAmmoId == DefaultAssets.EMPTY_AMMO_ID) {
            boxItem.setAmmoId(boxCopy, slotAmmoId)
        }
        if (boxItem.isCreative(boxCopy)) {
            boxItem.setAmmoCount(boxCopy, Int.MAX_VALUE)
            player.inventory.itemStack = boxCopy
            slot.onSlotChanged()
            playInsertSound(player)
            return true
        }
        val boxAmmoCount = boxItem.getAmmoCount(boxCopy)
        val levelMultiplier = boxItem.getAmmoLevel(boxCopy) + 1
        val maxSize = ammoDef.stackSize * LegacyConfigManager.server.ammoBoxStackSize * levelMultiplier
        val needCount = maxSize - boxAmmoCount
        if (needCount <= 0) {
            return false
        }
        val requestCount = min(slotStack.count, needCount)
        if (requestCount <= 0) {
            return false
        }
        // 提取（decrStackSize 内部已写回 handler），按实际提取量入账
        val extracted = slot.decrStackSize(requestCount)
        val takeCount = extracted.count
        if (takeCount <= 0) {
            return false
        }
        boxItem.setAmmoCount(boxCopy, boxAmmoCount + takeCount)
        player.inventory.itemStack = boxCopy
        // 回调
        slot.onSlotChanged()
        slot.onTake(player, extracted)
        playInsertSound(player)
        return true
    }

    /** 槽位持有弹药盒、光标为空 → 从盒取弹入光标（写回槽位中的盒子副本） */
    private fun withdrawAmmoIntoCursor(
        ammoBox: ItemStack,
        boxItem: IAmmoBox,
        slot: Slot,
        player: EntityPlayer,
    ): Boolean {
        if (boxItem.isAllTypeCreative(ammoBox) || boxItem.isCreative(ammoBox)) {
            return false
        }
        val boxAmmoId = boxItem.getAmmoId(ammoBox)
        if (boxAmmoId == DefaultAssets.EMPTY_AMMO_ID) {
            return false
        }
        val boxAmmoCount = boxItem.getAmmoCount(ammoBox)
        if (boxAmmoCount <= 0) {
            return false
        }
        val ammoDef = TACZGunPackRuntimeRegistry.getSnapshot().ammos[boxAmmoId] ?: return false
        // 取出方向：校验玩家可从此槽取出
        if (!slot.canTakeStack(player)) {
            return false
        }
        val takeCount = min(ammoDef.stackSize, boxAmmoCount)
        val takeAmmo = ItemStack(LegacyItems.AMMO, takeCount)
        (LegacyItems.AMMO as IAmmo).setAmmoId(takeAmmo, boxAmmoId)
        // 读副本并扣减弹药盒，提交回槽位
        val boxCopy = ammoBox.copy()
        val remainCount = boxAmmoCount - takeCount
        boxItem.setAmmoCount(boxCopy, remainCount)
        if (remainCount <= 0) {
            boxItem.setAmmoId(boxCopy, DefaultAssets.EMPTY_AMMO_ID)
        }
        commitBoxToSlot(slot, boxCopy)
        // 光标为空，直接放置提取物
        player.inventory.itemStack = takeAmmo
        playRemoveSound(player)
        return true
    }

    /** 槽位持有弹药盒、光标持有弹药 → 从光标取弹入盒（写回槽位中的盒子副本） */
    private fun depositAmmoFromCursor(
        ammoBox: ItemStack,
        boxItem: IAmmoBox,
        cursor: ItemStack,
        slot: Slot,
        player: EntityPlayer,
    ): Boolean {
        if (boxItem.isAllTypeCreative(ammoBox)) {
            return false
        }
        val cursorAmmo = cursor.item as? IAmmo ?: return false
        val cursorAmmoId = cursorAmmo.getAmmoId(cursor)
        if (cursorAmmoId == DefaultAssets.EMPTY_AMMO_ID) {
            return false
        }
        val boxAmmoId = boxItem.getAmmoId(ammoBox)
        if (boxAmmoId != DefaultAssets.EMPTY_AMMO_ID && cursorAmmoId != boxAmmoId) {
            return false
        }
        // 先确认弹药定义存在再写入弹药盒类型，避免失败时空弹药盒被错误绑定类型
        val ammoDef = TACZGunPackRuntimeRegistry.getSnapshot().ammos[cursorAmmoId] ?: return false
        val boxCopy = ammoBox.copy()
        if (boxAmmoId == DefaultAssets.EMPTY_AMMO_ID) {
            boxItem.setAmmoId(boxCopy, cursorAmmoId)
        }
        if (boxItem.isCreative(boxCopy)) {
            boxItem.setAmmoCount(boxCopy, Int.MAX_VALUE)
            commitBoxToSlot(slot, boxCopy)
            playInsertSound(player)
            return true
        }
        val boxAmmoCount = boxItem.getAmmoCount(boxCopy)
        val levelMultiplier = boxItem.getAmmoLevel(boxCopy) + 1
        val maxSize = ammoDef.stackSize * LegacyConfigManager.server.ammoBoxStackSize * levelMultiplier
        val needCount = maxSize - boxAmmoCount
        if (needCount <= 0) {
            return false
        }
        val takeCount = min(cursor.count, needCount)
        if (takeCount <= 0) {
            return false
        }
        // 提交：写回槽位中的盒子副本，再从光标消耗
        boxItem.setAmmoCount(boxCopy, boxAmmoCount + takeCount)
        commitBoxToSlot(slot, boxCopy)
        cursor.shrink(takeCount)
        player.inventory.itemStack = if (cursor.isEmpty) ItemStack.EMPTY else cursor
        playInsertSound(player)
        return true
    }

    private fun playInsertSound(player: EntityPlayer) {
        player.world.playSound(
            null,
            player.posX,
            player.posY,
            player.posZ,
            SoundEvents.ENTITY_ITEMFRAME_ADD_ITEM,
            SoundCategory.PLAYERS,
            0.8f,
            0.8f + player.world.rand.nextFloat() * 0.4f,
        )
    }

    private fun playRemoveSound(player: EntityPlayer) {
        player.world.playSound(
            null,
            player.posX,
            player.posY,
            player.posZ,
            SoundEvents.ENTITY_ITEMFRAME_REMOVE_ITEM,
            SoundCategory.PLAYERS,
            0.8f,
            0.8f + player.world.rand.nextFloat() * 0.4f,
        )
    }
}
