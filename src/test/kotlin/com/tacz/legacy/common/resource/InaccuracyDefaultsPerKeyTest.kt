package com.tacz.legacy.common.resource

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 测试任务书 09 · 组 1：[TACZGunPropertyResolver.parseInaccuracyDefaults] 的逐 key 缺省语义。
 *
 * 上游 `CommonGunIndex.checkInaccuracy` 是 `defaultInaccuracy.forEach(readInaccuracy::putIfAbsent)`，
 * 即"缺的键取默认值"，而不是"整表替换"。本类锁定这一点（含 stand 塌成 0 的回归）。
 */
class InaccuracyDefaultsPerKeyTest {

    private val tolerance = 1e-4f

    private fun json(text: String) = TACZJson.parseObject(text)

    private fun parse(
        inaccuracyJson: String?,
        otherAdjust: Float = 0f,
        aimAdjust: Float = 0f,
    ): Map<String, Float> {
        val raw = if (inaccuracyJson == null) json("{}") else json("""{"inaccuracy":$inaccuracyJson}""")
        return TACZGunPropertyResolver.parseInaccuracyDefaults(raw, otherAdjust, aimAdjust)
    }

    private fun assertProfile(
        actual: Map<String, Float>,
        stand: Float,
        move: Float,
        sneak: Float,
        lie: Float,
        aim: Float,
    ) {
        assertEquals(stand, actual["stand"]!!, tolerance)
        assertEquals(move, actual["move"]!!, tolerance)
        assertEquals(sneak, actual["sneak"]!!, tolerance)
        assertEquals(lie, actual["lie"]!!, tolerance)
        assertEquals(aim, actual["aim"]!!, tolerance)
    }

    /** #1 字段完全缺失 → 全表默认值 */
    @Test
    fun `missing inaccuracy field falls back to the whole default table`() {
        assertProfile(parse(null), stand = 5f, move = 5.75f, sneak = 3.5f, lie = 2.5f, aim = 0.15f)
    }

    /** #2 本轮修复的核心：只写 aim 时其余键必须取默认值，不能塌成 0 */
    @Test
    fun `partial object keeps defaults for the absent keys`() {
        assertProfile(parse("""{"aim": 0.2}"""), stand = 5f, move = 5.75f, sneak = 3.5f, lie = 2.5f, aim = 0.2f)
    }

    /** #3 只写 stand 时其余键取默认值（与 #2 对称，防止实现只补 aim 一侧） */
    @Test
    fun `partial object on the non aim side keeps the other defaults`() {
        assertProfile(parse("""{"stand": 2}"""), stand = 2f, move = 5.75f, sneak = 3.5f, lie = 2.5f, aim = 0.15f)
    }

    /** #4 完整 5 键 → 全部取给定值，默认值不参与 */
    @Test
    fun `full object takes every given value`() {
        val full = """{"stand": 1.5, "move": 2.25, "sneak": 0.75, "lie": 0.5, "aim": 0.05}"""
        assertProfile(parse(full), stand = 1.5f, move = 2.25f, sneak = 0.75f, lie = 0.5f, aim = 0.05f)
    }

    /** #5 fire_mode_adjust：非 aim 键加 otherAdjust，aim 键加 aimAdjust */
    @Test
    fun `fire mode adjust offsets the non aim keys and the aim key separately`() {
        val full = """{"stand": 1.5, "move": 2.25, "sneak": 0.75, "lie": 0.5, "aim": 0.05}"""
        assertProfile(
            parse(full, otherAdjust = 0.5f, aimAdjust = 0.1f),
            stand = 2.0f,
            move = 2.75f,
            sneak = 1.25f,
            lie = 1.0f,
            aim = 0.15f,
        )
    }

    /** #5 补：缺省键同样参与偏移（缺省值 + otherAdjust） */
    @Test
    fun `offsets also apply to keys that came from the default table`() {
        assertProfile(
            parse("""{"aim": 0.2}""", otherAdjust = 0.5f, aimAdjust = 0.1f),
            stand = 5.5f,
            move = 6.25f,
            sneak = 4.0f,
            lie = 3.0f,
            aim = 0.3f,
        )
    }

    /** #6 负值被 coerceAtLeast(0f) 钳到 0 */
    @Test
    fun `negative value is clamped to zero`() {
        assertProfile(parse("""{"stand": -10}"""), stand = 0f, move = 5.75f, sneak = 3.5f, lie = 2.5f, aim = 0.15f)
    }

    /** #6 补：偏移后为负同样钳到 0 */
    @Test
    fun `value driven negative by the offset is clamped to zero`() {
        val full = """{"stand": 0.2, "move": 0.2, "sneak": 0.2, "lie": 0.2, "aim": 0.05}"""
        assertProfile(
            parse(full, otherAdjust = -1f, aimAdjust = -1f),
            stand = 0f,
            move = 0f,
            sneak = 0f,
            lie = 0f,
            aim = 0f,
        )
    }

    /** JSON null 与"缺 key"同义：仍取默认值（floatValueOrNull 同时排除 isJsonNull） */
    @Test
    fun `explicit json null behaves like an absent key`() {
        assertProfile(parse("""{"stand": null, "aim": null}"""), stand = 5f, move = 5.75f, sneak = 3.5f, lie = 2.5f, aim = 0.15f)
    }

    /** inaccuracy 整体不是 object（写成数字）时不抛异常，退回默认表 */
    @Test
    fun `non object inaccuracy is ignored rather than throwing`() {
        assertProfile(TACZGunPropertyResolver.parseInaccuracyDefaults(json("""{"inaccuracy": 3}"""), 0f, 0f), 5f, 5.75f, 3.5f, 2.5f, 0.15f)
    }

    /** 返回表的键集合固定为 5 档，GUI 侧按 key 取值不会漏档 */
    @Test
    fun `result contains exactly the five state keys`() {
        assertEquals(setOf("stand", "move", "sneak", "lie", "aim"), parse(null).keys)
    }
}
