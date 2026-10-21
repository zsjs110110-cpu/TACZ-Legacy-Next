package com.tacz.legacy.client.audio

import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent

internal object TACZClientAudioHooks {
    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        TACZOpenALSoundEngine.tick()
    }
}
