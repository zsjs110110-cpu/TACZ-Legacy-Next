package com.tacz.legacy.api.gunsmith

import com.tacz.legacy.common.resource.DefaultGunPackExporter
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.init.Bootstrap
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files

/**
 * 脚本配方叠加层的行为验证。CraftTweaker、GroovyScript、JEI 三条兼容都读这一层，
 * 而测试环境里没装 JEI/CT/GroovyScript，所以这是唯一能真跑起来、也最该被测的一段。
 *
 * 用真实默认枪包（`Bootstrap.register()` + `exportIfNeeded`）而不是手搓 ItemStack：
 * 手搓的那版会让 `add(id, json)` 走完整枪包解析路径、却因为物品没注册而解析成空，
 * 测出来的是环境而不是逻辑。
 */
public class GunSmithRecipesOverlayTest {

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
    }

    private fun withDefaultPack(block: () -> Unit) {
        val gameDir = Files.createTempDirectory("tacz-gunsmith-overlay").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            GunSmithRecipes.clearChanges()
            DefaultGunPackExporter.exportIfNeeded(gameDir)
            block()
        } finally {
            GunSmithRecipes.clearChanges()
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }

    @Test
    public fun removeHidesARealPackRecipeAndClearRestoresIt() {
        withDefaultPack {
            val original = GunSmithRecipes.all()
            assertTrue("默认包应当解析出制作台配方", original.isNotEmpty())
            val target = original.first()

            assertTrue("删除存在的配方要返回 true", GunSmithRecipes.remove(target.id))
            assertFalse(GunSmithRecipes.exists(target.id))
            assertEquals("列表少一条", original.size - 1, GunSmithRecipes.all().size)
            assertFalse("重复删除要返回 false", GunSmithRecipes.remove(target.id))

            assertEquals("清掉的改动条数", 1, GunSmithRecipes.clearChanges())
            assertEquals("枪包原始状态恢复", original.size, GunSmithRecipes.all().size)
            assertTrue(GunSmithRecipes.exists(target.id))
        }
    }

    /**
     * 这里刻意**不**测 `add(id, json)` 的成功路径。
     *
     * 它复用枪包的 `result` 解析，而那条解析要按注册表名字查物品；单元测试里 TaCZ 的物品
     * 从没进过 Forge 注册表，任何 item 都会解析成空。在这种环境里断言成功或失败，
     * 测到的都是"注册缺失"而不是逻辑。`add` 的成功路径需要进游戏验证（见 docs/SCRIPTING-COMPAT.md）。
     * 错误契约由下面的 `malformedJsonFailsLoudly` 覆盖。
     */
    @Test
    public fun malformedJsonFailsLoudly() {
        withDefaultPack {
            var threw = false
            try {
                GunSmithRecipes.add("tacz:script_overlay/broken", "{ not json ")
            } catch (exception: IllegalArgumentException) {
                threw = true
            }
            assertTrue("非法 JSON 必须报错，不能静默成功", threw)

            threw = false
            try {
                GunSmithRecipes.add("tacz:script_overlay/no_result", "{\"materials\":[]}")
            } catch (exception: IllegalArgumentException) {
                threw = true
            }
            assertTrue("缺 result 必须报错", threw)
            assertFalse(GunSmithRecipes.exists("tacz:script_overlay/no_result"))
            assertEquals(0, GunSmithRecipes.changeCount())
        }
    }

    @Test
    public fun scriptChangesSurviveASnapshotRebuild() {
        // 叠加层是在读取时套到基础表上的，所以快照重建不会抹掉脚本改动。
        // 这条语义是给 CraftTweaker 用的：1.12.2 的 CT 不能重跑脚本，抹掉就等于逼玩家重启游戏。
        val gameDir = Files.createTempDirectory("tacz-gunsmith-overlay-rebuild").toFile()
        try {
            TACZGunPackRuntimeRegistry.clearForTests()
            GunSmithRecipes.clearChanges()
            DefaultGunPackExporter.exportIfNeeded(gameDir)
            val target = GunSmithRecipes.all().first().id
            GunSmithRecipes.remove(target)
            val afterRemove = GunSmithRecipes.all().size

            TACZGunPackRuntimeRegistry.reload(gameDir)
            assertEquals("重建快照后删除仍然生效", afterRemove, GunSmithRecipes.all().size)
            assertFalse(GunSmithRecipes.exists(target))
        } finally {
            GunSmithRecipes.clearChanges()
            TACZGunPackRuntimeRegistry.clearForTests()
            gameDir.deleteRecursively()
        }
    }
}
