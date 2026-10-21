package com.tacz.legacy.common.registry

import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.creativetab.CreativeTabs
import net.minecraft.item.ItemStack
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import java.util.Locale

internal object LegacyCreativeTabs {
    internal val OTHER: CreativeTabs = object : DynamicIconTab("tacz.other") {
        override fun createIcon(): ItemStack = ItemStack(LegacyItems.GUN_SMITH_TABLE)
    }

    internal val AMMO: CreativeTabs = object : DynamicIconTab("tacz.ammo") {
        override fun createIcon(): ItemStack {
            val ammo = TACZGunPackPresentation.sortedAmmos(TACZGunPackRuntimeRegistry.getSnapshot()).firstOrNull()
            return if (ammo == null) {
                ItemStack(LegacyItems.AMMO)
            } else {
                ItemStack(LegacyItems.AMMO).apply {
                    LegacyItems.AMMO.setAmmoId(this, ammo.key)
                }
            }
        }
    }

    internal val PARTS: CreativeTabs = object : DynamicIconTab("tacz.attachments.all") {
        override fun createIcon(): ItemStack = firstAttachmentIcon(null)
    }

    internal val ATTACHMENT_SCOPE: CreativeTabs = object : DynamicIconTab("tacz.attachments.scope") {
        override fun createIcon(): ItemStack = firstAttachmentIcon(AttachmentType.SCOPE)
    }

    internal val ATTACHMENT_MUZZLE: CreativeTabs = object : DynamicIconTab("tacz.attachments.muzzle") {
        override fun createIcon(): ItemStack = firstAttachmentIcon(AttachmentType.MUZZLE)
    }

    internal val ATTACHMENT_STOCK: CreativeTabs = object : DynamicIconTab("tacz.attachments.stock") {
        override fun createIcon(): ItemStack = firstAttachmentIcon(AttachmentType.STOCK)
    }

    internal val ATTACHMENT_GRIP: CreativeTabs = object : DynamicIconTab("tacz.attachments.grip") {
        override fun createIcon(): ItemStack = firstAttachmentIcon(AttachmentType.GRIP)
    }

    internal val ATTACHMENT_EXTENDED_MAG: CreativeTabs = object : DynamicIconTab("tacz.attachments.extended_mag") {
        override fun createIcon(): ItemStack = firstAttachmentIcon(AttachmentType.EXTENDED_MAG)
    }

    internal val ATTACHMENT_LASER: CreativeTabs = object : DynamicIconTab("tacz.attachments.laser") {
        override fun createIcon(): ItemStack = firstAttachmentIcon(AttachmentType.LASER)
    }

    internal val GUNS: CreativeTabs = object : DynamicIconTab("tacz.guns.all") {
        override fun createIcon(): ItemStack = firstGunIcon(null)
    }

    internal val GUN_PISTOL: CreativeTabs = object : DynamicIconTab("tacz.guns.pistol") {
        override fun createIcon(): ItemStack = firstGunIcon("pistol")
    }

    internal val GUN_SNIPER: CreativeTabs = object : DynamicIconTab("tacz.guns.sniper") {
        override fun createIcon(): ItemStack = firstGunIcon("sniper")
    }

    internal val GUN_RIFLE: CreativeTabs = object : DynamicIconTab("tacz.guns.rifle") {
        override fun createIcon(): ItemStack = firstGunIcon("rifle")
    }

    internal val GUN_SHOTGUN: CreativeTabs = object : DynamicIconTab("tacz.guns.shotgun") {
        override fun createIcon(): ItemStack = firstGunIcon("shotgun")
    }

    internal val GUN_SMG: CreativeTabs = object : DynamicIconTab("tacz.guns.smg") {
        override fun createIcon(): ItemStack = firstGunIcon("smg")
    }

    internal val GUN_RPG: CreativeTabs = object : DynamicIconTab("tacz.guns.rpg") {
        override fun createIcon(): ItemStack = firstGunIcon("rpg")
    }

    internal val GUN_MG: CreativeTabs = object : DynamicIconTab("tacz.guns.mg") {
        override fun createIcon(): ItemStack = firstGunIcon("mg")
    }

    internal val DECORATION: CreativeTabs = OTHER

    private val gunTabsByType: LinkedHashMap<String, CreativeTabs> = linkedMapOf(
        "pistol" to GUN_PISTOL,
        "sniper" to GUN_SNIPER,
        "rifle" to GUN_RIFLE,
        "shotgun" to GUN_SHOTGUN,
        "smg" to GUN_SMG,
        "rpg" to GUN_RPG,
        "mg" to GUN_MG,
    )

    private val attachmentTabsByType: LinkedHashMap<AttachmentType, CreativeTabs> = linkedMapOf(
        AttachmentType.SCOPE to ATTACHMENT_SCOPE,
        AttachmentType.MUZZLE to ATTACHMENT_MUZZLE,
        AttachmentType.STOCK to ATTACHMENT_STOCK,
        AttachmentType.GRIP to ATTACHMENT_GRIP,
        AttachmentType.EXTENDED_MAG to ATTACHMENT_EXTENDED_MAG,
        AttachmentType.LASER to ATTACHMENT_LASER,
    )

    internal val gunCategoryTabs: Array<CreativeTabs> = arrayOf(
        GUNS,
        GUN_PISTOL,
        GUN_SNIPER,
        GUN_RIFLE,
        GUN_SHOTGUN,
        GUN_SMG,
        GUN_RPG,
        GUN_MG,
    )

    internal val attachmentCategoryTabs: Array<CreativeTabs> = arrayOf(
        PARTS,
        ATTACHMENT_SCOPE,
        ATTACHMENT_MUZZLE,
        ATTACHMENT_STOCK,
        ATTACHMENT_GRIP,
        ATTACHMENT_EXTENDED_MAG,
        ATTACHMENT_LASER,
    )

    internal fun gunCreativeTabsForType(rawType: String?): Array<CreativeTabs> {
        val specificTab = gunTabsByType[normalizeType(rawType)] ?: return arrayOf(GUNS)
        return arrayOf(GUNS, specificTab)
    }

    internal fun attachmentCreativeTabsForType(type: AttachmentType): Array<CreativeTabs> {
        val specificTab = attachmentTabsByType[type] ?: return arrayOf(PARTS)
        return arrayOf(PARTS, specificTab)
    }

    internal fun gunTypeForTab(tab: CreativeTabs): String? = gunTabsByType.entries.firstOrNull { it.value === tab }?.key

    internal fun attachmentTypeForTab(tab: CreativeTabs): AttachmentType? =
        attachmentTabsByType.entries.firstOrNull { it.value === tab }?.key

    private fun firstGunIcon(type: String?): ItemStack {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gun = TACZGunPackPresentation.sortedGuns(snapshot)
            .firstOrNull { type == null || normalizeType(it.index.type) == type }
        return if (gun == null) {
            ItemStack(LegacyItems.MODERN_KINETIC_GUN)
        } else {
            ItemStack(LegacyItems.MODERN_KINETIC_GUN).apply {
                LegacyItems.MODERN_KINETIC_GUN.setGunId(this, gun.id)
            }
        }
    }

    private fun firstAttachmentIcon(type: AttachmentType?): ItemStack {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachment = TACZGunPackPresentation.sortedAttachments(snapshot)
            .firstOrNull { type == null || AttachmentType.fromSerializedName(it.index.type) == type }
        return if (attachment == null) {
            ItemStack(LegacyItems.ATTACHMENT)
        } else {
            ItemStack(LegacyItems.ATTACHMENT).apply {
                LegacyItems.ATTACHMENT.setAttachmentId(this, attachment.id)
            }
        }
    }

    private fun normalizeType(rawType: String?): String = rawType?.trim()?.lowercase(Locale.ROOT).orEmpty()

    private abstract class DynamicIconTab(label: String) : CreativeTabs(label) {
        @SideOnly(Side.CLIENT)
        override fun getIcon(): ItemStack = createIcon()
    }
}
