package com.tacz.legacy.client

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.config.LegacyConfigManager
import net.minecraftforge.fml.client.event.ConfigChangedEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

@SideOnly(Side.CLIENT)
internal object ClientConfigEventHandler {
    @SubscribeEvent
    internal fun onConfigChanged(event: ConfigChangedEvent.OnConfigChangedEvent): Unit {
        if (event.modID == TACZLegacy.MOD_ID) {
            LegacyConfigManager.reloadAll()
        }
    }
}
