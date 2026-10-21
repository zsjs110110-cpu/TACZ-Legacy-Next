package com.tacz.legacy.common.resource

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSerializer
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.modifier.JsonProperty
import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.api.modifier.ModifierEvaluator
import com.tacz.legacy.api.modifier.ParameterizedCachePair
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.Loader
import net.minecraftforge.fml.common.ModContainer
import net.minecraftforge.fml.common.versioning.ArtifactVersion
import net.minecraftforge.fml.common.versioning.VersionParser
import java.io.File
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.ZipFile

internal data class TACZPackMeta(
    @SerializedName("namespace")
    val name: String = "",
    @SerializedName("dependencies")
    val dependencies: LinkedHashMap<String, String> = LinkedHashMap(),
)

internal data class TACZPackInfo(
    @SerializedName("version")
    val version: String = "1.0.0",
    @SerializedName("name")
    val name: String = "custom.tacz.error.no_name",
    @SerializedName("desc")
    val description: String = "",
    @SerializedName("license")
    val license: String = "All Rights Reserved",
    @SerializedName("authors")
    val authors: List<String> = emptyList(),
    @SerializedName("date")
    val date: String = "1919-08-10",
    @SerializedName("url")
    val url: String? = null,
)

internal data class TACZGunIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("tooltip")
    val tooltip: String? = null,
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("data")
    val data: ResourceLocation? = null,
    @SerializedName("type")
    val type: String = "",
    @SerializedName("item_type")
    val itemType: String = "modern_kinetic",
    @SerializedName("sort")
    val sort: Int = 0,
)

internal data class TACZAttachmentIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("tooltip")
    val tooltip: String? = null,
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("data")
    val data: ResourceLocation? = null,
    @SerializedName("type")
    val type: String = "",
    @SerializedName("sort")
    val sort: Int = 0,
    @SerializedName("hidden")
    val hidden: Boolean = false,
)

internal data class TACZAmmoIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("stack_size")
    val stackSize: Int = 1,
    @SerializedName("tooltip")
    val tooltip: String? = null,
    // 弹药在创造栏中的排序权重，语义与 gun/block index 的 sort 一致（默认 0，与上游 AmmoIndexPOJO 对齐）
    @SerializedName("sort")
    val sort: Int = 0,
)

internal data class TACZBlockIndexDefinition(
    @SerializedName("name")
    val name: String = "",
    @SerializedName("display")
    val display: ResourceLocation? = null,
    @SerializedName("data")
    val data: ResourceLocation? = null,
    @SerializedName("id")
    val id: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "gun_smith_table"),
    @SerializedName("tooltip")
    val tooltip: String? = null,
    @SerializedName("sort")
    val sort: Int = 0,
)

internal data class TACZRecipeFilterDefinition(
    @SerializedName("whitelist")
    val whitelist: List<String> = emptyList(),
    @SerializedName("blacklist")
    val blacklist: List<String> = emptyList(),
) {
    fun allows(recipeId: ResourceLocation): Boolean {
        val value = recipeId.toString()
        val allowed = whitelist.isEmpty() || whitelist.any { pattern -> matchesRecipe(pattern, value) }
        if (!allowed) {
            return false
        }
        return blacklist.none { pattern -> matchesRecipe(pattern, value) }
    }

    private fun matchesRecipe(pattern: String, recipeId: String): Boolean {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        if (trimmed.startsWith("^") || trimmed.endsWith("$")) {
            return runCatching { Pattern.matches(trimmed, recipeId) }.getOrDefault(false)
        }
        return trimmed.equals(recipeId, ignoreCase = true)
    }
}

internal data class TACZWorkbenchTabIconDefinition(
    @SerializedName("item")
    val itemId: ResourceLocation? = null,
    @SerializedName("nbt")
    val nbt: JsonObject? = null,
)

internal data class TACZWorkbenchTabDefinition(
    @SerializedName("id")
    val id: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "misc"),
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("icon")
    val icon: TACZWorkbenchTabIconDefinition? = null,
)

internal data class TACZGunDataDefinition(
    val raw: JsonObject,
    val ammoId: ResourceLocation?,
    val ammoAmount: Int,
    val extendedMagAmmoAmount: IntArray?,
    val roundsPerMinute: Int,
    val weight: Float,
    val aimTime: Float,
    val allowAttachmentTypes: List<String>,
    // exclusive_attachments: 对齐原版 Map<ResourceLocation, AttachmentData>。
    // 上游语义是「整份 AttachmentData 替换配件自身的 data」，所以这里直接解析成与配件索引同构的
    // TACZAttachmentDataDefinition（复用同一套 readModifiers），由 TACZGunPropertyResolver.attachmentDataFor
    // 在消费点做"命中即整份替换"，而不是逐字段特判。
    val exclusiveAttachments: Map<ResourceLocation, TACZAttachmentDataDefinition> = emptyMap(),
)

internal data class TACZAttachmentDataDefinition(
    val raw: JsonObject,
    val weight: Float,
    val extendedMagLevel: Int,
    val modifiers: Map<String, JsonProperty<*>>,
    /**
     * 审计全局-1：近战数据解析结果挂在条目上缓存。
     * 旧实现由 `GunDataAccessor.getAttachmentMeleeData` 每次调用重新 `fromRawJson`，
     * 而它位于近战冷却查询（每 tick）与客户端动画驱动里；一次冷却判定还要走两遍。
     */
    val meleeData: AttachmentMeleeCombatData? = null,
)

internal data class TACZBlockDataDefinition(
    val raw: JsonObject,
    val filter: ResourceLocation,
    val tabs: List<TACZWorkbenchTabDefinition>,
)

/**
 * 兼容层 ③ C13：`tacz_loot_injectors/<id>.json`。
 *
 * 对齐上游 `LootTableInjection`：`{ "loot_table"|"loot_tables": [...], "pools": [...] }`。
 * 1.12.2 侧在 `LootTableLoadEvent` 里把 pools 追加到目标战利品表。
 */
internal data class TACZLootInjectionDefinition(
    val id: ResourceLocation,
    val lootTables: List<ResourceLocation>,
    val pools: JsonArray,
)

/**
 * B5 遗留 / B4：`tacz_loot_injectors/<id>.json` 的归一化结果。
 *
 * [lootTables] 只包含**带命名空间**且能解析的表名；`pools` 为 null 表示字段缺失或不是数组。
 * [issues] 由调用方并入全局 issue 列表，B11 的单测可直接断言而不需要 Minecraft 世界。
 */
internal data class TACZLootInjectionNormalization(
    val lootTables: List<ResourceLocation>,
    val pools: JsonArray?,
    val issues: List<String>,
)

/**
 * B5 遗留 / B4：把 loot 注入文件归一化成「目标表 + pools + 诊断」。
 *
 * 语义对齐上游 `LootTableInjection` 并修掉移植层旧实现的两个偏差：
 * 1. `loot_tables` 与 `loot_table` 是 **else-if** 关系（前者优先），不再把两者合并；
 * 2. 表名必须带命名空间。旧实现用 `ResourceLocation("foo")` 会默认补 `minecraft:foo`，
 *    一条写错的注入会静默落到原版表上，所以这里明确记 issue 并跳过，绝不补命名空间。
 *
 * `loot_tables` 同时兼容字符串与数组（上游 codec 两种都接受）。
 * 纯函数：只读入参，不访问 Minecraft 世界/注册表，便于单测。
 */
internal fun normalizeLootInjection(
    id: ResourceLocation,
    jsonObject: JsonObject,
): TACZLootInjectionNormalization {
    val issues = mutableListOf<String>()
    val poolsElement = jsonObject.get("pools")
    val pools = poolsElement?.takeIf { it.isJsonArray }?.asJsonArray
    if (poolsElement == null || poolsElement.isJsonNull) {
        issues += "Loot injection $id must define 'pools' as a JSON array; skipped."
    } else if (pools == null) {
        issues += "Loot injection $id: 'pools'=$poolsElement is not a JSON array; skipped."
    }

    val tables = LinkedHashSet<ResourceLocation>()
    fun addTable(raw: String?, field: String) {
        if (raw.isNullOrBlank()) {
            issues += "Loot injection $id: '$field' contains a blank loot table id; ignored."
            return
        }
        // 注意不能用 contains(':') 判断：":foo" 也含冒号，但 1.12 的 ResourceLocation
        // 会把它解析成 minecraft:foo，正是要避免的静默兜底。
        val namespace = raw.substringBefore(':', "")
        if (namespace.isBlank()) {
            issues += "Loot injection $id: '$field' loot table '$raw' has no namespace; ignored " +
                "(not defaulting to the default namespace)."
            return
        }
        if (raw.substringAfter(':', "").isBlank()) {
            issues += "Loot injection $id: '$field' has invalid loot table '$raw' (empty path); ignored."
            return
        }
        val parsed = runCatching { ResourceLocation(raw) }.getOrNull()
        if (parsed == null) {
            issues += "Loot injection $id: '$field' has invalid loot table '$raw'; ignored."
        } else {
            tables += parsed
        }
    }

    val tablesElement = jsonObject.get("loot_tables")
    val singleElement = jsonObject.get("loot_table")
    when {
        tablesElement != null && !tablesElement.isJsonNull -> when {
            tablesElement.isJsonPrimitive && tablesElement.asJsonPrimitive.isString ->
                addTable(tablesElement.asString, "loot_tables")
            tablesElement.isJsonArray -> tablesElement.asJsonArray.forEachIndexed { index, element ->
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    addTable(element.asString, "loot_tables[$index]")
                } else {
                    issues += "Loot injection $id: 'loot_tables[$index]'=$element is not a string; ignored."
                }
            }
            else -> issues += "Loot injection $id: 'loot_tables'=$tablesElement must be a string or array; ignored."
        }
        // else-if：只要写了 loot_tables（哪怕内容非法），就完全忽略 loot_table
        singleElement != null && !singleElement.isJsonNull -> {
            if (singleElement.isJsonPrimitive && singleElement.asJsonPrimitive.isString) {
                addTable(singleElement.asString, "loot_table")
            } else {
                issues += "Loot injection $id: 'loot_table'=$singleElement is not a string; ignored."
            }
        }
    }
    if (tables.isEmpty()) {
        issues += "Loot injection $id must define a namespaced 'loot_table' or 'loot_tables'; skipped."
    }
    return TACZLootInjectionNormalization(lootTables = tables.toList(), pools = pools, issues = issues.toList())
}

internal data class TACZDisplayDefinition(
    val id: ResourceLocation,
    val raw: JsonObject,
)

internal data class TACZRecipeDefinition(
    val id: ResourceLocation,
    val raw: JsonObject,
)

internal data class TACZRecoilModifierValue(
    val pitch: Modifier = Modifier(),
    val yaw: Modifier = Modifier(),
)

internal data class TACZIgniteValue(
    @SerializedName("entity")
    val igniteEntity: Boolean = false,
    @SerializedName("block")
    val igniteBlock: Boolean = false,
)

/**
 * 兼容层 ③ C2-exp：配件 `explosion` 的 typed 形态（对齐上游 ExplosionModifier.ExplosionModifierValue）。
 *
 * radius / damage / delay 是 Modifier 四元组，缺省即中性值（等价上游的 `new Modifier()`）；
 * explode / knockback / destroy_block 是布尔，上游语义里它们**不以 explode 为条件**参与聚合。
 */
internal data class TACZExplosionModifierValue(
    val explode: Boolean = false,
    val radius: Modifier = Modifier(),
    val damage: Modifier = Modifier(),
    val knockback: Boolean = false,
    val destroyBlock: Boolean = false,
    val delay: Modifier = Modifier(),
)

internal data class TACZLoadedPack(
    val meta: TACZPackMeta,
    val sourceFile: File,
    val infoByNamespace: Map<String, TACZPackInfo>,
    /**
     * 兼容层 ③ C2：本包**可下发**的原始文件内容（相对路径 → UTF-8 原文）。
     *
     * 由扫描期从同一个 [TACZPackSource] 读出并随快照保存，因此"服务端下发的"与
     * "服务端解析用的"必然是同一份字节 —— 不再在下发时重读磁盘（后者会漏掉 zip 包，
     * 且与快照可能不同源）。范围见 [TACZPackTransport.isSyncable]。
     */
    val rawFiles: Map<String, String> = emptyMap(),
)

internal data class TACZLoadedGun(
    val id: ResourceLocation,
    val index: TACZGunIndexDefinition,
    val data: TACZGunDataDefinition,
)

internal data class TACZLoadedAttachment(
    val id: ResourceLocation,
    val index: TACZAttachmentIndexDefinition,
    val data: TACZAttachmentDataDefinition,
)

internal data class TACZLoadedBlock(
    val id: ResourceLocation,
    val index: TACZBlockIndexDefinition,
    val data: TACZBlockDataDefinition,
)

internal data class TACZRuntimeSnapshot(
    val packs: Map<String, TACZLoadedPack>,
    val packInfos: Map<String, TACZPackInfo>,
    val guns: Map<ResourceLocation, TACZLoadedGun>,
    val attachments: Map<ResourceLocation, TACZLoadedAttachment>,
    val ammos: Map<ResourceLocation, TACZAmmoIndexDefinition>,
    val blocks: Map<ResourceLocation, TACZLoadedBlock>,
    val recipes: Map<ResourceLocation, TACZRecipeDefinition>,
    val recipeFilters: Map<ResourceLocation, TACZRecipeFilterDefinition>,
    val attachmentTags: Map<ResourceLocation, Set<String>>,
    val allowAttachmentTags: Map<ResourceLocation, Set<String>>,
    val gunDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val ammoDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val attachmentDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val blockDisplays: Map<ResourceLocation, TACZDisplayDefinition>,
    val translations: Map<String, Map<String, String>>,
    val dataScripts: Map<ResourceLocation, String>,
    /** 兼容层 ③ C13：枪包战利品注入（在 LootTableLoadEvent 里按目标表追加 pools）。 */
    val lootInjections: List<TACZLootInjectionDefinition> = emptyList(),
    val issues: List<String>,
) {
    private val gunIdsByItemType: Map<String, List<ResourceLocation>> by lazy {
        guns.values
            .sortedWith(compareBy<TACZLoadedGun> { it.index.sort.coerceIn(0, 65536) }.thenBy { it.id.toString() })
            .groupBy { it.index.itemType }
            .mapValues { (_, values) -> values.map(TACZLoadedGun::id) }
    }

    fun resolveDefaultGunId(itemType: String): ResourceLocation? = gunIdsByItemType[itemType]?.firstOrNull()

    fun gunItemTypes(): Set<String> = gunIdsByItemType.keys

    companion object {
        val EMPTY: TACZRuntimeSnapshot = TACZRuntimeSnapshot(
            packs = emptyMap(),
            packInfos = emptyMap(),
            guns = emptyMap(),
            attachments = emptyMap(),
            ammos = emptyMap(),
            blocks = emptyMap(),
            recipes = emptyMap(),
            recipeFilters = emptyMap(),
            attachmentTags = emptyMap(),
            allowAttachmentTags = emptyMap(),
            gunDisplays = emptyMap(),
            ammoDisplays = emptyMap(),
            attachmentDisplays = emptyMap(),
            blockDisplays = emptyMap(),
            translations = emptyMap(),
            dataScripts = emptyMap(),
            lootInjections = emptyList(),
            issues = emptyList(),
        )
    }
}

internal object TACZGunPackRuntimeRegistry {
    @Volatile
    private var snapshot: TACZRuntimeSnapshot = TACZRuntimeSnapshot.EMPTY

    /**
     * 重建运行时快照。
     *
     * 用 `@Synchronized` 串行化：这条路径同时被 `/tacz reload`（服务端线程，经
     * `DefaultGunPackExporter.ensureInstalled`）和客户端断线处理器（`ClientProxy`）
     * 调用，两边并发进入会各写一次 [snapshot] 并互相清掉 [TACZDataScriptManager]
     * 的脚本缓存，最终的快照取决于谁后收尾（X-7）。锁在同一个单例上，且可重入
     * （`ensureInstalled` → 本方法 是同一线程的嵌套调用）。
     */
    @Synchronized
    internal fun reload(gameDirectory: File): TACZRuntimeSnapshot {
        val packsRoot = File(gameDirectory, TACZLegacy.MOD_ID).apply { mkdirs() }
        TACZDataScriptManager.reload()
        val previousSnapshot = snapshot
        val loaded = try {
            TACZGunPackScanner.scan(packsRoot)
        } catch (throwable: Throwable) {
            val issue = "Gun pack reload failed; keeping previous runtime snapshot (${throwable.message ?: throwable.javaClass.simpleName})."
            TACZLegacy.logger.error("[GunPackRuntime] {}", issue, throwable)
            // 去掉上一次的同类失败条目，避免连续失败时 WARN 单调累积；旧快照的其它 issue 保持不变。
            val fallbackSnapshot = previousSnapshot.copy(
                issues = previousSnapshot.issues.filterNot { it.startsWith("Gun pack reload failed;") } + issue,
            )
            snapshot = fallbackSnapshot
            logSnapshotSummary(fallbackSnapshot)
            return fallbackSnapshot
        }
        snapshot = loaded
        // D-5：数据换了一轮就重新给一次"缺 ID"告警，否则上一次 reload 缺过的 ID 会永久静音
        TACZMissingIdReporter.reset()
        logSnapshotSummary(loaded)
        return loaded
    }

    private fun logSnapshotSummary(loaded: TACZRuntimeSnapshot): Unit {
        TACZLegacy.logger.info(
            "[GunPackRuntime] Loaded {} pack(s), {} gun(s), {} attachment(s), {} ammo index(es), {} block index(es), {} loot injection(s).",
            loaded.packs.size,
            loaded.guns.size,
            loaded.attachments.size,
            loaded.ammos.size,
            loaded.blocks.size,
            loaded.lootInjections.size,
        )
        if (loaded.issues.isNotEmpty()) {
            // 聚合限流：一个破损严重的第三方包可能产生几十上百条同类 issue，
            // 逐条 WARN 会把日志淹没。按"前缀"分组，每组最多打印 10 条，其余折叠成摘要。
            val perGroupLimit = 10
            loaded.issues
                .groupBy { issue -> issue.take(48) }
                .forEach { (_, group) ->
                    group.take(perGroupLimit).forEach { issue -> TACZLegacy.logger.warn("[GunPackRuntime] {}", issue) }
                    if (group.size > perGroupLimit) {
                        TACZLegacy.logger.warn(
                            "[GunPackRuntime] ... and {} more issue(s) starting with '{}' (suppressed).",
                            group.size - perGroupLimit,
                            group.first().take(48),
                        )
                    }
                }
        }
    }

    internal fun getSnapshot(): TACZRuntimeSnapshot = snapshot

    @JvmStatic
    fun currentSnapshotForJava(): TACZRuntimeSnapshot = snapshot

    internal fun clearForTests(): Unit {
        snapshot = TACZRuntimeSnapshot.EMPTY
    }
}

/**
 * 上游 `FireMode` 的四个 @SerializedName。`unknown` 是合法枚举值，但索引层会把它判为非法，
 * 因此这里保留在集合里，让"显式写 unknown"和"缺省回退 unknown"走同一条拒绝路径。
 */
private const val FIRE_MODE_UNKNOWN: String = "unknown"
private val FIRE_MODE_NAMES: Set<String> = setOf("auto", "semi", "burst", FIRE_MODE_UNKNOWN)

/**
 * 上游 `FeedType` 的四个 @SerializedName。缺省是 `magazine`。
 * 本移植的换弹状态机目前只有 magazine / fuel 两条填充分支，manual / inventory 仍按 magazine 处理，
 * 但索引层必须接受它们（上游 `reload.type` 的合法值就是这四个）。
 */
internal val TACZ_RELOAD_TYPES: Set<String> = setOf("magazine", "manual", "fuel", "inventory")

/**
 * B4 / X-06、X-20：解析 `fire_mode` 数组。
 *
 * - 字段缺省（不存在/null）→ `[unknown]`，对齐上游 `GunData.fireModeSet` 默认值；
 * - 数组元素非法（非字符串 / 未知名字）→ 记 issue 并**只跳过该元素**；
 * - 字段存在但不是数组 → 记 issue，按 `[unknown]` 处理（最终由索引层拒绝该枪）。
 *
 * 返回值是规范化后的小写名字，供索引层校验与 [GunCombatData] 消费。
 */
internal fun parseFireModeNames(
    raw: JsonObject,
    issues: MutableList<String>? = null,
    issuePrefix: String = "",
): List<String> {
    val element = raw.get("fire_mode")
    if (element == null || element.isJsonNull) {
        return listOf(FIRE_MODE_UNKNOWN)
    }
    if (!element.isJsonArray) {
        issues?.add(
            "$issuePrefix 'fire_mode'=$element is not a JSON array; treated as [$FIRE_MODE_UNKNOWN] " +
                "(the gun is rejected by the index check).",
        )
        return listOf(FIRE_MODE_UNKNOWN)
    }
    val modes = LinkedHashSet<String>()
    element.asJsonArray.forEachIndexed { index, item ->
        if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
            issues?.add("$issuePrefix 'fire_mode[$index]'=$item is not a string; mode skipped.")
            return@forEachIndexed
        }
        val rawName = item.asString.trim()
        val normalized = rawName.lowercase(Locale.ROOT)
        if (normalized in FIRE_MODE_NAMES) {
            modes += normalized
        } else {
            issues?.add(
                "$issuePrefix 'fire_mode[$index]'='$rawName' is not a valid fire mode " +
                    "(expected auto/semi/burst/unknown); mode skipped.",
            )
        }
    }
    return modes.toList()
}

internal object TACZGunPackScanner {
    private val GUNPACK_INFO_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/gunpack_info\\.json$", Pattern.CASE_INSENSITIVE)
    private val GUN_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/guns/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val GUN_DATA_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/data/guns/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/attachments/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_DATA_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/data/attachments/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val AMMO_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/ammo/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val BLOCK_INDEX_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/index/blocks/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val BLOCK_DATA_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/data/blocks/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val RECIPE_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/recipes/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val RECIPE_FILTER_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/recipe_filters/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_TAG_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/tacz_tags/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val GUN_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/guns/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val AMMO_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/ammo/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val ATTACHMENT_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/attachments/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val BLOCK_DISPLAY_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/display/blocks/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)
    private val LANG_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/lang/([\\w-]+)\\.(json|lang)$", Pattern.CASE_INSENSITIVE)
    private val DATA_SCRIPT_PATTERN: Pattern = Pattern.compile("^data/([a-z0-9_.-]+)/scripts/([\\w/.-]+)\\.lua$", Pattern.CASE_INSENSITIVE)
    /**
     * 上游的脚本位置：`FileToIdConverter("scripts", ".lua")` 在 1.20.1 里读的是
     * **`assets/<ns>/scripts/`**（`CommonAssetsManager:84`、`ClientAssetsManager:105`）。
     *
     * 移植层原先只认 `data/<ns>/scripts/`，导致**按上游规范放置脚本的枪包装不进脚本** ——
     * 实测 `fatal_contract` 的 3 个、`daffas_arsenal` 的几十个 `*_state_machine.lua`
     * 全部落在 `assets/` 下，于是 `require` 互相引用时全部解析失败。
     */
    private val ASSET_SCRIPT_PATTERN: Pattern = Pattern.compile("^assets/([a-z0-9_.-]+)/scripts/([\\w/.-]+)\\.lua$", Pattern.CASE_INSENSITIVE)
    // 兼容层 ③ C13：1.20 枪包的战利品注入目录（上游 FileToIdConverter.json("tacz_loot_injectors")）
    private val LOOT_INJECTOR_PATTERN: Pattern =
        Pattern.compile("^data/([a-z0-9_.-]+)/tacz_loot_injectors/([\\w/.-]+)\\.json$", Pattern.CASE_INSENSITIVE)

    internal fun scan(packsRoot: File): TACZRuntimeSnapshot {
        if (!packsRoot.exists()) {
            packsRoot.mkdirs()
        }
        val rawPacks = LinkedHashMap<String, TACZLoadedPack>()
        val rawPackInfos = LinkedHashMap<String, TACZPackInfo>()
        val rawGunIndices = LinkedHashMap<ResourceLocation, TACZGunIndexDefinition>()
        val rawGunData = LinkedHashMap<ResourceLocation, TACZGunDataDefinition>()
        val rawAttachmentIndices = LinkedHashMap<ResourceLocation, TACZAttachmentIndexDefinition>()
        val rawAttachmentData = LinkedHashMap<ResourceLocation, TACZAttachmentDataDefinition>()
        val rawAmmoIndices = LinkedHashMap<ResourceLocation, TACZAmmoIndexDefinition>()
        val rawBlockIndices = LinkedHashMap<ResourceLocation, TACZBlockIndexDefinition>()
        val rawBlockData = LinkedHashMap<ResourceLocation, TACZBlockDataDefinition>()
        val rawRecipes = LinkedHashMap<ResourceLocation, TACZRecipeDefinition>()
        val rawRecipeFilters = LinkedHashMap<ResourceLocation, TACZRecipeFilterDefinition>()
        val rawAttachmentTags = LinkedHashMap<ResourceLocation, LinkedHashSet<String>>()
        val rawAllowAttachmentTags = LinkedHashMap<ResourceLocation, LinkedHashSet<String>>()
        val rawGunDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawAmmoDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawAttachmentDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawBlockDisplays = LinkedHashMap<ResourceLocation, TACZDisplayDefinition>()
        val rawTranslations = LinkedHashMap<String, LinkedHashMap<String, String>>()
        val rawDataScripts = LinkedHashMap<ResourceLocation, String>()
        val rawLootInjections = ArrayList<TACZLootInjectionDefinition>()
        val issues = mutableListOf<String>()

        // 兼容层 ③ C2：服务端已同步同名远程包时，本地目录**不再整体让位**。
        //
        // 同步包只含数据 / display / lang / 脚本，不含模型、贴图和音效
        // （见 [TACZPackTransport.isSyncable]）。以前这里直接 `return@forEach` 跳过本地包，
        // 结果是远程客户端把本地解压出来的完整包丢掉、只留同步来的一半文件 ——
        // 表现为 `model not found: tacz:gun/xxx_geo`、`Missing gun display instance`，
        // 枪能 give 出来但完全不可见。
        //
        // 现在改成"本地文件兜底、远程同路径覆盖"：资源由本地提供，数据以服务端为准。
        //
        // 两个细节必须处理掉，否则修完还是错的：
        // ① 包名归一化 —— 服务端拿文件名当包名（`foo.zip` / `foo`），客户端可能以另一种形态
        //    存在同名包（压缩包 vs 解压目录），不归一化就会"远程一套、本地一套"加载两遍；
        // ② 只有真正会被扫描的条目才算"本地已有这个包" —— 否则 packsRoot 里随便一个
        //    无关文件就能把同名的远程包整个屏蔽掉。
        val remotePacks = TACZRemotePackStore.snapshot()
        val entries = packsRoot.listFiles()?.sortedBy { it.name.lowercase(Locale.ROOT) }.orEmpty()
        val remoteByKey = remotePacks.entries.associate { packKeyOf(it.key) to it.value }
        val localPackKeys = entries
            .filter { it.isDirectory || it.name.lowercase(Locale.ROOT).endsWith(".zip") }
            .mapTo(HashSet()) { packKeyOf(it.name) }
        entries.forEach { candidate ->
            scanCandidate(
                candidate = candidate,
                remoteOverride = remoteByKey[packKeyOf(candidate.name)],
                rawPacks = rawPacks,
                rawPackInfos = rawPackInfos,
                rawGunIndices = rawGunIndices,
                rawGunData = rawGunData,
                rawAttachmentIndices = rawAttachmentIndices,
                rawAttachmentData = rawAttachmentData,
                rawAmmoIndices = rawAmmoIndices,
                rawBlockIndices = rawBlockIndices,
                rawBlockData = rawBlockData,
                rawRecipes = rawRecipes,
                rawRecipeFilters = rawRecipeFilters,
                rawAttachmentTags = rawAttachmentTags,
                rawAllowAttachmentTags = rawAllowAttachmentTags,
                rawGunDisplays = rawGunDisplays,
                rawAmmoDisplays = rawAmmoDisplays,
                rawAttachmentDisplays = rawAttachmentDisplays,
                rawBlockDisplays = rawBlockDisplays,
                rawTranslations = rawTranslations,
                rawDataScripts = rawDataScripts,
                rawLootInjections = rawLootInjections,
                issues = issues,
            )
        }

        // 兼容层 ③ C2：扫描服务端同步下来的内存包（与本地包走同一套解析路径）
        remotePacks.forEach { (packName, entryMap) ->
            // 本地已有同名包：上面已经按"本地兜底 + 远程覆盖"合并扫过，这里不能再扫一遍
            if (packKeyOf(packName) in localPackKeys) {
                return@forEach
            }
            try {
                TACZMemoryPackSource(entryMap).use { source ->
                    loadPack(
                        source = source,
                        candidate = File(packName),
                        rawPacks = rawPacks,
                        rawPackInfos = rawPackInfos,
                        rawGunIndices = rawGunIndices,
                        rawGunData = rawGunData,
                        rawAttachmentIndices = rawAttachmentIndices,
                        rawAttachmentData = rawAttachmentData,
                        rawAmmoIndices = rawAmmoIndices,
                        rawBlockIndices = rawBlockIndices,
                        rawBlockData = rawBlockData,
                        rawRecipes = rawRecipes,
                        rawRecipeFilters = rawRecipeFilters,
                        rawAttachmentTags = rawAttachmentTags,
                        rawAllowAttachmentTags = rawAllowAttachmentTags,
                        rawGunDisplays = rawGunDisplays,
                        rawAmmoDisplays = rawAmmoDisplays,
                        rawAttachmentDisplays = rawAttachmentDisplays,
                        rawBlockDisplays = rawBlockDisplays,
                        rawTranslations = rawTranslations,
                        rawDataScripts = rawDataScripts,
                        rawLootInjections = rawLootInjections,
                        issues = issues,
                    )
                }
            } catch (throwable: Throwable) {
                issues += "Remote pack $packName skipped due to load failure " +
                    "(${throwable.message ?: throwable.javaClass.simpleName})."
            }
        }

        val guns = LinkedHashMap<ResourceLocation, TACZLoadedGun>()
        rawGunIndices.entries
            .sortedWith(compareBy<Map.Entry<ResourceLocation, TACZGunIndexDefinition>> { it.value.sort.coerceIn(0, 65536) }.thenBy { it.key.toString() })
            .forEach { (id, index) ->
                val dataId = index.data
                val data = dataId?.let(rawGunData::get)
                if (data == null) {
                    issues += "Gun index $id references missing data ${dataId ?: "<null>"}."
                    return@forEach
                }
                if (index.type.isBlank()) {
                    issues += "Gun index $id is missing type field."
                    return@forEach
                }
                // B4 / X-17：上游 CommonGunIndex.checkData 要求 ammo id 非空；
                // 这里对齐为"整把枪拒绝加载"而不是整包失败。
                if (data.ammoId == null) {
                    issues += "Gun index $id rejected: gun data ${dataId ?: "<null>"} has no 'ammo' " +
                        "(upstream: ammo id is empty)."
                    return@forEach
                }
                // B4 / X-06：fire_mode 缺省为 [unknown]；索引层硬校验"不含 UNKNOWN"，
                // 非法元素已由 parseFireModeNames 记 issue 并跳过，剩下的列表为空同样拒绝。
                val fireModes = parseFireModeNames(data.raw, issues, "Gun data $dataId")
                if (fireModes.isEmpty()) {
                    issues += "Gun index $id rejected: gun data ${dataId ?: "<null>"} has an empty 'fire_mode' " +
                        "(upstream: fire mode is empty)."
                    return@forEach
                }
                if (FIRE_MODE_UNKNOWN in fireModes) {
                    issues += "Gun index $id rejected: gun data ${dataId ?: "<null>"} 'fire_mode' contains " +
                        "'$FIRE_MODE_UNKNOWN' (upstream: fire mode is error)."
                    return@forEach
                }
                guns[id] = TACZLoadedGun(id = id, index = index.copy(sort = index.sort.coerceIn(0, 65536)), data = data)
            }

        val attachments = LinkedHashMap<ResourceLocation, TACZLoadedAttachment>()
        rawAttachmentIndices.entries
            .sortedWith(compareBy<Map.Entry<ResourceLocation, TACZAttachmentIndexDefinition>> { it.value.sort.coerceIn(0, 65536) }.thenBy { it.key.toString() })
            .forEach { (id, index) ->
                val dataId = index.data
                val data = dataId?.let(rawAttachmentData::get)
                if (data == null) {
                    issues += "Attachment index $id references missing data ${dataId ?: "<null>"}."
                    return@forEach
                }
                if (index.type.isBlank()) {
                    issues += "Attachment index $id is missing type field."
                    return@forEach
                }
                attachments[id] = TACZLoadedAttachment(id = id, index = index.copy(sort = index.sort.coerceIn(0, 65536)), data = data)
            }

        val blocks = LinkedHashMap<ResourceLocation, TACZLoadedBlock>()
        rawBlockIndices.entries
            .sortedWith(compareBy<Map.Entry<ResourceLocation, TACZBlockIndexDefinition>> { it.value.sort.coerceIn(0, 65536) }.thenBy { it.key.toString() })
            .forEach { (id, index) ->
                val dataId = index.data
                val data = dataId?.let(rawBlockData::get)
                if (data == null) {
                    issues += "Block index $id references missing data ${dataId ?: "<null>"}."
                    return@forEach
                }
                blocks[id] = TACZLoadedBlock(id = id, index = index.copy(sort = index.sort.coerceIn(0, 65536)), data = data)
            }

        return TACZRuntimeSnapshot(
            packs = rawPacks.toMap(),
            packInfos = rawPackInfos.toMap(),
            guns = guns.toMap(),
            attachments = attachments.toMap(),
            ammos = rawAmmoIndices.toMap(),
            blocks = blocks.toMap(),
            recipes = rawRecipes.toMap(),
            recipeFilters = rawRecipeFilters.toMap(),
            attachmentTags = rawAttachmentTags.mapValues { (_, values) -> values.toSet() },
            allowAttachmentTags = rawAllowAttachmentTags.mapValues { (_, values) -> values.toSet() },
            gunDisplays = rawGunDisplays.toMap(),
            ammoDisplays = rawAmmoDisplays.toMap(),
            attachmentDisplays = rawAttachmentDisplays.toMap(),
            blockDisplays = rawBlockDisplays.toMap(),
            dataScripts = rawDataScripts.toMap(),
            lootInjections = rawLootInjections.toList(),
            translations = rawTranslations.mapValues { (_, values) -> values.toMap() },
            issues = issues.toList(),
        )
    }

    /** 包名归一化：去掉压缩包后缀并转小写，把"远程包的 key"和"本地文件/目录名"对上。 */
    private fun packKeyOf(name: String): String =
        name.lowercase(Locale.ROOT).removeSuffix(".zip")

    /** 本地源叠上远程覆盖；没有远程版本时原样返回本地源（零额外开销）。 */
    private fun overlaySource(local: TACZPackSource, remoteOverride: Map<String, String>?): TACZPackSource =
        if (remoteOverride.isNullOrEmpty()) local else TACZOverlayPackSource(local, remoteOverride)

    private fun scanCandidate(
        candidate: File,
        /** 服务端同步下来的同名包内容；为 null 表示这个包没有远程版本。 */
        remoteOverride: Map<String, String>? = null,
        rawPacks: MutableMap<String, TACZLoadedPack>,
        rawPackInfos: MutableMap<String, TACZPackInfo>,
        rawGunIndices: MutableMap<ResourceLocation, TACZGunIndexDefinition>,
        rawGunData: MutableMap<ResourceLocation, TACZGunDataDefinition>,
        rawAttachmentIndices: MutableMap<ResourceLocation, TACZAttachmentIndexDefinition>,
        rawAttachmentData: MutableMap<ResourceLocation, TACZAttachmentDataDefinition>,
        rawAmmoIndices: MutableMap<ResourceLocation, TACZAmmoIndexDefinition>,
        rawBlockIndices: MutableMap<ResourceLocation, TACZBlockIndexDefinition>,
        rawBlockData: MutableMap<ResourceLocation, TACZBlockDataDefinition>,
        rawRecipes: MutableMap<ResourceLocation, TACZRecipeDefinition>,
        rawRecipeFilters: MutableMap<ResourceLocation, TACZRecipeFilterDefinition>,
        rawAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawAllowAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawGunDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAmmoDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAttachmentDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawBlockDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawTranslations: MutableMap<String, LinkedHashMap<String, String>>,
        rawDataScripts: MutableMap<ResourceLocation, String>,
        rawLootInjections: MutableList<TACZLootInjectionDefinition>,
        issues: MutableList<String>,
    ): Unit {
        if (!candidate.isDirectory && !(candidate.isFile && candidate.name.lowercase(Locale.ROOT).endsWith(".zip"))) {
            return
        }
        runCatching {
            when {
                candidate.isDirectory -> TACZDirectoryPackSource(candidate).use { local ->
                    loadPack(
                        source = overlaySource(local, remoteOverride),
                        candidate = candidate,
                        rawPacks = rawPacks,
                        rawPackInfos = rawPackInfos,
                        rawGunIndices = rawGunIndices,
                        rawGunData = rawGunData,
                        rawAttachmentIndices = rawAttachmentIndices,
                        rawAttachmentData = rawAttachmentData,
                        rawAmmoIndices = rawAmmoIndices,
                        rawBlockIndices = rawBlockIndices,
                        rawBlockData = rawBlockData,
                        rawRecipes = rawRecipes,
                        rawRecipeFilters = rawRecipeFilters,
                        rawAttachmentTags = rawAttachmentTags,
                        rawAllowAttachmentTags = rawAllowAttachmentTags,
                        rawGunDisplays = rawGunDisplays,
                        rawAmmoDisplays = rawAmmoDisplays,
                        rawAttachmentDisplays = rawAttachmentDisplays,
                        rawBlockDisplays = rawBlockDisplays,
                        rawTranslations = rawTranslations,
                        rawDataScripts = rawDataScripts,
                rawLootInjections = rawLootInjections,
                        issues = issues,
                    )
                }
                else -> ZipFile(candidate).use { zipFile ->
                    TACZZipPackSource(candidate, zipFile).use { local ->
                        loadPack(
                            source = overlaySource(local, remoteOverride),
                            candidate = candidate,
                            rawPacks = rawPacks,
                            rawPackInfos = rawPackInfos,
                            rawGunIndices = rawGunIndices,
                            rawGunData = rawGunData,
                            rawAttachmentIndices = rawAttachmentIndices,
                            rawAttachmentData = rawAttachmentData,
                            rawAmmoIndices = rawAmmoIndices,
                            rawBlockIndices = rawBlockIndices,
                            rawBlockData = rawBlockData,
                            rawRecipes = rawRecipes,
                            rawRecipeFilters = rawRecipeFilters,
                            rawAttachmentTags = rawAttachmentTags,
                            rawAllowAttachmentTags = rawAllowAttachmentTags,
                            rawGunDisplays = rawGunDisplays,
                            rawAmmoDisplays = rawAmmoDisplays,
                            rawAttachmentDisplays = rawAttachmentDisplays,
                            rawBlockDisplays = rawBlockDisplays,
                            rawTranslations = rawTranslations,
                            rawDataScripts = rawDataScripts,
                rawLootInjections = rawLootInjections,
                            issues = issues,
                        )
                    }
                }
            }
        }.onFailure { throwable ->
            TACZLegacy.logger.warn("[GunPackRuntime] Skipping pack {} due to load failure.", candidate.name, throwable)
            issues += "Pack ${candidate.name} skipped because it could not be read safely (${throwable.message ?: throwable.javaClass.simpleName})."
        }
    }

    private fun loadPack(
        source: TACZPackSource,
        candidate: File,
        rawPacks: MutableMap<String, TACZLoadedPack>,
        rawPackInfos: MutableMap<String, TACZPackInfo>,
        rawGunIndices: MutableMap<ResourceLocation, TACZGunIndexDefinition>,
        rawGunData: MutableMap<ResourceLocation, TACZGunDataDefinition>,
        rawAttachmentIndices: MutableMap<ResourceLocation, TACZAttachmentIndexDefinition>,
        rawAttachmentData: MutableMap<ResourceLocation, TACZAttachmentDataDefinition>,
        rawAmmoIndices: MutableMap<ResourceLocation, TACZAmmoIndexDefinition>,
        rawBlockIndices: MutableMap<ResourceLocation, TACZBlockIndexDefinition>,
        rawBlockData: MutableMap<ResourceLocation, TACZBlockDataDefinition>,
        rawRecipes: MutableMap<ResourceLocation, TACZRecipeDefinition>,
        rawRecipeFilters: MutableMap<ResourceLocation, TACZRecipeFilterDefinition>,
        rawAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawAllowAttachmentTags: MutableMap<ResourceLocation, LinkedHashSet<String>>,
        rawGunDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAmmoDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawAttachmentDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawBlockDisplays: MutableMap<ResourceLocation, TACZDisplayDefinition>,
        rawTranslations: MutableMap<String, LinkedHashMap<String, String>>,
        rawDataScripts: MutableMap<ResourceLocation, String>,
        rawLootInjections: MutableList<TACZLootInjectionDefinition>,
        issues: MutableList<String>,
    ): Unit {
        // 兼容性修复：缺 gunpack.meta.json 时不再静默丢包，改用目录名推断 namespace 继续加载
        val packMetaText = source.readText("gunpack.meta.json")
        var meta: TACZPackMeta = if (packMetaText != null) {
            val parsed = runCatching { TACZJson.fromJson(packMetaText, TACZPackMeta::class.java) }.getOrNull()
            if (parsed == null) {
                issues += "Pack ${candidate.name} has invalid gunpack.meta.json, using fallback namespace."
                TACZPackMeta()
            } else {
                parsed
            }
        } else {
            issues += "Pack ${candidate.name} has no gunpack.meta.json, using fallback namespace."
            TACZPackMeta()
        }
        // name 为空时用净化的目录名兜底（F3/F4 修复：实际赋值而非只记日志）
        if (meta.name.isBlank()) {
            val fallback = sanitizeNamespace(candidate.name)
            issues += "Pack ${candidate.name} has empty namespace, using '$fallback' as fallback."
            meta = meta.copy(name = fallback)
        }
        // 兼容性修复：依赖版本不匹配时不再整包丢弃，仅记 issue 后继续加载
        // 移植版版本号(0.1.1)与上游 1.20.1 枪包声明的版本区间不兼容，但数据格式是兼容的
        if (!TACZPackDependencyChecker.allMatch(meta.dependencies)) {
            issues += "Pack ${candidate.name}: some dependencies not matched, loading continues anyway."
        }

        val packInfoByNamespace = LinkedHashMap<String, TACZPackInfo>()
        // F2 修复：包身份最小校验 —— 先收集 entry 列表，判断是否是枪包（含 index/data/display），
        // 不是枪包就 return 不处理任何 entry，避免 lang/script 被写入全局 map 后又被 skip 造成半加载
        val allEntries = source.listEntries().toList()
        val isGunPack = allEntries.any { entryName ->
            GUNPACK_INFO_PATTERN.matcher(entryName).matches() ||
            GUN_INDEX_PATTERN.matcher(entryName).matches() ||
            GUN_DATA_PATTERN.matcher(entryName).matches() ||
            ATTACHMENT_INDEX_PATTERN.matcher(entryName).matches() ||
            ATTACHMENT_DATA_PATTERN.matcher(entryName).matches() ||
            AMMO_INDEX_PATTERN.matcher(entryName).matches() ||
            BLOCK_INDEX_PATTERN.matcher(entryName).matches() ||
            BLOCK_DATA_PATTERN.matcher(entryName).matches() ||
            GUN_DISPLAY_PATTERN.matcher(entryName).matches() ||
            AMMO_DISPLAY_PATTERN.matcher(entryName).matches() ||
            ATTACHMENT_DISPLAY_PATTERN.matcher(entryName).matches() ||
            BLOCK_DISPLAY_PATTERN.matcher(entryName).matches()
        }
        if (!isGunPack) {
            issues += "Pack ${candidate.name} has no gunpack content (index/data/display); skipped (not a TACZ gun pack)."
            return
        }
        // 兼容层 ③ C2：把可下发的原始文件随包留在快照里。
        // 用同一个 source 读，保证"下发的"与"解析的"是同源字节；zip 包也能读到（旧的磁盘遍历做不到）。
        val syncableFiles = LinkedHashMap<String, String>()
        allEntries.forEach { entryName ->
            if (!TACZPackTransport.isSyncable(entryName)) {
                return@forEach
            }
            val text = runCatching { source.readText(entryName) }.getOrNull()
            if (text == null) {
                // 读不出来就明确记一条：静默少发会让客户端表现成"这枪没有"，无从排查
                issues += "Pack ${candidate.name}: cannot read syncable file '$entryName'; " +
                    "it will not be sent to clients."
            } else {
                syncableFiles[entryName] = text
            }
        }
        allEntries.forEach { entryName ->
            try {
                when {
                    entryName == "gunpack.meta.json" -> Unit
                    GUNPACK_INFO_PATTERN.matcher(entryName).matches() -> {
                        val matcher = GUNPACK_INFO_PATTERN.matcher(entryName)
                        matcher.find()
                        val namespace = matcher.group(1)
                        val info = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZPackInfo::class.java)
                        packInfoByNamespace[namespace] = info
                        putOrReport(rawPackInfos, namespace, info, candidate.name, "pack info", issues)
                    }
                    GUN_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, GUN_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZGunIndexDefinition::class.java)
                        putOrReport(rawGunIndices, id, index, candidate.name, "gun index", issues)
                    }
                    GUN_DATA_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, GUN_DATA_PATTERN)
                        val jsonObject = TACZJson.parseObject(requireNotNull(source.readText(entryName)))
                        putOrReport(rawGunData, id, parseGunData(jsonObject, id, candidate.name, issues), candidate.name, "gun data", issues)
                    }
                    ATTACHMENT_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZAttachmentIndexDefinition::class.java)
                        putOrReport(rawAttachmentIndices, id, index, candidate.name, "attachment index", issues)
                    }
                    ATTACHMENT_DATA_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_DATA_PATTERN)
                        val rawText = requireNotNull(source.readText(entryName))
                        val jsonObject = TACZJson.parseObject(rawText)
                        val data = toAttachmentData(jsonObject, rawText, issues, "Attachment data $id")
                        if (data == null) {
                            return@forEach
                        }
                        putOrReport(rawAttachmentData, id, data, candidate.name, "attachment data", issues)
                    }
                    AMMO_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, AMMO_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZAmmoIndexDefinition::class.java)
                        putOrReport(
                            rawAmmoIndices, id,
                            index.copy(
                                stackSize = index.stackSize.coerceAtLeast(1),
                                // 与 gun/attachment/block 三类对齐：装载时就把 sort 钳进 [0, 65536]，
                                // 避免将来任何直接读 index.sort 的消费者绕过 TACZGunPackPresentation 的比较器。
                                sort = index.sort.coerceIn(0, 65536),
                            ),
                            candidate.name, "ammo index", issues,
                        )
                    }
                    BLOCK_INDEX_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, BLOCK_INDEX_PATTERN)
                        val index = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZBlockIndexDefinition::class.java)
                        putOrReport(rawBlockIndices, id, index, candidate.name, "block index", issues)
                    }
                    BLOCK_DATA_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, BLOCK_DATA_PATTERN)
                        putOrReport(rawBlockData, id, parseBlockData(TACZJson.parseObject(requireNotNull(source.readText(entryName)))), candidate.name, "block data", issues)
                    }
                    RECIPE_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, RECIPE_PATTERN)
                        putOrReport(rawRecipes, id, TACZRecipeDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName)))), candidate.name, "recipe", issues)
                    }
                    RECIPE_FILTER_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, RECIPE_FILTER_PATTERN)
                        val filter = TACZJson.fromJson(requireNotNull(source.readText(entryName)), TACZRecipeFilterDefinition::class.java)
                        putOrReport(rawRecipeFilters, id, filter, candidate.name, "recipe filter", issues)
                    }
                    LOOT_INJECTOR_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, LOOT_INJECTOR_PATTERN)
                        val parsed = parseLootInjection(
                            id,
                            TACZJson.parseObject(requireNotNull(source.readText(entryName))),
                            issues,
                        )
                        if (parsed != null) {
                            rawLootInjections += parsed
                        }
                    }
                    ATTACHMENT_TAG_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_TAG_PATTERN)
                        val values = parseTagEntries(requireNotNull(source.readText(entryName)))
                        val normalizedPath = id.path.removePrefix(ATTACHMENT_TAG_ROOT_PREFIX)
                        if (normalizedPath.startsWith(ALLOW_ATTACHMENT_PREFIX) && normalizedPath.length > ALLOW_ATTACHMENT_PREFIX.length) {
                            val gunId = ResourceLocation(id.namespace, normalizedPath.substring(ALLOW_ATTACHMENT_PREFIX.length))
                            mergeTagEntries(rawAllowAttachmentTags, gunId, values)
                        } else {
                            mergeTagEntries(rawAttachmentTags, ResourceLocation(id.namespace, normalizedPath), values)
                        }
                    }
                    GUN_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, GUN_DISPLAY_PATTERN)
                        putOrReport(rawGunDisplays, id, TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName)))), candidate.name, "gun display", issues)
                    }
                    AMMO_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, AMMO_DISPLAY_PATTERN)
                        putOrReport(rawAmmoDisplays, id, TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName)))), candidate.name, "ammo display", issues)
                    }
                    ATTACHMENT_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, ATTACHMENT_DISPLAY_PATTERN)
                        putOrReport(rawAttachmentDisplays, id, TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName)))), candidate.name, "attachment display", issues)
                    }
                    BLOCK_DISPLAY_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, BLOCK_DISPLAY_PATTERN)
                        putOrReport(rawBlockDisplays, id, TACZDisplayDefinition(id, TACZJson.parseObject(requireNotNull(source.readText(entryName)))), candidate.name, "block display", issues)
                    }
                    LANG_PATTERN.matcher(entryName).matches() -> {
                        val matcher = LANG_PATTERN.matcher(entryName)
                        matcher.find()
                        val locale = normalizeLocaleName(matcher.group(2))
                        val extension = matcher.group(3)
                        val translations = parseTranslations(requireNotNull(source.readText(entryName)), extension)
                        if (translations.isNotEmpty()) {
                            rawTranslations.computeIfAbsent(locale) { LinkedHashMap() }.putAll(translations)
                        }
                    }
                    DATA_SCRIPT_PATTERN.matcher(entryName).matches() -> {
                        val id = resourceIdFrom(entryName, DATA_SCRIPT_PATTERN)
                        val scriptSource = source.readText(entryName)
                        if (scriptSource != null) {
                            rawDataScripts[id] = scriptSource
                        }
                    }
                    ASSET_SCRIPT_PATTERN.matcher(entryName).matches() -> {
                        // 上游规范位置：assets/<ns>/scripts/<path>.lua → 脚本 id 仍是 <ns>:<path>
                        val id = resourceIdFrom(entryName, ASSET_SCRIPT_PATTERN)
                        val scriptSource = source.readText(entryName)
                        if (scriptSource != null) {
                            rawDataScripts[id] = scriptSource
                        }
                    }
                }
            } catch (throwable: Throwable) {
                issues += "Failed to parse ${candidate.name}:$entryName (${throwable.message})"
            }
        }

        rawPacks[candidate.name] = TACZLoadedPack(
            meta = meta,
            sourceFile = candidate,
            infoByNamespace = packInfoByNamespace,
            rawFiles = syncableFiles,
        )
    }

    /**
     * 兼容层 ③ C10：把「配件 data 文件」与「枪的 exclusive_attachments 条目」解析成统一的
     * TACZAttachmentDataDefinition（两者 JSON 结构相同，都是上游的 AttachmentData）。
     *
     * @param rawText 原始文本。当前所有 modifier reader 都只用 jsonObject、忽略该参数
     *                （保留形参仅为对齐上游 `TACZModifierReader.read(String, JsonObject)` 签名）；
     *                exclusive 条目没有独立文件，因此传入 `jsonObject.toString()` 合成一份。
     *                若将来有 reader 依赖原文，exclusive 路径拿到的将是规范化输出而非文件原文。
     * @param enforceHardThreshold 坏 modifier 占多数时是否**整件放弃**（返回 null）。
     *        只对专属条目（`exclusive_attachments`）开启：独占语义要求"整份替换或整份回落"，
     *        留半套数值最难察觉。普通配件 data **不开**，因为它没有下一级回落 ——
     *        整件丢弃会让配件彻底消失（索引指向不存在的 data），比少一个加成更重。
     */
    private fun toAttachmentData(
        jsonObject: JsonObject,
        rawText: String = jsonObject.toString(),
        issues: MutableList<String>? = null,
        issuePrefix: String = "",
        enforceHardThreshold: Boolean = false,
    ): TACZAttachmentDataDefinition? {
        val stats = TACZModifierReadStats()
        val modifiers = TACZAttachmentModifierRegistry.readModifiers(rawText, jsonObject, issues, issuePrefix, stats)
        if (stats.rejected) {
            // review-10a 阻断项 2：坏 modifier 不少于好 modifier 时，**exclusive 条目**判整件失败。
            // 理由：独占语义是"命中即整份替换、绝不回退"，留半套数值比直接回落普通配件更难察觉。
            //
            // 但普通配件 data 不一样 —— 它没有"下一级回落"：整件丢弃会让配件从创造栏、
            // 渲染、数据消费里**全面消失**（index 指向不存在的 data），比"少一个加成"更重。
            // 所以硬阈值只对 exclusive 启用，普通配件仍走 reader 级隔离（丢坏字段、留其余）。
            if (enforceHardThreshold) {
                issues?.add("$issuePrefix rejected: ${stats.describe()}; the whole entry is dropped.")
                return null
            }
            issues?.add(
                "$issuePrefix: ${stats.describe()}; the bad modifier(s) are skipped and the entry is kept " +
                    "with the remaining ones.",
            )
        }
        return TACZAttachmentDataDefinition(
            raw = jsonObject,
            weight = jsonObject.floatValue("weight"),
            extendedMagLevel = jsonObject.intValue("extended_mag_level"),
            modifiers = modifiers,
            meleeData = parseAttachmentMeleeData(jsonObject, issues, issuePrefix),
        )
    }

    /**
     * 近战段解析失败（第三方把 `prep` 写成对象、`effects` 写成对象）只丢掉近战数据并记 issue，
     * 不拖垮整条配件 —— 与 reader 级隔离同一取向。
     */
    private fun parseAttachmentMeleeData(
        jsonObject: JsonObject,
        issues: MutableList<String>?,
        issuePrefix: String,
    ): AttachmentMeleeCombatData? = try {
        AttachmentMeleeCombatData.fromRawJson(jsonObject)
    } catch (throwable: Throwable) {
        val detail = "$issuePrefix 'melee' parse failed " +
            "(${throwable.javaClass.simpleName}: ${throwable.message}); melee data dropped."
        if (issues != null) issues.add(detail) else TACZLegacy.logger.warn("[GunPackRuntime] {}", detail)
        null
    }

    /**
     * 审计 B4-20：逐元素解析 `allow_attachment_types`，坏元素只跳过自己。
     *
     * 旧写法用 `runCatching { stringArray(...) }` 包住**整段**：第三方包 nova9 的尾逗号让 lenient
     * Gson 产出 `[scope, JsonNull]`，`asString` 抛在 JsonNull 元素上，整段被降级成 emptyList()，
     * 连合法的 `"scope"` 一起丢。而读取门 `LegacyItems.allowAttachmentType` 把空列表解释成
     * "禁止一切"、改装台显示 0 个槽位 —— 一个逗号让整把枪的配件全灭。
     * 上游 `GunData.allowAttachments` 是 `List<AttachmentType>`，能容忍 `[SCOPE, null]`，
     * 所以"留好的、跳坏的"才是对齐行为。大小写由消费端 `AttachmentType.fromSerializedName` 归一。
     */
    private fun parseAllowAttachmentTypes(
        raw: JsonObject,
        issues: MutableList<String>,
        issuePrefix: String,
    ): List<String> {
        val element = raw.get("allow_attachment_types") ?: return emptyList()
        if (!element.isJsonArray) {
            val shown = if (element.isJsonNull) "null" else element.toString()
            issues += "$issuePrefix: 'allow_attachment_types'=$shown is not a JSON array; ignored."
            return emptyList()
        }
        val types = ArrayList<String>(element.asJsonArray.size())
        element.asJsonArray.forEachIndexed { index, item ->
            val name = if (item.isJsonPrimitive && item.asJsonPrimitive.isString) item.asString else null
            if (name.isNullOrBlank()) {
                issues += "$issuePrefix: 'allow_attachment_types[$index]'=$item is not a type name; " +
                    "that entry is skipped."
                return@forEachIndexed
            }
            types += name
        }
        return types
    }

    private fun parseGunData(
        jsonObject: JsonObject,
        dataId: ResourceLocation,
        packName: String,
        issues: MutableList<String>,
    ): TACZGunDataDefinition {
        // B4：issue 统一带上「来源包 / data id / 字段 / 值」，让第三方包能直接定位到文件。
        val issuePrefix = "Gun data $dataId (pack '$packName')"
        fun rawField(key: String): String =
            jsonObject.get(key)?.let { if (it.isJsonNull) "null" else it.toString() } ?: "<missing>"
        fun safeInt(key: String): Int? = jsonObject.get(key)
            ?.takeIf { !it.isJsonNull }
            ?.let { element -> runCatching { element.asInt }.getOrNull() }
        fun safeFloat(key: String): Float? = jsonObject.get(key)
            ?.takeIf { !it.isJsonNull }
            ?.let { element -> runCatching { element.asFloat }.getOrNull() }

        // ammo_amount：上游默认值 30；缺失视为合法缺省（minigun 等 inventory 枪本就不写），
        // 只有显式写了非法值（<=0 / 非数字）才记 issue 并回退，保持"记 issue 继续"的宽容策略。
        val rawAmmoAmount = safeInt("ammo_amount")
        val ammoAmount = if (rawAmmoAmount != null && rawAmmoAmount >= 1) {
            rawAmmoAmount
        } else {
            if (jsonObject.has("ammo_amount") && !jsonObject.get("ammo_amount").isJsonNull) {
                issues += "$issuePrefix: 'ammo_amount'=${rawField("ammo_amount")} is invalid " +
                    "(upstream: ammo count must >= 1); falling back to 30."
            }
            30
        }
        // rpm：上游默认值 300；缺失合法，显式非法值回退 300 并记 issue。
        val rawRpm = safeInt("rpm")
        val roundsPerMinute = if (rawRpm != null && rawRpm >= 1) {
            rawRpm
        } else {
            if (jsonObject.has("rpm") && !jsonObject.get("rpm").isJsonNull) {
                issues += "$issuePrefix: 'rpm'=${rawField("rpm")} is invalid " +
                    "(upstream: rpm count must >= 1); falling back to 300."
            }
            300
        }
        // aim_time：上游不做任何数值校验（写 0 就是 0，负值也保留），只处理字段缺失/非数字。
        val aimTime = if (!jsonObject.has("aim_time") || jsonObject.get("aim_time").isJsonNull) {
            0.2f
        } else {
            safeFloat("aim_time") ?: run {
                issues += "$issuePrefix: 'aim_time'=${rawField("aim_time")} is not a number; falling back to 0.2."
                0.2f
            }
        }
        // B4 / X-17：ammo 必填。这里只负责解析与非法值诊断；
        // 缺失/null 的"整把枪拒绝"在 scan() 构建 guns map 时执行（对齐上游 CommonGunIndex.checkData）。
        val ammoElement = jsonObject.get("ammo")
        val ammoId = ammoElement
            ?.takeIf { !it.isJsonNull }
            ?.let { element -> runCatching { element.asString }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { ResourceLocation(raw) }.getOrNull() }
        if (ammoElement != null && !ammoElement.isJsonNull && ammoId == null) {
            issues += "$issuePrefix: 'ammo'=${rawField("ammo")} is not a valid resource location; " +
                "the gun is rejected by the index check."
        }
        // B4 / X-19：上游要求 extended_mag_ammo_amount 为 null 或长度 >= 3（不是"恰好 3"）。
        // 长度不足只记 issue 并原样保留，下游 getOrNull 不会越界。
        val extendedMagElement = jsonObject.get("extended_mag_ammo_amount")
        val extendedMagAmmo = if (extendedMagElement == null || extendedMagElement.isJsonNull) {
            null
        } else {
            runCatching { jsonObject.intArray("extended_mag_ammo_amount") }.getOrNull()
        }
        if (extendedMagElement != null && !extendedMagElement.isJsonNull) {
            if (extendedMagAmmo == null) {
                issues += "$issuePrefix: 'extended_mag_ammo_amount'=${rawField("extended_mag_ammo_amount")} " +
                    "is not an integer array; ignored."
            } else if (extendedMagAmmo.size < 3) {
                issues += "$issuePrefix: 'extended_mag_ammo_amount'=" +
                    "${extendedMagAmmo.joinToString(prefix = "[", postfix = "]")} has " +
                    "${extendedMagAmmo.size} value(s) (upstream: extended_mag_ammo_amount size must be at least 3, " +
                    "one per extended_mag_level); used as-is."
            }
        }
        // B4 / X-10：reload.type 只接受上游 FeedType 的四个值，非法值记 issue 并回退 magazine。
        // 用 takeIf 而不是 Gson 的 getAsJsonObject（成员函数会 ClassCastException），
        // 保证 reload 写成数组/字符串时只丢这一个字段，不丢整把枪。
        val reloadTypeElement = jsonObject.get("reload")
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("type")
        if (reloadTypeElement != null && !reloadTypeElement.isJsonNull) {
            val rawReloadType = runCatching { reloadTypeElement.asString }.getOrNull()
            if (rawReloadType == null || rawReloadType.lowercase(Locale.ROOT) !in TACZ_RELOAD_TYPES) {
                issues += "$issuePrefix: 'reload.type'=$reloadTypeElement is invalid " +
                    "(expected magazine/manual/fuel/inventory); falling back to magazine."
            }
        }
        // B4 / X-15：script_param 必须是对象；数组/原始值无法形成脚本参数表。
        val scriptParamElement = jsonObject.get("script_param")
        if (scriptParamElement != null && !scriptParamElement.isJsonNull && !scriptParamElement.isJsonObject) {
            issues += "$issuePrefix: 'script_param'=$scriptParamElement must be a JSON object; ignored."
        }
        val weight = safeFloat("weight") ?: run {
            if (jsonObject.has("weight") && !jsonObject.get("weight").isJsonNull) {
                issues += "$issuePrefix: 'weight'=${rawField("weight")} is not a number; using 0."
            }
            0f
        }
        if (weight < 0f) {
            issues += "$issuePrefix: negative 'weight' (=$weight); movement penalty will be inverted."
        }
        return TACZGunDataDefinition(
            raw = jsonObject,
            ammoId = ammoId,
            ammoAmount = ammoAmount,
            extendedMagAmmoAmount = extendedMagAmmo,
            roundsPerMinute = roundsPerMinute,
            weight = weight,
            aimTime = aimTime,
            allowAttachmentTypes = parseAllowAttachmentTypes(jsonObject, issues, issuePrefix),
            // exclusive_attachments: Map<配件ID, 独占 AttachmentData>
            // C10：与配件索引 data 同构解析（同一 readModifiers 路径），消费点命中即整份替换。
            // 两层隔离：
            //   容器级——字段存在但不是 object（写成数组/字符串）时只记 issue，不让 getAsJsonObject 抛出去丢整把枪；
            //   条目级——单个条目的格式错误只跳过该条目并记 issue。
            exclusiveAttachments = run {
                val rawExclusive = jsonObject.get("exclusive_attachments")
                if (rawExclusive == null) return@run emptyMap()
                if (!rawExclusive.isJsonObject) {
                    issues += "Gun data $dataId exclusive_attachments is not a JSON object " +
                        "(${rawExclusive.javaClass.simpleName}); ignored."
                    return@run emptyMap()
                }
                rawExclusive.asJsonObject.entrySet().mapNotNull { entry ->
                    val key = entry.key
                    val id = runCatching { ResourceLocation(key) }.getOrNull()
                    if (id == null) {
                        issues += "Gun data $dataId exclusive_attachments['$key']: invalid id; skipped."
                        return@mapNotNull null
                    }
                    if (!key.contains(':')) {
                        // 上游 ResourceLocation.tryParse 会把无命名空间的键默认成 minecraft:xxx，
                        // 通常匹配不到任何配件，表现为"写了独占却完全没生效"。
                        issues += "Gun data $dataId exclusive_attachments['$key'] has no namespace; " +
                            "it resolves to $id and likely never matches an attachment."
                    }
                    val data = entry.value?.takeIf { it.isJsonObject }?.asJsonObject
                    if (data == null) {
                        issues += "Gun data $dataId exclusive_attachments['$key']: value is not a JSON object; skipped."
                        return@mapNotNull null
                    }
                    val parsed = try {
                        toAttachmentData(
                            data,
                            issues = issues,
                            issuePrefix = "Gun data $dataId exclusive_attachments['$key']",
                            // 独占条目才启用硬阈值：没有下一级回落，留半套数值最难察觉
                            enforceHardThreshold = true,
                        )
                    } catch (throwable: Throwable) {
                        // 典型诱因：第三方把 "damage": 1.5 写成裸数字、"weight": "2" 写成字符串等
                        issues += "Gun data $dataId exclusive_attachments['$key']: parse failed " +
                            "(${throwable.javaClass.simpleName}: ${throwable.message}); skipped."
                        return@mapNotNull null
                    }
                    if (parsed == null) {
                        // 整件失败：该条 exclusive 不生效，回落普通配件数据（见 attachmentDataFor）
                        return@mapNotNull null
                    }
                    id to parsed
                }.toMap()
            },
        )
    }

    private fun parseBlockData(jsonObject: JsonObject): TACZBlockDataDefinition {
        val tabs = jsonObject.getAsJsonArray("tabs")
            ?.mapNotNull { element -> runCatching { TACZJson.GSON.fromJson(element, TACZWorkbenchTabDefinition::class.java) }.getOrNull() }
            ?: emptyList()
        return TACZBlockDataDefinition(
            raw = jsonObject,
            filter = jsonObject.resourceLocation("filter") ?: ResourceLocation(TACZLegacy.MOD_ID, "default"),
            tabs = tabs,
        )
    }

    /**
     * 兼容层 ③ C13：解析 `tacz_loot_injectors/<id>.json`。
     *
     * 上游结构（`LootTableInjection`）：必须提供 `loot_table` 或 `loot_tables`，以及 `pools`。
     * 这里只做结构校验与索引；`LootPool` 的反序列化放到 [com.tacz.legacy.common.event.LootInjectionHandler]，
     * 因为需要 `LootTableManager` 的 Gson 上下文（含 LootEntry/LootFunction 的 type 适配器）。
     */
    private fun parseLootInjection(
        id: ResourceLocation,
        jsonObject: JsonObject,
        issues: MutableList<String>,
    ): TACZLootInjectionDefinition? {
        // 归一化逻辑抽到顶层纯函数 normalizeLootInjection，便于 B11 直接单测；
        // 这里只负责把结果转成快照条目并合并 issue。
        val normalized = normalizeLootInjection(id, jsonObject)
        issues += normalized.issues
        val pools = normalized.pools ?: return null
        if (normalized.lootTables.isEmpty()) {
            return null
        }
        return TACZLootInjectionDefinition(id = id, lootTables = normalized.lootTables, pools = pools)
    }

    private fun parseTagEntries(json: String): List<String> {
        val element = TACZJson.parseElement(json)
        if (!element.isJsonArray) {
            return emptyList()
        }
        return element.asJsonArray.mapNotNull { entry ->
            runCatching { entry.asString.trim() }.getOrNull()?.takeIf(String::isNotBlank)
        }
    }

    private fun parseTranslations(json: String, extension: String): Map<String, String> {
        return if (extension.equals("json", ignoreCase = true)) {
            val objectNode = TACZJson.parseObject(json)
            val result = LinkedHashMap<String, String>()
            objectNode.entrySet().forEach { entry ->
                runCatching { entry.value.asString }.getOrNull()?.let { translated ->
                    result[entry.key] = translated
                }
            }
            result
        } else {
            parseLangFile(json)
        }
    }

    private fun parseLangFile(content: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            val separator = trimmed.indexOf('=')
            if (separator <= 0) {
                return@forEach
            }
            val key = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim()
            if (key.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private fun mergeTagEntries(target: MutableMap<ResourceLocation, LinkedHashSet<String>>, id: ResourceLocation, values: List<String>): Unit {
        if (values.isEmpty()) {
            return
        }
        target.computeIfAbsent(id) { linkedSetOf() }.addAll(values)
    }

    private fun resourceIdFrom(entryName: String, pattern: Pattern): ResourceLocation {
        val matcher = pattern.matcher(entryName)
        require(matcher.find()) { "Path did not match expected pattern: $entryName" }
        return ResourceLocation(matcher.group(1), matcher.group(2))
    }

    private fun normalizeLocaleName(value: String): String = value.lowercase(Locale.ROOT).replace('-', '_')
}

internal object TACZAttachmentModifierRegistry {
    private val readers: LinkedHashMap<String, TACZModifierReader<*>> = linkedMapOf(
        "ads" to TACZNumericModifierReader(id = "ads", optionalField = "ads_addend"),
        "ammo_speed" to TACZNumericModifierReader(id = "ammo_speed"),
        "armor_ignore" to TACZNumericModifierReader(id = "armor_ignore"),
        "damage" to TACZNumericModifierReader(id = "damage"),
        "effective_range" to TACZNumericModifierReader(id = "effective_range"),
        "explosion" to TACZExplosionModifierReader,
        "head_shot" to TACZNumericModifierReader(id = "head_shot"),
        "ignite" to TACZIgniteModifierReader,
        "inaccuracy" to TACZInaccuracyModifierReader,
        "knockback" to TACZNumericModifierReader(id = "knockback"),
        "pierce" to TACZNumericModifierReader(id = "pierce"),
        "recoil" to TACZRecoilModifierReader,
        "rpm" to TACZNumericModifierReader(id = "rpm"),
        "silence" to TACZRawJsonModifierReader(id = "silence"),
        "weight_modifier" to TACZNumericModifierReader(id = "weight_modifier", optionalField = "weight"),
        "movement_speed" to TACZRawJsonModifierReader(id = "movement_speed"),
    )

    /**
     * 已注册的 modifier id 集合（供守门测试使用）。
     *
     * 存在的意义：这份注册表曾经和消费面脱钩过一次（16 个里只有 4 个进玩法）。
     * 有一个能在"新增注册但没人消费"时变红的测试，比每次靠人肉核对可靠。
     */
    internal fun registeredIds(): Set<String> = readers.keys.toSet()

    /**
     * 读取一个 AttachmentData 的所有 modifier。
     *
     * **reader 级隔离**：单个 reader 抛异常（例如第三方把 `damage` 写成裸数字）只跳过该 modifier，
     * 不影响同一条目的其它 modifier；传入 [issues] 时会记录一条可诊断的错误。
     * 这与 scanner 的"条目级隔离"互补：条目级防止整枪消失，reader 级防止一个坏字段拖垮整条配件。
     */
    internal fun readModifiers(
        rawJson: String,
        jsonObject: JsonObject,
        issues: MutableList<String>? = null,
        issuePrefix: String = "",
        stats: TACZModifierReadStats? = null,
    ): Map<String, JsonProperty<*>> {
        val result = LinkedHashMap<String, JsonProperty<*>>()
        readers.forEach { (id, reader) ->
            val property = try {
                reader.read(rawJson, jsonObject)
            } catch (throwable: Throwable) {
                val detail = "$issuePrefix modifier '$id' parse failed " +
                    "(${throwable.javaClass.simpleName}: ${throwable.message}); skipped."
                // 没有 issue sink 时至少 warn 一次，避免"部分生效"完全不可观测
                if (issues != null) issues.add(detail) else TACZLegacy.logger.warn("[GunPackRuntime] {}", detail)
                stats?.let { it.failed++ }
                null
            } ?: return@forEach
            // B10：目前所有 reader 都返回 TACZSimpleJsonProperty（initComponents 为空实现），
            // 这个 catch 实际不会命中；保留它是 reader 级隔离契约的一部分——将来若引入
            // 需要构建文本组件的 JsonProperty，坏数据仍应只跳过该 modifier 而不是拖垮整条配件。
            try {
                property.initComponents()
            } catch (throwable: Throwable) {
                val detail = "$issuePrefix modifier '$id' initComponents failed " +
                    "(${throwable.javaClass.simpleName}: ${throwable.message}); skipped."
                if (issues != null) issues.add(detail) else TACZLegacy.logger.warn("[GunPackRuntime] {}", detail)
                stats?.let { it.failed++ }
                return@forEach
            }
            stats?.let { it.succeeded++ }
            result[id] = property
        }
        return result
    }

    internal fun evalNumeric(modifiers: List<Modifier>, defaultValue: Double): Double = ModifierEvaluator.eval(modifiers, defaultValue)

    internal fun evalRecoil(
        modifiers: List<TACZRecoilModifierValue>,
        defaultPitch: Float,
        defaultYaw: Float,
    ): ParameterizedCachePair<Float, Float> {
        val pitch = modifiers.map(TACZRecoilModifierValue::pitch)
        val yaw = modifiers.map(TACZRecoilModifierValue::yaw)
        return ParameterizedCachePair.of(pitch, yaw, defaultPitch, defaultYaw)
    }

    internal fun evalIgnite(modifiers: List<TACZIgniteValue>, defaultValue: TACZIgniteValue): TACZIgniteValue {
        val igniteEntity = ModifierEvaluator.eval(listOf(defaultValue.igniteEntity) + modifiers.map(TACZIgniteValue::igniteEntity), false)
        val igniteBlock = ModifierEvaluator.eval(listOf(defaultValue.igniteBlock) + modifiers.map(TACZIgniteValue::igniteBlock), false)
        return TACZIgniteValue(igniteEntity = igniteEntity, igniteBlock = igniteBlock)
    }

    internal fun evalInaccuracy(
        modifiers: List<Map<String, Modifier>>,
        defaults: Map<String, Float>,
    ): Map<String, Float> {
        val result = LinkedHashMap<String, Float>()
        defaults.forEach { (key, defaultValue) ->
            // 兼容性修复：通用 inaccuracy 只作用于 stand/move（对齐上游 InaccuracyModifier.java:62-75），
            // aim/sneak/lie 无专属键时不回落到通用值，避免越界污染开镜/蹲射/卧射精度
            val values = modifiers.mapNotNull { modifierMap ->
                modifierMap[key] ?: if (key == "stand" || key == "move") modifierMap["default"] else null
            }
            result[key] = ModifierEvaluator.eval(values, defaultValue.toDouble()).toFloat()
        }
        return result
    }
}

/**
 * reader 级隔离的统计（review-10a 阻断项 2 的补救）。
 *
 * 背景：`{"damage":{"addend":4}, "rpm":0.9}` 这种条目，damage 生效、rpm 静默丢失，
 * 既不是"整份独占生效"也不是"完全回退"，是最难察觉的中间态。
 * 这里给出一条**硬阈值**：失败数不少于成功数时判整件失败，让调用方直接丢弃整个条目，
 * 而不是带着半套数值继续跑。
 *
 * 阈值保守的原因：单个坏字段夹在一堆好字段里仍走隔离（只跳过那一个），
 * 只有"坏的不比好的少"才说明这份数据整体不可信。
 */
internal class TACZModifierReadStats {
    var succeeded: Int = 0
    var failed: Int = 0

    val rejected: Boolean
        get() = failed > 0 && failed >= succeeded

    fun describe(): String = "$failed of ${succeeded + failed} modifier(s) failed to parse"
}

private interface TACZModifierReader<T> {
    val id: String
    fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<T>?
}

private class TACZNumericModifierReader(
    override val id: String,
    private val optionalField: String? = null,
) : TACZModifierReader<Modifier> {
    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<Modifier>? {
        val modifier = when {
            jsonObject.has(id) -> TACZJson.GSON.fromJson(jsonObject.get(id), Modifier::class.java)
            !optionalField.isNullOrBlank() && jsonObject.has(optionalField) -> Modifier(addend = jsonObject.get(optionalField).asDouble)
            else -> null
        }
        return modifier?.let(::TACZSimpleJsonProperty)
    }
}

private object TACZRecoilModifierReader : TACZModifierReader<TACZRecoilModifierValue> {
    override val id: String = "recoil"

    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<TACZRecoilModifierValue>? {
        val newValue = jsonObject.getAsJsonObject("recoil")?.let { recoilObject ->
            TACZRecoilModifierValue(
                pitch = recoilObject.get("pitch")?.let { TACZJson.GSON.fromJson(it, Modifier::class.java) } ?: Modifier(),
                yaw = recoilObject.get("yaw")?.let { TACZJson.GSON.fromJson(it, Modifier::class.java) } ?: Modifier(),
            )
        }
        if (newValue != null) {
            return TACZSimpleJsonProperty(newValue)
        }
        val oldValue = jsonObject.getAsJsonObject("recoil_modifier")?.let { recoilObject ->
            TACZRecoilModifierValue(
                pitch = Modifier(percent = recoilObject.floatValue("pitch").toDouble()),
                yaw = Modifier(percent = recoilObject.floatValue("yaw").toDouble()),
            )
        }
        return oldValue?.let(::TACZSimpleJsonProperty)
    }
}

private object TACZIgniteModifierReader : TACZModifierReader<TACZIgniteValue> {
    override val id: String = "ignite"

    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<TACZIgniteValue>? {
        val element = jsonObject.get(id) ?: return null
        val value = when {
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> TACZIgniteValue(
                igniteEntity = element.asBoolean,
                igniteBlock = element.asBoolean,
            )
            else -> TACZJson.GSON.fromJson(element, TACZIgniteValue::class.java)
        }
        return TACZSimpleJsonProperty(value)
    }
}

private object TACZExplosionModifierReader : TACZModifierReader<TACZExplosionModifierValue> {
    override val id: String = "explosion"

    /**
     * 审计全局-1：explosion 过去用 [TACZRawJsonModifierReader] 把 JsonObject 原样带到结算侧，
     * 每发子弹对每个配件、每个 key 重新 `GSON.fromJson`（TypeAdapter 查找 + 反射 + 装箱），
     * 是射击热路径上唯一的反射点。这里与 recoil / ignite 同构，改成解析期一次成型。
     *
     * 审计 B1-8：格式错误（`radius` 写成裸数字、整个 `explosion` 写成数组）过去被结算侧的
     * `takeIf { isJsonObject }` 静默吞掉，不记 issue。上游靠反射适配器抛 JsonSyntaxException
     * 让该 modifier 可见地失败，所以这里也**直接抛出**，由 readModifiers 的 reader 级隔离记 issue。
     */
    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<TACZExplosionModifierValue>? {
        val element = jsonObject.get(id) ?: return null
        val explosionObject = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
            ?: throw JsonSyntaxException("'explosion' must be a JSON object, but is $element")

        fun modifier(key: String): Modifier {
            val value = explosionObject.get(key) ?: return Modifier()
            if (value.isJsonNull) return Modifier()
            return TACZJson.GSON.fromJson(value, Modifier::class.java) ?: Modifier()
        }

        fun boolean(key: String): Boolean =
            explosionObject.get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

        return TACZSimpleJsonProperty(
            TACZExplosionModifierValue(
                explode = boolean("explode"),
                radius = modifier("radius"),
                damage = modifier("damage"),
                knockback = boolean("knockback"),
                destroyBlock = boolean("destroy_block"),
                delay = modifier("delay"),
            ),
        )
    }
}

private object TACZInaccuracyModifierReader : TACZModifierReader<Map<String, Modifier>> {
    override val id: String = "inaccuracy"

    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<Map<String, Modifier>>? {
        val defaultModifier = when {
            jsonObject.has("inaccuracy") -> TACZJson.GSON.fromJson(jsonObject.get("inaccuracy"), Modifier::class.java)
            jsonObject.has("inaccuracy_addend") -> Modifier(addend = jsonObject.get("inaccuracy_addend").asDouble)
            else -> null
        }
        val map = LinkedHashMap<String, Modifier>()
        if (defaultModifier != null) {
            map["default"] = defaultModifier
        }
        jsonObject.get("aim_inaccuracy")?.let { map["aim"] = TACZJson.GSON.fromJson(it, Modifier::class.java) }
        jsonObject.get("sneak_inaccuracy")?.let { map["sneak"] = TACZJson.GSON.fromJson(it, Modifier::class.java) }
        jsonObject.get("lie_inaccuracy")?.let { map["lie"] = TACZJson.GSON.fromJson(it, Modifier::class.java) }
        return if (map.isEmpty()) null else TACZSimpleJsonProperty(map)
    }
}

private class TACZRawJsonModifierReader(
    override val id: String,
) : TACZModifierReader<JsonElement> {
    override fun read(rawJson: String, jsonObject: JsonObject): JsonProperty<JsonElement>? =
        jsonObject.get(id)?.let(::TACZSimpleJsonProperty)
}

private class TACZSimpleJsonProperty<T>(value: T) : JsonProperty<T>(value) {
    // 纯数据载体：本移植直接从 Modifier 数值求值，不构建 tooltip 组件，
    // 因此 initComponents 没有工作可做；保留空实现仅为满足上游 API 的抽象方法。
    override fun initComponents(): Unit = Unit
}

private object TACZPackDependencyChecker {
    private val versionCache: MutableMap<String, ModContainer?> = ConcurrentHashMap()

    fun allMatch(dependencies: Map<String, String>): Boolean {
        if (dependencies.isEmpty()) {
            return true
        }
        return dependencies.entries.all { (modId, versionRange) -> versionMatch(modId, versionRange) }
    }

    private fun versionMatch(modId: String, version: String): Boolean {
        // 兼容性修复：对 tacz 自身特判，移植版版本号(0.1.1)与上游版本区间(如 [1.20.1-1.1.0,))不匹配，
        // 但枪包数据格式兼容，跳过版本判定避免 1.20.1 枪包被误拦
        if (modId == TACZLegacy.MOD_ID) {
            return true
        }
        val container = runCatching {
            versionCache.computeIfAbsent(modId) { Loader.instance().indexedModList[modId] }
        }.getOrNull() ?: return false
        val currentVersion = container.processedVersion
        return runCatching {
            val expected = VersionParser.parseVersionReference("$modId@$version")
            VersionParser.satisfies(expected, currentVersion)
        }.getOrDefault(true) // 解析不了版本区间时不判死
    }
}

// 将目录名/zip名净化为合法 MC namespace（小写，仅 a-z0-9_.-，去 .zip 后缀）
private fun sanitizeNamespace(raw: String): String =
    raw.lowercase().removeSuffix(".zip").replace(Regex("[^a-z0-9_.-]"), "_").ifBlank { "unknown" }

// F1 修复：跨包重复 id 告警，put 前检测是否已被其他包注册
private fun <K, V> putOrReport(
    map: MutableMap<K, V>,
    key: K,
    value: V,
    packName: String,
    kind: String,
    issues: MutableList<String>,
) {
    if (map.containsKey(key)) {
        issues += "Duplicate $kind '$key' from pack '$packName' (overrides existing entry)."
    }
    map[key] = value
}

private interface TACZPackSource : AutoCloseable {
    fun listEntries(): Sequence<String>
    fun readText(path: String): String?
    override fun close(): Unit = Unit
}

/**
 * 兼容层 ③ C2：哪些包内文件要下发给远程客户端。
 *
 * 判据是**客户端缺了它会不会坏**，而不是体积：
 * - `data/` 子树（json/lua）与 `gunpack.meta.json`：数值、索引、脚本 —— 不下发则数值不一致。
 * - `assets/<ns>/display/` 下的 json：显示定义（模型/贴图路径、音效名、动画名、text_show）。
 *   不下发时远程客户端的 `gunDisplays` 为空，所有渲染/音效入口都会静默 return，
 *   表现为"数值对、名字对，但枪不可见、无声"。
 * - `assets/<ns>/lang/` 下的文件与 `gunpack_info.json`：名称/描述 —— 不影响玩法，但缺了全是英文 key。
 * - 贴图/模型/声音（二进制、体积大）仍由客户端本地包提供，这是上游架构，不是缺陷。
 * - `tacz_loot_injectors` **不下发**：注入只在服务端 `LootTableLoadEvent` 生效，
 *   客户端拿到既不执行、又会让人误以为"掉落已同步"，属于只发不执行的死数据。
 */
internal object TACZPackTransport {

    fun isSyncable(path: String): Boolean {
        if (path == "gunpack.meta.json") {
            return true
        }
        if (path.contains("/tacz_loot_injectors/")) {
            return false
        }
        if (path.startsWith("assets/") && path.endsWith(".json") &&
            (path.contains("/display/") || path.contains("/lang/") || path.endsWith("/gunpack_info.json"))
        ) {
            return true
        }
        if (path.startsWith("assets/") && path.contains("/lang/") && path.endsWith(".lang")) {
            return true
        }
        // 上游把脚本放在 assets/<ns>/scripts/ 下（FileToIdConverter("scripts", ".lua")）
        if (path.startsWith("assets/") && path.contains("/scripts/") && path.endsWith(".lua")) {
            return true
        }
        if (path.startsWith("data/") && (path.endsWith(".json") || path.endsWith(".lua"))) {
            return true
        }
        return false
    }
}

/**
 * 兼容层 ③ C2：服务端同步下来的枪包（内存源）。
 *
 * 多人环境下由服务端通过 [com.tacz.legacy.common.network.message.event.ServerMessageSyncGunPack]
 * 下发，客户端不需要本地安装枪包即可获得一致的枪包数据。
 * 扫描时远程包**优先于**同名本地目录。
 */
internal object TACZRemotePackStore {
    @Volatile
    private var packs: Map<String, Map<String, String>> = emptyMap()

    fun snapshot(): Map<String, Map<String, String>> = packs

    fun update(newPacks: Map<String, Map<String, String>>) {
        packs = newPacks
    }

    fun clear() {
        packs = emptyMap()
    }
}

private class TACZMemoryPackSource(
    private val entries: Map<String, String>,
) : TACZPackSource {
    override fun listEntries(): Sequence<String> = entries.keys.asSequence().sorted()

    override fun readText(path: String): String? = entries[path]
}

/**
 * 本地完整包 + 服务端同步内容的叠加视图。
 *
 * 同步只携带数据/display/lang/脚本（见 [TACZPackTransport.isSyncable]），
 * 模型、贴图、音效必须由客户端本地包提供。同名包若让远程内容整体顶替本地，
 * 远程客户端就会缺资源（枪能 give 出来但不可见、无模型）。
 *
 * 这里的关键取舍：**注册集合只认服务端下发的那批路径**（服务端权威），
 * 本地只作为同路径的读取兜底。如果反过来取并集，客户端本地比服务端新的
 * 枪包数据也会被注册进来 —— 结果是 give 不到的枪出现在 `/tacz` 列表里、
 * 服务端已经删掉的条目在客户端还活着。
 *
 * 本地承担的是**资源**职责：`loadPack` 把 `candidate`（本地真实路径）记进
 * `TACZLoadedPack.sourceFile`，模型/贴图由资产层按这个路径从磁盘加载，
 * 不经过本类的 `listEntries()`。
 */
private class TACZOverlayPackSource(
    private val local: TACZPackSource,
    private val override: Map<String, String>,
) : TACZPackSource {
    override fun listEntries(): Sequence<String> = override.keys.asSequence().sorted()

    override fun readText(path: String): String? = override[path] ?: local.readText(path)
}

private class TACZDirectoryPackSource(private val root: File) : TACZPackSource {
    override fun listEntries(): Sequence<String> {
        if (!root.isDirectory) {
            return emptySequence()
        }
        val rootPath = root.toPath()
        val entries = mutableListOf<String>()
        Files.walk(rootPath).use { stream ->
            stream
                .filter(Files::isRegularFile)
                .forEach { path ->
                    entries += rootPath.relativize(path).toString().replace(File.separatorChar, '/')
                }
        }
        entries.sort()
        return entries.asSequence()
    }

    override fun readText(path: String): String? {
        val file = root.toPath().resolve(path)
        if (!Files.isRegularFile(file)) {
            return null
        }
        return Files.newBufferedReader(file, StandardCharsets.UTF_8).use { it.readText() }
    }
}

private class TACZZipPackSource(
    private val file: File,
    private val zipFile: ZipFile,
) : TACZPackSource {
    override fun listEntries(): Sequence<String> = try {
        Collections.list(zipFile.entries()).map { it.name }.sorted().asSequence()
    } catch (exception: IllegalArgumentException) {
        throw IllegalStateException("Failed to enumerate zip entries for ${file.name}", exception)
    }

    override fun readText(path: String): String? {
        try {
            val entry = zipFile.getEntry(path) ?: return null
            return zipFile.getInputStream(entry).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException("Failed to read zip entry $path from ${file.name}", exception)
        }
    }
}

internal object TACZJson {
    private val resourceLocationSerializer: JsonSerializer<ResourceLocation> = JsonSerializer { src, _, _ ->
        com.google.gson.JsonPrimitive(src.toString())
    }
    private val resourceLocationDeserializer: JsonDeserializer<ResourceLocation> = JsonDeserializer { json, _, _ ->
        ResourceLocation(json.asString)
    }

    val GSON: Gson = GsonBuilder()
        .registerTypeAdapter(ResourceLocation::class.java, resourceLocationSerializer)
        .registerTypeAdapter(ResourceLocation::class.java, resourceLocationDeserializer)
        .create()

    fun parseObject(json: String): JsonObject = parseElement(json).asJsonObject

    fun parseElement(json: String): JsonElement {
        val reader = JsonReader(StringReader(json))
        reader.isLenient = true
        return JsonParser().parse(reader)
    }

    fun <T> fromJson(json: String, clazz: Class<T>): T {
        val reader = JsonReader(StringReader(json))
        reader.isLenient = true
        return GSON.fromJson(reader, clazz)
    }
}

private fun JsonObject.resourceLocation(key: String): ResourceLocation? =
    get(key)?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let(::ResourceLocation)

private fun JsonObject.intValue(key: String): Int = get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0

private fun JsonObject.floatValue(key: String): Float = get(key)?.takeIf { !it.isJsonNull }?.asFloat ?: 0.0f

private fun JsonObject.intArray(key: String): IntArray? =
    getAsJsonArray(key)?.map { it.asInt }?.toIntArray()

private fun JsonObject.stringArray(key: String): List<String> =
    getAsJsonArray(key)?.map { it.asString } ?: emptyList()

private fun JsonObject.getAsJsonObject(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private const val ALLOW_ATTACHMENT_PREFIX: String = "allow_attachments/"
private const val ATTACHMENT_TAG_ROOT_PREFIX: String = "attachments/"
