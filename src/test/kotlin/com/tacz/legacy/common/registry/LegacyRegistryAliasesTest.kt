package com.tacz.legacy.common.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyRegistryAliasesTest {
    @Test
    fun `known aliases resolve to active names`() {
        assertEquals("gun_smith_table", LegacyRegistryAliases.resolveBlockAlias("weapon_workbench"))
        assertEquals("target", LegacyRegistryAliases.resolveItemAlias("steel_target"))
    }

    @Test
    fun `unknown aliases remain unresolved`() {
        assertNull(LegacyRegistryAliases.resolveBlockAlias("totally_new_block"))
        assertNull(LegacyRegistryAliases.resolveItemAlias("totally_new_item"))
    }
}
