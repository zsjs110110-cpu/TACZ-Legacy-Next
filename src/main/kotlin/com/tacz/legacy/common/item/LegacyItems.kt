package com.tacz.legacy.common.item

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IAmmoBox
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.item.gun.FireMode
import com.tacz.legacy.api.item.gun.GunItemManager
import com.tacz.legacy.common.block.LegacyBlocks
import com.tacz.legacy.common.block.LegacyGunSmithTableBlock
import com.tacz.legacy.common.block.entity.GunSmithTableTileEntity
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.resource.BoltType
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import com.tacz.legacy.common.registry.LegacyCreativeTabs
import com.tacz.legacy.common.entity.TargetMinecartEntity
import net.minecraft.block.BlockRailBase
import net.minecraft.block.state.IBlockState
import net.minecraft.client.util.ITooltipFlag
import net.minecraft.creativetab.CreativeTabs
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.Item
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.EnumActionResult
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.NonNullList
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.BlockPos
import net.minecraft.util.text.TextFormatting
import net.minecraftforge.items.CapabilityItemHandler
import net.minecraftforge.items.IItemHandler
import net.minecraftforge.items.IItemHandlerModifiable
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import net.minecraftforge.registries.IForgeRegistry
import net.minecraft.world.World

internal object LegacyItems {
    internal val MODERN_KINETIC_GUN: ModernKineticGunItem = ModernKineticGunItem().named("modern_kinetic_gun", LegacyCreativeTabs.GUNS)
    internal val AMMO: AmmoItem = AmmoItem().named("ammo", LegacyCreativeTabs.AMMO)
    internal val ATTACHMENT: AttachmentItem = AttachmentItem().named("attachment", LegacyCreativeTabs.PARTS)
    internal val AMMO_BOX: AmmoBoxItem = AmmoBoxItem().named("ammo_box", LegacyCreativeTabs.OTHER)
    internal val TARGET_MINECART: TargetMinecartItem = TargetMinecartItem().named("target_minecart", LegacyCreativeTabs.OTHER)

    internal val GUN_SMITH_TABLE: LegacyBlockItem = createBlockItem(LegacyBlocks.GUN_SMITH_TABLE)
    internal val WORKBENCH_A: LegacyBlockItem = createBlockItem(LegacyBlocks.WORKBENCH_A)
    internal val WORKBENCH_B: LegacyBlockItem = createBlockItem(LegacyBlocks.WORKBENCH_B)
    internal val WORKBENCH_C: LegacyBlockItem = createBlockItem(LegacyBlocks.WORKBENCH_C)
    internal val TARGET: LegacyBlockItem = createBlockItem(LegacyBlocks.TARGET)
    internal val STATUE: LegacyBlockItem = createBlockItem(LegacyBlocks.STATUE)

    internal val allItems: List<Item> = listOf(
        MODERN_KINETIC_GUN,
        AMMO,
        ATTACHMENT,
        AMMO_BOX,
        TARGET_MINECART,
        GUN_SMITH_TABLE,
        WORKBENCH_A,
        WORKBENCH_B,
        WORKBENCH_C,
        TARGET,
        STATUE,
    )

    internal fun registerAll(registry: IForgeRegistry<Item>): Unit {
        allItems.forEach(registry::register)
        GunItemManager.clear()
        val itemTypes = TACZGunPackRuntimeRegistry.getSnapshot().gunItemTypes().ifEmpty { setOf(ModernKineticGunItem.TYPE_NAME) }
        itemTypes.forEach { typeName ->
            GunItemManager.registerGunItem(typeName, MODERN_KINETIC_GUN)
        }
        TACZLegacy.logger.info("[GunPackRuntime] Registered {} gun item type mapping(s): {}", itemTypes.size, itemTypes.joinToString())
    }

    private fun createBlockItem(block: net.minecraft.block.Block): LegacyBlockItem = LegacyBlockItem(block).apply {
        registryName = requireNotNull(block.registryName)
        setTranslationKey("${TACZLegacy.MOD_ID}.${requireNotNull(block.registryName).path}")
        setCreativeTab(LegacyCreativeTabs.DECORATION)
    }

    private fun <T : Item> T.named(path: String, tab: CreativeTabs): T {
        registryName = ResourceLocation(TACZLegacy.MOD_ID, path)
        setTranslationKey("${TACZLegacy.MOD_ID}.$path")
        setCreativeTab(tab)
        return this
    }
}

internal open class LegacySimpleItem(maxStackSize: Int = 64) : Item() {
    init {
        this.maxStackSize = maxStackSize
    }
}

/**
 * 靶车物品：与原版矿车物品一样只能放置在铁轨上（原先为普通物品，无法放置）。
 */
internal class TargetMinecartItem : LegacySimpleItem(maxStackSize = 1) {
    override fun onItemUse(
        player: EntityPlayer,
        worldIn: World,
        pos: BlockPos,
        hand: EnumHand,
        facing: EnumFacing,
        hitX: Float,
        hitY: Float,
        hitZ: Float,
    ): EnumActionResult {
        val state = worldIn.getBlockState(pos)
        if (!BlockRailBase.isRailBlock(state)) {
            return EnumActionResult.FAIL
        }
        val stack = player.getHeldItem(hand)
        if (!worldIn.isRemote) {
            val railBlock = state.block as? BlockRailBase
            val direction = railBlock?.getRailDirection(worldIn, pos, state, null)
                ?: BlockRailBase.EnumRailDirection.NORTH_SOUTH
            val yOffset = if (direction.isAscending) 0.5 else 0.0
            val cart = TargetMinecartEntity(worldIn, pos.x + 0.5, pos.y + 0.0625 + yOffset, pos.z + 0.5)
            if (stack.hasDisplayName()) {
                cart.customNameTag = stack.displayName
            }
            worldIn.spawnEntity(cart)
        }
        stack.shrink(1)
        return EnumActionResult.SUCCESS
    }
}

/**
 * 弹药物品。实现 IAmmo 使其可被 hasInventoryAmmo 识别。
 * NBT 格式与上游 TACZ AmmoItemDataAccessor 一致。
 */
internal class AmmoItem : Item(), IAmmo {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = arrayOf(LegacyCreativeTabs.AMMO)

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val ammos = TACZGunPackPresentation.sortedAmmos(snapshot)
        if (ammos.isEmpty()) {
            items += ItemStack(this)
            return
        }
        ammos.forEach { (ammoId, _) ->
            val stack = ItemStack(this)
            setAmmoId(stack, ammoId)
            items += stack
        }
    }

    override fun getAmmoId(ammo: ItemStack): ResourceLocation {
        val tag = ammo.tagCompound ?: return DefaultAssets.EMPTY_AMMO_ID
        val str = tag.getString(AMMO_ID_TAG)
        return if (str.isBlank()) DefaultAssets.EMPTY_AMMO_ID else ResourceLocation(str)
    }

    override fun setAmmoId(ammo: ItemStack, ammoId: ResourceLocation?) {
        ensureTag(ammo).setString(AMMO_ID_TAG, (ammoId ?: DefaultAssets.DEFAULT_AMMO_ID).toString())
    }

    override fun isAmmoOfGun(gun: ItemStack, ammo: ItemStack): Boolean {
        val iGun = gun.item as? IGun ?: return false
        val iAmmo = ammo.item as? IAmmo ?: return false
        val gunId = iGun.getGunId(gun)
        val ammoId = iAmmo.getAmmoId(ammo)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunEntry = snapshot.guns[gunId] ?: return false
        return gunEntry.data.ammoId == ammoId
    }

    override fun getItemStackLimit(stack: ItemStack): Int {
        val id = getAmmoId(stack)
        val def = TACZGunPackRuntimeRegistry.getSnapshot().ammos[id]
        return def?.stackSize ?: 1
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val ammoId = getAmmoId(stack)
        return TACZGunPackPresentation.localizedAmmoName(snapshot, ammoId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val ammoId = getAmmoId(stack)
        TACZGunPackPresentation.localizedAmmoTooltip(snapshot, ammoId)?.let { tooltip += "${TextFormatting.GRAY}$it" }
        TACZGunPackPresentation.localizedPackName(snapshot, ammoId)?.let { tooltip += "${TextFormatting.BLUE}${TextFormatting.ITALIC}$it" }
        appendAdvancedTooltip(
            tooltip = tooltip,
            flag = flagIn,
            runtimeId = ammoId,
            displayId = TACZGunPackPresentation.resolveAmmoDisplayId(snapshot, ammoId),
        )
    }

    internal companion object {
        internal const val AMMO_ID_TAG: String = "AmmoId"
    }
}

internal class AttachmentItem : Item(), IAttachment {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = LegacyCreativeTabs.attachmentCategoryTabs

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val requestedType = LegacyCreativeTabs.attachmentTypeForTab(tab)
        val attachments = TACZGunPackPresentation.sortedAttachments(snapshot)
        if (attachments.isEmpty()) {
            items += ItemStack(this)
            return
        }
        attachments.asSequence()
            .filter { attachment -> requestedType == null || AttachmentType.fromSerializedName(attachment.index.type) == requestedType }
            .forEach { attachment ->
            val stack = ItemStack(this)
            setAttachmentId(stack, attachment.id)
            items += stack
        }
    }

    override fun getAttachmentId(stack: ItemStack): ResourceLocation {
        val value = stack.tagCompound?.getString(ATTACHMENT_ID_TAG).orEmpty()
        return value.takeIf(String::isNotBlank)?.let(::ResourceLocation) ?: DefaultAssets.EMPTY_ATTACHMENT_ID
    }

    override fun setAttachmentId(stack: ItemStack, attachmentId: ResourceLocation?) {
        if (attachmentId == null) {
            ensureTag(stack).removeTag(ATTACHMENT_ID_TAG)
            return
        }
        ensureTag(stack).setString(ATTACHMENT_ID_TAG, attachmentId.toString())
    }

    override fun getZoomNumber(stack: ItemStack): Int {
        return stack.tagCompound?.getInteger(ZOOM_NUMBER_TAG) ?: 0
    }

    override fun setZoomNumber(stack: ItemStack, zoomNumber: Int) {
        ensureTag(stack).setInteger(ZOOM_NUMBER_TAG, zoomNumber)
    }

    override fun getType(stack: ItemStack): AttachmentType {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachmentId = getAttachmentId(stack)
        val rawType = snapshot.attachments[attachmentId]?.index?.type
        return AttachmentType.fromSerializedName(rawType)
    }

    override fun hasCustomLaserColor(stack: ItemStack): Boolean {
        return stack.tagCompound?.hasKey(LASER_COLOR_TAG, NbtType.INT) ?: false
    }

    override fun getLaserColor(stack: ItemStack): Int {
        if (!hasCustomLaserColor(stack)) {
            return DEFAULT_LASER_COLOR
        }
        return stack.tagCompound?.getInteger(LASER_COLOR_TAG) ?: DEFAULT_LASER_COLOR
    }

    override fun setLaserColor(stack: ItemStack, color: Int) {
        ensureTag(stack).setInteger(LASER_COLOR_TAG, color)
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachmentId = getAttachmentId(stack)
        return TACZGunPackPresentation.localizedAttachmentName(snapshot, attachmentId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val attachmentId = getAttachmentId(stack)
        TACZGunPackPresentation.localizedAttachmentTooltip(snapshot, attachmentId)?.let { tooltip += "${TextFormatting.GRAY}$it" }
        TACZGunPackPresentation.localizedAttachmentTypeName(snapshot, attachmentId)?.let { tooltip += "${TextFormatting.DARK_AQUA}$it" }
        val compatibleGuns = TACZGunPackPresentation.compatibleGunCount(snapshot, attachmentId)
        if (compatibleGuns > 0) {
            tooltip += "${TextFormatting.GRAY}Compatible guns: $compatibleGuns"
        }
        TACZGunPackPresentation.localizedPackName(snapshot, attachmentId)?.let { tooltip += "${TextFormatting.BLUE}${TextFormatting.ITALIC}$it" }
        appendAdvancedTooltip(
            tooltip = tooltip,
            flag = flagIn,
            runtimeId = attachmentId,
            displayId = TACZGunPackPresentation.resolveAttachmentDisplayId(snapshot, attachmentId),
        )
    }

    internal companion object {
        internal const val ATTACHMENT_ID_TAG: String = "AttachmentId"
        internal const val ZOOM_NUMBER_TAG: String = "ZoomNumber"
        internal const val LASER_COLOR_TAG: String = "LaserColor"
        private const val DEFAULT_LASER_COLOR: Int = 0xFF0000
    }
}

/**
 * 子弹盒物品。实现 IAmmoBox 使其可被 hasInventoryAmmo 识别。
 * NBT 格式与上游 TACZ AmmoBoxItemDataAccessor 一致。
 */
internal class AmmoBoxItem : Item(), IAmmoBox {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = arrayOf(LegacyCreativeTabs.OTHER)

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val base = ItemStack(this)
        items.add(setAmmoLevel(base.copy(), IRON_LEVEL))
        items.add(setAmmoLevel(base.copy(), GOLD_LEVEL))
        items.add(setAmmoLevel(base.copy(), DIAMOND_LEVEL))
        items.add(setCreative(base.copy(), true))
        val allTypeCreative = base.copy()
        setAllTypeCreative(allTypeCreative, true)
        items.add(allTypeCreative)
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val key = when {
            isAllTypeCreative(stack) -> "item.tacz.ammo_box.all_type_creative"
            isCreative(stack) -> "item.tacz.ammo_box.creative"
            getAmmoLevel(stack) == GOLD_LEVEL -> "item.tacz.ammo_box.gold"
            getAmmoLevel(stack) == DIAMOND_LEVEL -> "item.tacz.ammo_box.diamond"
            else -> "item.tacz.ammo_box.iron"
        }
        val color = when {
            isAllTypeCreative(stack) || isCreative(stack) -> net.minecraft.util.text.TextFormatting.DARK_PURPLE
            getAmmoLevel(stack) == GOLD_LEVEL -> net.minecraft.util.text.TextFormatting.YELLOW
            getAmmoLevel(stack) == DIAMOND_LEVEL -> net.minecraft.util.text.TextFormatting.AQUA
            else -> net.minecraft.util.text.TextFormatting.GRAY
        }
        return color.toString() + net.minecraft.util.text.translation.I18n.translateToLocal(key)
    }

    override fun getAmmoId(ammoBox: ItemStack): ResourceLocation {
        val tag = ammoBox.tagCompound ?: return DefaultAssets.EMPTY_AMMO_ID
        val str = tag.getString(AMMO_ID_TAG)
        return if (str.isBlank()) DefaultAssets.EMPTY_AMMO_ID else ResourceLocation(str)
    }

    override fun setAmmoId(ammoBox: ItemStack, ammoId: ResourceLocation) {
        ensureTag(ammoBox).setString(AMMO_ID_TAG, ammoId.toString())
    }

    override fun getAmmoCount(ammoBox: ItemStack): Int {
        if (isAllTypeCreative(ammoBox) || isCreative(ammoBox)) return Int.MAX_VALUE
        return ammoBox.tagCompound?.getInteger(AMMO_COUNT_TAG) ?: 0
    }

    override fun setAmmoCount(ammoBox: ItemStack, count: Int) {
        if (isCreative(ammoBox)) {
            ensureTag(ammoBox).setInteger(AMMO_COUNT_TAG, Int.MAX_VALUE)
            return
        }
        ensureTag(ammoBox).setInteger(AMMO_COUNT_TAG, count)
    }

    override fun isAmmoBoxOfGun(gun: ItemStack, ammoBox: ItemStack): Boolean {
        val iGun = gun.item as? IGun ?: return false
        if (isAllTypeCreative(ammoBox)) return true
        val boxAmmoId = getAmmoId(ammoBox)
        if (boxAmmoId == DefaultAssets.EMPTY_AMMO_ID) return false
        val gunId = iGun.getGunId(gun)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunEntry = snapshot.guns[gunId] ?: return false
        return gunEntry.data.ammoId == boxAmmoId
    }

    override fun isCreative(ammoBox: ItemStack): Boolean {
        return ammoBox.tagCompound?.getBoolean(CREATIVE_TAG) ?: false
    }

    override fun isAllTypeCreative(ammoBox: ItemStack): Boolean {
        return ammoBox.tagCompound?.getBoolean(ALL_TYPE_CREATIVE_TAG) ?: false
    }

    override fun getAmmoLevel(ammoBox: ItemStack): Int {
        return ammoBox.tagCompound?.getInteger(AMMO_LEVEL_TAG) ?: IRON_LEVEL
    }

    override fun setAmmoLevel(ammoBox: ItemStack, level: Int): ItemStack {
        ensureTag(ammoBox).setInteger(AMMO_LEVEL_TAG, level.coerceIn(IRON_LEVEL, DIAMOND_LEVEL))
        return ammoBox
    }

    override fun setCreative(ammoBox: ItemStack, creative: Boolean): ItemStack {
        ensureTag(ammoBox).setBoolean(CREATIVE_TAG, creative)
        if (creative) {
            setAmmoCount(ammoBox, Int.MAX_VALUE)
        }
        return ammoBox
    }

    private fun setAllTypeCreative(ammoBox: ItemStack, allTypeCreative: Boolean): ItemStack {
        ensureTag(ammoBox).setBoolean(ALL_TYPE_CREATIVE_TAG, allTypeCreative)
        if (allTypeCreative) {
            setAmmoCount(ammoBox, Int.MAX_VALUE)
        }
        return ammoBox
    }

    internal companion object {
        internal const val AMMO_ID_TAG: String = "AmmoId"
        internal const val AMMO_COUNT_TAG: String = "AmmoCount"
        internal const val AMMO_LEVEL_TAG: String = "AmmoLevel"
        internal const val CREATIVE_TAG: String = "Creative"
        internal const val ALL_TYPE_CREATIVE_TAG: String = "AllTypeCreative"
        internal const val IRON_LEVEL: Int = 0
        internal const val GOLD_LEVEL: Int = 1
        internal const val DIAMOND_LEVEL: Int = 2
        private const val DEFAULT_TINT_COLOR: Int = 0x555555

        @JvmStatic
        internal fun getStatue(stack: ItemStack, box: IAmmoBox): Int {
            if (box.isAllTypeCreative(stack)) {
                return 8
            }
            val openStatue = getOpenStatue(stack, box)
            if (box.isCreative(stack)) {
                return openStatue + 6
            }
            return openStatue + box.getAmmoLevel(stack) * 2
        }

        private fun getOpenStatue(stack: ItemStack, box: IAmmoBox): Int {
            val emptyAmmoId = box.getAmmoId(stack) == DefaultAssets.EMPTY_AMMO_ID
            if (emptyAmmoId) {
                return 0
            }
            return if (box.getAmmoCount(stack) > 0) 1 else 0
        }

        @JvmStatic
        internal fun getTintColor(stack: ItemStack): Int {
            val display = stack.tagCompound?.getCompoundTag("display") ?: return DEFAULT_TINT_COLOR
            return if (display.hasKey("color", 99)) display.getInteger("color") else DEFAULT_TINT_COLOR
        }
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        LegacyRuntimeTooltipSupport.appendTooltip(stack, tooltip, flagIn.isAdvanced)
    }
}

internal class ModernKineticGunItem : Item(), IGun {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = LegacyCreativeTabs.gunCategoryTabs

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val requestedType = LegacyCreativeTabs.gunTypeForTab(tab)
        val guns = TACZGunPackPresentation.sortedGuns(snapshot)
        if (guns.isEmpty()) {
            items += ItemStack(this)
            return
        }
        guns.asSequence()
            .filter { gun -> requestedType == null || gun.index.type.equals(requestedType, ignoreCase = true) }
            .forEach { gun ->
            val stack = ItemStack(this)
            setGunId(stack, gun.id)
            setCurrentAmmoCount(stack, gun.data.ammoAmount.coerceAtLeast(0))
            val firstFireMode = GunDataAccessor.getGunData(gun.id)?.fireModesSet?.firstOrNull()?.let(::parseFireMode)
            setFireMode(stack, firstFireMode ?: FireMode.UNKNOWN)
            val boltType = GunDataAccessor.getGunData(gun.id)?.boltType ?: BoltType.OPEN_BOLT
            if (boltType != BoltType.OPEN_BOLT) {
                setBulletInBarrel(stack, true)
            }
            items += stack
        }
    }

    override fun getGunId(stack: ItemStack): ResourceLocation {
        val explicitGunId = stack.tagCompound
            ?.getString(IGun.GUN_ID_TAG)
            ?.takeIf { it.isNotBlank() }
            ?.let(::ResourceLocation)
        return explicitGunId
            ?: TACZGunPackRuntimeRegistry.getSnapshot().resolveDefaultGunId(TYPE_NAME)
            ?: DefaultAssets.DEFAULT_GUN_ID
    }

    override fun setGunId(stack: ItemStack, gunId: ResourceLocation?) {
        if (gunId != null) {
            ensureTag(stack).setString(IGun.GUN_ID_TAG, gunId.toString())
        }
    }

    override fun getAimingZoom(stack: ItemStack): Float {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        var zoom = 1.0f
        var scopeId = getAttachmentId(stack, AttachmentType.SCOPE)
        var builtinScope = false
        if (scopeId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            scopeId = getBuiltInAttachmentId(stack, AttachmentType.SCOPE)
            builtinScope = true
        }
        if (scopeId != DefaultAssets.EMPTY_ATTACHMENT_ID) {
            val zoomLevels = TACZGunPackPresentation.resolveAttachmentZoomLevels(snapshot, scopeId)
            if (zoomLevels != null && zoomLevels.isNotEmpty()) {
                val zoomNumber = if (builtinScope) {
                    0
                } else {
                    getAttachmentTag(stack, AttachmentType.SCOPE)?.getInteger(AttachmentItem.ZOOM_NUMBER_TAG) ?: 0
                }
                zoom = zoomLevels[Math.floorMod(zoomNumber, zoomLevels.size)]
            }
        } else {
            zoom = TACZGunPackPresentation.resolveGunIronZoom(snapshot, getGunId(stack))
        }
        return zoom.coerceAtLeast(1.0f)
    }

    override fun getFireMode(stack: ItemStack): FireMode {
        val gunId = getGunId(stack)
        val configuredModes = GunDataAccessor.getGunData(gunId)?.fireModesSet.orEmpty()
        val configuredFallback = configuredModes.firstOrNull()
            ?.let(::parseFireMode)
            ?.takeIf { it != FireMode.UNKNOWN }

        val tag = stack.tagCompound
        val storedMode = tag?.getString(IGun.FIRE_MODE_TAG)
            ?.takeIf(String::isNotBlank)
            ?.let {
                try {
                    FireMode.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        if (storedMode != null && storedMode != FireMode.UNKNOWN) {
            if (configuredModes.isEmpty() || configuredModes.any { mode -> mode.equals(storedMode.name, ignoreCase = true) }) {
                return storedMode
            }
        }

        return configuredFallback ?: storedMode ?: FireMode.UNKNOWN
    }

    override fun setFireMode(stack: ItemStack, fireMode: FireMode?) {
        ensureTag(stack).setString(IGun.FIRE_MODE_TAG, (fireMode ?: FireMode.UNKNOWN).name)
    }

    override fun getCurrentAmmoCount(stack: ItemStack): Int {
        return stack.tagCompound?.getInteger(IGun.AMMO_COUNT_TAG) ?: 0
    }

    override fun setCurrentAmmoCount(stack: ItemStack, ammoCount: Int) {
        ensureTag(stack).setInteger(IGun.AMMO_COUNT_TAG, ammoCount.coerceAtLeast(0))
    }

    override fun reduceCurrentAmmoCount(stack: ItemStack) {
        if (!useInventoryAmmo(stack)) {
            setCurrentAmmoCount(stack, getCurrentAmmoCount(stack) - 1)
        }
    }

    override fun hasBulletInBarrel(stack: ItemStack): Boolean {
        return stack.tagCompound?.getBoolean(IGun.BULLET_IN_BARREL_TAG) ?: false
    }

    override fun setBulletInBarrel(stack: ItemStack, bulletInBarrel: Boolean) {
        ensureTag(stack).setBoolean(IGun.BULLET_IN_BARREL_TAG, bulletInBarrel)
    }

    override fun useInventoryAmmo(stack: ItemStack): Boolean {
        // 审计 B4-21：对齐上游 AbstractGunItem:407 —— **只有** FeedType.INVENTORY 是背包直读。
        // 旧实现把 "fuel" 也算进去，于是 fuel 枪（M320、RPG7 等）射击走背包直读、
        // 扣弹与 HUD 全部跳过弹匣，LivingEntityReload 里新增的 fuel 填弹分支效果被抵消。
        val gunId = getGunId(stack)
        val gun = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId] ?: return false
        val reloadType = gun.data.raw.getAsJsonObject("reload")?.get("type")?.asString
        return reloadType == "inventory"
    }

    override fun hasInventoryAmmo(shooter: EntityLivingBase, stack: ItemStack, needCheckAmmo: Boolean): Boolean {
        if (!needCheckAmmo) return true
        if (useDummyAmmo(stack)) return getDummyAmmoAmount(stack) > 0

        if (shooter is net.minecraft.entity.player.EntityPlayer) {
            val inventory = shooter.inventory
            for (slot in 0 until inventory.sizeInventory) {
                val checkStack = inventory.getStackInSlot(slot)
                if (checkStack.isEmpty) continue
                val ammo = checkStack.item as? IAmmo
                if (ammo != null && ammo.isAmmoOfGun(stack, checkStack)) return true
                val box = checkStack.item as? IAmmoBox
                if (box != null && box.isAmmoBoxOfGun(stack, checkStack)) return true
            }
            return false
        }

        val handler = shooter.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)
        if (handler != null) {
            for (i in 0 until handler.slots) {
                val checkStack = handler.getStackInSlot(i)
                if (checkStack.isEmpty) continue
                val ammo = checkStack.item as? IAmmo
                if (ammo != null && ammo.isAmmoOfGun(stack, checkStack)) return true
                val box = checkStack.item as? IAmmoBox
                if (box != null && box.isAmmoBoxOfGun(stack, checkStack)) return true
            }
        }
        return false
    }

    /**
     * 从背包中查找并消耗弹药。返回实际消耗的弹药数量。
     * 与上游 TACZ AbstractGunItem.findAndExtractInventoryAmmo 行为一致。
     */
    public fun findAndExtractInventoryAmmo(handler: IItemHandler, gunItem: ItemStack, needAmmoCount: Int): Int {
        var remaining = needAmmoCount
        for (i in 0 until handler.slots) {
            if (remaining <= 0) break
            val checkStack = handler.getStackInSlot(i)
            if (checkStack.isEmpty) continue
            val ammo = checkStack.item as? IAmmo
            if (ammo != null && ammo.isAmmoOfGun(gunItem, checkStack)) {
                val extracted = handler.extractItem(i, remaining, false)
                remaining -= extracted.count
                continue
            }
            val box = checkStack.item as? IAmmoBox
            if (box != null && box.isAmmoBoxOfGun(gunItem, checkStack)) {
                // 直接改动 handler 返回栈违反 IItemHandler 契约：需要可写回的处理者，否则不得报告消耗成功
                val modifiable = handler as? IItemHandlerModifiable ?: continue
                val boxCount = box.getAmmoCount(checkStack)
                val extract = minOf(boxCount, remaining)
                if (extract <= 0) continue
                val boxCopy = checkStack.copy()
                val leftover = boxCount - extract
                box.setAmmoCount(boxCopy, leftover)
                if (leftover <= 0) {
                    box.setAmmoId(boxCopy, DefaultAssets.EMPTY_AMMO_ID)
                }
                modifiable.setStackInSlot(i, boxCopy)
                remaining -= extract
            }
        }
        return needAmmoCount - remaining
    }

    override fun useDummyAmmo(stack: ItemStack): Boolean {
        return stack.tagCompound?.hasKey(IGun.DUMMY_AMMO_TAG) ?: false
    }

    override fun getDummyAmmoAmount(stack: ItemStack): Int {
        return (stack.tagCompound?.getInteger(IGun.DUMMY_AMMO_TAG) ?: 0).coerceAtLeast(0)
    }

    override fun setDummyAmmoAmount(stack: ItemStack, amount: Int) {
        ensureTag(stack).setInteger(IGun.DUMMY_AMMO_TAG, amount.coerceAtLeast(0))
    }

    override fun addDummyAmmoAmount(stack: ItemStack, amount: Int) {
        if (!useDummyAmmo(stack)) return
        val current = getDummyAmmoAmount(stack)
        // X-48：对齐上游 GunItemDataAccessor.addDummyAmmoAmount 的上限封顶。
        // 用 Long 求和，避免 current + amount 溢出成负数后被 coerceAtLeast(0) 悄悄清零。
        val maxDummyAmmo = if (hasMaxDummyAmmo(stack)) getMaxDummyAmmoAmount(stack) else Int.MAX_VALUE
        val summed = current.toLong() + amount.toLong()
        setDummyAmmoAmount(stack, Math.min(summed, maxDummyAmmo.toLong()).toInt())
    }

    override fun hasMaxDummyAmmo(stack: ItemStack): Boolean {
        return stack.tagCompound?.hasKey(IGun.MAX_DUMMY_AMMO_TAG) ?: false
    }

    override fun getMaxDummyAmmoAmount(stack: ItemStack): Int {
        return (stack.tagCompound?.getInteger(IGun.MAX_DUMMY_AMMO_TAG) ?: 0).coerceAtLeast(0)
    }

    override fun setMaxDummyAmmoAmount(stack: ItemStack, amount: Int) {
        ensureTag(stack).setInteger(IGun.MAX_DUMMY_AMMO_TAG, amount.coerceAtLeast(0))
    }

    override fun getAttachmentTag(stack: ItemStack, type: AttachmentType): NBTTagCompound? {
        if (!allowAttachmentType(stack, type)) {
            return null
        }
        val root = stack.tagCompound ?: return null
        val key = attachmentKey(type)
        if (!root.hasKey(key, NbtType.COMPOUND)) {
            return null
        }
        val attachmentStackTag = root.getCompoundTag(key)
        return if (attachmentStackTag.hasKey("tag", NbtType.COMPOUND)) attachmentStackTag.getCompoundTag("tag") else null
    }

    override fun getAttachment(stack: ItemStack, type: AttachmentType): ItemStack {
        if (!allowAttachmentType(stack, type)) {
            return ItemStack.EMPTY
        }
        val root = stack.tagCompound ?: return ItemStack.EMPTY
        val key = attachmentKey(type)
        if (!root.hasKey(key, NbtType.COMPOUND)) {
            return ItemStack.EMPTY
        }
        return ItemStack(root.getCompoundTag(key))
    }

    override fun getBuiltinAttachment(stack: ItemStack, type: AttachmentType): ItemStack {
        val attachmentId = getBuiltInAttachmentId(stack, type)
        if (attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return ItemStack.EMPTY
        }
        return ItemStack(LegacyItems.ATTACHMENT).apply {
            LegacyItems.ATTACHMENT.setAttachmentId(this, attachmentId)
        }
    }

    override fun getAttachmentId(stack: ItemStack, type: AttachmentType): ResourceLocation {
        val tag = getAttachmentTag(stack, type) ?: return DefaultAssets.EMPTY_ATTACHMENT_ID
        val value = tag.getString(AttachmentItem.ATTACHMENT_ID_TAG)
        return value.takeIf(String::isNotBlank)?.let(::ResourceLocation) ?: DefaultAssets.EMPTY_ATTACHMENT_ID
    }

    override fun getBuiltInAttachmentId(stack: ItemStack, type: AttachmentType): ResourceLocation {
        if (type == AttachmentType.NONE) {
            return DefaultAssets.EMPTY_ATTACHMENT_ID
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        return TACZGunPackPresentation.resolveBuiltinAttachmentId(snapshot, getGunId(stack), type)
            ?: DefaultAssets.EMPTY_ATTACHMENT_ID
    }

    override fun installAttachment(gun: ItemStack, attachment: ItemStack) {
        val iAttachment = attachment.item as? IAttachment ?: return
        if (!allowAttachment(gun, attachment)) {
            return
        }
        val key = attachmentKey(iAttachment.getType(attachment))
        ensureTag(gun).setTag(key, attachment.writeToNBT(NBTTagCompound()))
    }

    override fun unloadAttachment(gun: ItemStack, type: AttachmentType) {
        if (!allowAttachmentType(gun, type)) {
            return
        }
        ensureTag(gun).setTag(attachmentKey(type), ItemStack.EMPTY.writeToNBT(NBTTagCompound()))
    }

    override fun allowAttachment(gun: ItemStack, attachmentItem: ItemStack): Boolean {
        val iAttachment = attachmentItem.item as? IAttachment ?: return false
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        return TACZGunPackPresentation.allowsAttachment(snapshot, getGunId(gun), iAttachment.getAttachmentId(attachmentItem))
    }

    override fun allowAttachmentType(gun: ItemStack, type: AttachmentType): Boolean {
        if (type == AttachmentType.NONE) {
            return false
        }
        val gunId = getGunId(gun)
        val gunEntry = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId] ?: return false
        return gunEntry.data.allowAttachmentTypes.any { AttachmentType.fromSerializedName(it) == type }
    }

    override fun hasCustomLaserColor(stack: ItemStack): Boolean {
        return stack.tagCompound?.hasKey(IGun.LASER_COLOR_TAG, NbtType.INT) ?: false
    }

    override fun getLaserColor(stack: ItemStack): Int {
        if (!hasCustomLaserColor(stack)) {
            return DEFAULT_LASER_COLOR
        }
        return stack.tagCompound?.getInteger(IGun.LASER_COLOR_TAG) ?: DEFAULT_LASER_COLOR
    }

    override fun setLaserColor(stack: ItemStack, color: Int) {
        ensureTag(stack).setInteger(IGun.LASER_COLOR_TAG, color)
    }

    override fun hasAttachmentLock(stack: ItemStack): Boolean {
        return stack.tagCompound?.getBoolean(IGun.ATTACHMENT_LOCK_TAG) ?: false
    }

    override fun setAttachmentLock(stack: ItemStack, locked: Boolean) {
        ensureTag(stack).setBoolean(IGun.ATTACHMENT_LOCK_TAG, locked)
    }

    override fun isOverheatLocked(stack: ItemStack): Boolean {
        return stack.tagCompound?.getBoolean(IGun.OVERHEAT_LOCK_TAG) ?: false
    }

    override fun setOverheatLocked(stack: ItemStack, locked: Boolean) {
        ensureTag(stack).setBoolean(IGun.OVERHEAT_LOCK_TAG, locked)
    }

    override fun getHeatAmount(stack: ItemStack): Float {
        return (stack.tagCompound?.getFloat(IGun.HEAT_AMOUNT_TAG) ?: 0f).coerceAtLeast(0f)
    }

    override fun setHeatAmount(stack: ItemStack, amount: Float) {
        ensureTag(stack).setFloat(IGun.HEAT_AMOUNT_TAG, amount.coerceAtLeast(0f))
    }

    /**
     * 当前射速（发/分）。
     * 对齐上游 AbstractGunItem#getRPM：基准值 = 枪械数据 rpm + fire_mode_adjust.rpm（<=0 兜底 300），
     * 有 heat 数据时再乘 [lerpRPM]。
     *
     * 审计 B3-17：本组 getter 是 **API 面对齐**（供第三方物品/脚本读取），玩法侧没有消费点 ——
     * 实际开火间隔走 `GunCombatData.getShootIntervalMs`，不读本方法。
     */
    override fun getRPM(stack: ItemStack): Int {
        val gunData = GunDataAccessor.getGunData(getGunId(stack))
        var rpm = gunData?.let { data ->
            data.roundsPerMinute + (data.fireModeAdjusts[getFireMode(stack)]?.rpm ?: 0)
        } ?: DEFAULT_RPM
        // 对齐原版 GunData.getRoundsPerMinute：合计后 <=0 时兜底 300
        if (rpm <= 0) {
            rpm = DEFAULT_RPM
        }
        if (gunData != null && gunData.hasHeatData) {
            // 对齐原版 rpm *= lerpRPM（原版先截断倍率再相乘，倍率 <1 时会得到 0，这里按文档语义保留小数相乘）
            rpm = (rpm * TACZGunPropertyResolver.resolveHeatRpmModifier(stack, this, gunData)).toInt()
        }
        return rpm
    }

    /** heat 对射速的插值倍率；无 heat 数据返回 1.0（冷枪不受影响）。 */
    override fun lerpRPM(stack: ItemStack): Float {
        val gunData = GunDataAccessor.getGunData(getGunId(stack)) ?: return 1.0f
        return TACZGunPropertyResolver.resolveHeatRpmModifier(stack, this, gunData)
    }

    /**
     * heat 对散布的插值倍率；无 heat 数据返回 1.0。
     * 仅 API 对齐上游 IGun#lerpInaccuracy，**不要**接进散布路径：
     * 散布消费仍走 TACZGunPropertyResolver#resolveInaccuracy（已含 heat 缩放，再接会重复缩放）。
     */
    override fun lerpInaccuracy(stack: ItemStack): Float {
        val gunData = GunDataAccessor.getGunData(getGunId(stack)) ?: return 1.0f
        return TACZGunPropertyResolver.resolveHeatInaccuracyMultiplier(stack, this, gunData)
    }

    /** 对齐上游 AbstractGunItem#isCanCrawl：读枪械数据 can_crawl，无枪械数据返回 false。 */
    override fun isCanCrawl(stack: ItemStack): Boolean {
        return GunDataAccessor.getGunData(getGunId(stack))?.canCrawl ?: false
    }

    /** 该枪是否有 heat 配置（heat 段存在即 true）。 */
    override fun hasHeatData(stack: ItemStack): Boolean {
        return GunDataAccessor.getGunData(getGunId(stack))?.hasHeatData == true
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = getGunId(stack)
        return TACZGunPackPresentation.localizedGunName(snapshot, gunId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        LegacyRuntimeTooltipSupport.appendTooltip(stack, tooltip, flagIn.isAdvanced)
    }

    override fun onEntitySwing(entityLiving: EntityLivingBase, stack: ItemStack): Boolean = true

    internal companion object {
        internal const val TYPE_NAME: String = "modern_kinetic"
        private const val DEFAULT_LASER_COLOR: Int = 0xFF0000
        // 对齐原版 GunData#getRoundsPerMinute 的非法值兜底
        private const val DEFAULT_RPM: Int = 300
    }
}

internal class LegacyBlockItem(block: net.minecraft.block.Block) : ItemBlock(block) {
    init {
        maxStackSize = 1
    }

    override fun getCreativeTabs(): Array<CreativeTabs> = arrayOf(LegacyCreativeTabs.OTHER)

    fun getBlockId(stack: ItemStack): ResourceLocation {
        val explicitBlockId = stack.tagCompound
            ?.getString(BLOCK_ID_TAG)
            ?.takeIf { it.isNotBlank() }
            ?.let(::ResourceLocation)
        if (explicitBlockId != null) {
            return explicitBlockId
        }
        val itemId = requireNotNull(registryName)
        // 没有 BlockId 的工作台物品（例如没有任何枪包使用的 workbench_b）回退到可用的工作台定义
        return if (block is LegacyGunSmithTableBlock) GunSmithTableTileEntity.resolveWorkbenchBlockId(itemId) else itemId
    }

    fun setBlockId(stack: ItemStack, blockId: ResourceLocation?) {
        if (blockId == null) {
            ensureTag(stack).removeTag(BLOCK_ID_TAG)
            return
        }
        ensureTag(stack).setString(BLOCK_ID_TAG, blockId.toString())
    }

    override fun getSubItems(tab: CreativeTabs, items: NonNullList<ItemStack>) {
        if (!isInCreativeTab(tab)) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val entries = TACZGunPackPresentation.sortedBlocksForItem(snapshot, requireNotNull(registryName))
        if (entries.isEmpty()) {
            items += ItemStack(this)
            return
        }
        entries.forEach { blockEntry ->
            val stack = ItemStack(this)
            setBlockId(stack, blockEntry.id)
            items += stack
        }
    }

    override fun getItemStackDisplayName(stack: ItemStack): String {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val blockId = getBlockId(stack)
        return TACZGunPackPresentation.localizedBlockName(snapshot, blockId, super.getItemStackDisplayName(stack))
    }

    @SideOnly(Side.CLIENT)
    override fun addInformation(stack: ItemStack, worldIn: World?, tooltip: MutableList<String>, flagIn: ITooltipFlag) {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val blockId = getBlockId(stack)
        TACZGunPackPresentation.localizedBlockTooltip(snapshot, blockId)?.let { tooltip += "${TextFormatting.GRAY}$it" }
        val tabs = TACZGunPackPresentation.localizedWorkbenchTabs(snapshot, blockId)
        if (tabs.isNotEmpty()) {
            val preview = tabs.take(3).joinToString(", ")
            val suffix = if (tabs.size > 3) ", …" else ""
            tooltip += "${TextFormatting.DARK_AQUA}$preview$suffix"
        }
        TACZGunPackPresentation.localizedPackName(snapshot, blockId)?.let { tooltip += "${TextFormatting.BLUE}${TextFormatting.ITALIC}$it" }
        if (flagIn.isAdvanced) {
            val recipeCount = TACZGunPackPresentation.visibleRecipeCount(snapshot, blockId)
            tooltip += "${TextFormatting.DARK_GRAY}Recipes: $recipeCount"
            snapshot.blocks[blockId]?.data?.filter?.let { filterId ->
                tooltip += "${TextFormatting.DARK_GRAY}Filter: $filterId"
            }
        }
        appendAdvancedTooltip(
            tooltip = tooltip,
            flag = flagIn,
            runtimeId = blockId,
            displayId = TACZGunPackPresentation.resolveBlockDisplayId(snapshot, blockId),
        )
    }

    override fun placeBlockAt(
        stack: ItemStack,
        player: EntityPlayer,
        world: World,
        pos: BlockPos,
        side: EnumFacing,
        hitX: Float,
        hitY: Float,
        hitZ: Float,
        newState: IBlockState,
    ): Boolean {
        // 多方块工作台：其余部位的位置必须都可放置，否则整体不放置（不消耗物品）
        val workbench = block as? LegacyGunSmithTableBlock
        if (workbench != null && !workbench.canPlaceStructure(world, pos, newState.getValue(LegacyGunSmithTableBlock.FACING), player, stack, side)) {
            return false
        }
        val placed = super.placeBlockAt(stack, player, world, pos, side, hitX, hitY, hitZ, newState)
        if (!placed) {
            return false
        }
        val tile = world.getTileEntity(pos) as? GunSmithTableTileEntity ?: return true
        tile.blockId = getBlockId(stack)
        tile.markDirty()
        return true
    }

    internal companion object {
        internal const val BLOCK_ID_TAG: String = "BlockId"
    }
}

private fun ensureTag(stack: ItemStack): NBTTagCompound {
    val existing = stack.tagCompound
    if (existing != null) {
        return existing
    }
    val created = NBTTagCompound()
    stack.tagCompound = created
    return created
}

private fun parseFireMode(rawValue: String): FireMode =
    runCatching { FireMode.valueOf(rawValue.uppercase()) }.getOrDefault(FireMode.UNKNOWN)

private fun attachmentKey(type: AttachmentType): String = "${IGun.ATTACHMENT_BASE_TAG}${type.name}"

private object NbtType {
    const val COMPOUND: Int = 10
    const val INT: Int = 3
}

@SideOnly(Side.CLIENT)
private fun appendAdvancedTooltip(
    tooltip: MutableList<String>,
    flag: ITooltipFlag,
    runtimeId: ResourceLocation,
    displayId: ResourceLocation?,
) {
    if (!flag.isAdvanced || !LegacyConfigManager.client.enableTaczIdInTooltip) {
        return
    }
    tooltip += "${TextFormatting.DARK_GRAY}TACZ ID: $runtimeId"
    displayId?.let { tooltip += "${TextFormatting.DARK_GRAY}Display: $it" }
}