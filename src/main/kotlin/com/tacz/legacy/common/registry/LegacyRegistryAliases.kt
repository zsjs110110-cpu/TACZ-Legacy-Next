package com.tacz.legacy.common.registry

internal object LegacyRegistryAliases {
    private val blockAliases: Map<String, String> = mapOf(
        "weapon_workbench" to "gun_smith_table",
        "steel_target" to "target",
    )

    private val itemAliases: Map<String, String> = mapOf(
        "weapon_workbench" to "gun_smith_table",
        "steel_target" to "target",
    )

    internal fun resolveBlockAlias(path: String): String? = blockAliases[path]

    internal fun resolveItemAlias(path: String): String? = itemAliases[path]
}
