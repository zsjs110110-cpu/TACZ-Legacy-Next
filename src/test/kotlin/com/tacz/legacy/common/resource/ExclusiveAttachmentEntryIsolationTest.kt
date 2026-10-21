package com.tacz.legacy.common.resource

import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 测试任务书 09 · 组 5：`parseGunData` 对 `exclusive_attachments` 的条目级隔离。
 *
 * 用 [TACZGunPackScanner.scan] 扫一个临时构造的目录型枪包（不依赖 zip / Forge 运行时），
 * 锁定"C10 修复后坏数据只丢条目、不丢枪"。
 */
class ExclusiveAttachmentEntryIsolationTest {

    private val namespace = "excl"

    private fun scanWith(gunFiles: Map<String, String>): TACZRuntimeSnapshot {
        val root = Files.createTempDirectory("tacz-exclusive-isolation").toFile()
        try {
            val packsRoot = File(root, "tacz")
            val pack = File(packsRoot, "isolation_pack")
            write(pack, "gunpack.meta.json", """{ "namespace": "$namespace" }""")
            write(pack, "assets/$namespace/gunpack_info.json", """{ "version": "1.0.0", "name": "pack.$namespace.name" }""")
            gunFiles.forEach { (gunKey, exclusiveJson) ->
                val index = """
                    {
                      "name": "$namespace.gun.$gunKey.name",
                      "data": "$namespace:${gunKey}_data",
                      "type": "rifle",
                      "item_type": "modern_kinetic",
                      "sort": 1
                    }
                """.trimIndent()
                write(pack, "data/$namespace/index/guns/$gunKey.json", index)
                val data = """
                    {
                      "ammo": "$namespace:round",
                      "fire_mode": ["auto"],
                      "ammo_amount": 30,
                      "rpm": 600,
                      "weight": 3.0,
                      "aim_time": 0.2,
                      "exclusive_attachments": $exclusiveJson
                    }
                """.trimIndent()
                write(pack, "data/$namespace/data/guns/${gunKey}_data.json", data)
            }
            return TACZGunPackScanner.scan(packsRoot)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun write(root: File, relativePath: String, content: String) {
        val file = File(root, relativePath)
        file.parentFile.mkdirs()
        Files.write(file.toPath(), content.toByteArray(StandardCharsets.UTF_8))
    }

    private fun dataOf(snapshot: TACZRuntimeSnapshot, gunKey: String): TACZGunDataDefinition {
        val gun = snapshot.guns[ResourceLocation(namespace, gunKey)]
        assertNotNull("gun $gunKey should still be loaded, issues=${snapshot.issues}", gun)
        return requireNotNull(gun).data
    }

    private fun issuesAbout(snapshot: TACZRuntimeSnapshot, gunKey: String): List<String> =
        snapshot.issues.filter { it.contains("${namespace}:$gunKey") }

    /** #1 容器写成数组：只记 issue，整把枪仍在，exclusiveAttachments 为空 */
    @Test
    fun `array container is ignored with an issue and the gun still loads`() {
        val snapshot = scanWith(mapOf("container_array" to "[]"))
        val data = dataOf(snapshot, "container_array")

        assertTrue(data.exclusiveAttachments.isEmpty())
        assertTrue(
            "expected a 'not a JSON object' issue, got ${issuesAbout(snapshot, "container_array")}",
            issuesAbout(snapshot, "container_array").any { it.contains("exclusive_attachments is not a JSON object") },
        )
    }

    /** #1 补：容器写成字符串同样只记 issue */
    @Test
    fun `string container is ignored with an issue`() {
        val snapshot = scanWith(mapOf("container_string" to "\"oops\""))
        assertTrue(dataOf(snapshot, "container_string").exclusiveAttachments.isEmpty())
        assertTrue(issuesAbout(snapshot, "container_string").any { it.contains("not a JSON object") })
    }

    /** #1 补：完全没有该字段 → 空表且无 exclusive 相关 issue */
    @Test
    fun `absent container yields an empty map without issues`() {
        val snapshot = scanWith(mapOf("container_absent" to "null"))
        assertTrue(dataOf(snapshot, "container_absent").exclusiveAttachments.isEmpty())
        // JSON null 不是 object，会记一条 issue（JsonNull），但枪仍在
        assertTrue(issuesAbout(snapshot, "container_absent").any { it.contains("exclusive_attachments") })
    }

    /** #2 条目值不是对象（裸数字）→ 跳过该条目并记 issue，枪仍在 */
    @Test
    fun `scalar entry value is skipped with an issue`() {
        val snapshot = scanWith(mapOf("entry_scalar" to """{ "$namespace:x": 5 }"""))
        val data = dataOf(snapshot, "entry_scalar")

        assertTrue(data.exclusiveAttachments.isEmpty())
        assertTrue(
            "expected a 'value is not a JSON object' issue, got ${issuesAbout(snapshot, "entry_scalar")}",
            issuesAbout(snapshot, "entry_scalar").any { it.contains("value is not a JSON object; skipped") },
        )
    }

    /**
     * #3 条目值是对象但内部字段非法（裸数字 damage）：
     * reader 级隔离先跳过该 modifier，**随后**按硬阈值判定整件丢弃（见下一条测试）。
     *
     * 注意这里断言的是**新语义**（review-10a 阻断项 2）：唯一的字段坏了就等于 100% 失败率，
     * 与其留下一个"装上了但什么都没生效"的空条目，不如整条丢弃并明确报错。
     */
    @Test
    fun `entry with only broken modifiers is dropped entirely`() {
        val snapshot = scanWith(mapOf("entry_bad_modifier" to """{ "$namespace:x": { "damage": 1.5 } }"""))
        val data = dataOf(snapshot, "entry_bad_modifier")

        assertNull(
            "坏 modifier 占多数时整件应被丢弃，避免留下半生效的空条目",
            data.exclusiveAttachments[ResourceLocation(namespace, "x")],
        )
        assertTrue(
            "expected a reject issue, got ${issuesAbout(snapshot, "entry_bad_modifier")}",
            issuesAbout(snapshot, "entry_bad_modifier").any {
                it.contains("modifier 'damage'") && it.contains("parse failed")
            },
        )
        assertTrue(
            "整件丢弃必须留下一条可定位的 rejected issue",
            issuesAbout(snapshot, "entry_bad_modifier").any { it.contains("rejected") },
        )
        // 整把枪的其它数据没被牵连
        assertEquals(30, data.ammoAmount)
        assertEquals(600, data.roundsPerMinute)
    }

    /**
     * #3 补：坏条目被整件丢弃，但不连累同容器里的好条目。
     *
     * 旧的期望是"坏条目仍保留、只是缺该 modifier"；引入硬阈值后改为整条丢弃 ——
     * 关键性质仍然是**好的兄弟条目不受影响**。
     */
    @Test
    fun `dropped bad entry does not poison a sibling good entry`() {
        val snapshot = scanWith(
            mapOf(
                "entry_mixed" to """{ "$namespace:good": { "weight": 2.0 }, "$namespace:bad": { "damage": 1.5 } }""",
            ),
        )
        val data = dataOf(snapshot, "entry_mixed")

        assertEquals(
            "只剩好条目：坏条目因 100% 失败率被整件丢弃",
            setOf(ResourceLocation(namespace, "good")),
            data.exclusiveAttachments.keys,
        )
        assertEquals(2.0f, requireNotNull(data.exclusiveAttachments[ResourceLocation(namespace, "good")]).weight, 1e-4f)
        assertTrue(
            issuesAbout(snapshot, "entry_mixed").any {
                it.contains("modifier 'damage'") && it.contains("parse failed")
            },
        )
        assertTrue(
            issuesAbout(snapshot, "entry_mixed").any { it.contains("rejected") },
        )
    }

    /**
     * 硬阈值的另一侧：**一个坏字段混在一堆好字段里不应拖垮整件**。
     * 否则第三方包里一个手滑就会让整个配件失效，比"部分生效"更难用。
     */
    @Test
    fun `single bad modifier among many good ones keeps the entry`() {
        val snapshot = scanWith(
            mapOf(
                "entry_mostly_good" to
                    """{ "$namespace:x": { "weight": 2.0, "ads_addend": -0.1, "head_shot": { "addend": 0.5 }, "damage": 1.5 } }""",
            ),
        )
        val data = dataOf(snapshot, "entry_mostly_good")
        val entry = requireNotNull(data.exclusiveAttachments[ResourceLocation(namespace, "x")])
        assertFalse(entry.modifiers.containsKey("damage"))
        assertTrue(entry.modifiers.containsKey("weight_modifier"))
        assertTrue(
            issuesAbout(snapshot, "entry_mostly_good").any {
                it.contains("modifier 'damage'") && it.contains("parse failed")
            },
        )
        assertFalse(
            "失败率不足半数时不该整件丢弃",
            issuesAbout(snapshot, "entry_mostly_good").any { it.contains("rejected") },
        )
    }

    /**
     * #4 key 无命名空间：ResourceLocation 默认补 `minecraft:`，
     * 条目仍解析成功，但记一条 "has no namespace" 提示。
     */
    @Test
    fun `key without namespace resolves to minecraft and is reported`() {
        val snapshot = scanWith(mapOf("entry_no_namespace" to """{ "x": { "weight": 1.25 } }"""))
        val data = dataOf(snapshot, "entry_no_namespace")

        assertEquals(setOf(ResourceLocation("minecraft", "x")), data.exclusiveAttachments.keys)
        assertEquals(1.25f, requireNotNull(data.exclusiveAttachments[ResourceLocation("minecraft", "x")]).weight, 1e-4f)
        assertTrue(
            "expected a 'has no namespace' issue, got ${issuesAbout(snapshot, "entry_no_namespace")}",
            issuesAbout(snapshot, "entry_no_namespace").any { it.contains("has no namespace") },
        )
    }

    /**
     * 1.12.2 的 `ResourceLocation(String)` 只做 split + lowercase，**不校验字符**，
     * 所以 "Bad Key" 不会走 invalid id 分支，而是被解析成 minecraft:"bad key"。
     * （生产代码里的 invalid id 分支只是防御性代码，在本版本实际不可达。）
     */
    @Test
    fun `key with illegal characters is lowercased instead of rejected`() {
        val snapshot = scanWith(mapOf("entry_bad_id" to """{ "Bad Key": { "weight": 1.0 } }"""))
        val data = dataOf(snapshot, "entry_bad_id")

        assertEquals(
            setOf(ResourceLocation("minecraft", "bad key")),
            data.exclusiveAttachments.keys,
        )
        assertTrue(issuesAbout(snapshot, "entry_bad_id").any { it.contains("has no namespace") })
    }

    /** exclusive 条目与配件 data 同构：readModifiers 在同一容器里正常产出 modifier */
    @Test
    fun `good entry parses modifiers like an attachment data file`() {
        val snapshot = scanWith(mapOf("entry_good_full" to """{ "$namespace:stock": { "weight": 2.0, "inaccuracy_addend": -0.125 } }"""))
        val exclusive = requireNotNull(dataOf(snapshot, "entry_good_full").exclusiveAttachments[ResourceLocation(namespace, "stock")])

        assertEquals(2.0f, exclusive.weight, 1e-4f)
        assertTrue(exclusive.modifiers.containsKey("inaccuracy"))
        assertTrue(exclusive.modifiers.containsKey("weight_modifier"))
    }
}
