package com.tacz.legacy.common.resource

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 坏 modifier 硬阈值的**作用域**测试。
 *
 * 这套阈值有两条路径，语义必须不同：
 * - `exclusive_attachments` 条目：坏的不比好的少 → **整件丢弃**（回落普通配件）。
 *   独占语义是"整份替换或整份回落"，留半套数值是最难察觉的失败形态。
 * - 普通配件 `data/attachments/<id>.json`：坏的不比好的少 → **只丢坏字段，条目保留**。
 *   普通配件没有下一级回落，整件丢弃会让它从创造栏/渲染/数据消费里全面消失。
 *
 * 这个测试把两条路径的差异钉死，防止将来有人把阈值无差别地套到普通配件上。
 */
class AttachmentModifierHardThresholdScopeTest {

    private val namespace = "threshold_scope"

    @Test
    fun `broken attachment data keeps the entry and drops only the bad modifier`() {
        val snapshot = scanWith(
            mapOf(
                "data/$namespace/index/attachments/broken.json" to
                    """{ "name": "broken", "data": "$namespace:broken_data", "type": "scope", "sort": 1 }""",
                "data/$namespace/data/attachments/broken_data.json" to """{ "damage": 1.5 }""",
            ),
        )

        val data = snapshot.attachments[net.minecraft.util.ResourceLocation(namespace, "broken")]?.data
        assertNotNull("普通配件 data 不应因为坏 modifier 整件消失", data)
        assertFalse("坏字段本身仍被 reader 级隔离跳过", data!!.modifiers.containsKey("damage"))
        assertTrue(
            "必须留下可定位的 issue：${snapshot.issues}",
            snapshot.issues.any { it.contains("broken_data") && it.contains("parse failed") },
        )
        assertFalse(
            "普通配件路径不该出现整件 rejected 的文案",
            snapshot.issues.any { it.contains("broken_data") && it.contains("the whole entry is dropped") },
        )
    }

    @Test
    fun `broken exclusive entry is dropped because it has a fallback`() {
        val snapshot = scanWith(
            mapOf(
                "data/$namespace/index/guns/broken_gun.json" to
                    """{ "name": "broken", "data": "$namespace:broken_gun_data", "type": "scope" }""",
                "data/$namespace/data/guns/broken_gun_data.json" to
                    """{ "ammo": "$namespace:round", "ammo_amount": 30, "rpm": 600, "weight": 3.0, "aim_time": 0.2,
                        "fire_mode": ["auto"],
                        "exclusive_attachments": { "$namespace:scope": { "damage": 1.5 } } }""",
            ),
        )

        val gun = snapshot.guns[net.minecraft.util.ResourceLocation(namespace, "broken_gun")]
        assertNotNull("枪本身仍要加载（独占条目坏了不该拖垮整枪）", gun)
        assertTrue(
            "exclusive 条目应被整件丢弃：${snapshot.issues}",
            snapshot.issues.any { it.contains("exclusive_attachments") && it.contains("rejected") },
        )
    }

    // ---- helpers ----

    private fun scanWith(files: Map<String, String>): TACZRuntimeSnapshot {
        val root = Files.createTempDirectory("tacz-threshold-scope").toFile()
        try {
            val packsRoot = File(root, "tacz")
            val pack = File(packsRoot, "threshold_pack")
            write(pack, "gunpack.meta.json", """{ "namespace": "$namespace" }""")
            write(
                pack,
                "assets/$namespace/gunpack_info.json",
                """{ "version": "1.0.0", "name": "pack.$namespace.name" }""",
            )
            files.forEach { (path, content) -> write(pack, path, content) }
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
}
