package com.tacz.legacy.common.registry

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.sound.SoundManager
import net.minecraft.util.ResourceLocation
import net.minecraft.util.SoundEvent
import net.minecraftforge.event.RegistryEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent

internal object LegacySoundEvents {
    internal val GUN: SoundEvent = create("gun")
    internal val TARGET_BLOCK_HIT: SoundEvent = create("target_block_hit")
    internal val BULLET_WHIZ: SoundEvent = create("bullet_whiz")

    internal val allSounds: List<SoundEvent> = listOf(
        GUN,
        TARGET_BLOCK_HIT,
        BULLET_WHIZ,
    )

    private fun create(path: String): SoundEvent {
        val id = ResourceLocation(TACZLegacy.MOD_ID, path)
        return SoundEvent(id).setRegistryName(id)
    }
}

internal object SoundRegisterer {
    @SubscribeEvent
    internal fun onRegister(event: RegistryEvent.Register<SoundEvent>): Unit {
        LegacySoundEvents.allSounds.forEach(event.registry::register)
        TACZLegacy.logger.info("Registered {} TACZ foundation sounds: {}", LegacySoundEvents.allSounds.size, SoundManager.knownKeys())
    }
}
