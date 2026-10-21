package com.tacz.legacy.common.resource

import com.tacz.legacy.api.modifier.Modifier
import net.minecraft.util.ResourceLocation
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 兼容层 ③ C10 的数值验收：exclusive_attachments 命中时应「整份替换」配件数据。
 *
 * 把 review-08 里"需人工复验"的结论固化成可回归的断言：
 * - db_short + oem_stock_tactical：散布 stand 5 → 4.875（`inaccuracy_addend: -0.125`），
 *   而不是配件自身的 `multiplier: 0.9` → 4.5；
 * - 后坐：exclusive 的 `recoil_modifier` 是上游 `@Deprecated` 旧字段（pitch/yaw 为 percent），
 *   与配件自身 `recoil.pitch.multiplier: 0.8` / `yaw.multiplier: 0.7` 方向相反（pitch/yaw 互换）；
 * - 重量：exclusive 写 `weight: 2.0` → addend 2.0，配件自身是 0.4。
 *
 * 这些数值差异在游戏里肉眼不可辨，只能用断言锁定。
 */
class ExclusiveAttachmentNumericTest {

    private val dataDir = File("src/main/resources/assets/tacz/custom/tacz_default_gun/data/tacz")

    private val stockId = ResourceLocation("tacz:oem_stock_tactical")

    /** 与 TACZGunPackScanner.toAttachmentData 同构：raw + weight + extended_mag_level + readModifiers */
    private fun definitionOf(json: com.google.gson.JsonObject) = TACZAttachmentDataDefinition(
        raw = json,
        weight = json.get("weight")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f,
        extendedMagLevel = json.get("extended_mag_level")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
        modifiers = TACZAttachmentModifierRegistry.readModifiers(json.toString(), json),
    )

    private fun exclusiveOfDbShort(): Map<ResourceLocation, TACZAttachmentDataDefinition> {
        val gunJson = TACZJson.parseObject(File(dataDir, "data/guns/db_short_data.json").readText())
        val entry = gunJson.getAsJsonObject("exclusive_attachments").getAsJsonObject(stockId.toString())
        return mapOf(stockId to definitionOf(entry))
    }

    private val defaults = linkedMapOf(
        "stand" to 5f,
        "move" to 5.75f,
        "sneak" to 3.5f,
        "lie" to 2.5f,
        "aim" to 0.15f,
    )

    @Test
    fun `exclusive replaces inaccuracy with addend`() {
        val mods = TACZGunPropertyResolver.collectInaccuracyModifiers(
            TACZRuntimeSnapshot.EMPTY, listOf(stockId), exclusiveOfDbShort(),
        )
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(mods, defaults)
        // 5 + (-0.125)
        assertEquals(4.875f, result["stand"]!!, 1e-4f)
        // 5.75 + (-0.125)
        assertEquals(5.625f, result["move"]!!, 1e-4f)
        // 通用 inaccuracy 只作用于 stand/move，aim 不应被污染
        assertEquals(0.15f, result["aim"]!!, 1e-4f)
    }

    @Test
    fun `without exclusive the attachment multiplier applies`() {
        val snapshot = TACZRuntimeSnapshot.EMPTY.copy(
            attachments = mapOf(
                stockId to TACZLoadedAttachment(
                    id = stockId,
                    index = TACZAttachmentIndexDefinition(),
                    data = definitionOf(
                        TACZJson.parseObject(File(dataDir, "data/attachments/oem_stock_tactical_data.json").readText()),
                    ),
                ),
            ),
        )
        val mods = TACZGunPropertyResolver.collectInaccuracyModifiers(snapshot, listOf(stockId), emptyMap())
        val result = TACZAttachmentModifierRegistry.evalInaccuracy(mods, defaults)
        // 5 × 0.9
        assertEquals(4.5f, result["stand"]!!, 1e-4f)
    }

    @Test
    fun `exclusive recoil uses legacy percent fields`() {
        val values = TACZGunPropertyResolver.collectRecoilModifiers(
            TACZRuntimeSnapshot.EMPTY, listOf(stockId), exclusiveOfDbShort(),
        )
        val v = values.single()
        // recoil_modifier.pitch = -0.3 → percent -0.3（合计 ×0.7）
        assertEquals(-0.3, v.pitch.percent, 1e-6)
        // recoil_modifier.yaw = -0.2 → percent -0.2（合计 ×0.8）
        assertEquals(-0.2, v.yaw.percent, 1e-6)
    }

    @Test
    fun `exclusive weight overrides attachment weight`() {
        val mods = TACZGunPropertyResolver.collectNumericModifiers(
            TACZRuntimeSnapshot.EMPTY, listOf(stockId), "weight_modifier", exclusiveOfDbShort(),
        )
        // exclusive 写 weight: 2.0 → addend 2.0（配件自身是 0.4）；multiplier 保持中性 1.0
        assertEquals(2.0, mods.single().addend, 1e-6)
        assertEquals(1.0, mods.single().multiplier, 1e-6)
    }
}
