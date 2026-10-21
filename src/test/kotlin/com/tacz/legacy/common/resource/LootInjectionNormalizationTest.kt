package com.tacz.legacy.common.resource

import com.google.gson.JsonParser
import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B4-22 / B11：`tacz_loot_injectors/<id>.json` 归一化（[normalizeLootInjection]）的纯函数单测。
 *
 * 只解析内存里的 JSON 字符串，不触碰 Minecraft 世界/注册表，也不依赖枪包目录。
 * 覆盖审计点名的六个场景：
 * 1. `loot_tables` 与 `loot_table` 同时存在时走 else-if（只认 `loot_tables`）；
 * 2. `loot_tables` 的字符串与数组两种写法；
 * 3. 空 `pools` 数组必须保留为"空数组"，不能变成 null；
 * 4. `pools` 缺失/为 null/非数组 -> null + issue；
 * 5. 表名无命名空间（`foo`、`:foo`）必须跳过并记 issue，绝不补 `minecraft:`；
 * 6. 同一 `loot_tables` 数组里的重复表名去重。
 */
class LootInjectionNormalizationTest {
    private val injectionId = ResourceLocation("demo", "loot/injector")

    private fun normalize(json: String): TACZLootInjectionNormalization {
        val element = JsonParser().parse(json)
        assertTrue("测试输入必须是 JSON 对象: $json", element.isJsonObject)
        return normalizeLootInjection(injectionId, element.asJsonObject)
    }

    private fun issueText(result: TACZLootInjectionNormalization): String =
        result.issues.joinToString(separator = "\n")

    @Test
    fun `loot_tables wins over loot_table when both are present`() {
        val result = normalize(
            """{"loot_tables":["demo:from_multi"],"loot_table":"demo:from_single","pools":[]}"""
        )

        // else-if：写了 loot_tables 就完全不看 loot_table
        assertEquals(listOf(ResourceLocation("demo", "from_multi")), result.lootTables)
        assertFalse(issueText(result).contains("from_single"))
    }

    @Test
    fun `invalid loot_tables still shadows loot_table`() {
        val result = normalize(
            """{"loot_tables":42,"loot_table":"demo:from_single","pools":[]}"""
        )

        // 只要 loot_tables 字段存在（哪怕类型非法），loot_table 也不参与解析
        assertTrue(result.lootTables.isEmpty())
        assertTrue(issueText(result).contains("must be a string or array"))
        assertTrue(issueText(result).contains("must define a namespaced"))
    }

    @Test
    fun `loot_table single string is used when loot_tables is absent`() {
        val result = normalize("""{"loot_table":"demo:single_field","pools":[]}""")

        assertEquals(listOf(ResourceLocation("demo", "single_field")), result.lootTables)
    }

    @Test
    fun `loot_tables accepts a single string`() {
        val result = normalize("""{"loot_tables":"demo:string_form","pools":[]}""")

        assertEquals(listOf(ResourceLocation("demo", "string_form")), result.lootTables)
    }

    @Test
    fun `loot_tables accepts an array`() {
        val result = normalize("""{"loot_tables":["demo:alpha","demo:beta"],"pools":[]}""")

        assertEquals(
            listOf(ResourceLocation("demo", "alpha"), ResourceLocation("demo", "beta")),
            result.lootTables,
        )
    }

    @Test
    fun `empty pools array is preserved as an empty array`() {
        val result = normalize("""{"loot_table":"demo:empty_pools","pools":[]}""")

        val pools = requireNotNull(result.pools)
        assertEquals(0, pools.size())
        // 空数组是合法数据，不应产生 "pools 缺失/非法" 类 issue
        assertTrue(issueText(result), result.issues.isEmpty())
    }

    @Test
    fun `missing pools yields null pools with an issue`() {
        val result = normalize("""{"loot_table":"demo:no_pools"}""")

        assertNull(result.pools)
        assertTrue(issueText(result).contains("'pools'"))
    }

    @Test
    fun `null pools yields null pools with an issue`() {
        val result = normalize("""{"loot_table":"demo:null_pools","pools":null}""")

        assertNull(result.pools)
        assertTrue(issueText(result).contains("must define 'pools' as a JSON array"))
    }

    @Test
    fun `non-array pools yields null pools with an issue`() {
        val result = normalize("""{"loot_table":"demo:bad_pools","pools":{}}""")

        assertNull(result.pools)
        assertTrue(issueText(result).contains("is not a JSON array"))
    }

    @Test
    fun `loot table without namespace is skipped instead of defaulting to minecraft`() {
        val result = normalize(
            """{"loot_tables":["foo",":foo","demo:valid"],"pools":[]}"""
        )

        // 无命名空间的两个条目被跳过，合法条目保留
        assertEquals(listOf(ResourceLocation("demo", "valid")), result.lootTables)
        val text = issueText(result)
        assertTrue(text.contains("'foo' has no namespace"))
        assertTrue(text.contains("':foo' has no namespace"))
        // 关键：绝不能静默落到 minecraft:foo
        assertTrue(result.lootTables.none { it.namespace == "minecraft" })
    }

    @Test
    fun `duplicate loot table ids are deduplicated`() {
        val result = normalize(
            """{"loot_tables":["demo:dup","demo:dup","demo:dup"],"pools":[]}"""
        )

        assertEquals(listOf(ResourceLocation("demo", "dup")), result.lootTables)
        assertTrue(issueText(result), result.issues.isEmpty())
    }

    @Test
    fun `blank loot table id inside array is ignored with an issue`() {
        val result = normalize("""{"loot_tables":["   ","demo:valid"],"pools":[]}""")

        assertEquals(listOf(ResourceLocation("demo", "valid")), result.lootTables)
        assertTrue(issueText(result).contains("contains a blank loot table id"))
    }

    /** 冒号后为空：`demo:` 不是合法路径，必须拒绝而不是解析成 `demo:` */
    @Test
    fun `loot table with empty path after the colon is rejected`() {
        val result = normalize("""{"loot_table":"demo:","pools":[]}""")

        assertTrue(result.lootTables.isEmpty())
        assertTrue(issueText(result).contains("empty path"))
    }

    /** 逐元素容错：坏元素只丢自己，且 issue 里带上数组下标便于定位 */
    @Test
    fun `each bad array element is reported with its own index`() {
        val result = normalize("""{"loot_tables":["demo:a",42,"nonamespace","demo:b"],"pools":[]}""")

        assertEquals(
            listOf(ResourceLocation("demo", "a"), ResourceLocation("demo", "b")),
            result.lootTables,
        )
        val text = issueText(result)
        assertTrue(text, text.contains("loot_tables[1]"))
        assertTrue(text, text.contains("loot_tables[2]"))
    }

    /** 两个表字段都缺（或写成 null）时，只报"必须定义表"，不该误报 pools */
    @Test
    fun `both table fields absent or null is reported as missing tables only`() {
        val withPools = normalize("""{"pools":[]}""")
        assertTrue(withPools.lootTables.isEmpty())
        assertTrue(issueText(withPools).contains("must define a namespaced"))
        assertFalse(issueText(withPools).contains("must define 'pools'"))

        val allNull = normalize("""{"loot_tables":null,"loot_table":null,"pools":[]}""")
        assertTrue(allNull.lootTables.isEmpty())
        assertTrue(issueText(allNull).contains("must define a namespaced"))
        assertFalse(issueText(allNull).contains("must define 'pools'"))
    }
}
