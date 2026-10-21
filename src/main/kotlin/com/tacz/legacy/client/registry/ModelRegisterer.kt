package com.tacz.legacy.client.registry

import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.client.model.TACZPerspectiveAwareBakedModel
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.item.AmmoBoxItem
import com.tacz.legacy.common.item.LegacyItems
import net.minecraft.client.renderer.block.model.ModelResourceLocation
import net.minecraft.client.renderer.color.IItemColor
import net.minecraft.item.IItemPropertyGetter
import net.minecraft.item.Item
import net.minecraft.util.ResourceLocation
import net.minecraftforge.client.event.ColorHandlerEvent
import net.minecraftforge.client.event.ModelBakeEvent
import net.minecraftforge.client.event.ModelRegistryEvent
import net.minecraftforge.client.model.ModelLoader
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

@SideOnly(Side.CLIENT)
internal object ModelRegisterer {
    private val AMMO_STATUE_PROPERTY: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "ammo_statue")
    /**
     * Items whose baked models should be wrapped with [TACZPerspectiveAwareBakedModel]
     * so that the TEISRs can read the current [ItemCameraTransforms.TransformType].
     */
    private val perspectiveAwareItems: List<Item> = listOf(
        LegacyItems.MODERN_KINETIC_GUN,
        LegacyItems.AMMO,
        LegacyItems.ATTACHMENT,
        LegacyItems.GUN_SMITH_TABLE,
        LegacyItems.WORKBENCH_A,
        LegacyItems.WORKBENCH_B,
        LegacyItems.WORKBENCH_C,
    )

    @SubscribeEvent
    internal fun onModelRegister(event: ModelRegistryEvent): Unit {
        LegacyItems.allItems.forEach(::registerInventoryModel)
    }

    @SubscribeEvent
    internal fun onModelBake(event: ModelBakeEvent) {
        val registry = event.modelRegistry
        var wrapped = 0
        for (item in perspectiveAwareItems) {
            val id = item.registryName ?: continue
            val mrl = ModelResourceLocation(id, "inventory")
            val original = registry.getObject(mrl) ?: continue
            if (original is TACZPerspectiveAwareBakedModel) continue
            registry.putObject(mrl, TACZPerspectiveAwareBakedModel(original))
            wrapped++
        }
        TACZLegacy.logger.info("[ModelRegisterer] Wrapped {} item models with perspective-aware bridge", wrapped)
    }

    private fun registerInventoryModel(item: Item): Unit {
        val id = requireNotNull(item.registryName)
        ModelLoader.setCustomModelResourceLocation(item, 0, ModelResourceLocation(id, "inventory"))
    }

    @SubscribeEvent
    internal fun onItemColor(event: ColorHandlerEvent.Item): Unit {
        event.itemColors.registerItemColorHandler(
            IItemColor { stack, tintIndex ->
                if (tintIndex > 0) {
                    -1
                } else {
                    AmmoBoxItem.getTintColor(stack)
                }
            },
            LegacyItems.AMMO_BOX,
        )
    }

    internal fun registerAmmoBoxPropertyOverride(): Unit {
        LegacyItems.AMMO_BOX.addPropertyOverride(
            AMMO_STATUE_PROPERTY,
            IItemPropertyGetter { stack, _, _ ->
                val box = stack.item as? IAmmoBox ?: return@IItemPropertyGetter 0f
                AmmoBoxItem.getStatue(stack, box).toFloat()
            },
        )
    }
}
