package com.tacz.legacy.common.registry

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.block.LegacyBlocks
import net.minecraft.block.Block
import net.minecraftforge.event.RegistryEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

internal object BlockRegisterer {
    @SubscribeEvent
    internal fun onRegister(event: RegistryEvent.Register<Block>): Unit {
        LegacyBlocks.registerAll(event.registry)
        TACZLegacy.logger.info("Registered {} TACZ foundation blocks.", LegacyBlocks.allBlocks.size)
    }
}
