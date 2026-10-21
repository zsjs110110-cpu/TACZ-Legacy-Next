package com.tacz.legacy.client.event

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPersonRenderGunEventTransformTest {
    @Test
    fun `build positioning inverse returns identity for missing path`() {
        val matrix = FirstPersonRenderMatrices.buildPositioningNodeInverse(null)

        assertEquals(1.0f, matrix.m00(), 1.0e-6f)
        assertEquals(1.0f, matrix.m11(), 1.0e-6f)
        assertEquals(1.0f, matrix.m22(), 1.0e-6f)
        assertEquals(1.0f, matrix.m33(), 1.0e-6f)
        assertEquals(0.0f, matrix.m30(), 1.0e-6f)
        assertEquals(0.0f, matrix.m31(), 1.0e-6f)
        assertEquals(0.0f, matrix.m32(), 1.0e-6f)
    }

    @Test
    fun `build positioning inverse converts root node pivot to camera space`() {
        val idleView = FirstPersonRenderMatrices.PositioningNode(
            x = 4.0f,
            y = 20.0f,
            z = -8.0f,
            xRot = 0.0f,
            yRot = 0.0f,
            zRot = 0.0f,
            hasParent = false,
        )

        val matrix = FirstPersonRenderMatrices.buildPositioningNodeInverse(listOf(idleView))

        assertEquals(-0.25f, matrix.m30(), 1.0e-6f)
        assertEquals(0.25f, matrix.m31(), 1.0e-6f)
        assertEquals(0.5f, matrix.m32(), 1.0e-6f)
        assertTrue(matrix.m00() > 0.99f)
        assertTrue(matrix.m11() > 0.99f)
        assertTrue(matrix.m22() > 0.99f)
    }

    @Test
    fun `append path keeps gun mount nodes before attachment view nodes`() {
        val scopePos = listOf(
            FirstPersonRenderMatrices.PositioningNode(0f, 24f, 0f, 0f, 0f, 0f, false),
            FirstPersonRenderMatrices.PositioningNode(2f, 0f, 0f, 0f, 0f, 0f, true),
        )
        val scopeView = listOf(
            FirstPersonRenderMatrices.PositioningNode(1f, -1f, 3f, 0f, 0f, 0f, false),
        )

        val combined = FirstPersonRenderMatrices.appendPath(scopePos, scopeView)

        assertEquals(3, combined!!.size)
        assertEquals(2.0f, combined[1].x, 1.0e-6f)
        assertEquals(1.0f, combined[2].x, 1.0e-6f)
        assertEquals(3.0f, combined[2].z, 1.0e-6f)
    }

    @Test
    fun `resolve scope view switch index wraps zoom number and converts to zero based index`() {
        assertEquals(1, FirstPersonRenderMatrices.resolveScopeViewSwitchIndex(intArrayOf(2, 1), 0))
        assertEquals(0, FirstPersonRenderMatrices.resolveScopeViewSwitchIndex(intArrayOf(2, 1), 1))
        assertEquals(1, FirstPersonRenderMatrices.resolveScopeViewSwitchIndex(intArrayOf(2, 1), 2))
        assertEquals(0, FirstPersonRenderMatrices.resolveScopeViewSwitchIndex(null, 99))
    }

    @Test
    fun `build aiming positioning transform lerps from idle to scope view`() {
        val idleView = listOf(
            FirstPersonRenderMatrices.PositioningNode(0f, 24f, 0f, 0f, 0f, 0f, false),
        )
        val aimingView = listOf(
            FirstPersonRenderMatrices.PositioningNode(16f, 24f, 0f, 0f, 0f, 0f, false),
        )

        val matrix = FirstPersonRenderMatrices.buildAimingPositioningTransform(idleView, aimingView, 0.25f)

        assertEquals(-0.25f, matrix.m30(), 1.0e-6f)
        assertEquals(0.0f, matrix.m31(), 1.0e-6f)
        assertEquals(0.0f, matrix.m32(), 1.0e-6f)
    }
}