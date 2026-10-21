package com.tacz.legacy.common.event

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZLootInjectionDefinition
import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import net.minecraft.util.ResourceLocation
import net.minecraft.world.storage.loot.LootPool
import net.minecraft.world.storage.loot.LootTable
import net.minecraft.world.storage.loot.LootTableManager
import net.minecraftforge.common.ForgeHooks
import net.minecraftforge.event.LootTableLoadEvent
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.relauncher.ReflectionHelper
import java.lang.reflect.Field
import java.util.Deque

/**
 * 兼容层 ③ C13：把枪包里 `tacz_loot_injectors/<id>.json` 的 pools 注入到目标战利品表。
 *
 * 上游 1.20 用 GLM（`LootTableInjectorModifier`）在生成战利品时追加；
 * 1.12.2 没有 GLM，这里改为在 [LootTableLoadEvent] 里把 pool 加到表上。
 *
 * 四个已知坑：
 * 1. **Forge ThreadLocal 上下文**：不能裸 `gson.fromJson(pools)`，必须走 [ForgeHooks.loadLootTable]；
 *    且它只在 `JsonParseException` 时 pop，其它 RuntimeException 会泄漏 context，需要自己兜底。
 * 2. **1.12 的 LootEntry 是字面量 type 比较**（`item`/`loot_table`/`empty`），
 *    1.20 写的 `minecraft:item` 会直接抛异常 —— 需要 [normalizePoolsFor112]。
 * 3. **`LootTable.pools` 是 `List<LootPool>`**（不是数组），且 `addPool` 对重名会抛异常，
 *    需要逐条 try。
 * 4. **注入的 pool 出生即 frozen**（D-13）：[ForgeHooks.loadLootTable] 返回前会无条件 freeze，
 *    1.12.2 没有"只解析不冻结"的入口；原因、为什么不 hack、实际影响见 [applyInjection] 的注释。
 *
 * 诊断（R-04 / D-18）：`pools` 为空、以及"注入器从未命中任何已加载表"这两种静默失败，
 * 统一由 [onServerTick] 在"本轮表加载完"之后按注入器各报一次 WARN。
 */
internal object LootInjectionHandler {

    /** 同类汇总最多逐条打印多少行，其余折叠成一条摘要，避免破损包刷屏。 */
    private const val MAX_SUMMARY_LINES: Int = 8

    /**
     * `LootTableManager.GSON_INSTANCE` 是 private static final（生产环境 `field_186526_b`），
     * 取一次缓存；失败时整个功能静默降级（不影响启动）。
     */
    private val lootGson: Gson? by lazy {
        try {
            val field = ReflectionHelper.findField(
                LootTableManager::class.java,
                "GSON_INSTANCE",
                "field_186526_b",
            )
            field.isAccessible = true
            field.get(null) as? Gson
        } catch (throwable: Throwable) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Loot injection disabled: cannot access LootTableManager GSON ({})",
                throwable.message,
            )
            null
        }
    }

    /** `LootTable.pools` 是 private final（生产环境 `field_186466_c`），实际类型是 `List<LootPool>`。 */
    private val lootTablePoolsField: Field? by lazy {
        try {
            ReflectionHelper.findField(LootTable::class.java, "pools", "field_186466_c").apply { isAccessible = true }
        } catch (throwable: Throwable) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Loot injection disabled: cannot access LootTable.pools ({})",
                throwable.message,
            )
            null
        }
    }

    /** `ForgeHooks.lootContext`（private static ThreadLocal<Deque>），用于异常兜底。 */
    private val lootContextField: Field? by lazy {
        try {
            ReflectionHelper.findField(ForgeHooks::class.java, "lootContext").apply { isAccessible = true }
        } catch (throwable: Throwable) {
            // 拿不到就退化为"不做栈平衡"，不影响正常路径
            null
        }
    }

    /** D-18 诊断状态的锁：事件、tick 汇总、reload 后的快照切换都串行化。 */
    private val diagnosticsLock: Any = Any()

    /** 最近一次真正触发过 [LootTableLoadEvent] 的快照；换快照时清空下面两个命中集合。 */
    @Volatile
    private var diagnosticSnapshot: TACZRuntimeSnapshot? = null

    /** 目标表名字至少触发过一次 [LootTableLoadEvent] 的注入器（不管表能不能用）。 */
    private val injectionsReachingTable: MutableSet<ResourceLocation> = HashSet()

    /** 目标表非空、未冻结（即真的尝试过注入）的注入器。 */
    private val injectionsWithUsableTable: MutableSet<ResourceLocation> = HashSet()

    /** 已经报过"空 pools"的快照；每个快照只报一次，reload 出新快照再报。 */
    @Volatile
    private var poolsReportedSnapshot: TACZRuntimeSnapshot? = null

    /** 已经汇总过"命中结果"的那一轮加载；每轮只报一次。 */
    @Volatile
    private var passReportedSnapshot: TACZRuntimeSnapshot? = null

    /**
     * "本轮先跳过、还没汇总"的那一轮加载：本快照的第一个 server tick 早于它的 [LootTableLoadEvent] 时置位。
     *
     * 与 [passReportedSnapshot] 对**同一快照**互斥（同一快照只会处在其中一个状态）：
     * - `/tacz reload` 只换快照、不重放表加载，第一个 tick 时 [diagnosticSnapshot] 还是旧快照，
     *   此时汇总 "never matched" 是假阳性，只能记为"跳过"；
     * - 原版 `/reload`、tick 中构造的新维度会稍后补上表加载事件，
     *   [onLootTableLoad] 收到本快照的事件后清空这个标记，下一次 tick 再真正汇总。
     */
    @Volatile
    private var passSkippedSnapshot: TACZRuntimeSnapshot? = null

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun onLootTableLoad(event: LootTableLoadEvent) {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        if (snapshot.lootInjections.isEmpty()) {
            return
        }
        // 先登记"本快照确实经历过 loot 表加载"，再判断是否命中。
        // 这一步与 matched 无关：D-18 要能报"所有注入器都没命中任何表"，
        // 而 `/tacz reload` 不会重放 LootTableLoadEvent，不能把"没机会匹配"误报成"没命中"。
        synchronized(diagnosticsLock) {
            beginPassLocked(snapshot)
            // 本快照真的开始经历表加载了：清掉"跳过汇总"标记。
            // 典型场景：`/tacz reload` 换了快照、第一个 tick 时还没有表加载（被记为跳过），
            // 之后原版 `/reload` 或新维度构造在本 tick 稍后重放 LootTableLoadEvent；
            // 下一个 server tick 就会用补全后的命中集合重新汇总，而不是永久漏报。
            passSkippedSnapshot = null
        }
        val matched = snapshot.lootInjections.filter { event.name in it.lootTables }
        if (matched.isEmpty()) {
            return
        }
        // 先记"匹配到了表"，再判空表/冻结：汇总要能区分
        // "目标表根本没加载"（从没命中）与"表加载了但不可用"（命中却没法注入）。
        synchronized(diagnosticsLock) {
            matched.forEach { injectionsReachingTable += it.id }
        }
        // 空表/已冻结的表不能再 addPool
        if (event.table === LootTable.EMPTY_LOOT_TABLE) {
            return
        }
        try {
            if (event.table.isFrozen) {
                return
            }
        } catch (throwable: Throwable) {
            // isFrozen 取不到时按未冻结处理
        }
        // beginPassLocked 在上面登记"经历过表加载"时已调用过（同一事件、同一快照，重复调用没有意义），
        // 这里只登记"表非空且未冻结、真的尝试过注入"。
        synchronized(diagnosticsLock) {
            matched.forEach { injectionsWithUsableTable += it.id }
        }
        val gson = lootGson
        val poolsField = lootTablePoolsField
        if (gson == null || poolsField == null) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] loot injection skipped: gson={} poolsField={}",
                gson != null, poolsField != null,
            )
            return
        }
        matched.forEach { injection -> applyInjection(injection, event, gson, poolsField) }
    }

    /**
     * R-04 / D-18 的汇总入口。`LootTableLoadEvent` 没有"这批表加载完了"的结束事件，所以把汇总
     * 放在服务端第一个 tick：
     *
     * 1.12.2 的 `WorldServer` 在构造 `LootTableManager` 时会调用 `reloadLootTables()`，对
     * `LootTableList` 里注册的每一张表各拉一次（[onLootTableLoad] 因此被同步触发），而这发生在
     * 服务端 tick 循环启动之前，所以第一 tick 时"已加载表"的集合已经完整。目标表若不在
     * `LootTableList`（只会在被实际请求时懒加载），这里可能提前报一次，因此日志措辞是
     * "never matched"（到目前为止），不承诺"以后也不会"。
     *
     * 但"第一 tick 时集合已完整"只对开服成立：快照可能在运行期被 `/tacz reload` 换掉，
     * 或者表加载本就发生在 tick 之内（原版 `/reload` 重放、新维度构造）。因此一轮加载用三个标记描述：
     * [poolsReportedSnapshot]（空 pools 已报）、[passReportedSnapshot]（命中结果已汇总）是终态；
     * [passSkippedSnapshot] 表示"第一个 tick 时还没经历过表加载，本轮只是跳过"，
     * 它会在 [onLootTableLoad] 收到本快照的事件后清空，下一个 tick 再重新判定。
     */
    @SubscribeEvent
    fun onServerTick(event: TickEvent.ServerTickEvent) {
        if (event.phase != TickEvent.Phase.END) {
            return
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        // 常态路径：每 tick 最多 4 次 volatile 读 + 引用比较，不分配、不进锁。
        // "已汇总"与"已跳过"都算本轮处理完，只有两者都不是当前快照时才需要进锁。
        // 注意不能用 diagnosticSnapshot 是否为 null 当"本轮已开始"的判据：
        // 一个注入器都没命中任何表时 diagnosticSnapshot 会一直是 null，
        // 那样恰好漏掉 D-18 最重要的一类（never matched）。
        if (snapshot === poolsReportedSnapshot &&
            (snapshot === passReportedSnapshot || snapshot === passSkippedSnapshot)
        ) {
            return
        }
        synchronized(diagnosticsLock) {
            // R-04：pools 为空与"目标表是否加载"无关，reload 出这样的包就该立刻看到 WARN
            if (snapshot !== poolsReportedSnapshot) {
                poolsReportedSnapshot = snapshot
                reportInjectorsWithoutPools(snapshot)
            }
            if (snapshot !== passReportedSnapshot && snapshot !== passSkippedSnapshot) {
                // 只有本快照真的经历过 loot 表加载（diagnosticSnapshot === snapshot）才汇总命中结果。
                // `/tacz reload` 只换快照、不重放 LootTableLoadEvent，此时报 "never matched" 是假阳性；
                // 而且这一段可能先于本快照的表加载执行（原版 `/reload`、tick 中构造的新维度），
                // 所以"没机会汇总"只能记成 skipped、不能记成 reported —— 否则之后表加载了也永远补不上。
                if (diagnosticSnapshot === snapshot) {
                    passReportedSnapshot = snapshot
                    reportInjectorOutcomes(snapshot)
                } else {
                    passSkippedSnapshot = snapshot
                }
            }
        }
    }

    /**
     * 把一条注入器的 pools 追加到 `event.table`。
     *
     * **D-13：注入的 pool 出生即 frozen，且 1.12.2 无法规避**：
     * [ForgeHooks.loadLootTable] 返回前会**无条件**执行 `LootTable.freeze()`（`custom` 参数只决定
     * 要不要为临时表再发一次 `LootTableLoadEvent`，与 freeze 无关）；`LootTable.freeze()` 会把
     * 表内每个 `LootPool` 一并冻结，于是取出的 pool 天生 `isFrozen == true`，之后任何
     * `setRolls`/`setBonusRolls`/`addEntry`/`removeEntry` 都会抛异常。想绕开只有两条路，都不该走：
     * 1. 绕开 ForgeHooks 直接 `lootGson.fromJson(..., LootTable.class)`：`LootPool`/`LootEntry` 的
     *    反序列化要读 `ForgeHooks` 的 `lootContext` ThreadLocal（`readPoolName`/`readLootEntryName`
     *    都经 `getLootTableContext`），而唯一的压栈入口就是 [ForgeHooks.loadLootTable] 本身。
     *    本线程从未调用过它时 `lootContext.get()` 返回 null，`getLootTableContext()` 里的
     *    `lootContext.get().peek()` 抛的是 **NPE**；只有 ThreadLocal 已初始化、栈恰好为空时
     *    才抛 `JsonParseException("Invalid call stack, could not grab json context!")`。
     *    [withBalancedLootContext] 只负责异常退出时弹掉多压的帧（防止 context 泄漏），
     *    不会凭空创建上下文，所以这条绕行路线走不通。
     * 2. 反射把 `LootPool.isFrozen` 改回 false：`freeze()` 没有配对的 unfreeze，这是篡改 Forge
     *    内部状态，后续所有 `checkFrozen` 都建立在这个被篡改的状态上，属于强行 hack。
     * 实际影响有限：`LootPool.generateLoot` 不检查 frozen（freeze 只锁"改"不锁"产出"），本 handler
     * 又是 `LOWEST`，事件里排在最后，之后没有别的 `LootTableLoadEvent` 监听者会拿到这个 pool；
     * 只有"事件结束后别的 mod 主动改我们的 pool"这一种情况会撞上异常。在 Forge 提供"只解析
     * 不冻结"的入口之前保持现状，不做 hack。
     */
    private fun applyInjection(
        injection: TACZLootInjectionDefinition,
        event: LootTableLoadEvent,
        gson: Gson,
        poolsField: Field,
    ) {
        try {
            val normalized = normalizePoolsFor112(injection.pools)
            if (normalized.size() == 0) {
                // R-04：这里不逐表 WARN（同一注入器会在多张表、多个维度各触发一次），
                // 统一由 [onServerTick] 按注入器报一次 "has no pools, skipped"。
                return
            }
            val jsonText = JsonObject().apply { add("pools", normalized) }.toString()
            val loaded = withBalancedLootContext {
                ForgeHooks.loadLootTable(gson, event.name, jsonText, true, event.lootTableManager)
            }
            val raw = poolsField.get(loaded)
            val extra = raw as? List<*>
            if (extra == null) {
                TACZLegacy.logger.warn(
                    "[GunPackRuntime] loot injection skipped {}: pools field type {}",
                    injection.id, raw?.javaClass?.name,
                )
                return
            }
            var added = 0
            extra.filterIsInstance<LootPool>().forEachIndexed { index, pool ->
                try {
                    event.table.addPool(pool)
                    added++
                } catch (throwable: Throwable) {
                    // 常见：pool 重名（1.20 包普遍给 pool 起名）或表已冻结
                    TACZLegacy.logger.warn(
                        "[GunPackRuntime] loot pool #{} from {} skipped for {}: {}",
                        index, injection.id, event.name, throwable.message,
                    )
                }
            }
            TACZLegacy.logger.info(
                "[GunPackRuntime] Injected {}/{} pool(s) from {} into {}.",
                added, extra.size, injection.id, event.name,
            )
        } catch (throwable: Throwable) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Failed to apply loot injection {} to {}: {}",
                injection.id, event.name, throwable.message,
            )
        }
    }

    /** 换快照就清空上一轮的命中记录，避免旧包的注入器 id 残留。调用方需持有 [diagnosticsLock]。 */
    private fun beginPassLocked(snapshot: TACZRuntimeSnapshot) {
        if (diagnosticSnapshot === snapshot) {
            return
        }
        diagnosticSnapshot = snapshot
        injectionsReachingTable.clear()
        injectionsWithUsableTable.clear()
    }

    /**
     * R-04：`pools` 为合法空数组的注入器以前完全静默（不计 `Injected`、不报 issue）。
     *
     * 这里只覆盖"`pools: []`"这一种情况。`pools` 字段缺失、为 null 或不是数组时，
     * `TACZGunPackRuntime` 的 `normalizeLootInjection` 会把它判成结构错误、让 `pools` 保持 null，
     * 该注入器在扫描期就被丢弃（只留一条 issue），根本不会进入 `snapshot.lootInjections`，
     * 自然也进不到这里的 WARN —— 这是预期行为，不是漏报。
     */
    private fun reportInjectorsWithoutPools(snapshot: TACZRuntimeSnapshot) {
        val empty = snapshot.lootInjections.filter { it.pools.size() == 0 }
        empty.take(MAX_SUMMARY_LINES).forEach { injection ->
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Loot injector {} has no pools, skipped (targets {}).",
                injection.id, injection.lootTables.joinToString(", "),
            )
        }
        if (empty.size > MAX_SUMMARY_LINES) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] ... and {} more loot injector(s) have no pools, skipped.",
                empty.size - MAX_SUMMARY_LINES,
            )
        }
    }

    /**
     * D-18：区分两种"什么也没注入"的原因 ——
     * 目标表从未触发 [LootTableLoadEvent]（表不存在 / 写错 id / 只写了 tag 等非表 id），
     * 与目标表加载了但为空表或已冻结（命中却没法 addPool）。
     * `pools` 为合法空数组属于第三种，已由 [reportInjectorsWithoutPools] 单独按注入器报过；
     * 这里把这类注入器排除掉，两类 WARN 互斥，避免同一个注入器被报两次。
     * （`pools` 字段缺失/非数组的注入器在扫描期就已被丢弃，不会出现在快照里，这里是第四种情形。）
     */
    private fun reportInjectorOutcomes(snapshot: TACZRuntimeSnapshot) {
        // 只要 pools 是空的，匹配与否都不影响结论，交给 reportInjectorsWithoutPools 解释
        val candidates = snapshot.lootInjections.filter { it.pools.size() > 0 }
        val unmatched = candidates.filter { it.id !in injectionsReachingTable }
        unmatched.take(MAX_SUMMARY_LINES).forEach { injection ->
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Loot injector {} never matched a loaded loot table; targets {}.",
                injection.id, injection.lootTables.joinToString(", "),
            )
        }
        if (unmatched.size > MAX_SUMMARY_LINES) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] ... and {} more loot injector(s) never matched a loaded loot table.",
                unmatched.size - MAX_SUMMARY_LINES,
            )
        }
        val unusable = candidates.filter {
            it.id in injectionsReachingTable && it.id !in injectionsWithUsableTable
        }
        unusable.take(MAX_SUMMARY_LINES).forEach { injection ->
            TACZLegacy.logger.warn(
                "[GunPackRuntime] Loot injector {} only matched empty/frozen loot table(s), nothing injected; targets {}.",
                injection.id, injection.lootTables.joinToString(", "),
            )
        }
        if (unusable.size > MAX_SUMMARY_LINES) {
            TACZLegacy.logger.warn(
                "[GunPackRuntime] ... and {} more loot injector(s) only matched empty/frozen loot tables.",
                unusable.size - MAX_SUMMARY_LINES,
            )
        }
    }

    /**
     * 把 1.20 写法的 pools 归一化成 1.12 的 `LootEntry` 能解析的形式：
     * - `type`：`minecraft:item` → `item`（1.12 是字面量比较）；缺失时补 `item`
     * - `entries`：单对象 → 数组（1.20 codec 容忍单对象）
     * - `rolls`/`bonus_rolls`：`{type:"minecraft:constant", value:n}` → `{min:n,max:n}`
     *
     * 不支持的 entry 类型（group/alternatives/sequence/dynamic/tag）只告警并原样保留，
     * 让 1.12 的解析器给出更明确的错误，而不是我们静默丢弃。
     */
    private fun normalizePoolsFor112(pools: JsonArray): JsonArray {
        val result = JsonArray()
        pools.forEach { element ->
            val pool = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val normalized = JsonObject()
            pool.entrySet().forEach { (key, value) -> normalized.add(key, value) }

            val entries = normalized.get("entries")
            if (entries != null && entries.isJsonObject) {
                normalized.add("entries", JsonArray().apply { add(entries) })
            }
            normalized.getAsJsonArray("entries")?.forEach { entry ->
                val obj = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val rawType = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                val shortType = rawType?.substringAfter(':')
                when (shortType) {
                    null -> obj.addProperty("type", "item")
                    "item", "loot_table", "empty" -> obj.addProperty("type", shortType)
                    else -> TACZLegacy.logger.warn(
                        "[GunPackRuntime] loot entry type '{}' is not supported on 1.12.2; left as-is.",
                        rawType,
                    )
                }
            }

            for (key in arrayOf("rolls", "bonus_rolls")) {
                val range = normalized.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                if (range.has("min") || range.has("max")) {
                    continue
                }
                val type = range.get("type")?.takeIf { it.isJsonPrimitive }?.asString?.substringAfter(':')
                when (type) {
                    "constant" -> {
                        val value = range.get("value") ?: continue
                        normalized.add(key, JsonObject().apply {
                            add("min", value)
                            add("max", value)
                        })
                    }
                    "uniform" -> {
                        // 理论上带 min/max，会走上面的 continue
                    }
                    else -> TACZLegacy.logger.warn(
                        "[GunPackRuntime] loot rolls type '{}' is not supported on 1.12.2; left as-is.",
                        type,
                    )
                }
            }
            result.add(normalized)
        }
        return result
    }

    /**
     * `ForgeHooks.loadLootTable` 只在 `JsonParseException` 时 pop 上下文；
     * 其它 `RuntimeException` 会让 ThreadLocal 栈永久偏移，后续表加载会弹错别人的 ctx。
     * 这里记录进入前的栈深，异常退出时把多出来的 pop 掉。
     */
    private inline fun <T> withBalancedLootContext(block: () -> T): T {
        val field = lootContextField ?: return block()
        val deque: Deque<Any>? = try {
            @Suppress("UNCHECKED_CAST")
            (field.get(null) as? ThreadLocal<*>)?.get() as? Deque<Any>
        } catch (throwable: Throwable) {
            null
        }
        if (deque == null) {
            return block()
        }
        val before = deque.size
        try {
            return block()
        } finally {
            while (deque.size > before) {
                try {
                    deque.pop()
                } catch (throwable: Throwable) {
                    break
                }
            }
        }
    }
}
