package com.tacz.legacy.integration.jei

import com.tacz.legacy.api.gunsmith.GunSmithMaterialView
import com.tacz.legacy.api.gunsmith.GunSmithRecipeView
import mezz.jei.api.IGuiHelper
import mezz.jei.api.gui.IDrawable
import mezz.jei.api.gui.IRecipeLayout
import mezz.jei.api.ingredients.IIngredients
import mezz.jei.api.ingredients.VanillaTypes
import mezz.jei.api.recipe.IRecipeCategory
import mezz.jei.api.recipe.IRecipeWrapper
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Gui
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.resources.I18n
import net.minecraft.item.Item
import net.minecraft.item.ItemStack

/**
 * 一条制作台配方的 JEI 包装。
 *
 * JEI 4.x 的输入是"每个槽位一个候选列表"，正好对上我们的 `materials`
 * （`Ingredient` 可以是 ore dictionary 或数组，展开成可选物品），但槽位上显示的数量
 * 取自 `ItemStack.stackSize`，而 `Ingredient.getMatchingStacks()` 给的每条都是 1——
 * 所以必须把 `material.count` 写进拷贝出来的 stack，否则 JEI 画出来的材料**没有个数**
 * （2026-11-06 用户按 R 实测到的就是这个）。
 */
public class GunSmithRecipeWrapper(
    public val view: GunSmithRecipeView,
) : IRecipeWrapper {

    override fun getIngredients(ingredients: IIngredients) {
        ingredients.setInputLists(VanillaTypes.ITEM, view.materials.map { it.sizedStacks() })
        ingredients.setOutput(VanillaTypes.ITEM, view.result)
    }

    /** 材料与产出之间画一个实心小箭头，填掉中间那段空白。 */
    override fun drawInfo(minecraft: Minecraft, recipeWidth: Int, recipeHeight: Int, mouseX: Int, mouseY: Int) {
        val centerY = recipeHeight / 2
        for (i in 0 until ARROW_WIDTH) {
            val thickness = ((ARROW_WIDTH - i) * ARROW_HEIGHT / ARROW_WIDTH).coerceAtLeast(1)
            Gui.drawRect(ARROW_X + i, centerY - thickness / 2, ARROW_X + i + 1, centerY - thickness / 2 + thickness, ARROW_COLOR)
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F)
    }

    public companion object {
        private const val ARROW_X = 116
        private const val ARROW_WIDTH = 14
        private const val ARROW_HEIGHT = 9
        private val ARROW_COLOR: Int = 0xFF6E6E6E.toInt()
    }
}

/**
 * TaCZ 制作台配方的 JEI/HEI 分类。
 *
 * 这批配方是 `data/<ns>/recipes` 里的 `tacz:gun_smith_table_crafting`，**不是** vanilla
 * `IRecipe`，所以 JEI 不会自动显示，必须自己画。
 *
 * 槽位：产出在右、材料从左边起单行排开。实测材料条数 1~6（3 个包 235 条配方统计），
 * 第 6 格右边界 114 < 箭头 116~130 < 产出格 136，单行放得下，所以不做上游那种双行分支。
 *
 * 槽位框用 JEI 自己的 `getSlotDrawable()`，并且必须走 `IGuiIngredientGroup.setBackground`
 * 一格一格挂：分类背景是整张图，自己按坐标平移贴图会画歪（2026-11-06 试过，
 * `IDrawable.draw(mc, x, y)` 的两个 int 不是位置，结果只画出一格且压在物品上）。
 *
 * 刻意不做"+合成"按钮：工作台容器只有隐藏槽（`GunSmithTableContainer`），
 * JEI 的 transfer 拿不到可写槽位，接上必然失败。
 */
public class GunSmithTableCategory(
    private val guiHelper: IGuiHelper,
) : IRecipeCategory<GunSmithRecipeWrapper> {

    private val background: IDrawable = guiHelper.createBlankDrawable(WIDTH, HEIGHT)

    override fun getUid(): String = UID

    override fun getTitle(): String = I18n.format(TITLE_KEY)

    override fun getModName(): String = MOD_NAME

    override fun getBackground(): IDrawable = background

    override fun setRecipe(layout: IRecipeLayout, wrapper: GunSmithRecipeWrapper, ingredients: IIngredients) {
        val slots = layout.itemStacks
        val frame = guiHelper.slotDrawable
        slots.init(OUTPUT_SLOT, false, OUTPUT_X, SLOT_Y)
        slots.setBackground(OUTPUT_SLOT, frame)
        slots.set(OUTPUT_SLOT, wrapper.view.result)
        wrapper.view.materials.forEachIndexed { index, material ->
            val slot = OUTPUT_SLOT + index + 1
            slots.init(slot, true, MATERIAL_X + index * SLOT_SIZE, SLOT_Y)
            slots.setBackground(slot, frame)
            val stacks = material.sizedStacks()
            // 标签在本机一个物品都没有时（1.12 没有紫水晶之类），画个屏障并把缺的标签名写进名字，
            // 否则这一格是纯空白，玩家和包作者都不知道为什么做不出来。只影响显示，不参与匹配。
            if (stacks.isEmpty()) {
                slots.set(slot, missingMaterialStack(material))
            } else {
                slots.set(slot, stacks)
            }
        }
    }

    private fun missingMaterialStack(material: GunSmithMaterialView): ItemStack {
        val marker = ItemStack(Item.getByNameOrId("minecraft:barrier"))
        marker.grow(material.count - 1)
        marker.setStackDisplayName(I18n.format(MISSING_MATERIAL_KEY, material.missingTag ?: "?"))
        return marker
    }

    public companion object {
        public const val UID: String = "tacz.gun_smith_table"
        private const val TITLE_KEY = "jei.tacz.gun_smith_table.title"
        private const val MISSING_MATERIAL_KEY = "jei.tacz.missing_material"
        private const val MOD_NAME = "TaCZ-Legacy"
        private const val WIDTH = 160
        private const val HEIGHT = 36
        private const val SLOT_SIZE = 18
        private const val SLOT_Y = 9
        private const val MATERIAL_X = 6
        private const val OUTPUT_X = 136
        private const val OUTPUT_SLOT = 0
    }
}

/** 把这条材料需要的数量套到每个候选物品上（拷贝，绝不改 Ingredient 内部的 stack）。 */
private fun GunSmithMaterialView.sizedStacks(): List<ItemStack> =
    // 候选 stack 都是 size 1（`Ingredient.getMatchingStacks()` 的约定），所以 grow 到需要的数量即可；
    // 直接写 `stackSize` 在本工程映射下是私有字段，编译不过，`grow` 返回 void 所以用 also。
    options.map { stack -> stack.copy().also { it.grow(count - 1) } }
