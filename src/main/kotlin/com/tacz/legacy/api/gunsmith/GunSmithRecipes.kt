package com.tacz.legacy.api.gunsmith

import com.google.gson.JsonParser
import com.tacz.legacy.common.application.gunsmith.LegacyGunSmithingRuntime
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation

/**
 * 制作台配方的一条材料。`options` 是这一个槽位可接受的所有物品
 * （`oreDictionary` 或 JSON 数组会展开成多项），`count` 是需要的数量。
 */
public data class GunSmithMaterialView(
    public val options: List<ItemStack>,
    public val count: Int,
    /** 该材料来自一个本机没有任何物品的标签（如 1.12 的 `forge:gems/amethyst`）；仅用于展示。 */
    public val missingTag: String? = null,
)

/**
 * 制作台配方的公开只读视图。字段与枪包 JSON 的对应关系：
 * `id` = 配方文件路径推出的 id，`group` = 归属的工作台/标签，
 * `result` = 产出（枪械类产出的 NBT 已经写好 GunId），`materials` = 消耗材料。
 */
public data class GunSmithRecipeView(
    public val id: String,
    public val group: String,
    public val result: ItemStack,
    public val materials: List<GunSmithMaterialView>,
)

/**
 * 给脚本加载器（CraftTweaker、GroovyScript）与展示层（JEI/HEI）用的公开入口。
 *
 * 之所以要单独一层：内部实现 `LegacyGunSmithingRuntime` 与它的配方类型都是 `internal`，
 * 不能出现在跨模块 API 上；而 1.12.2 的 GroovyScript 是"脚本直接访问 Java 代码"的模型，
 * 所以只要这里有一个稳定的公开面，它不需要我们的适配代码就能用。
 *
 * 每个方法都挂了 `@JvmStatic`：Kotlin `object` 的成员默认是单例上的实例方法，
 * Groovy 的 `GunSmithRecipes.all()` 静态调用风格会 `MissingMethodException`（实测踩过）。
 *
 * 改动是**叠加**在枪包解析结果之上的，且活过 `/tacz reload`（1.12.2 的 CraftTweaker
 * 不能重跑脚本，抹掉就等于逼玩家重启游戏）。能重跑脚本的加载器应先调 [clearChanges] 再重建。
 */
public object GunSmithRecipes {

    /** 当前生效的全部制作台配方（含脚本改动）。 */
    @JvmStatic
    public fun all(): List<GunSmithRecipeView> =
        LegacyGunSmithingRuntime.recipes().values.map { it.toView() }

    /** 按 id 取一条；id 写成 `命名空间:路径`，不带前缀时按 `tacz:` 处理。 */
    @JvmStatic
    public fun get(id: String): GunSmithRecipeView? =
        LegacyGunSmithingRuntime.recipes()[toId(id)]?.toView()

    /** 是否存在该配方。 */
    @JvmStatic
    public fun exists(id: String): Boolean = LegacyGunSmithingRuntime.recipes().containsKey(toId(id))

    /** 删除一条配方；返回它原本是否存在。 */
    @JvmStatic
    public fun remove(id: String): Boolean = LegacyGunSmithingRuntime.scriptRemove(toId(id))

    /**
     * 用 JSON 新增/覆盖一条配方，字符串内容与枪包 `data/<ns>/recipes` 目录下的 json 文件完全一致
     * （`result` + `materials`），因此 ore dictionary、数组、NBT、枪械 GunId 的语义都复用同一条解析路径。
     *
     * @return 是否覆盖了已存在的同 id 配方
     * @throws IllegalArgumentException JSON 不合法或缺少 result
     */
    @JvmStatic
    public fun add(id: String, json: String): Boolean {
        val targetId = toId(id)
        val element = try {
            JsonParser().parse(json)
        } catch (throwable: com.google.gson.JsonParseException) {
            throw IllegalArgumentException("Not a valid JSON object for gunsmith recipe $id", throwable)
        }
        if (!element.isJsonObject) {
            throw IllegalArgumentException("Gunsmith recipe $id must be a JSON object")
        }
        val recipe = LegacyGunSmithingRuntime.parseScriptRecipe(targetId, element.asJsonObject)
            ?: throw IllegalArgumentException("Gunsmith recipe $id could not be parsed (missing or invalid 'result')")
        return LegacyGunSmithingRuntime.scriptAdd(recipe)
    }

    /** 清空所有脚本改动，回到枪包原始状态；返回被清掉的改动条数。 */
    @JvmStatic
    public fun clearChanges(): Int {
        val count = LegacyGunSmithingRuntime.scriptChangeCount()
        LegacyGunSmithingRuntime.scriptClear()
        return count
    }

    /** 已记录的脚本改动条数（新增 + 删除）。 */
    @JvmStatic
    public fun changeCount(): Int = LegacyGunSmithingRuntime.scriptChangeCount()

    private fun toId(raw: String): ResourceLocation =
        if (raw.contains(':')) ResourceLocation(raw) else ResourceLocation("tacz", raw)

    private fun com.tacz.legacy.common.application.gunsmith.LegacyGunSmithRecipe.toView(): GunSmithRecipeView =
        GunSmithRecipeView(
            id = id.toString(),
            group = group.toString(),
            result = result.copy(),
            // 必须走 getter 方法而不是属性语法：Kotlin 把 `ingredient.matchingStacks` 编到
            // Ingredient 的私有字段上，未装 JEI 时会 IllegalAccessError（同 GunSmithTableScreen 的注释）
            materials = materials.map { GunSmithMaterialView(it.ingredient.getMatchingStacks().toList(), it.count, it.missingTag) },
        )
}
