package com.tacz.legacy.common.registry

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.item.LegacyItems
import net.minecraft.item.Item
import net.minecraftforge.event.RegistryEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

internal object ItemRegisterer {
    @SubscribeEvent
    internal fun onRegister(event: RegistryEvent.Register<Item>): Unit {
        LegacyItems.registerAll(event.registry)
        TACZLegacy.logger.info("Registered {} TACZ foundation items.", LegacyItems.allItems.size)
    }
}
