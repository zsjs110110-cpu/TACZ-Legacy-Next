package com.tacz.legacy.integration.crafttweaker

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.gunsmith.GunSmithRecipes
import crafttweaker.annotations.ZenRegister
import stanhebben.zenscript.annotations.ZenClass
import stanhebben.zenscript.annotations.ZenMethod

/**
 * CraftTweaker 4（1.12.2 线）入口，ZenScript 里写作 `mods.tacz.GunSmithing`。
 *
 * 覆盖的是 TaCZ 自制制作台的 160 条配方（`data/<ns>/recipes` 里 `type: tacz:gun_smith_table_crafting`
 * 那批）——它们不是 vanilla `IRecipe`，所以 CT 自带的 `recipes.remove()` 看不见它们。
 * 而 `assets/tacz/recipes` 下那 10 条工作台方块本身是 `forge:ore_shaped` 的原版配方，
 * CT 原生就能改，这里不重复提供。
 *
 * 用法（`scripts/tacz.zs`）：
 * ```
 * import mods.tacz.GunSmithing;
 * GunSmithing.remove("tacz:ammo/9mm");
 * print(GunSmithing.getRecipeIds());
 * // result 与枪包配方文件同构：type 取 gun / ammo / attachment / custom
 * GunSmithing.add("tacz:gun/my_test",
 *   "{\"result\":{\"type\":\"gun\",\"id\":\"tacz:ak47\"},\"materials\":[{\"item\":{\"tag\":\"forge:ingots/iron\"},\"count\":3}]}");
 * ```
 *
 * 实测两点（踩过才写下来）：`GunSmithing.recipes` 这种属性式写法在 CT4 静态类上不成立（`@ZenGetter`
 * 只作用于实例成员），要调 `getRecipeIds()`；`result` 写成 `{"item":...}` 会被当成 `custom` 走
 * `CraftingHelper`，字段不合就整条解析失败并被拒绝。
 *
 * 改动是叠加式的并且活过 `/tacz reload`（1.12.2 的 CT 不能重跑脚本，抹掉等于逼玩家重启）。
 *
 * 编译期依赖 CT4 jar（`build.gradle.kts` 里 `../mc-devlibs/`，运行期由整合包自己提供）；
 * 没装 CT 的包不会加载这个类。
 */
@ZenRegister
@ZenClass("mods.tacz.GunSmithing")
public object GunSmithingTweaker {

    /**
     * 全部制作台配方 id，ZenScript 里是 `string[]`。
     *
     * 用 `@ZenMethod` 而不是 `@ZenGetter`：CT4 的 getter 只作用于实例成员，
     * 静态类上写 `@ZenGetter("recipes")` 会得到 `No such static member: recipes`（实测）。
     */
    @JvmStatic
    @ZenMethod
    public fun getRecipeIds(): List<String> = GunSmithRecipes.all().map { it.id }

    @JvmStatic
    @ZenMethod
    public fun exists(id: String): Boolean = GunSmithRecipes.exists(id)

    /** 删除一条配方；返回它原本是否存在。 */
    @JvmStatic
    @ZenMethod
    public fun remove(id: String): Boolean = GunSmithRecipes.remove(id)

    /**
     * 新增/覆盖一条配方，`json` 与枪包里的配方文件内容同构。
     * 出错时返回 false 并把原因写进日志——ZenScript 侧不抛 Java 异常，脚本还能继续跑。
     */
    @JvmStatic
    @ZenMethod
    public fun add(id: String, json: String): Boolean = try {
        GunSmithRecipes.add(id, json)
    } catch (throwable: Throwable) {
        TACZLegacy.logger.warn("[CraftTweaker] rejected gunsmith recipe {}: {}", id, throwable.message)
        false
    }

    /** 某条配方需要的材料槽数，便于脚本作者核对。 */
    @JvmStatic
    @ZenMethod
    public fun materialCount(id: String): Int = GunSmithRecipes.get(id)?.materials?.size ?: -1

    /** 撤销全部脚本改动，回到枪包原始状态；返回被清掉的条数。 */
    @JvmStatic
    @ZenMethod
    public fun clear(): Int = GunSmithRecipes.clearChanges()
}
