package com.tacz.legacy.common.foundation

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tacz.legacy.common.resource.TACZAttachmentDataDefinition
import com.tacz.legacy.common.resource.TACZAttachmentIndexDefinition
import com.tacz.legacy.common.resource.TACZDisplayDefinition
import com.tacz.legacy.common.resource.TACZGunDataDefinition
import com.tacz.legacy.common.resource.TACZGunIndexDefinition
import com.tacz.legacy.common.resource.TACZLoadedAttachment
import com.tacz.legacy.common.resource.TACZLoadedGun
import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusedSmokePlannerTest {
    @Test
    fun `buildPlan prefers animated non explosive gun and compatible attachment`() {
        val regularGunId = ResourceLocation("tacz", "aug")
        val explosiveGunId = ResourceLocation("tacz", "rpg7")
        val attachmentId = ResourceLocation("tacz", "red_dot")
        val regularDisplayId = ResourceLocation("tacz", "aug_display")
        val explosiveDisplayId = ResourceLocation("tacz", "rpg7_display")

        val snapshot = TACZRuntimeSnapshot(
            packs = emptyMap(),
            packInfos = emptyMap(),
            guns = linkedMapOf(
                regularGunId to loadedGun(
                    id = regularGunId,
                    displayId = regularDisplayId,
                    raw = json(
                        """
                        {
                          "fire_mode": ["auto"],
                          "bolt": "closed_bolt",
                          "bullet": { "damage": 7.5, "speed": 24.0 }
                        }
                        """.trimIndent()
                    ),
                    sort = 10,
                ),
                explosiveGunId to loadedGun(
                    id = explosiveGunId,
                    displayId = explosiveDisplayId,
                    raw = json(
                        """
                        {
                          "fire_mode": ["single"],
                          "bolt": "closed_bolt",
                          "bullet": {
                            "damage": 24.0,
                            "speed": 12.0,
                            "explosion": { "explode": true, "radius": 3.0, "damage": 28.0 }
                          }
                        }
                        """.trimIndent()
                    ),
                    sort = 20,
                    type = "rpg",
                ),
            ),
            attachments = linkedMapOf(
                attachmentId to loadedAttachment(attachmentId, type = "scope", sort = 5)
            ),
            ammos = emptyMap(),
            blocks = emptyMap(),
            recipes = emptyMap(),
            recipeFilters = emptyMap(),
            attachmentTags = emptyMap(),
            allowAttachmentTags = mapOf(regularGunId to setOf(attachmentId.toString())),
            gunDisplays = linkedMapOf(
                regularDisplayId to TACZDisplayDefinition(
                    regularDisplayId,
                    json(
                        """
                        {
                          "use_default_animation": "rifle",
                          "state_machine": "tacz:aug_state_machine",
                          "sounds": { "shoot": "tacz:aug_shoot", "reload": "tacz:aug_reload" }
                        }
                        """.trimIndent()
                    ),
                ),
                explosiveDisplayId to TACZDisplayDefinition(
                    explosiveDisplayId,
                    json(
                        """
                        {
                          "use_default_animation": "rifle",
                          "sounds": { "shoot": "tacz:rpg_shoot" }
                        }
                        """.trimIndent()
                    ),
                ),
            ),
            ammoDisplays = emptyMap(),
            attachmentDisplays = emptyMap(),
            blockDisplays = emptyMap(),
            translations = emptyMap(),
            dataScripts = emptyMap(),
            issues = emptyList(),
        )

        val plan = FocusedSmokePlanner.buildPlan(snapshot)
        assertNotNull(plan)
        assertEquals(regularGunId, plan!!.regularGunId)
        assertEquals(explosiveGunId, plan.explosiveGunId)
        assertEquals(listOf(attachmentId), plan.attachmentIds)
        assertEquals("rifle", plan.regularDisplay?.defaultAnimationType)
        assertEquals(ResourceLocation("tacz", "aug_state_machine"), plan.regularDisplay?.stateMachineId)
        assertTrue(plan.regularDisplay?.soundKeys?.contains("shoot") == true)
    }

    @Test
    fun `buildPlan allows packs without explosive sample`() {
        val regularGunId = ResourceLocation("tacz", "m1911")
        val displayId = ResourceLocation("tacz", "m1911_display")
        val snapshot = TACZRuntimeSnapshot(
            packs = emptyMap(),
            packInfos = emptyMap(),
            guns = linkedMapOf(
                regularGunId to loadedGun(
                    id = regularGunId,
                    displayId = displayId,
                    raw = json(
                        """
                        {
                          "fire_mode": ["semi"],
                          "bolt": "closed_bolt",
                          "bullet": { "damage": 6.0, "speed": 20.0 }
                        }
                        """.trimIndent()
                    ),
                    sort = 10,
                    type = "pistol",
                )
            ),
            attachments = emptyMap(),
            ammos = emptyMap(),
            blocks = emptyMap(),
            recipes = emptyMap(),
            recipeFilters = emptyMap(),
            attachmentTags = emptyMap(),
            allowAttachmentTags = emptyMap(),
            gunDisplays = linkedMapOf(
                displayId to TACZDisplayDefinition(
                    displayId,
                    json(
                        """
                        {
                          "use_default_animation": "pistol",
                          "sounds": { "shoot": "tacz:pistol_shoot" }
                        }
                        """.trimIndent()
                    ),
                )
            ),
            ammoDisplays = emptyMap(),
            attachmentDisplays = emptyMap(),
            blockDisplays = emptyMap(),
            translations = emptyMap(),
            dataScripts = emptyMap(),
            issues = emptyList(),
        )

        val plan = FocusedSmokePlanner.buildPlan(snapshot)
        assertNotNull(plan)
        assertEquals(regularGunId, plan!!.regularGunId)
        assertNull(plan.explosiveGunId)
        assertTrue(plan.attachmentIds.isEmpty())
        assertEquals("pistol", plan.regularDisplay?.defaultAnimationType)
    }
    @Test
    fun `buildPlan prioritizes render heavy attachments over first compatible item`() {
        val regularGunId = ResourceLocation("tacz", "scar_h")
        val regularDisplayId = ResourceLocation("tacz", "scar_h_display")
        val scopeId = ResourceLocation("tacz", "scope_vudu")
        val stockId = ResourceLocation("tacz", "stock_moe")
        val muzzleId = ResourceLocation("tacz", "muzzle_brake")
        val magazineId = ResourceLocation("tacz", "extended_mag")

        val snapshot = TACZRuntimeSnapshot(
            packs = emptyMap(),
            packInfos = emptyMap(),
            guns = linkedMapOf(
                regularGunId to loadedGun(
                    id = regularGunId,
                    displayId = regularDisplayId,
                    raw = json(
                        """
                        {
                          "fire_mode": ["auto"],
                          "bolt": "closed_bolt",
                          "bullet": { "damage": 8.0, "speed": 24.0 }
                        }
                        """.trimIndent()
                    ),
                    sort = 10,
                    type = "rifle",
                )
            ),
            attachments = linkedMapOf(
                magazineId to loadedAttachment(magazineId, type = "magazine", sort = 1),
                scopeId to loadedAttachment(scopeId, type = "scope", sort = 2),
                stockId to loadedAttachment(stockId, type = "stock", sort = 3),
                muzzleId to loadedAttachment(muzzleId, type = "muzzle", sort = 4),
            ),
            ammos = emptyMap(),
            blocks = emptyMap(),
            recipes = emptyMap(),
            recipeFilters = emptyMap(),
            attachmentTags = emptyMap(),
            allowAttachmentTags = mapOf(
                regularGunId to setOf(
                    magazineId.toString(),
                    scopeId.toString(),
                    stockId.toString(),
                    muzzleId.toString(),
                )
            ),
            gunDisplays = linkedMapOf(
                regularDisplayId to TACZDisplayDefinition(
                    regularDisplayId,
                    json(
                        """
                        {
                          "use_default_animation": "rifle",
                          "state_machine": "tacz:scar_h_state_machine",
                          "sounds": { "shoot": "tacz:scar_h_shoot" }
                        }
                        """.trimIndent()
                    ),
                )
            ),
            ammoDisplays = emptyMap(),
            attachmentDisplays = linkedMapOf(
                scopeId to TACZDisplayDefinition(
                    scopeId,
                    json(
                        """
                        {
                          "scope": true,
                          "sight": true,
                          "views": [2, 1],
                          "zoom": [6.5, 1.35]
                        }
                        """.trimIndent()
                    ),
                ),
                stockId to TACZDisplayDefinition(
                    stockId,
                    json(
                        """
                        {
                          "adapter": "ar_stock_adapter"
                        }
                        """.trimIndent()
                    ),
                ),
                muzzleId to TACZDisplayDefinition(
                    muzzleId,
                    json(
                        """
                        {
                          "show_muzzle": true
                        }
                        """.trimIndent()
                    ),
                ),
            ),
            blockDisplays = emptyMap(),
            translations = emptyMap(),
            dataScripts = emptyMap(),
            issues = emptyList(),
        )

        val plan = FocusedSmokePlanner.buildPlan(snapshot)
        assertNotNull(plan)
        assertEquals(listOf(scopeId, stockId, muzzleId), plan!!.attachmentIds)
    }

    @Test
    fun `buildPlan prefers rpg style explosive sample over grenade launcher`() {
        val regularGunId = ResourceLocation("tacz", "ak12")
        val grenadeLauncherId = ResourceLocation("tacz", "m320")
        val rocketLauncherId = ResourceLocation("tacz", "rpg7")

        val snapshot = TACZRuntimeSnapshot(
            packs = emptyMap(),
            packInfos = emptyMap(),
            guns = linkedMapOf(
                regularGunId to loadedGun(
                    id = regularGunId,
                    displayId = ResourceLocation("tacz", "ak12_display"),
                    raw = json(
                        """
                        {
                          "fire_mode": ["auto"],
                          "bolt": "closed_bolt",
                          "bullet": { "damage": 7.5, "speed": 24.0 }
                        }
                        """.trimIndent()
                    ),
                    sort = 10,
                    type = "rifle",
                ),
                grenadeLauncherId to loadedGun(
                    id = grenadeLauncherId,
                    displayId = ResourceLocation("tacz", "m320_display"),
                    raw = explosiveRaw(),
                    sort = 20,
                    type = "launcher",
                ),
                rocketLauncherId to loadedGun(
                    id = rocketLauncherId,
                    displayId = ResourceLocation("tacz", "rpg7_display"),
                    raw = explosiveRaw(),
                    sort = 30,
                    type = "rpg",
                ),
            ),
            attachments = emptyMap(),
            ammos = emptyMap(),
            blocks = emptyMap(),
            recipes = emptyMap(),
            recipeFilters = emptyMap(),
            attachmentTags = emptyMap(),
            allowAttachmentTags = emptyMap(),
            gunDisplays = linkedMapOf(
                ResourceLocation("tacz", "ak12_display") to TACZDisplayDefinition(
                    ResourceLocation("tacz", "ak12_display"),
                    json(
                        """
                        {
                          "use_default_animation": "rifle",
                          "sounds": { "shoot": "tacz:ak12_shoot" }
                        }
                        """.trimIndent()
                    ),
                ),
                ResourceLocation("tacz", "m320_display") to TACZDisplayDefinition(
                    ResourceLocation("tacz", "m320_display"),
                    json("""{ "sounds": { "shoot": "tacz:m320_shoot" } }"""),
                ),
                ResourceLocation("tacz", "rpg7_display") to TACZDisplayDefinition(
                    ResourceLocation("tacz", "rpg7_display"),
                    json("""{ "sounds": { "shoot": "tacz:rpg7_shoot" } }"""),
                ),
            ),
            ammoDisplays = emptyMap(),
            attachmentDisplays = emptyMap(),
            blockDisplays = emptyMap(),
            translations = emptyMap(),
            dataScripts = emptyMap(),
            issues = emptyList(),
        )

        val plan = FocusedSmokePlanner.buildPlan(snapshot)
        assertNotNull(plan)
        assertEquals(rocketLauncherId, plan!!.explosiveGunId)
    }

    private fun loadedGun(
        id: ResourceLocation,
        displayId: ResourceLocation,
        raw: JsonObject,
        sort: Int,
        type: String = "rifle",
    ): TACZLoadedGun = TACZLoadedGun(
        id = id,
        index = TACZGunIndexDefinition(
            display = displayId,
            type = type,
            itemType = "modern_kinetic",
            sort = sort,
        ),
        data = TACZGunDataDefinition(
            raw = raw,
            ammoId = ResourceLocation("tacz", "sample_ammo"),
            ammoAmount = 30,
            extendedMagAmmoAmount = null,
            roundsPerMinute = 600,
            weight = 1.0f,
            aimTime = 0.2f,
            allowAttachmentTypes = listOf("scope"),
        ),
    )

    private fun loadedAttachment(
        id: ResourceLocation,
        type: String,
        sort: Int,
    ): TACZLoadedAttachment = TACZLoadedAttachment(
        id = id,
        index = TACZAttachmentIndexDefinition(display = id, type = type, sort = sort),
        data = TACZAttachmentDataDefinition(raw = JsonObject(), weight = 0.15f, extendedMagLevel = 0, modifiers = emptyMap()),
    )

    private fun json(text: String): JsonObject = JsonParser().parse(text).asJsonObject

        private fun explosiveRaw(): JsonObject = json(
                """
                {
                    "fire_mode": ["single"],
                    "bolt": "closed_bolt",
                    "bullet": {
                        "damage": 20.0,
                        "speed": 10.0,
                        "explosion": { "explode": true, "radius": 3.0, "damage": 28.0 }
                    }
                }
                """.trimIndent()
        )
}
