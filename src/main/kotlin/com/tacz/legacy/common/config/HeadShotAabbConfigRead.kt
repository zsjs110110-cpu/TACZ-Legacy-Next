package com.tacz.legacy.common.config

import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.AxisAlignedBB
import java.util.LinkedHashMap
import java.util.regex.Pattern

internal object HeadShotAabbConfigRead {
    private val aabbByEntityId: MutableMap<ResourceLocation, AxisAlignedBB> = LinkedHashMap()

    private val pattern: Pattern = Pattern.compile(
        "^([a-z0-9_.-]+:[a-z0-9/._-]+)\\s*\\[([-+]?[0-9]*\\.?[0-9]+),\\s*([-+]?[0-9]*\\.?[0-9]+),\\s*([-+]?[0-9]*\\.?[0-9]+),\\s*([-+]?[0-9]*\\.?[0-9]+),\\s*([-+]?[0-9]*\\.?[0-9]+),\\s*([-+]?[0-9]*\\.?[0-9]+),*\\s*]$"
    )

    internal fun reload(entries: List<String>): Unit {
        aabbByEntityId.clear()
        entries.forEach(::addCheck)
    }

    internal fun addCheck(raw: String): Unit {
        val matcher = pattern.matcher(raw.trim())
        if (!matcher.find()) {
            return
        }
        val id = ResourceLocation(matcher.group(1))
        val x1 = matcher.group(2).toDouble()
        val y1 = matcher.group(3).toDouble()
        val z1 = matcher.group(4).toDouble()
        val x2 = matcher.group(5).toDouble()
        val y2 = matcher.group(6).toDouble()
        val z2 = matcher.group(7).toDouble()
        aabbByEntityId[id] = AxisAlignedBB(x1, y1, z1, x2, y2, z2)
    }

    internal fun clear(): Unit {
        aabbByEntityId.clear()
    }

    internal fun getAabb(id: ResourceLocation): AxisAlignedBB? = aabbByEntityId[id]

    internal fun snapshot(): Map<ResourceLocation, AxisAlignedBB> = LinkedHashMap(aabbByEntityId)
}
