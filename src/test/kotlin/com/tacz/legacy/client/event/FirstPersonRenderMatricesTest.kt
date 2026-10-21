package com.tacz.legacy.client.event

import org.joml.Matrix4f
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPersonRenderMatricesTest {

    @Test
    fun `resolvePositioningPaths keeps both explicit paths`() {
        val idle = listOf(
            FirstPersonRenderMatrices.PositioningNode(0f, 0f, 0f, 0f, 0f, 0f, false),
        )
        val aiming = listOf(
            FirstPersonRenderMatrices.PositioningNode(1f, 2f, 3f, 0.1f, 0.2f, 0.3f, true),
        )

        val resolved = FirstPersonRenderMatrices.resolvePositioningPaths(idle, aiming)

        assertSame(idle, resolved.idlePath)
        assertSame(aiming, resolved.aimingPath)
        assertFalse(resolved.usedIdleFallback)
        assertFalse(resolved.usedAimingFallback)
    }

    @Test
    fun `resolvePositioningPaths reuses aiming when idle path missing`() {
        val aiming = listOf(
            FirstPersonRenderMatrices.PositioningNode(1f, 2f, 3f, 0.1f, 0.2f, 0.3f, true),
        )

        val resolved = FirstPersonRenderMatrices.resolvePositioningPaths(null, aiming)

        assertSame(aiming, resolved.idlePath)
        assertSame(aiming, resolved.aimingPath)
        assertTrue(resolved.usedIdleFallback)
        assertFalse(resolved.usedAimingFallback)
    }

    @Test
    fun `resolvePositioningPaths reuses idle when aiming path missing`() {
        val idle = listOf(
            FirstPersonRenderMatrices.PositioningNode(0f, 0f, 0f, 0f, 0f, 0f, false),
        )

        val resolved = FirstPersonRenderMatrices.resolvePositioningPaths(idle, null)

        assertSame(idle, resolved.idlePath)
        assertSame(idle, resolved.aimingPath)
        assertFalse(resolved.usedIdleFallback)
        assertTrue(resolved.usedAimingFallback)
    }

    @Test
    fun `isFinite rejects non finite matrices`() {
        val matrix = Matrix4f().identity()
        matrix.m30(Float.NaN)

        assertFalse(FirstPersonRenderMatrices.isFinite(matrix))
        assertTrue(FirstPersonRenderMatrices.isFinite(Matrix4f().identity()))
    }
}
