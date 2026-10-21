package com.tacz.legacy.common.resource

import com.tacz.legacy.api.modifier.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 测试任务书 09 · 组 2：[TACZAttachmentModifierRegistry.readModifiers] 的字段优先级。
 *
 * 上游 reader 的约定是「新字段（对象形式，完整四元组）优先，`*_addend` / `recoil_modifier`
 * 这类旧字段只在新字段缺席时回落」。本类逐条锁定该优先级，以及条目级隔离所依赖的"坏数据必须抛"。
 */
class AttachmentModifierReaderPriorityTest {

    private fun read(
        jsonText: String,
        issues: MutableList<String>? = null,
    ): Map<String, JsonPropertyHolder> =
        TACZAttachmentModifierRegistry
            .readModifiers(jsonText, TACZJson.parseObject(jsonText), issues)
            .mapValues { (_, property) -> JsonPropertyHolder(property.getValue()) }

    /** reader 输出的 value 类型各异（Modifier / Map / TACZRecoilModifierValue / TACZIgniteValue / JsonElement），先擦成 Any? */
    private data class JsonPropertyHolder(val value: Any?)

    @Suppress("UNCHECKED_CAST")
    private fun recoilOf(modifiers: Map<String, JsonPropertyHolder>): TACZRecoilModifierValue =
        requireNotNull(modifiers["recoil"]?.value) { "expected a recoil modifier" } as TACZRecoilModifierValue

    @Suppress("UNCHECKED_CAST")
    private fun inaccuracyOf(modifiers: Map<String, JsonPropertyHolder>): Map<String, Modifier> =
        requireNotNull(modifiers["inaccuracy"]?.value) { "expected an inaccuracy modifier" } as Map<String, Modifier>

    // ---- 1 / 2：recoil ----

    /** #1 新字段优先：同时给 recoil 与 recoil_modifier 时只取 recoil 的 multiplier，旧字段的 percent 被忽略 */
    @Test
    fun `recoil object wins over the deprecated recoil_modifier`() {
        val modifiers = read(
            """
            {
              "recoil": { "pitch": { "multiplier": 0.8 } },
              "recoil_modifier": { "pitch": -0.3 }
            }
            """.trimIndent(),
        )
        val recoil = recoilOf(modifiers)
        assertEquals(0.8, recoil.pitch.multiplier, 1e-6)
        // 旧字段 -0.3 若被误读成 percent，这里就会是 -0.3
        assertEquals(0.0, recoil.pitch.percent, 1e-6)
        // 未写的 yaw 取中性 Modifier
        assertEquals(1.0, recoil.yaw.multiplier, 1e-6)
        assertEquals(0.0, recoil.yaw.percent, 1e-6)
    }

    /** #2 旧字段回落：recoil_modifier 的 pitch/yaw 语义是 percent（上游 RecoilModifier.java:54-55） */
    @Test
    fun `recoil_modifier is read as percent when the new field is absent`() {
        val recoil = recoilOf(read("""{ "recoil_modifier": { "pitch": -0.3, "yaw": -0.2 } }"""))
        assertEquals(-0.3, recoil.pitch.percent, 1e-6)
        assertEquals(-0.2, recoil.yaw.percent, 1e-6)
        assertEquals(1.0, recoil.pitch.multiplier, 1e-6)
        assertEquals(0.0, recoil.pitch.addend, 1e-6)
    }

    /** recoil 对象里缺 yaw 时 yaw 取中性 Modifier，而不是整条丢弃 */
    @Test
    fun `recoil object with only pitch still yields a neutral yaw`() {
        val recoil = recoilOf(read("""{ "recoil": { "pitch": { "addend": -0.5 } } }"""))
        assertEquals(-0.5, recoil.pitch.addend, 1e-6)
        assertEquals(Modifier(), recoil.yaw)
    }

    /** 两个字段都没有 → 不产出 recoil modifier */
    @Test
    fun `absent recoil fields produce no recoil modifier`() {
        assertNull(read("""{ "weight": 1.0 }""")["recoil"])
    }

    // ---- 3 / 4：inaccuracy ----

    /** #3 新字段优先：inaccuracy 存在时 inaccuracy_addend 被完全忽略 */
    @Test
    fun `inaccuracy object wins over the deprecated inaccuracy_addend`() {
        val map = inaccuracyOf(read("""{ "inaccuracy": { "stand": 2 }, "inaccuracy_addend": -0.125 }"""))
        // 新字段走 GSON → Modifier：stand 不是 Modifier 的字段，被忽略后得到中性四元组；
        // 关键断言是 addend ≠ -0.125（旧字段没参与）。
        assertNotNull(map["default"])
        assertEquals(0.0, map.getValue("default").addend, 1e-6)
        assertEquals(1.0, map.getValue("default").multiplier, 1e-6)
    }

    /** #3 补：新字段写成完整四元组时按四元组取值，旧字段仍被忽略 */
    @Test
    fun `inaccuracy object in full modifier form is parsed and the old field ignored`() {
        val map = inaccuracyOf(read("""{ "inaccuracy": { "addend": -0.2, "percent": 0.1 }, "inaccuracy_addend": -0.125 }"""))
        assertEquals(-0.2, map.getValue("default").addend, 1e-6)
        assertEquals(0.1, map.getValue("default").percent, 1e-6)
    }

    /** #4 旧字段回落：inaccuracy_addend → default 键的 addend */
    @Test
    fun `inaccuracy_addend is the fallback for the default key`() {
        val map = inaccuracyOf(read("""{ "inaccuracy_addend": -0.125 }"""))
        assertEquals(-0.125, map.getValue("default").addend, 1e-6)
        assertEquals(1.0, map.getValue("default").multiplier, 1e-6)
    }

    /** 专属档位键 aim/sneak/lie 各自独立解析 */
    @Test
    fun `per state inaccuracy keys are parsed separately`() {
        val map = inaccuracyOf(
            read(
                """
                {
                  "inaccuracy_addend": -0.1,
                  "aim_inaccuracy": { "multiplier": 0.5 },
                  "sneak_inaccuracy": { "addend": -0.3 },
                  "lie_inaccuracy": { "percent": -0.25 }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(setOf("default", "aim", "sneak", "lie"), map.keys)
        assertEquals(-0.1, map.getValue("default").addend, 1e-6)
        assertEquals(0.5, map.getValue("aim").multiplier, 1e-6)
        assertEquals(-0.3, map.getValue("sneak").addend, 1e-6)
        assertEquals(-0.25, map.getValue("lie").percent, 1e-6)
    }

    /** 既无 inaccuracy 也无 inaccuracy_addend 且无专属键 → 不产出 inaccuracy modifier */
    @Test
    fun `absent inaccuracy fields produce no inaccuracy modifier`() {
        assertNull(read("""{ "weight": 1.0 }""")["inaccuracy"])
    }

    // ---- 5 / 6：ignite ----

    /** #5 布尔简写同时点亮 entity 与 block */
    @Test
    fun `ignite boolean shorthand sets both flags`() {
        val ignite = read("""{ "ignite": true }""")["ignite"]?.value as? TACZIgniteValue
        assertEquals(TACZIgniteValue(igniteEntity = true, igniteBlock = true), ignite)
    }

    /** #6 对象形式按 entity / block 字段分别取值 */
    @Test
    fun `ignite object form sets the flags independently`() {
        val ignite = read("""{ "ignite": { "entity": true, "block": false } }""")["ignite"]?.value as? TACZIgniteValue
        assertEquals(TACZIgniteValue(igniteEntity = true, igniteBlock = false), ignite)
    }

    /** ignite 缺省 → 不产出 */
    @Test
    fun `absent ignite field produces no ignite modifier`() {
        assertNull(read("""{ "weight": 1.0 }""")["ignite"])
    }

    // ---- 7：非法数值类型：reader 级隔离后不再外抛，而是跳过该 modifier 并记录 issue ----

    /** 把四元组字段写成裸数字：该 modifier 被跳过 */
    @Test
    fun `bare number where a modifier object is expected is skipped`() {
        val issues = mutableListOf<String>()
        val modifiers = read("""{ "damage": 1.5 }""", issues)
        assertNull(modifiers["damage"])
        assertTrue(issues.any { it.contains("modifier 'damage'") && it.contains("parse failed") })
    }

    /** 字符串形式的 modifier 同样被跳过 */
    @Test
    fun `bare string where a modifier object is expected is skipped`() {
        val issues = mutableListOf<String>()
        val modifiers = read("""{ "weight_modifier": "2" }""", issues)
        assertNull(modifiers["weight_modifier"])
        assertTrue(issues.any { it.contains("modifier 'weight_modifier'") })
    }

    /** 坏字段不影响同一条目的好字段 */
    @Test
    fun `bad modifier does not prevent good fields`() {
        val issues = mutableListOf<String>()
        val modifiers = read("""{ "weight": 0.4, "damage": 1.5 }""", issues)
        assertNull(modifiers["damage"])
        assertNotNull(modifiers["weight_modifier"])
        assertTrue(issues.isNotEmpty())
    }

    /**
     * 任务书 #7 字面示例是 `{"weight": "2"}`。这里把它单独测出来：
     * `weight` 是 weight_modifier 的 optionalField，走 `asDouble` 分支，
     * Gson 的 JsonPrimitive 会把数字字符串 "2" 强制解析成 2.0，因此**不抛**。
     * 结论记录在 test-task-09-result.md（不修改生产代码）。
     */
    @Test
    fun `string weight is tolerated rather than thrown`() {
        val modifiers = read("""{ "weight": "2" }""")
        assertEquals(2.0, (modifiers["weight_modifier"]?.value as Modifier).addend, 1e-6)
    }
}
