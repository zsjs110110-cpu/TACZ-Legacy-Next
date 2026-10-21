package com.tacz.legacy.common.resource

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Test

class GunDataAccessorReloadTimingTest {
    @Test
    fun `parses tactical and empty reload timings from feed and cooldown blocks`() {
        val raw = json(
            """
            {
              "bolt": "closed_bolt",
              "rpm": 600,
              "aim_time": 0.2,
              "reload": {
                "type": "magazine",
                "feed": {
                  "empty": 2.48,
                  "tactical": 1.16
                },
                "cooldown": {
                  "empty": 3.10,
                  "tactical": 1.98
                }
              },
              "bullet": {
                "damage": 5.0,
                "speed": 80.0
              }
            }
            """.trimIndent(),
        )

        val data = GunCombatData.fromRawJson(raw, definition(raw))

        assertEquals(1.16f, data.reloadFeedingTimeS, 0.0001f)
        assertEquals(1.98f, data.reloadFinishingTimeS, 0.0001f)
        assertEquals(2.48f, data.emptyReloadFeedingTimeS, 0.0001f)
        assertEquals(3.10f, data.emptyReloadFinishingTimeS, 0.0001f)
    }

    @Test
    fun `falls back to legacy feeding and finishing fields when tactical blocks are absent`() {
        val raw = json(
            """
            {
              "bolt": "closed_bolt",
              "rpm": 600,
              "aim_time": 0.2,
              "reload": {
                "feeding_time": 1.25,
                "finishing_time": 0.75,
                "empty_feeding_time": 2.25,
                "empty_finishing_time": 1.15
              },
              "bullet": {
                "damage": 5.0,
                "speed": 80.0
              }
            }
            """.trimIndent(),
        )

        val data = GunCombatData.fromRawJson(raw, definition(raw))

        assertEquals(1.25f, data.reloadFeedingTimeS, 0.0001f)
        assertEquals(0.75f, data.reloadFinishingTimeS, 0.0001f)
        assertEquals(2.25f, data.emptyReloadFeedingTimeS, 0.0001f)
        assertEquals(1.15f, data.emptyReloadFinishingTimeS, 0.0001f)
    }

    private fun json(text: String): JsonObject = JsonParser().parse(text).asJsonObject

    private fun definition(raw: JsonObject): TACZGunDataDefinition = TACZGunDataDefinition(
        raw = raw,
        ammoId = ResourceLocation("demo", "ammo"),
        ammoAmount = 30,
        extendedMagAmmoAmount = null,
        roundsPerMinute = 600,
        weight = 1.0f,
        aimTime = 0.2f,
        allowAttachmentTypes = emptyList(),
    )
}
