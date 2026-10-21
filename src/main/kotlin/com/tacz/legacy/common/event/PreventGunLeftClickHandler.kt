package com.tacz.legacy.common.event

import com.tacz.legacy.api.item.IGun
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

internal object PreventGunLeftClickHandler {
    @SubscribeEvent
    fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        val player = event.entityPlayer ?: return
        val stack = player.heldItemMainhand
        if (!stack.isEmpty && stack.item is IGun) {
            event.isCanceled = true
        }
    }
}