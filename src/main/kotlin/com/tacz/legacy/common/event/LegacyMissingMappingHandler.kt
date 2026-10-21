package com.tacz.legacy.common.event

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.registry.LegacyRegistryAliases
import net.minecraft.block.Block
import net.minecraft.item.Item
import net.minecraft.util.ResourceLocation
import net.minecraftforge.event.RegistryEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.registry.ForgeRegistries

internal object LegacyMissingMappingHandler {
    @SubscribeEvent
    internal fun onMissingBlocks(event: RegistryEvent.MissingMappings<Block>): Unit {
        event.allMappings
            .asSequence()
            .filter { it.key.namespace == TACZLegacy.MOD_ID }
            .forEach { mapping ->
                val replacementPath = LegacyRegistryAliases.resolveBlockAlias(mapping.key.path) ?: return@forEach
                val replacement = ForgeRegistries.BLOCKS.getValue(ResourceLocation(TACZLegacy.MOD_ID, replacementPath)) ?: return@forEach
                mapping.remap(replacement)
            }
    }

    @SubscribeEvent
    internal fun onMissingItems(event: RegistryEvent.MissingMappings<Item>): Unit {
        event.allMappings
            .asSequence()
            .filter { it.key.namespace == TACZLegacy.MOD_ID }
            .forEach { mapping ->
                val replacementPath = LegacyRegistryAliases.resolveItemAlias(mapping.key.path) ?: return@forEach
                val replacement = ForgeRegistries.ITEMS.getValue(ResourceLocation(TACZLegacy.MOD_ID, replacementPath)) ?: return@forEach
                mapping.remap(replacement)
            }
    }
}
