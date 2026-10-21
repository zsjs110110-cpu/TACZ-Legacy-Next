package com.tacz.legacy.util.math

import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * R-05：第一人称定位插值偶发产出非有限矩阵。
 *
 * 根因在 JOML `Matrix4f#getEulerAnglesZYX`：它算 `y` 用的是
 * `atan2(-m02, sqrt(1 - m02*m02))`，矩阵落在奇异位姿、浮点误差让
 * `|m02|` 略越 1 时，`sqrt` 的参数变成负数 -> NaN，整条定位链随后
 * 全是 NaN。修复是把 sqrt 的参数钳到不小于 0（见 [MathUtil.getEulerAngles]），
 * 公式其余部分与 JOML 完全一致。
 *
 * 这里同时守两件事：越界时不再 NaN；正常位姿下与 JOML 结果逐项相同
 * （防止再次出现"按记忆写公式"导致取错列的问题）。
 */
class MathUtilLerpTest {

    private fun isFinite(v: Vector3f): Boolean =
        v.x().isFinite() && v.y().isFinite() && v.z().isFinite()

    private fun isFinite(m: Matrix4f): Boolean {
        for (v in floatArrayOf(
            m.m00(), m.m01(), m.m02(), m.m03(),
            m.m10(), m.m11(), m.m12(), m.m13(),
            m.m20(), m.m21(), m.m22(), m.m23(),
            m.m30(), m.m31(), m.m32(), m.m33(),
        )) {
            if (!v.isFinite()) {
                return false
            }
        }
        return true
    }

    private fun jomlEuler(matrix: Matrix4f): Vector3f {
        val dest = Vector3f()
        matrix.getEulerAnglesZYX(dest)
        return dest
    }

    @Test
    fun `euler angles stay finite when m02 slightly exceeds minus one`() {
        // 奇异位姿 + 浮点误差：JOML 的 sqrt(1 - m02^2) 会吃到负数
        val matrix = Matrix4f().identity()
        matrix.m02(-1.0000001f)

        assertTrue("JOML should be non-finite here, otherwise the test no longer proves anything",
            !isFinite(jomlEuler(matrix)))

        val euler = MathUtil.getEulerAngles(matrix)
        assertTrue("euler=$euler", isFinite(euler))
        assertEquals((Math.PI / 2).toFloat(), euler.y(), 1.0e-4f)
    }

    @Test
    fun `euler angles stay finite when m02 slightly exceeds one`() {
        val matrix = Matrix4f().identity()
        matrix.m02(1.0000001f)

        assertTrue(!isFinite(jomlEuler(matrix)))

        val euler = MathUtil.getEulerAngles(matrix)
        assertTrue("euler=$euler", isFinite(euler))
        assertEquals((-Math.PI / 2).toFloat(), euler.y(), 1.0e-4f)
    }

    @Test
    fun `euler angles match JOML exactly on ordinary poses`() {
        val random = Random(20261021)
        for (i in 0 until 500) {
            val matrix = Matrix4f().identity()
                .rotateX(random.nextFloat() * 6.0f - 3.0f)
                .rotateY(random.nextFloat() * 6.0f - 3.0f)
                .rotateZ(random.nextFloat() * 6.0f - 3.0f)

            val expected = jomlEuler(matrix)
            val actual = MathUtil.getEulerAngles(matrix)

            assertTrue("case $i expected=$expected actual=$actual", isFinite(expected))
            assertEquals("case $i x", expected.x(), actual.x(), 1.0e-5f)
            assertEquals("case $i y", expected.y(), actual.y(), 1.0e-5f)
            assertEquals("case $i z", expected.z(), actual.z(), 1.0e-5f)
        }
    }

    @Test
    fun `matrix lerp of two rigid transforms stays finite`() {
        val from = Matrix4f().identity().rotateX(-0.3f).rotateY(0.7f).translate(0.1f, 1.2f, -0.4f)
        val to = Matrix4f().identity().rotateX(1.1f).rotateY(-0.2f).translate(-0.5f, 0.9f, 0.3f)

        for (step in 0..10) {
            val alpha = step / 10.0f
            val result = Matrix4f(from)
            MathUtil.applyMatrixLerp(from, to, result, alpha)
            assertTrue("alpha=$alpha result=$result", isFinite(result))
        }
    }

    @Test
    fun `matrix lerp of a near gimbal pose stays finite`() {
        // 把目标姿态摆到接近 ±pi/2 的奇异位姿，这正是 R-05 触发的地方
        val from = Matrix4f().identity().translate(0.0f, 1.4f, 0.0f).rotateX(0.0f).rotateY(0.0f)
        val to = Matrix4f().identity().translate(0.0f, 0.1f, 0.2f).rotateX((Math.PI / 2).toFloat())

        for (step in 0..20) {
            val alpha = step / 20.0f
            val result = Matrix4f(from)
            MathUtil.applyMatrixLerp(from, to, result, alpha)
            assertTrue("alpha=$alpha result=$result", isFinite(result))
        }
    }

    @Test
    fun `matrix lerp of a rigid transform into itself is a no-op`() {
        val from = Matrix4f().identity().rotateX(0.4f).rotateZ(-0.9f).translate(0.2f, 1.5f, 0.0f)
        val result = Matrix4f(from)

        MathUtil.applyMatrixLerp(from, Matrix4f(from), result, 0.5f)

        assertTrue(isFinite(result))
        assertTrue(abs(result.m00() - from.m00()) < 1.0e-5f)
    }
}
