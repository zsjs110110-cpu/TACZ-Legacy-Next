package com.tacz.legacy.integration.jei

import com.tacz.legacy.common.item.LegacyBlockItem
import com.tacz.legacy.common.item.LegacyItems
import mezz.jei.api.IModPlugin
import mezz.jei.api.ISubtypeRegistry
import mezz.jei.api.JEIPlugin
import net.minecraft.item.ItemStack

/**
 * JEI / HEI 兼容。
 *
 * 枪械、弹药、配件、弹药盒与工作台都只用一个物品 ID，具体种类保存在 NBT 中。
 * 不注册子类型时 JEI 会把它们全部当作同一个物品合并显示（列表中只剩一把枪/一种弹药）。
 * 只有安装了 JEI/HEI 时该类才会被加载。
 */
@JEIPlugin
public class TACZJeiPlugin : IModPlugin {
    override fun registerItemSubtypes(subtypeRegistry: ISubtypeRegistry) {
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.MODERN_KINETIC_GUN) { stack ->
            LegacyItems.MODERN_KINETIC_GUN.getGunId(stack).toString()
        }
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.AMMO) { stack ->
            LegacyItems.AMMO.getAmmoId(stack).toString()
        }
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.ATTACHMENT) { stack ->
            LegacyItems.ATTACHMENT.getAttachmentId(stack).toString()
        }
        subtypeRegistry.registerSubtypeInterpreter(LegacyItems.AMMO_BOX) { stack ->
            ammoBoxSubtype(stack)
        }
        listOf(
            LegacyItems.GUN_SMITH_TABLE,
            LegacyItems.WORKBENCH_A,
            LegacyItems.WORKBENCH_B,
            LegacyItems.WORKBENCH_C,
        ).forEach { item: LegacyBlockItem ->
            subtypeRegistry.registerSubtypeInterpreter(item) { stack -> item.getBlockId(stack).toString() }
        }
    }

    private fun ammoBoxSubtype(stack: ItemStack): String {
        val item = LegacyItems.AMMO_BOX
        return when {
            item.isAllTypeCreative(stack) -> "all_type_creative"
            item.isCreative(stack) -> "creative"
            else -> "level_${item.getAmmoLevel(stack)}"
        }
    }
}
