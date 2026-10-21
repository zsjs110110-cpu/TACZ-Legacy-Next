package com.tacz.legacy.client.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TACZOpenALSoundEngineTest {
    @Test
    fun `legacy minecraft alias resolves to vanilla bridge backend`() {
        assertEquals(TACZAudioBackendMode.VANILLA_MINECRAFT, TACZAudioBackendMode.fromProperty("legacy-minecraft"))
    }

    @Test
    fun `decode default pack rpg7 put away ogg to pcm`() {
        val resourcePath = "/assets/tacz/custom/tacz_default_gun/assets/tacz/tacz_sounds/rpg7/rpg7_put_away.ogg"
        val pcm = javaClass.getResourceAsStream(resourcePath).use { stream ->
            assertNotNull("Expected bundled OGG sample at $resourcePath", stream)
            TACZOpenALSoundEngine.decodeOggToPcm(stream!!)
        }
        assertNotNull(pcm)
        assertTrue(pcm!!.data.isNotEmpty())
        assertTrue(pcm.channels > 0)
        assertTrue(pcm.sampleRate > 0)
    }
}