package com.tacz.legacy.common.resource

import com.google.gson.JsonObject
import com.tacz.legacy.api.modifier.Modifier
import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 测试任务书 09 · 组 3 / 组 4：
 * - [TACZAttachmentModifierRegistry.evalInaccuracy] 的 `default` 键作用域；
 * - [TACZGunPropertyResolver.attachmentDataFor] 的「命中即整份替换」语义（C10）。
 */
class InaccuracyEvalScopeAndExclusiveReplacementTest {

    private val tolerance = 1e-4f

    private val defaults = linkedMapOf(
        "stand" to 5f,
        "move" to 5.75f,
        "sneak" to 3.5f,
        "lie" to 2.5f,
        "aim" to 0.15f,
    )

    private fun definition(weight: Float, marker: String): TACZAttachmentDataDefinition =
        TACZAttachmentDataDefinition(
            raw = JsonObject().apply { addProperty("marker", marker) },
            weight = weight,
            extendedMagLevel = 0,
            modifiers = emptyMap(),
        )

    private fun snapshotWithAttachment(id: ResourceLocation, data: TACZAttachmentDataDefinition) =
        TACZRuntimeSnapshot.EMPTY.copy(attachments = mapOf(id to TACZLoadedAttachment(id, TACZAttachmentIndexDefinition(), data)))

    // ---- 组 3 ----

    /** #1 通用 default 只作用于 stand/move（对齐上游 InaccuracyModifier.java:62-75） */
    @Test
    fun `default key only affects stand and move`() {
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(
            listOf(mapOf("default" to Modifier(addend = -0.125))),
            defaults,
        )
        assertEquals(4.875f, result["stand"]!!, tolerance)
        assertEquals(5.625f, result["move"]!!, tolerance)
        // 不回落到通用值
        assertEquals(0.15f, result["aim"]!!, tolerance)
        assertEquals(3.5f, result["sneak"]!!, tolerance)
        assertEquals(2.5f, result["lie"]!!, tolerance)
    }

    /** #2 无配件 modifier 时结果等于缺省表 */
    @Test
    fun `empty modifier list returns the defaults unchanged`() {
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(emptyList(), defaults)
        assertEquals(defaults, result)
    }

    /**
     * #3 两个配件同时给 default：ModifierEvaluator.eval 的累积规则是
     * addend 累加到基准值、percent 累加到 1.0、multiplier 连乘，最后 (base+Σaddend) × (1+Σpercent) × Πmultiplier。
     * 所以 -0.1 与 ×0.5 → stand (5-0.1)×0.5=2.45、move (5.75-0.1)×0.5=2.825。
     */
    @Test
    fun `multiple default modifiers accumulate addend then multiplier`() {
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(
            listOf(mapOf("default" to Modifier(addend = -0.1)), mapOf("default" to Modifier(multiplier = 0.5))),
            defaults,
        )
        assertEquals(2.45f, result["stand"]!!, tolerance)
        assertEquals(2.825f, result["move"]!!, tolerance)
        assertEquals(3.5f, result["sneak"]!!, tolerance)
        assertEquals(0.15f, result["aim"]!!, tolerance)
    }

    /** percent 与 multiplier 同时存在时的累积顺序：(base+addend) × (1+percent) × multiplier */
    @Test
    fun `percent accumulates onto one and multiplies after addend`() {
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(
            listOf(mapOf("default" to Modifier(addend = -0.5, percent = -0.2, multiplier = 2.0))),
            defaults,
        )
        // (5 - 0.5) × 0.8 × 2.0
        assertEquals(7.2f, result["stand"]!!, tolerance)
    }

    /** 专属档位键优先于 default：default 不再叠加到 aim/sneak/lie，反过来专属键存在时用专属键 */
    @Test
    fun `state specific keys take precedence over default`() {
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(
            listOf(
                mapOf(
                    "default" to Modifier(addend = -0.125),
                    "aim" to Modifier(addend = 1.0),
                    "sneak" to Modifier(multiplier = 2.0),
                ),
            ),
            defaults,
        )
        assertEquals(4.875f, result["stand"]!!, tolerance)
        assertEquals(1.15f, result["aim"]!!, tolerance)
        assertEquals(7.0f, result["sneak"]!!, tolerance)
        // lie 既无专属键也不吃 default
        assertEquals(2.5f, result["lie"]!!, tolerance)
    }

    /** multiplier 负值被 coerceAtLeast(0.0) 钳到 0 */
    @Test
    fun `negative multiplier is clamped to zero by the evaluator`() {
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(
            listOf(mapOf("default" to Modifier(multiplier = -1.0))),
            defaults,
        )
        assertEquals(0f, result["stand"]!!, tolerance)
        // 默认表里 sneak = 3.5（不是 5）；default 键只作用于 stand/move
        assertEquals(3.5f, result["sneak"]!!, tolerance)
    }

    /** 输出的键顺序/集合与 defaults 一致，GUI 直接遍历不会漏档 */
    @Test
    fun `output keeps the same keys as the defaults table`() {
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(listOf(mapOf("default" to Modifier(addend = 1.0))), defaults)
        assertEquals(defaults.keys.toList(), result.keys.toList())
    }

    // ---- 组 4 ----

    private val attachmentId = ResourceLocation("tacz", "test_attachment")

    /** #1 exclusive 命中 → 整份替换，即使配件索引里也有 data 且值不同 */
    @Test
    fun `exclusive hit replaces the whole attachment data`() {
        val exclusive = definition(weight = 2.0f, marker = "exclusive")
        val own = definition(weight = 0.4f, marker = "own")
        val snapshot = snapshotWithAttachment(attachmentId, own)

        val resolved = TACZGunPropertyResolver.attachmentDataFor(snapshot, attachmentId, mapOf(attachmentId to exclusive))

        assertNotNull(resolved)
        assertSame(exclusive, resolved)
        assertEquals(2.0f, requireNotNull(resolved).weight, tolerance)
        assertEquals("exclusive", resolved.raw.get("marker").asString)
    }

    /** #1 补：exclusive 命中时，即便配件不存在也不受影响 */
    @Test
    fun `exclusive hit does not require the attachment to be indexed`() {
        val exclusive = definition(weight = 2.0f, marker = "exclusive")
        val resolved = TACZGunPropertyResolver.attachmentDataFor(TACZRuntimeSnapshot.EMPTY, attachmentId, mapOf(attachmentId to exclusive))
        assertSame(exclusive, resolved)
    }

    /** #2 未命中 → 回落配件索引 data */
    @Test
    fun `miss falls back to the indexed attachment data`() {
        val own = definition(weight = 0.4f, marker = "own")
        val snapshot = snapshotWithAttachment(attachmentId, own)
        val otherId = ResourceLocation("tacz", "other_attachment")

        val resolved = TACZGunPropertyResolver.attachmentDataFor(snapshot, attachmentId, mapOf(otherId to definition(9f, "exclusive-other")))

        assertSame(own, resolved)
    }

    /** #3 两边都没有 → null */
    @Test
    fun `unknown id with no exclusive entry resolves to null`() {
        assertNull(TACZGunPropertyResolver.attachmentDataFor(TACZRuntimeSnapshot.EMPTY, attachmentId, emptyMap()))
    }

    /** exclusive 的 value 与配件 ID 大小写/命名空间都必须严格相等才命中 */
    @Test
    fun `exclusive lookup is exact id match`() {
        val exclusive = definition(weight = 2.0f, marker = "exclusive")
        val own = definition(weight = 0.4f, marker = "own")
        val snapshot = snapshotWithAttachment(attachmentId, own)
        val nearMiss = mapOf(ResourceLocation("tacz", "test_attachment_v2") to exclusive)

        assertSame(own, TACZGunPropertyResolver.attachmentDataFor(snapshot, attachmentId, nearMiss))
    }
}
