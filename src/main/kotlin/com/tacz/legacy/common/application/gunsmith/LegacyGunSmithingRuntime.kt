package com.tacz.legacy.common.application.gunsmith

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.item.gun.FireMode
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.item.LegacyBlockItem
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZRecipeDefinition
import com.tacz.legacy.common.resource.TACZRecipeFilterDefinition
import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import com.tacz.legacy.common.resource.TACZWorkbenchTabDefinition
import net.minecraft.block.Block
import net.minecraftforge.items.ItemHandlerHelper
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.crafting.Ingredient
import net.minecraft.nbt.JsonToNBT
import net.minecraft.nbt.NBTException
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.JsonUtils
import net.minecraft.util.ResourceLocation
import net.minecraftforge.common.crafting.CraftingHelper
import com.google.gson.JsonSyntaxException
import net.minecraftforge.common.crafting.JsonContext
import net.minecraftforge.oredict.OreDictionary
import net.minecraftforge.fml.common.registry.ForgeRegistries
import java.util.Locale

internal data class LegacyGunSmithIngredient(
    val ingredient: Ingredient,
    val count: Int,
    /**
     * `forge:partial_nbt` 的样本栈（物品 + 元数据 + 那份"部分 NBT"）。
     *
     * 非空时**不**走 `ingredient.apply`：原版 `Ingredient` 比的是 NBT 全等，
     * 而枪械的 NBT 里还有弹药数、已装配件等一堆运行时字段，全等永远不成立。
     * 这里改成"物品与元数据相同，且手上物品的 NBT 是样本 NBT 的超集"。
     * 样本同时用来给 JEI / 制作台画图标（带 GunId 的栈才会渲染成那把具体的枪）。
     */
    val partialSample: ItemStack? = null,
    /**
     * 这条材料来自哪个标签、而该标签在本机一个物品都没有（例如 1.12 没有紫水晶，
     * `forge:gems/amethyst` 就是空的）。只用于展示层把"空格"画成"缺什么"，不参与匹配。
     */
    val missingTag: String? = null,
) {
    fun matches(stack: ItemStack?): Boolean {
        if (stack == null || stack.isEmpty) {
            return false
        }
        val sample = partialSample
            ?: return ingredient.apply(stack)
        if (stack.item != sample.item || stack.metadata != sample.metadata) {
            return false
        }
        return PartialNbtMatcher.contains(stack.tagCompound, sample.tagCompound)
    }
}

/**
 * "部分 NBT 是否被实际 NBT 满足"的判定，与物品注册表无关，所以单独拿出来便于单测。
 */
internal object PartialNbtMatcher {
    fun contains(actual: NBTTagCompound?, required: NBTTagCompound?): Boolean {
        if (required == null || required.keySet.isEmpty()) {
            return true
        }
        if (actual == null) {
            return false
        }
        return required.keySet.all { key ->
            val want = required.getTag(key)
            val have = actual.getTag(key)
            when {
                want is NBTTagCompound && have is NBTTagCompound -> contains(have, want)
                else -> have != null && have == want
            }
        }
    }
}

internal data class LegacyGunSmithRecipe(
    val id: ResourceLocation,
    val group: ResourceLocation,
    val result: ItemStack,
    val materials: List<LegacyGunSmithIngredient>,
    val sourceNamespace: String,
)

internal data class LegacyGunSmithTab(
    val id: ResourceLocation,
    val displayName: String,
    val icon: ItemStack,
)

internal object LegacyGunSmithingRuntime {
    private var cachedSnapshot: TACZRuntimeSnapshot? = null
    private var cachedRecipes: Map<ResourceLocation, LegacyGunSmithRecipe> = emptyMap()

    private const val GUNSMITH_RECIPE_TYPE = "tacz:gun_smith_table_crafting"
    private const val PARTIAL_NBT_TYPE = "forge:partial_nbt"
    private const val TAG_PREFIX = "#"

    /**
     * 1.13 扁平化里"同一个物品换了 id"的等价改名，只收录实测过的：
     * 1.12 的闪烁西瓜片注册名是 `speckled_melon`（实测本机物品表里没有 `golden_melon`），
     * 1.13 起才叫 `glistering_melon_slice`。
     * 新增条目请保持"同一物品、只是改名"，不要往里塞不同物品。
     */
    private val FLATTENING_RENAMES = mapOf(
        "minecraft:glistering_melon_slice" to "minecraft:speckled_melon",
    )

    /**
     * 脚本（CraftTweaker / GroovyScript）改动，叠在枪包解析出来的基础表之上。
     *
     * 故意**不随 `/tacz reload` 清空**：1.12.2 的 CraftTweaker 无法重跑脚本（只有 1.11- 的
     * `/ct reload` 和 1.14+ 的 `/reload` 能重载），如果重载就把改动抹掉，等于让 CT 用户
     * 每次改数据都得重启游戏。能重跑脚本的 GroovyScript 侧可以显式调 `scriptClear()` 再重建。
     */
    private val removedByScript = LinkedHashSet<ResourceLocation>()
    private val addedByScript = LinkedHashMap<ResourceLocation, LegacyGunSmithRecipe>()

    /** 基础表：枪包 JSON 解析结果，快照换了才重建。调用方必须已持有本对象锁。 */
    private fun baseRecipes(snapshot: TACZRuntimeSnapshot): Map<ResourceLocation, LegacyGunSmithRecipe> {
        if (cachedSnapshot !== snapshot) {
            cachedSnapshot = snapshot
            cachedRecipes = snapshot.recipes.values.asSequence()
                .mapNotNull { parseRecipe(it, snapshot) }
                .associateBy(LegacyGunSmithRecipe::id)
        }
        return cachedRecipes
    }

    private fun withOverlay(base: Map<ResourceLocation, LegacyGunSmithRecipe>): Map<ResourceLocation, LegacyGunSmithRecipe> {
        if (removedByScript.isEmpty() && addedByScript.isEmpty()) {
            return base
        }
        val merged = LinkedHashMap(base)
        removedByScript.forEach { merged.remove(it) }
        addedByScript.forEach { (id, recipe) -> merged[id] = recipe }
        return merged
    }

    fun recipes(snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot()): Map<ResourceLocation, LegacyGunSmithRecipe> {
        synchronized(this) { return withOverlay(baseRecipes(snapshot)) }
    }

    /** 脚本删除一条配方。返回它原本是否存在。 */
    fun scriptRemove(id: ResourceLocation, snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot()): Boolean {
        synchronized(this) {
            // 必须查"叠加之后的有效表"而不是基础表：基础表里那条配方一直都在，
            // 只是被 removedByScript 挡住了。查基础表会让第二次删除也返回 true。
            val present = withOverlay(baseRecipes(snapshot)).containsKey(id)
            if (present) {
                addedByScript.remove(id)
                removedByScript += id
            }
            return present
        }
    }

    /** 脚本新增/覆盖一条配方。返回是否覆盖了同名条目。 */
    fun scriptAdd(recipe: LegacyGunSmithRecipe, snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot()): Boolean {
        synchronized(this) {
            val replaced = baseRecipes(snapshot).containsKey(recipe.id) || addedByScript.containsKey(recipe.id)
            removedByScript.remove(recipe.id)
            addedByScript[recipe.id] = recipe
            return replaced
        }
    }

    /** 清空全部脚本改动（给能重跑脚本的加载器用，比如 GroovyScript 热重载）。 */
    fun scriptClear() {
        synchronized(this) {
            removedByScript.clear()
            addedByScript.clear()
        }
    }

    fun scriptChangeCount(): Int = synchronized(this) { removedByScript.size + addedByScript.size }

    /**
     * 用与枪包完全相同的解析路径构造脚本配方：复用 `result`/`materials` 的
     * item / ore dictionary / 数组 / NBT 语义，避免在脚本侧重新实现一套产出规则
     * （枪械产出要写 GunId NBT、附件要按类型装位，重复实现必然漂移）。
     */
    fun parseScriptRecipe(id: ResourceLocation, raw: JsonObject): LegacyGunSmithRecipe? {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        return parseRecipe(TACZRecipeDefinition(id, raw), snapshot)
    }

    fun recipeNamespacesForBlock(
        blockId: ResourceLocation,
        snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot(),
    ): List<String> = visibleRecipes(
        blockId = blockId,
        selectedTab = null,
        selectedNamespaces = emptySet(),
        searchText = "",
        heldStack = ItemStack.EMPTY,
        byHandOnly = false,
        snapshot = snapshot,
    ).map(LegacyGunSmithRecipe::sourceNamespace).distinct()

    fun tabsForBlock(
        blockId: ResourceLocation,
        snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot(),
    ): List<LegacyGunSmithTab> {
        val configuredTabs = snapshot.blocks[blockId]?.data?.tabs.orEmpty()
        val tabs = if (configuredTabs.isNotEmpty()) configuredTabs else deriveFallbackTabs(blockId, snapshot)
        return tabs.map { definition ->
            LegacyGunSmithTab(
                id = definition.id,
                displayName = TACZGunPackPresentation.localizedText(snapshot, definition.name)
                    ?: TACZGunPackPresentation.prettyResourceName(definition.id),
                icon = buildTabIcon(definition, snapshot),
            )
        }
    }

    fun visibleRecipes(
        blockId: ResourceLocation,
        selectedTab: ResourceLocation?,
        selectedNamespaces: Set<String>,
        searchText: String,
        heldStack: ItemStack,
        byHandOnly: Boolean,
        snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot(),
    ): List<LegacyGunSmithRecipe> {
        val availableTabIds = tabsForBlock(blockId, snapshot).mapTo(linkedSetOf(), LegacyGunSmithTab::id)
        val normalizedSearch = searchText.trim().lowercase(Locale.ROOT)
        return recipes(snapshot).values.asSequence()
            .filter { recipe -> isVisibleForWorkbench(recipe, blockId, availableTabIds, snapshot) }
            .filter { recipe -> selectedTab == null || recipe.group == selectedTab }
            .filter { recipe -> selectedNamespaces.isEmpty() || recipe.sourceNamespace in selectedNamespaces }
            .filter { recipe -> normalizedSearch.isBlank() || matchesSearch(recipe, normalizedSearch) }
            .filter { recipe -> !byHandOnly || matchesHeldItem(recipe.result, heldStack) }
            .sortedWith(compareBy<LegacyGunSmithRecipe>({ it.sourceNamespace }, { it.id.toString() }))
            .toList()
    }

    fun isVisibleForWorkbench(
        recipe: LegacyGunSmithRecipe,
        blockId: ResourceLocation,
        availableTabIds: Set<ResourceLocation> = emptySet(),
        snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot(),
    ): Boolean {
        val resolvedTabIds = if (availableTabIds.isEmpty()) {
            tabsForBlock(blockId, snapshot).mapTo(linkedSetOf(), LegacyGunSmithTab::id)
        } else {
            availableTabIds
        }
        if (resolvedTabIds.isNotEmpty() && recipe.group !in resolvedTabIds) {
            return false
        }
        val filter = activeRecipeFilter(blockId, snapshot)
        return filter?.allows(recipe.id) ?: true
    }

    fun ingredientCount(player: EntityPlayer, ingredient: LegacyGunSmithIngredient): Int {
        return player.inventory.mainInventory.sumOfMatching(ingredient)
    }

    fun canCraft(player: EntityPlayer, recipe: LegacyGunSmithRecipe): Boolean {
        if (player.capabilities.isCreativeMode) {
            return true
        }
        return buildConsumptionPlan(player, recipe) != null
    }

    fun craft(player: EntityPlayer, blockId: ResourceLocation, recipeId: ResourceLocation): ItemStack? {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val recipe = recipes(snapshot)[recipeId] ?: return null
        if (!isVisibleForWorkbench(recipe, blockId, snapshot = snapshot)) {
            return null
        }
        val plan = if (player.capabilities.isCreativeMode) emptyMap() else buildConsumptionPlan(player, recipe) ?: return null
        applyConsumptionPlan(player, plan)
        val output = recipe.result.copy()
        if (!player.world.isRemote) {
            // 与上游一致直接放入背包，背包满时才掉落在脚下（原实现总是生成掉落物，可能被他人捡走）
            ItemHandlerHelper.giveItemToPlayer(player, output.copy())
        }
        player.inventory.markDirty()
        player.inventoryContainer.detectAndSendChanges()
        player.openContainer.detectAndSendChanges()
        return output
    }

    fun packName(snapshot: TACZRuntimeSnapshot, recipe: LegacyGunSmithRecipe): String? {
        val key = snapshot.packInfos[recipe.sourceNamespace]?.name ?: return recipe.sourceNamespace
        return TACZGunPackPresentation.localizedText(snapshot, key) ?: recipe.sourceNamespace
    }

    private fun parseRecipe(definition: TACZRecipeDefinition, snapshot: TACZRuntimeSnapshot): LegacyGunSmithRecipe? {
        val root = definition.raw
        // 枪包里也能放原版工作台配方（1.20 靠 datapack 自己加载）。1.12.2 没有 datapack，
        // 这些文件不该由制作台解析器接管，以前会被当成 custom 产出解析然后整条报错。
        val declaredType = root.get("type")?.takeIf(JsonElement::isJsonPrimitive)?.asString
        if (declaredType != null && declaredType != GUNSMITH_RECIPE_TYPE) {
            TACZLegacy.logger.debug("Skipped non-gunsmith recipe {} (type {})", definition.id, declaredType)
            return null
        }
        val resultObject = root.getAsJsonObject("result") ?: return null
        val context = JsonContext(definition.id.namespace)
        return runCatching {
            val result = buildResultStack(resultObject, context)
            val materials = root.getAsJsonArray("materials")
                ?.mapNotNull { materialElement -> parseMaterial(materialElement, context) }
                .orEmpty()
            LegacyGunSmithRecipe(
                id = definition.id,
                group = resolveResultGroup(resultObject, result, snapshot),
                result = result,
                materials = materials,
                sourceNamespace = definition.id.namespace,
            )
        }.onFailure { throwable ->
            TACZLegacy.logger.warn("Failed to parse gunsmith recipe {}", definition.id, throwable)
        }.getOrNull()
    }

    private fun parseMaterial(element: JsonElement, context: JsonContext): LegacyGunSmithIngredient? {
        if (!element.isJsonObject) {
            return null
        }
        val obj = element.asJsonObject
        val itemElement = obj.get("item") ?: return null
        val count = JsonUtils.getInt(obj, "count", 1).coerceAtLeast(1)
        val sample = parsePartialNbtSample(itemElement, context)
        if (sample != null) {
            return LegacyGunSmithIngredient(
                ingredient = Ingredient.fromStacks(sample.copy()),
                count = count,
                partialSample = sample,
            )
        }
        val ingredient = parseIngredient(itemElement, context)
        val tagId = sourceTagId(itemElement)
        val missingTag = if (tagId != null && ingredient.getMatchingStacks().isEmpty()) tagId else null
        return LegacyGunSmithIngredient(ingredient = ingredient, count = count, missingTag = missingTag)
    }

    /** 材料写法里携带的标签 id（`{"tag": ...}` 或 1.15+ 的 `"#..."`），没有则 null。 */
    private fun sourceTagId(itemElement: JsonElement): String? = when {
        itemElement.isJsonObject && itemElement.asJsonObject.has("tag") ->
            JsonUtils.getString(itemElement.asJsonObject, "tag")
        itemElement.isJsonPrimitive && itemElement.asString.startsWith(TAG_PREFIX) ->
            itemElement.asString.substring(TAG_PREFIX.length)
        else -> null
    }

    /**
     * `forge:partial_nbt`（1.20 Forge 的 ingredient 类型）在 1.12.2 的 `CraftingHelper` 里不存在，
     * 以前会让整条配方解析失败被丢弃（daffas_arsenal 有 12 条枪械配方因此消失）。
     * 这里自己把它读成一个"物品 + 部分 NBT"的样本；匹配规则见 [LegacyGunSmithIngredient.matches]。
     */
    private fun parsePartialNbtSample(element: JsonElement, context: JsonContext): ItemStack? {
        if (!element.isJsonObject) {
            return null
        }
        val obj = element.asJsonObject
        if (JsonUtils.getString(obj, "type", "") != PARTIAL_NBT_TYPE) {
            return null
        }
        val sample = stackFromItemId(JsonUtils.getString(obj, "item"), context)
        parseCompoundTag(obj.get("nbt"))?.let { ensureTag(sample).merge(it) }
        return sample
    }

    /** 把 `namespace:path` 形式的物品 id 解析成 1 个物品的 stack，支持 1.13 扁平化改名。 */
    private fun stackFromItemId(rawId: String, context: JsonContext): ItemStack {
        val id = appendNamespace(context.modId, rawId)
        val alias = FLATTENING_RENAMES[id.toString()]
        val resolved = if (alias != null) ResourceLocation(alias) else id
        val item = ForgeRegistries.ITEMS.getValue(resolved)
        if (item == null || item == Items.AIR) {
            throw JsonSyntaxException(
                if (alias == null) "Unknown item '$rawId'" else "Unknown item '$rawId' (改名别名 '$alias' 同样不存在)"
            )
        }
        return ItemStack(item)
    }

    private fun parseIngredient(element: JsonElement, context: JsonContext): Ingredient {
        if (element.isJsonArray) {
            val merged = element.asJsonArray.flatMap { child -> parseIngredient(child, context).getMatchingStacks().map(ItemStack::copy) }
            return if (merged.isEmpty()) Ingredient.EMPTY else Ingredient.fromStacks(*merged.toTypedArray())
        }
        if (element.isJsonPrimitive) {
            // 1.15+ 的裸字符串写法：`"#forge:ingots/iron"` 是标签，其余是物品 id
            val raw = element.asString
            if (raw.startsWith(TAG_PREFIX)) {
                return ingredientFromTag(raw.substring(TAG_PREFIX.length))
            }
            return Ingredient.fromStacks(stackFromItemId(raw, context))
        }
        if (!element.isJsonObject) {
            return CraftingHelper.getIngredient(element, context)
        }
        val obj = element.asJsonObject
        if (obj.has("tag")) {
            val tagId = JsonUtils.getString(obj, "tag")
            return ingredientFromTag(tagId)
        }
        val itemMember = obj.get("item")
        if (itemMember != null && itemMember.isJsonPrimitive && !obj.has("type")) {
            // `{"item": "minecraft:glistering_melon_slice"}` 这种 1.15+ 写法，
            // 1.12 的 CraftingHelper 只接受嵌套对象；自己解析顺带才能吃到 1.13 改名别名。
            return Ingredient.fromStacks(stackFromItemId(itemMember.asString, context))
        }
        return CraftingHelper.getIngredient(obj, context)
    }

    private fun ingredientFromTag(tagId: String): Ingredient {
        val stacks = resolveTagStacks(tagId)
        return if (stacks.isEmpty()) Ingredient.EMPTY else Ingredient.fromStacks(*stacks.toTypedArray())
    }

    private fun resolveTagStacks(tagId: String): List<ItemStack> {
        val oreName = oreDictionaryName(tagId)
        if (oreName != null) {
            val ores = OreDictionary.getOres(oreName)
            if (!ores.isEmpty()) {
                return ores.map(ItemStack::copy)
            }
        }
        val hardcoded = when (tagId) {
            "forge:glass" -> listOf(
                ItemStack(Item.getItemFromBlock(Blocks.GLASS)),
                ItemStack(Item.getItemFromBlock(Blocks.STAINED_GLASS), 1, OreDictionary.WILDCARD_VALUE),
            )
            "forge:gunpowder" -> listOf(ItemStack(Items.GUNPOWDER))
            "forge:leather" -> listOf(ItemStack(Items.LEATHER))
            "forge:rods/blaze" -> listOf(ItemStack(Items.BLAZE_ROD))
            "minecraft:logs" -> listOf(
                ItemStack(Item.getItemFromBlock(Blocks.LOG), 1, OreDictionary.WILDCARD_VALUE),
                ItemStack(Item.getItemFromBlock(Blocks.LOG2), 1, OreDictionary.WILDCARD_VALUE),
            )
            "forge:dusts/redstone" -> listOf(ItemStack(Items.REDSTONE))
            "forge:dusts/glowstone" -> listOf(ItemStack(Items.GLOWSTONE_DUST))
            "forge:gems/diamond" -> listOf(ItemStack(Items.DIAMOND))
            "forge:gems/quartz" -> listOf(ItemStack(Items.QUARTZ))
            "forge:gems/lapis" -> listOf(ItemStack(Items.DYE, 1, 4))
            "forge:ingots/iron" -> listOf(ItemStack(Items.IRON_INGOT))
            "forge:ingots/gold" -> listOf(ItemStack(Items.GOLD_INGOT))
            else -> emptyList()
        }
        if (hardcoded.isNotEmpty()) {
            return hardcoded
        }
        materialFallback(tagId, oreName)?.let { return listOf(it) }
        return emptyList()
    }

    private fun materialFallback(tagId: String, oreName: String?): ItemStack? {
        val key = (oreName ?: tagId).lowercase(Locale.ROOT)
        return when {
            key.contains("netherite") -> ItemStack(Items.SKULL, 1, 1) // 위더 스켈레톤 머리
            key.contains("copper") -> ItemStack(Item.getItemFromBlock(Blocks.REDSTONE_BLOCK))
            else -> null
        }
    }

    internal fun oreDictionaryName(tagId: String): String? {
        if (tagId == "minecraft:logs") {
            return "logWood"
        }
        // 1.20 的"可合成石料"标签（圆石/黑石/深板岩圆石），1.12 只有圆石
        if (tagId == "minecraft:stone_crafting_materials") {
            return "cobblestone"
        }
        if (!tagId.startsWith("forge:")) {
            return null
        }
        val path = tagId.removePrefix("forge:")
        // 1.20 的"硅质玻璃"就是普通玻璃（区别于染色玻璃），1.12 的矿典名是 blockGlass
        if (path == "glass/silica") {
            return "blockGlass"
        }
        if ('/' !in path) {
            return when (path) {
                "glass" -> "blockGlass"
                "gunpowder" -> "gunpowder"
                "leather" -> "leather"
                else -> path
            }
        }
        val prefix = path.substringBefore('/')
        val material = path.substringAfter('/').split('/', '_', '-')
            .filter(String::isNotBlank)
            .joinToString(separator = "") { segment -> segment.replaceFirstChar { it.titlecase(Locale.ROOT) } }
        // 1.12 的存储块矿典名是 blockIron / blockRedstone，不是 storage_blockIron
        if (prefix == "storage_blocks") {
            return "block$material"
        }
        val singular = when {
            prefix.endsWith("ies") -> prefix.dropLast(3) + "y"
            prefix.endsWith('s') -> prefix.dropLast(1)
            else -> prefix
        }
        return singular + material
    }

    private fun buildResultStack(result: JsonObject, context: JsonContext): ItemStack {
        val type = JsonUtils.getString(result, "type", "custom").trim().lowercase(Locale.ROOT)
        val stack = when (type) {
            "gun" -> buildGunResult(result, context)
            "ammo" -> buildAmmoResult(result, context)
            "attachment" -> buildAttachmentResult(result, context)
            "custom" -> {
                val itemElement = result.get("item")
                if (itemElement != null && itemElement.isJsonPrimitive) {
                    stackFromItemId(itemElement.asString, context)
                } else {
                    CraftingHelper.getItemStack(JsonUtils.getJsonObject(result, "item"), context)
                }
            }
            else -> throw IllegalArgumentException("Unsupported gunsmith result type: $type")
        }
        applyExtraNbt(stack, result.get("nbt"))
        return if (stack.isEmpty) ItemStack(LegacyItems.GUN_SMITH_TABLE) else stack
    }

    private fun buildGunResult(result: JsonObject, context: JsonContext): ItemStack {
        val gunId = contextualId(context, result, "id")
        val stack = ItemStack(LegacyItems.MODERN_KINETIC_GUN, JsonUtils.getInt(result, "count", 1).coerceAtLeast(1))
        LegacyItems.MODERN_KINETIC_GUN.setGunId(stack, gunId)
        val firstFireMode = GunDataAccessor.getGunData(gunId)?.fireModesSet?.firstOrNull()?.let(::parseFireMode)
        LegacyItems.MODERN_KINETIC_GUN.setFireMode(stack, firstFireMode ?: FireMode.UNKNOWN)
        LegacyItems.MODERN_KINETIC_GUN.setCurrentAmmoCount(stack, JsonUtils.getInt(result, "ammo_count", 0).coerceAtLeast(0))
        LegacyItems.MODERN_KINETIC_GUN.setBulletInBarrel(stack, false)
        result.getAsJsonObject("attachments")?.entrySet()?.forEach { (typeKey, rawId) ->
            val type = AttachmentType.fromSerializedName(typeKey)
            val attachmentId = rawId.asString.takeIf(String::isNotBlank)?.let { appendNamespace(context.modId, it) } ?: return@forEach
            val attachmentStack = ItemStack(LegacyItems.ATTACHMENT)
            LegacyItems.ATTACHMENT.setAttachmentId(attachmentStack, attachmentId)
            if (type != AttachmentType.NONE && LegacyItems.MODERN_KINETIC_GUN.allowAttachmentType(stack, type)) {
                LegacyItems.MODERN_KINETIC_GUN.installAttachment(stack, attachmentStack)
                if (LegacyItems.MODERN_KINETIC_GUN.getAttachmentId(stack, type) == attachmentId) {
                    return@forEach
                }
            }
            if (type != AttachmentType.NONE) {
                ensureTag(stack).setTag("${IGun.ATTACHMENT_BASE_TAG}${type.name}", attachmentStack.writeToNBT(NBTTagCompound()))
            }
        }
        return stack
    }

    private fun buildAmmoResult(result: JsonObject, context: JsonContext): ItemStack {
        val ammoId = contextualId(context, result, "id")
        val stack = ItemStack(LegacyItems.AMMO, JsonUtils.getInt(result, "count", 1).coerceAtLeast(1))
        LegacyItems.AMMO.setAmmoId(stack, ammoId)
        return stack
    }

    private fun buildAttachmentResult(result: JsonObject, context: JsonContext): ItemStack {
        val attachmentId = contextualId(context, result, "id")
        val stack = ItemStack(LegacyItems.ATTACHMENT, JsonUtils.getInt(result, "count", 1).coerceAtLeast(1))
        LegacyItems.ATTACHMENT.setAttachmentId(stack, attachmentId)
        return stack
    }

    private fun resolveResultGroup(
        result: JsonObject,
        resultStack: ItemStack,
        snapshot: TACZRuntimeSnapshot,
    ): ResourceLocation {
        result.get("group")?.let { groupValue ->
            // 对齐上游：result.group 省略命名空间时默认补 tacz（MOD_ID），而不是配方文件所在包的 namespace。
            // 默认工作台的 tab id 全部是 tacz: 命名空间，用配方包 namespace 拼前缀会让第三方包的
            // 无命名空间 group 无法匹配到 tab，从而在工作台被过滤掉、配方消失。带 ":" 的 group 原样解析。
            groupValue.asString.takeIf(String::isNotBlank)?.let { return appendNamespace(TACZLegacy.MOD_ID, it) }
        }
        return when (JsonUtils.getString(result, "type", "custom").trim().lowercase(Locale.ROOT)) {
            "gun" -> {
                val gunId = (resultStack.item as? IGun)?.getGunId(resultStack) ?: DefaultAssets.EMPTY_GUN_ID
                val type = snapshot.guns[gunId]?.index?.type.orEmpty().ifBlank { "misc" }
                ResourceLocation(TACZLegacy.MOD_ID, type)
            }
            "ammo" -> ResourceLocation(TACZLegacy.MOD_ID, "ammo")
            "attachment" -> {
                val attachmentId = (resultStack.item as? IAttachment)?.getAttachmentId(resultStack) ?: DefaultAssets.EMPTY_ATTACHMENT_ID
                val type = snapshot.attachments[attachmentId]?.index?.type.orEmpty().ifBlank { "misc" }
                ResourceLocation(TACZLegacy.MOD_ID, type)
            }
            else -> ResourceLocation(TACZLegacy.MOD_ID, "misc")
        }
    }

    private fun activeRecipeFilter(blockId: ResourceLocation, snapshot: TACZRuntimeSnapshot): TACZRecipeFilterDefinition? {
        val shouldApplyFilter = LegacyConfigManager.server.enableTableFilter || blockId != DefaultAssets.DEFAULT_BLOCK_ID
        return if (shouldApplyFilter) TACZGunPackPresentation.resolveRecipeFilter(snapshot, blockId) else null
    }

    private fun deriveFallbackTabs(blockId: ResourceLocation, snapshot: TACZRuntimeSnapshot): List<TACZWorkbenchTabDefinition> {
        val visibleRecipes = recipes(snapshot).values.filter { recipe ->
            val filter = activeRecipeFilter(blockId, snapshot)
            filter?.allows(recipe.id) ?: true
        }
        return visibleRecipes
            .groupBy(LegacyGunSmithRecipe::group)
            .entries
            .sortedBy { it.key.toString() }
            .map { (groupId, _) ->
                TACZWorkbenchTabDefinition(
                    id = groupId,
                    name = null,
                    icon = null,
                ).copy()
            }
    }

    private fun buildTabIcon(definition: TACZWorkbenchTabDefinition, snapshot: TACZRuntimeSnapshot): ItemStack {
        val iconDef = definition.icon
        if (iconDef?.itemId != null) {
            val item = ForgeRegistries.ITEMS.getValue(iconDef.itemId)
            if (item != null) {
                val stack = ItemStack(item)
                applyExtraNbt(stack, iconDef.nbt)
                return stack
            }
        }
        val fallback = recipes(snapshot).values.firstOrNull { it.group == definition.id }?.result?.copy()
        if (fallback != null && !fallback.isEmpty) {
            return fallback
        }
        return createBlockIcon(snapshot, DefaultAssets.DEFAULT_BLOCK_ID)
    }

    private fun createBlockIcon(snapshot: TACZRuntimeSnapshot, blockId: ResourceLocation): ItemStack {
        val blockEntry = snapshot.blocks[blockId]
        val itemId = blockEntry?.index?.id ?: DefaultAssets.DEFAULT_BLOCK_ID
        val item = ForgeRegistries.ITEMS.getValue(itemId) ?: return ItemStack(LegacyItems.GUN_SMITH_TABLE)
        val stack = ItemStack(item)
        if (item is LegacyBlockItem) {
            item.setBlockId(stack, blockId)
        }
        return stack
    }

    private fun matchesSearch(recipe: LegacyGunSmithRecipe, normalizedSearch: String): Boolean {
        if (normalizedSearch.isBlank()) {
            return true
        }
        return recipe.result.displayName.lowercase(Locale.ROOT).contains(normalizedSearch) ||
            recipe.id.toString().lowercase(Locale.ROOT).contains(normalizedSearch)
    }

    private fun matchesHeldItem(result: ItemStack, heldStack: ItemStack): Boolean {
        if (heldStack.isEmpty) {
            return false
        }
        val heldGun = heldStack.item as? IGun
        val heldAmmo = heldStack.item as? IAmmo
        val heldAttachment = heldStack.item as? IAttachment
        val resultGun = result.item as? IGun
        val resultAmmo = result.item as? IAmmo
        val resultAttachment = result.item as? IAttachment
        return when {
            heldGun != null && resultAmmo != null -> resultAmmo.isAmmoOfGun(heldStack, result)
            heldGun != null && resultAttachment != null -> heldGun.allowAttachment(heldStack, result)
            heldAmmo != null && resultGun != null -> heldAmmo.isAmmoOfGun(result, heldStack)
            heldAttachment != null && resultGun != null -> resultGun.allowAttachment(result, heldStack)
            else -> false
        }
    }

    private fun buildConsumptionPlan(player: EntityPlayer, recipe: LegacyGunSmithRecipe): Map<Int, Int>? {
        val available = player.inventory.mainInventory.map(ItemStack::getCount).toMutableList()
        val plan = linkedMapOf<Int, Int>()
        recipe.materials.forEach { material ->
            var remaining = material.count
            player.inventory.mainInventory.forEachIndexed { slot, stack ->
                if (remaining <= 0) {
                    return@forEachIndexed
                }
                if (!material.matches(stack)) {
                    return@forEachIndexed
                }
                val canTake = minOf(remaining, available[slot])
                if (canTake <= 0) {
                    return@forEachIndexed
                }
                plan[slot] = (plan[slot] ?: 0) + canTake
                available[slot] -= canTake
                remaining -= canTake
            }
            if (remaining > 0) {
                return null
            }
        }
        return plan
    }

    private fun applyConsumptionPlan(player: EntityPlayer, plan: Map<Int, Int>) {
        plan.forEach { (slot, amount) ->
            player.inventory.decrStackSize(slot, amount)
        }
    }

    private fun applyExtraNbt(stack: ItemStack, element: JsonElement?) {
        if (element == null || element.isJsonNull) {
            return
        }
        val extra = parseCompoundTag(element) ?: return
        val existing = ensureTag(stack)
        existing.merge(extra)
        stack.tagCompound = existing
    }

    private fun parseCompoundTag(element: JsonElement): NBTTagCompound? = try {
        if (element.isJsonObject) {
            JsonToNBT.getTagFromJson(CraftingHelper.GSON.toJson(element))
        } else {
            JsonToNBT.getTagFromJson(element.asString)
        }
    } catch (_: NBTException) {
        null
    }

    private fun appendNamespace(defaultNamespace: String, value: String): ResourceLocation {
        return if (':' in value) ResourceLocation(value) else ResourceLocation(defaultNamespace, value)
    }

    private fun contextualId(context: JsonContext, result: JsonObject, member: String): ResourceLocation =
        appendNamespace(context.modId, JsonUtils.getString(result, member))

    private fun parseFireMode(rawValue: String): FireMode =
        runCatching { FireMode.valueOf(rawValue.uppercase(Locale.ROOT)) }.getOrDefault(FireMode.UNKNOWN)

    private fun ensureTag(stack: ItemStack): NBTTagCompound {
        val existing = stack.tagCompound
        if (existing != null) {
            return existing
        }
        val created = NBTTagCompound()
        stack.tagCompound = created
        return created
    }

    private fun List<ItemStack>.sumOfMatching(material: LegacyGunSmithIngredient): Int = fold(0) { acc, stack ->
        if (material.matches(stack)) acc + stack.count else acc
    }
}
