package com.tacz.legacy.api.item.gun

import net.minecraft.item.Item
import java.util.LinkedHashMap

public object GunItemManager {
    private val gunItems: MutableMap<String, Item> = LinkedHashMap()

    @JvmStatic
    public fun registerGunItem(typeName: String, item: Item): Unit {
        gunItems[typeName] = item
    }

    @JvmStatic
    public fun getRegisteredGunItem(typeName: String): Item? = gunItems[typeName]

    @JvmStatic
    public fun snapshot(): Map<String, Item> = LinkedHashMap(gunItems)

    @JvmStatic
    public fun clear(): Unit {
        gunItems.clear()
    }
}
