package com.tacz.legacy.common.foundation

import net.minecraftforge.fml.relauncher.Side
import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationBootstrapTest {
    @Test
    fun `client preinit plan includes client runtime marker`() {
        assertEquals(
            listOf(
                BootstrapStep.PRELOAD_CONFIG,
                BootstrapStep.CORE_CONFIG,
                BootstrapStep.DEFAULT_PACK_EXPORT,
                BootstrapStep.PROXY_PRE_INIT,
                BootstrapStep.CLIENT_RUNTIME_READY,
            ),
            FoundationBootstrap.plannedPreInitSteps(Side.CLIENT)
        )
    }

    @Test
    fun `server preinit plan skips client runtime marker`() {
        assertEquals(
            listOf(
                BootstrapStep.PRELOAD_CONFIG,
                BootstrapStep.CORE_CONFIG,
                BootstrapStep.DEFAULT_PACK_EXPORT,
                BootstrapStep.PROXY_PRE_INIT,
            ),
            FoundationBootstrap.plannedPreInitSteps(Side.SERVER)
        )
    }
}
