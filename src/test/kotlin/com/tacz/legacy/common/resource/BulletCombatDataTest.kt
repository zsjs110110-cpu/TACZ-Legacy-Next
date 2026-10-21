package com.tacz.legacy.common.resource

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.util.ResourceLocation
import org.junit.Assert.*
import org.junit.Test

/**
 * BulletCombatData 构造、helper 方法、以及 GunCombatData.fromRawJson 的弹道相关字段解析测试。
 */
class BulletCombatDataTest {

    @Test
    fun `default bullet data has upstream-consistent defaults`() {
        val data = BulletCombatData(
            damage = 5.0f, speed = 5.0f, gravity = 0.0f, friction = 0.01f,
            pierce = 1, lifeSecond = 10.0f, bulletAmount = 1, knockback = 0.0f,
            tracerCountInterval = -1, igniteEntity = false, igniteEntityTime = 2,
            igniteBlock = false,
        )
        assertEquals(5.0f, data.damage, 0.001f)
        assertEquals(5.0f, data.speed, 0.001f)
        assertEquals(0.0f, data.gravity, 0.001f)
        assertEquals(0.01f, data.friction, 0.001f)
        assertEquals(1, data.pierce)
        assertEquals(10.0f, data.lifeSecond, 0.001f)
        assertEquals(1, data.bulletAmount)
        assertEquals(0.0f, data.knockback, 0.001f)
        assertEquals(-1, data.tracerCountInterval)
        assertFalse(data.igniteEntity)
        assertEquals(2, data.igniteEntityTime)
        assertFalse(data.igniteBlock)
    }

    @Test
    fun `getProcessedSpeed divides by 20`() {
        val data = makeBullet(speed = 100.0f)
        assertEquals(5.0f, data.getProcessedSpeed(), 0.001f)
    }

    @Test
    fun `getProcessedSpeed zero speed returns zero`() {
        val data = makeBullet(speed = 0.0f)
        assertEquals(0.0f, data.getProcessedSpeed(), 0.001f)
    }

    @Test
    fun `getProcessedSpeed negative speed clamps to zero`() {
        val data = makeBullet(speed = -10.0f)
        assertEquals(0.0f, data.getProcessedSpeed(), 0.001f)
    }

    @Test
    fun `getLifeTicks converts seconds to ticks`() {
        val data = makeBullet(lifeSecond = 10.0f)
        assertEquals(200, data.getLifeTicks())
    }

    @Test
    fun `getLifeTicks minimum is 1`() {
        val data = makeBullet(lifeSecond = 0.0f)
        assertEquals(1, data.getLifeTicks())
    }

    @Test
    fun `hasTracerAmmo true when interval non-negative`() {
        assertTrue(makeBullet(tracerCountInterval = 0).hasTracerAmmo())
        assertTrue(makeBullet(tracerCountInterval = 3).hasTracerAmmo())
    }

    @Test
    fun `hasTracerAmmo false when interval is -1`() {
        assertFalse(makeBullet(tracerCountInterval = -1).hasTracerAmmo())
    }

    // --- GunCombatData.fromRawJson bullet parsing ---

    @Test
    fun `fromRawJson parses bullet section with all fields`() {
        val json = JsonParser().parse("""
        {
            "bolt": "open_bolt",
            "rpm": 600,
            "bullet": {
                "damage": 12.5,
                "speed": 200,
                "gravity": 0.05,
                "friction": 0.02,
                "pierce": 3,
                "life": 5.0,
                "bullet_amount": 8,
                "knockback": 1.5,
                "tracer_count_interval": 4,
                "ignite": { "ignite_entity": true, "ignite_block": true },
                "ignite_entity_time": 5
            }
        }
        """.trimIndent()).asJsonObject

        val def = TACZGunDataDefinition(
            raw = json,
            ammoId = ResourceLocation("tacz", "9mm"),
            ammoAmount = 30,
            extendedMagAmmoAmount = null,
            roundsPerMinute = 600,
            weight = 1.0f,
            aimTime = 0.2f,
            allowAttachmentTypes = emptyList(),
        )

        val gunData = GunCombatData.fromRawJson(json, def)
        val b = gunData.bulletData
        assertEquals(12.5f, b.damage, 0.001f)
        assertEquals(200.0f, b.speed, 0.001f)
        assertEquals(0.05f, b.gravity, 0.001f)
        assertEquals(0.02f, b.friction, 0.001f)
        assertEquals(3, b.pierce)
        assertEquals(5.0f, b.lifeSecond, 0.001f)
        assertEquals(8, b.bulletAmount)
        assertEquals(1.5f, b.knockback, 0.001f)
        assertEquals(4, b.tracerCountInterval)
        assertTrue(b.igniteEntity)
        assertTrue(b.igniteBlock)
        assertEquals(5, b.igniteEntityTime)
        assertNull(b.explosionData)
    }

    @Test
    fun `fromRawJson parses bullet explosion section`() {
        val json = JsonParser().parse("""
        {
            "bolt": "open_bolt",
            "rpm": 150,
            "bullet": {
                "damage": 20,
                "speed": 80,
                "explosion": {
                    "explode": true,
                    "damage": 120,
                    "radius": 3,
                    "knockback": true,
                    "destroy_block": true,
                    "delay": 30
                }
            }
        }
        """.trimIndent()).asJsonObject

        val def = TACZGunDataDefinition(
            raw = json,
            ammoId = ResourceLocation("tacz", "rpg_rocket"),
            ammoAmount = 1,
            extendedMagAmmoAmount = null,
            roundsPerMinute = 150,
            weight = 6.3f,
            aimTime = 0.2f,
            allowAttachmentTypes = emptyList(),
        )

        val gunData = GunCombatData.fromRawJson(json, def)
        val explosion = gunData.bulletData.explosionData
        assertNotNull(explosion)
        assertTrue(explosion!!.explode)
        assertEquals(120.0f, explosion.damage, 0.001f)
        assertEquals(3.0f, explosion.radius, 0.001f)
        assertTrue(explosion.knockback)
        assertTrue(explosion.destroyBlock)
        assertEquals(30.0f, explosion.delay, 0.001f)
    }

    @Test
    fun `fromRawJson parses bullet extra damage section`() {
        val json = JsonParser().parse(
            """
            {
                "bolt": "open_bolt",
                "rpm": 800,
                "bullet": {
                    "damage": 26,
                    "extra_damage": {
                        "armor_ignore": 0.2,
                        "head_shot_multiplier": 1.5,
                        "damage_adjust": [
                            { "distance": 10, "damage": 28 },
                            { "distance": 30, "damage": 24 },
                            { "distance": "infinite", "damage": 20 }
                        ]
                    }
                }
            }
            """.trimIndent()
        ).asJsonObject

        val def = TACZGunDataDefinition(
            raw = json,
            ammoId = ResourceLocation("tacz", "5_56"),
            ammoAmount = 30,
            extendedMagAmmoAmount = null,
            roundsPerMinute = 800,
            weight = 1.0f,
            aimTime = 0.2f,
            allowAttachmentTypes = emptyList(),
        )

        val extra = GunCombatData.fromRawJson(json, def).bulletData.extraDamageData
        assertNotNull(extra)
        assertEquals(0.2f, extra!!.armorIgnore, 0.001f)
        assertEquals(1.5f, extra.headShotMultiplier, 0.001f)
        assertEquals(3, extra.damageAdjust.size)
        assertEquals(10.0f, extra.damageAdjust[0].distance, 0.001f)
        assertEquals(24.0f, extra.damageAdjust[1].damage, 0.001f)
        assertTrue(extra.damageAdjust[2].distance.isInfinite())
    }

    @Test
    fun `extra damage resolveDamage selects first matching distance band`() {
        val extra = BulletExtraDamageData(
            armorIgnore = 0.25f,
            headShotMultiplier = 1.75f,
            damageAdjust = listOf(
                DistanceDamagePoint(distance = 10.0f, damage = 30.0f),
                DistanceDamagePoint(distance = 20.0f, damage = 24.0f),
                DistanceDamagePoint(distance = Float.POSITIVE_INFINITY, damage = 18.0f),
            ),
        )

        assertEquals(30.0f, extra.resolveDamage(distance = 5.0, fallbackDamage = 12.0f), 0.001f)
        assertEquals(24.0f, extra.resolveDamage(distance = 12.0, fallbackDamage = 12.0f), 0.001f)
        assertEquals(18.0f, extra.resolveDamage(distance = 120.0, fallbackDamage = 12.0f), 0.001f)
    }

    @Test
    fun `extra damage splitDamage splits armor piercing portion`() {
        val extra = BulletExtraDamageData(
            armorIgnore = 0.3f,
            headShotMultiplier = 1.5f,
            damageAdjust = emptyList(),
        )

        val split = extra.splitDamage(totalDamage = 50.0f)
        assertEquals(35.0f, split.normalDamage, 0.001f)
        assertEquals(15.0f, split.armorPiercingDamage, 0.001f)
    }

    @Test
    fun `fromRawJson uses defaults when bullet section is missing`() {
        val json = JsonParser().parse("""{ "bolt": "open_bolt", "rpm": 300 }""").asJsonObject
        val def = TACZGunDataDefinition(
            raw = json,
            ammoId = null,
            ammoAmount = 10,
            extendedMagAmmoAmount = null,
            roundsPerMinute = 300,
            weight = 1.0f,
            aimTime = 0.2f,
            allowAttachmentTypes = emptyList(),
        )
        val gunData = GunCombatData.fromRawJson(json, def)
        val b = gunData.bulletData
        assertEquals(5.0f, b.damage, 0.001f)
        assertEquals(5.0f, b.speed, 0.001f)
        assertEquals(0.0f, b.gravity, 0.001f)
        assertEquals(0.01f, b.friction, 0.001f)
        assertEquals(1, b.pierce)
        assertEquals(10.0f, b.lifeSecond, 0.001f)
        assertEquals(1, b.bulletAmount)
        assertEquals(0.0f, b.knockback, 0.001f)
        assertEquals(-1, b.tracerCountInterval)
        assertFalse(b.igniteEntity)
        assertFalse(b.igniteBlock)
        assertNull(b.extraDamageData)
        assertNull(b.explosionData)
    }

    @Test
    fun `fromRawJson shotgun bullet amount and speed produce correct processed speed`() {
        val json = JsonParser().parse("""
        {
            "bolt": "open_bolt",
            "rpm": 60,
            "bullet": {
                "damage": 3.0,
                "speed": 120,
                "bullet_amount": 12
            }
        }
        """.trimIndent()).asJsonObject
        val def = TACZGunDataDefinition(
            raw = json,
            ammoId = ResourceLocation("tacz", "12gauge"),
            ammoAmount = 6,
            extendedMagAmmoAmount = null,
            roundsPerMinute = 60,
            weight = 2.0f,
            aimTime = 0.3f,
            allowAttachmentTypes = emptyList(),
        )
        val gunData = GunCombatData.fromRawJson(json, def)
        assertEquals(12, gunData.bulletData.bulletAmount)
        assertEquals(6.0f, gunData.bulletData.getProcessedSpeed(), 0.001f) // 120/20
    }

    private fun makeBullet(
        damage: Float = 5.0f,
        speed: Float = 5.0f,
        gravity: Float = 0.0f,
        friction: Float = 0.01f,
        pierce: Int = 1,
        lifeSecond: Float = 10.0f,
        bulletAmount: Int = 1,
        knockback: Float = 0.0f,
        tracerCountInterval: Int = -1,
        igniteEntity: Boolean = false,
        igniteEntityTime: Int = 2,
        igniteBlock: Boolean = false,
    ): BulletCombatData = BulletCombatData(
        damage = damage, speed = speed, gravity = gravity, friction = friction,
        pierce = pierce, lifeSecond = lifeSecond, bulletAmount = bulletAmount,
        knockback = knockback, tracerCountInterval = tracerCountInterval,
        igniteEntity = igniteEntity, igniteEntityTime = igniteEntityTime,
        igniteBlock = igniteBlock,
    )
}
