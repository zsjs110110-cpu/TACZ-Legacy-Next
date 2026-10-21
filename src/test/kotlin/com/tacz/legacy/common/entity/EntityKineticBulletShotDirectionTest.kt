package com.tacz.legacy.common.entity

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class EntityKineticBulletShotDirectionTest {
    @Test
    fun `zero pitch and yaw points forward`() {
        val direction = EntityKineticBullet.computeShotDirection(
            pitch = 0.0,
            yaw = 0.0,
            spreadX = 0.0,
            spreadY = 0.0,
        )

        assertEquals(0.0, direction.x, 1.0e-6)
        assertEquals(0.0, direction.y, 1.0e-6)
        assertEquals(1.0, direction.z, 1.0e-6)
    }

    @Test
    fun `positive yaw rotates toward negative x`() {
        val direction = EntityKineticBullet.computeShotDirection(
            pitch = 0.0,
            yaw = 90.0,
            spreadX = 0.0,
            spreadY = 0.0,
        )

        assertEquals(-1.0, direction.x, 1.0e-6)
        assertEquals(0.0, direction.y, 1.0e-6)
        assertEquals(0.0, direction.z, 1.0e-6)
    }

    @Test
    fun `positive pitch rotates downward`() {
        val direction = EntityKineticBullet.computeShotDirection(
            pitch = 45.0,
            yaw = 0.0,
            spreadX = 0.0,
            spreadY = 0.0,
        )
        val diagonal = sqrt(0.5)

        assertEquals(0.0, direction.x, 1.0e-6)
        assertEquals(-diagonal, direction.y, 1.0e-6)
        assertEquals(diagonal, direction.z, 1.0e-6)
    }
}
