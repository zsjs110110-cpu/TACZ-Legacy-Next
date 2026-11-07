package com.tacz.legacy.common.application.gunsmith

import com.google.gson.JsonParser
import com.tacz.legacy.common.resource.DefaultGunPackExporter
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.init.Bootstrap
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.ResourceLocation
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files

/**
 * 制作台材料解析的兼容性验证：`forge:partial_nbt`、裸字符串 item、1.13 改名别名、
 * 以及"枪包里混了原版工作台配方就跳过"。
 *
 * 这几条以前都会让整条配方解析失败并被丢弃（活环境日志里是 23 条 WARN）。
 * 物品一律用 `Item.getByNameOrId` 取，避免把测试绑死在某个映射表的常量命名上。
 */
public class GunSmithMaterialParsingTest {

    public companion object {
        @BeforeClass
        @JvmStatic
        fun setUp() {
            Bootstrap.register()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            TACZGunPackRuntimeRegistry.clearForTests()
        }

        private fun item(id: String): Item = Item.getByNameOrId(id)
            ?: throw AssertionError("测试前置失败：$id 没注册")
    }

    private fun parse(json: String): LegacyGunSmithRecipe? {
        val gameDir = Files.createTempDirectory("tacz-material-parse").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            DefaultGunPackExporter.exportIfNeeded(gameDir)
            return LegacyGunSmithingRuntime.parseScriptRecipe(
                ResourceLocation("tacz", "test/material"),
                JsonParser().parse(json).asJsonObject,
            )
        } finally {
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }

    private fun materials(json: String): List<LegacyGunSmithIngredient> {
        val recipe = parse(json)
        assertNotNull("配方应当解析成功，实际被丢弃：$json", recipe)
        return recipe!!.materials
    }

    private fun stackWith(id: String, vararg tags: Pair<String, Any>): ItemStack {
        val stack = ItemStack(item(id))
        if (tags.isNotEmpty()) {
            val tag = NBTTagCompound()
            tags.forEach { (key, value) ->
                when (value) {
                    is String -> tag.setString(key, value)
                    is Int -> tag.setInteger(key, value)
                }
            }
            stack.tagCompound = tag
        }
        return stack
    }

    private fun partialNbtRecipe(materialJson: String): String =
        """{
            "type": "tacz:gun_smith_table_crafting",
            "result": {"type": "custom", "item": "minecraft:iron_ingot"},
            "materials": [$materialJson]
        }"""

    @Test
    public fun partialNbtMatchesSupersetAndRejectsOtherValues() {
        val material = materials(
            partialNbtRecipe(
                """{"item": {"type": "forge:partial_nbt", "item": "minecraft:obsidian",
                 "nbt": {"GunId": "tacz:ak47"}}, "count": 2}"""
            )
        ).single()

        assertEquals(2, material.count)
        // 手上物品的 NBT 只要"包含"要求的那几项就算满足；额外的运行时字段不影响
        assertTrue(material.matches(stackWith("minecraft:obsidian", "GunId" to "tacz:ak47", "CurrentAmmo" to 5)))
        assertFalse(material.matches(stackWith("minecraft:obsidian", "GunId" to "tacz:m700")))
        assertFalse("缺 GunId 的普通黑曜石不该被当成那把枪", material.matches(stackWith("minecraft:obsidian")))
        assertFalse("少一个键也不能算匹配", material.matches(stackWith("minecraft:obsidian", "CurrentAmmo" to 5)))
        assertFalse(material.matches(ItemStack.EMPTY))
    }

    @Test
    public fun partialNbtSampleKeepsNbtForIcons() {
        val material = materials(
            partialNbtRecipe(
                """{"item": {"type": "forge:partial_nbt", "item": "minecraft:obsidian",
                 "nbt": {"GunId": "tacz:ak47"}}, "count": 1}"""
            )
        ).single()
        // JEI 与制作台的图标取 ingredient 的候选栈；带 GunId 才会渲染成那把具体的枪
        val sample = material.ingredient.getMatchingStacks().first()
        assertEquals("tacz:ak47", sample.tagCompound?.getString("GunId"))
    }

    @Test
    public fun bareStringItemIsAccepted() {
        val material = materials(partialNbtRecipe("""{"item": "minecraft:obsidian", "count": 3}""")).single()
        assertEquals(3, material.count)
        assertTrue(material.matches(stackWith("minecraft:obsidian")))
    }

    @Test
    public fun oneThirteenRenameAliasResolves() {
        // 1.13 把 speckled_melon 改名成 glistering_melon_slice，物品本身没变（活环境实测本机物品表里
        // 只有 speckled_melon，没有 golden_melon——别名目标就是这么定下来的）
        val recipe = parse(partialNbtRecipe("""{"item": "minecraft:glistering_melon_slice", "count": 1}"""))
        assertNotNull(recipe)
        assertTrue(recipe!!.materials.single().matches(stackWith("minecraft:speckled_melon")))
    }

    @Test
    public fun unknownItemStillFailsTheRecipeLoudly() {
        val recipe = parse(partialNbtRecipe("""{"item": "minecraft:crying_obsidian", "count": 2}"""))
        assertEquals("1.12 没有哭泣的黑曜石，这条配方应当被丢弃", null, recipe)
    }

    @Test
    public fun vanillaCraftingRecipeInGunPackIsSkippedNotMisparsed() {
        val recipe = parse(
            """{
                "type": "minecraft:crafting_shaped",
                "pattern": ["###"],
                "key": {"#": {"item": "minecraft:iron_ingot"}},
                "result": {"item": "minecraft:obsidian", "count": 1}
            }"""
        )
        assertEquals("非制作台配方不该由制作台解析器接管", null, recipe)
    }

    @Test
    public fun oreTagNamesMatchThe112OreDictionary() {
        val cases = mapOf(
            "forge:ingots/iron" to "ingotIron",
            "forge:gems/amethyst" to "gemAmethyst",
            "forge:storage_blocks/iron" to "blockIron",
            "forge:storage_blocks/redstone" to "blockRedstone",
            "forge:glass" to "blockGlass",
            "forge:glass/silica" to "blockGlass",
            "minecraft:logs" to "logWood",
            "minecraft:stone_crafting_materials" to "cobblestone",
        )
        cases.forEach { (tag, expected) ->
            assertEquals(tag, expected, LegacyGunSmithingRuntime.oreDictionaryName(tag))
        }
    }

    @Test
    public fun emptyTagIsReportedAsMissingInsteadOfSilentBlank() {
        // 单测环境矿典是空的，所以任何标签都解析不出物品——正好用来验证"缺材料"会被记下来
        val material = materials(partialNbtRecipe("""{"item": {"tag": "forge:gems/amethyst"}, "count": 3}""")).single()
        assertTrue(material.ingredient.getMatchingStacks().isEmpty())
        assertEquals("forge:gems/amethyst", material.missingTag)
        assertEquals(3, material.count)
        val concrete = materials(partialNbtRecipe("""{"item": "minecraft:obsidian", "count": 1}""")).single()
        assertEquals(null, concrete.missingTag)
    }

    @Test
    public fun nestedPartialNbtRequiresTheSameShape() {
        val required = NBTTagCompound().apply {
            setString("GunId", "tacz:ak47")
            setTag("Inner", NBTTagCompound().apply { setInteger("A", 1) })
        }
        val superset = NBTTagCompound().apply {
            setString("GunId", "tacz:ak47")
            setString("Extra", "whatever")
            setTag("Inner", NBTTagCompound().apply {
                setInteger("A", 1)
                setInteger("B", 2)
            })
        }
        val wrongInner = NBTTagCompound().apply {
            setString("GunId", "tacz:ak47")
            setTag("Inner", NBTTagCompound().apply { setInteger("A", 9) })
        }
        assertTrue(PartialNbtMatcher.contains(superset, required))
        assertFalse(PartialNbtMatcher.contains(wrongInner, required))
        assertFalse("实际完全没有 NBT 时不能算匹配", PartialNbtMatcher.contains(null, required))
        assertTrue("要求为空 NBT 时任何同物品都算匹配", PartialNbtMatcher.contains(NBTTagCompound(), NBTTagCompound()))
    }
}
