package com.tacz.legacy.common.config

import net.minecraft.util.ResourceLocation
import java.util.EnumMap
import java.util.LinkedHashSet

internal enum class InteractListType {
    BLOCK,
    ENTITY,
}

internal object InteractKeyConfigRead {
    private val whitelist: EnumMap<InteractListType, MutableSet<ResourceLocation>> = EnumMap(InteractListType::class.java)
    private val blacklist: EnumMap<InteractListType, MutableSet<ResourceLocation>> = EnumMap(InteractListType::class.java)

    internal fun reload(
        blockWhitelist: List<String>,
        entityWhitelist: List<String>,
        blockBlacklist: List<String>,
        entityBlacklist: List<String>,
    ): Unit {
        whitelist.clear()
        blacklist.clear()
        handleConfigData(blockWhitelist, whitelist, InteractListType.BLOCK)
        handleConfigData(entityWhitelist, whitelist, InteractListType.ENTITY)
        handleConfigData(blockBlacklist, blacklist, InteractListType.BLOCK)
        handleConfigData(entityBlacklist, blacklist, InteractListType.ENTITY)
    }

    internal fun canInteractBlock(id: ResourceLocation): Boolean {
        if (blacklist[InteractListType.BLOCK]?.contains(id) == true) {
            return false
        }
        return whitelist[InteractListType.BLOCK]?.contains(id) == true
    }

    internal fun canInteractEntity(id: ResourceLocation): Boolean {
        if (blacklist[InteractListType.ENTITY]?.contains(id) == true) {
            return false
        }
        return whitelist[InteractListType.ENTITY]?.contains(id) == true
    }

    internal fun snapshotWhitelist(type: InteractListType): Set<ResourceLocation> = LinkedHashSet(whitelist[type] ?: emptySet())

    internal fun snapshotBlacklist(type: InteractListType): Set<ResourceLocation> = LinkedHashSet(blacklist[type] ?: emptySet())

    private fun handleConfigData(
        configData: List<String>,
        store: EnumMap<InteractListType, MutableSet<ResourceLocation>>,
        type: InteractListType,
    ): Unit {
        configData
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .forEach { raw ->
                runCatching { ResourceLocation(raw) }
                    .onSuccess { id -> store.computeIfAbsent(type) { LinkedHashSet() }.add(id) }
            }
    }
}
