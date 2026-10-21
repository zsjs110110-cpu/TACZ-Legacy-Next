package com.tacz.legacy.common.config

import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyConfigParsingTest {
    @Test
    fun `head shot config parser keeps valid bounding boxes`() {
        HeadShotAabbConfigRead.reload(
            listOf(
                "tacz:test_target [0, 1, 2, 3, 4, 5]",
                "broken entry",
            )
        )

        val parsed = HeadShotAabbConfigRead.getAabb(ResourceLocation("tacz", "test_target"))
        assertNotNull(parsed)
        assertEquals(0.0, parsed!!.minX, 0.0001)
        assertEquals(4.0, parsed.maxY, 0.0001)
        assertEquals(5.0, parsed.maxZ, 0.0001)
    }

    @Test
    fun `interact key config trims blanks and blacklist wins`() {
        InteractKeyConfigRead.reload(
            blockWhitelist = listOf(" tacz:target ", ""),
            entityWhitelist = listOf("minecraft:armor_stand"),
            blockBlacklist = listOf("tacz:target"),
            entityBlacklist = emptyList(),
        )

        assertFalse(InteractKeyConfigRead.canInteractBlock(ResourceLocation("tacz", "target")))
        assertTrue(InteractKeyConfigRead.canInteractEntity(ResourceLocation("minecraft", "armor_stand")))
    }
}
