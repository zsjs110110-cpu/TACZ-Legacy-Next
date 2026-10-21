package com.tacz.legacy.common.registry

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.entity.LegacyEntities
import net.minecraftforge.event.RegistryEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.registry.EntityEntry

internal object EntityRegisterer {
    @SubscribeEvent
    internal fun onRegister(event: RegistryEvent.Register<EntityEntry>): Unit {
        LegacyEntities.registerAll(event.registry)
        TACZLegacy.logger.info("Registered TACZ foundation entities.")
    }
}
