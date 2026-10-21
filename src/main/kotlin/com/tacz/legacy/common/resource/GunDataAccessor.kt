package com.tacz.legacy.common.resource

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.api.item.IGun
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraft.util.math.MathHelper
import com.tacz.legacy.api.item.gun.FireMode

private fun JsonObject.safeGetObject(key: String): JsonObject? =
    get(key)?.let { if (it.isJsonObject) it.asJsonObject else null }

private fun JsonElement.safeGetObject(key: String): JsonObject? =
    if (this is JsonObject) safeGetObject(key) else null

/**
 * 提供从枪包数据中提取战斗逻辑所需运行时数据的访问器。
 * 对应上游 TACZ 的 CommonGunIndex / GunData 等查询。
 */
public object GunDataAccessor {

    /**
     * 查找指定枪械的运行时战斗数据。
     */
    @JvmStatic
    public fun getGunData(gunId: ResourceLocation): GunCombatData? {
        val gun = TACZGunPackRuntimeRegistry.getSnapshot().guns[gunId] ?: return null
        return GunCombatData.fromRawJson(gun.data.raw, gun.data)
    }

    /**
     * 兼容层 ③ C10：取该配件在当前枪上的近战数据。
     *
     * exclusive_attachments 命中时用独占数据的 melee 整体替换
     * （对齐上游 AttachmentData#getMeleeData 的整份替换语义）。
     * 无枪上下文的重载已移除：它会绕过 exclusive_attachments。
     */
    @JvmStatic
    public fun getAttachmentMeleeData(gunStack: ItemStack, attachmentId: ResourceLocation): AttachmentMeleeCombatData? {
        if (attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return null
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val exclusiveMap = IGun.getIGunOrNull(gunStack)
            ?.let { snapshot.guns[it.getGunId(gunStack)]?.data?.exclusiveAttachments }
            ?: emptyMap()
        val data = TACZGunPropertyResolver.attachmentDataFor(snapshot, attachmentId, exclusiveMap) ?: return null
        // 审计全局-1：近战数据在扫描期解析一次并挂在条目上（TACZAttachmentDataDefinition.meleeData）。
        // 旧实现在这里重新 fromRawJson，而本函数被近战冷却查询（每 tick）与客户端动画驱动调用。
        return data.meleeData
    }

    /**
     * 兼容层 ③ C10：取该配件在当前枪上的扩容等级。
     *
     * exclusive_attachments 命中时用独占数据的 extended_mag_level 整体替换
     * （对齐上游 AttachmentDataUtils#getMagExtendLevel）。
     * 无 gun 上下文的重载已移除：它会绕过 exclusive_attachments，是静默的数值错误来源。
     */
    @JvmStatic
    public fun getAttachmentExtendedMagLevel(gunStack: ItemStack, attachmentId: ResourceLocation?): Int {
        if (attachmentId == null || attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return 0
        }
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val exclusiveMap = IGun.getIGunOrNull(gunStack)
            ?.let { snapshot.guns[it.getGunId(gunStack)]?.data?.exclusiveAttachments }
            ?: emptyMap()
        val data = TACZGunPropertyResolver.attachmentDataFor(snapshot, attachmentId, exclusiveMap) ?: return 0
        return data.extendedMagLevel.coerceIn(0, 3)
    }
}

/**
 * 拉平的枪械战斗参数，从 gun data JSON 按需提取。
 */
public class GunCombatData private constructor(
    public val ammoId: ResourceLocation?,
    public val ammoAmount: Int,
    public val roundsPerMinute: Int,
    public val fireSoundMultiplier: Float,
    public val silenceSoundMultiplier: Float,
    public val canSlide: Boolean,
    public val canCrawl: Boolean,
    public val boltType: BoltType,
    public val drawTimeS: Float,
    public val putAwayTimeS: Float,
    public val aimTimeS: Float,
    public val sprintTimeS: Float,
    public val reloadFeedingTimeS: Float,
    public val reloadFinishingTimeS: Float,
    public val emptyReloadFeedingTimeS: Float,
    public val emptyReloadFinishingTimeS: Float,
    public val boltTimeS: Float,
    public val boltFeedTimeS: Float,
    public val fireModesSet: List<String>,
    internal val fireModeAdjusts: Map<FireMode, TACZGunPropertyResolver.FireModeAdjust>,
    public val burstMinInterval: Float,
    public val burstCount: Int,
    public val burstShootIntervalMillis: Long,
    public val burstContinuousShoot: Boolean,
    public val hasHeatData: Boolean,
    public val heatMax: Float,
    public val heatPerShot: Float,
    public val heatCoolingMultiplier: Float,
    public val heatCoolingDelayMs: Long,
    public val heatOverHeatTimeMs: Long,
    public val heatMinInaccuracy: Float,
    public val heatMaxInaccuracy: Float,
    public val heatMinRpmModifier: Float,
    public val heatMaxRpmModifier: Float,
    public val crawlRecoilMultiplier: Float,
    public val isReloadInfinite: Boolean,
    public val reloadType: String,
    public val scriptId: ResourceLocation?,
    public val scriptParams: Map<String, Any>?,
    public val meleeData: GunMeleeCombatData?,
    public val moveSpeedBase: Float,
    public val moveSpeedAim: Float,
    public val moveSpeedReload: Float,
    public val hurtBobTweakMultiplier: Float,
    public val chargeData: Map<FireMode, ChargeData>?,
    public val bulletData: BulletCombatData,
) {
    /**
     * 获取基于 RPM 的射击间隔（毫秒）。
     */
    public fun getShootIntervalMs(): Long = getShootIntervalMs(FireMode.UNKNOWN)

    /**
     * 兼容层 ③ C1-3：接入配件 rpm modifier。
     * 对齐上游 orig-GunData.java:302-312 —— 先按 (base rpm + fire_mode_adjust.rpm) 取基准值，
     * eval 配件 modifier，Math.round + clamp(1, 1200)，**然后**才乘 heat 倍率且不再 clamp。
     *
     * 审计 B3-15：旧实现把 heat 当成一个 `multiplier` modifier 混进同一次 eval，clamp 顺序与上游相反。
     * 可达差异算例：base 600、配件 +200%（=1800）、heat 0.5 →
     * 上游 clamp 到 1200 再乘 0.5 = 600 → 100 ms；旧实现 900 → 66 ms（射速快一倍半）。
     */
    public fun getShootIntervalMs(
        fireMode: FireMode,
        rpmModifiers: List<Modifier> = emptyList(),
        heatRpmMultiplier: Float = 1.0f,
    ): Long {
        // 对齐原版 getRoundsPerMinute(fireMode): base rpm + fireModeAdjust.rpm，<=0 兜底 300
        var rpm = roundsPerMinute
        fireModeAdjusts[fireMode]?.let { rpm += it.rpm }
        if (rpm <= 0) rpm = 300
        val evaluated = if (rpmModifiers.isEmpty()) {
            rpm.toDouble()
        } else {
            TACZAttachmentModifierRegistry.evalNumeric(rpmModifiers, rpm.toDouble())
        }
        val clamped = MathHelper.clamp(Math.round(evaluated).toInt(), 1, 1200)
        // 上游 :310 是 (int) 截断乘法，且乘完不再 clamp 上限；下限只保留 1 防除零（上游此处会抛）。
        val withHeat = (clamped * heatRpmMultiplier).toInt().coerceAtLeast(1)
        return 60_000L / withHeat
    }

    public fun getBurstMinIntervalMs(): Long {
        if (burstMinInterval <= 0f) return 0L
        return (burstMinInterval * 1000f).toLong()
    }

    public fun getBurstShootIntervalMs(): Long = burstShootIntervalMillis

    public fun isContinuousBurst(): Boolean = burstContinuousShoot

    public fun canSlide(): Boolean = canSlide

    public companion object {
        internal fun fromRawJson(raw: JsonObject, def: TACZGunDataDefinition): GunCombatData {
            val bolt = raw.getAsJsonPrimitive("bolt")?.asString?.let { name ->
                try { BoltType.valueOf(name.uppercase()) } catch (_: Exception) { BoltType.OPEN_BOLT }
            } ?: BoltType.OPEN_BOLT
            val canSlide = raw.getAsJsonPrimitive("can_slide")?.asBoolean
                ?: raw.getAsJsonPrimitive("canSlide")?.asBoolean
                ?: true
            val canCrawl = raw.getAsJsonPrimitive("can_crawl")?.asBoolean
                ?: raw.getAsJsonPrimitive("canCrawl")?.asBoolean
                ?: true
            val fireSound = raw.safeGetObject("fire_sound")
            val fireSoundMultiplier = fireSound?.getAsJsonPrimitive("fire_multiplier")?.asFloat ?: 1.0f
            val silenceSoundMultiplier = fireSound?.getAsJsonPrimitive("silence_multiplier")?.asFloat ?: 1.0f

            val drawTime = raw.getAsJsonPrimitive("draw_time")?.asFloat ?: 0.4f
            val putAwayTime = raw.getAsJsonPrimitive("put_away_time")?.asFloat ?: 0.4f
            val sprintTime = raw.getAsJsonPrimitive("sprint_time")?.asFloat ?: 0.2f

            val reloadObj = raw.safeGetObject("reload")
            val feedObj = reloadObj?.safeGetObject("feed")
            val cooldownObj = reloadObj?.safeGetObject("cooldown")
            // 对齐原版 GunReloadTime 默认 empty=2.5/tactical=2.0；empty 缺省回退各自默认而非 tactical（避免塌缩）
            val feedingTime = feedObj?.getAsJsonPrimitive("tactical")?.asFloat
                ?: feedObj?.getAsJsonPrimitive("time")?.asFloat
                ?: reloadObj?.getAsJsonPrimitive("feeding_time")?.asFloat
                ?: 2.0f
            val emptyFeedingTime = feedObj?.getAsJsonPrimitive("empty")?.asFloat
                ?: reloadObj?.getAsJsonPrimitive("empty_feeding_time")?.asFloat
                ?: 2.5f
            val finishingTime = cooldownObj?.getAsJsonPrimitive("tactical")?.asFloat
                ?: reloadObj?.getAsJsonPrimitive("finishing_time")?.asFloat
                ?: 2.0f
            val emptyFinishingTime = cooldownObj?.getAsJsonPrimitive("empty")?.asFloat
                ?: reloadObj?.getAsJsonPrimitive("empty_finishing_time")?.asFloat
                ?: 2.5f
            val isInfinite = reloadObj?.getAsJsonPrimitive("infinite")?.asBoolean ?: false
            // B4 / X-10：reload.type 对齐上游 FeedType（magazine/manual/fuel/inventory），
            // 缺省与非法值都回退 magazine；非法值的 issue 在 parseGunData 里记录（这里没有 issue sink）。
            val reloadType = reloadObj?.get("type")
                ?.takeIf { it.isJsonPrimitive }
                ?.asString
                ?.lowercase()
                ?.takeIf { it in TACZ_RELOAD_TYPES }
                ?: "magazine"

            val boltTime = raw.getAsJsonPrimitive("bolt_action_time")?.asFloat ?: raw.getAsJsonPrimitive("bolt_time")?.asFloat ?: 0f
            val boltFeedTime = raw.getAsJsonPrimitive("bolt_feed_time")?.asFloat ?: -1f

            val burstObj = raw.safeGetObject("burst_data") ?: raw.safeGetObject("burst")
            val burstInterval = burstObj?.getAsJsonPrimitive("min_interval")?.asFloat ?: 1.0f
            val burstCount = burstObj?.getAsJsonPrimitive("count")?.asInt ?: 3
            val burstBpm = burstObj?.getAsJsonPrimitive("bpm")?.asInt ?: 200
            val burstShootIntervalMs = if (burstBpm > 0) 60_000L / burstBpm else 300L
            val burstContinuousShoot = burstObj?.getAsJsonPrimitive("continuous_shoot")?.asBoolean ?: false

            // B4 / X-06、X-20：缺省 [unknown]（对齐上游 GunData.fireModeSet）；
            // 非法元素由 parseFireModeNames 跳过（issue 在索引层记录），不再整段 asString 抛异常。
            val fireModes = parseFireModeNames(raw)
            // fire_mode_adjust: 解析全 fireMode 的 adjust（对齐原版 GunData.fireModeAdjust EnumMap）
            val fireModeAdjusts: Map<FireMode, TACZGunPropertyResolver.FireModeAdjust> =
                listOf(FireMode.AUTO, FireMode.SEMI, FireMode.BURST).associateWith { fm ->
                    val modeKey = when (fm) { FireMode.AUTO -> "auto"; FireMode.SEMI -> "semi"; FireMode.BURST -> "burst"; else -> null }
                    val adjObj = modeKey?.let { raw.safeGetObject("fire_mode_adjust")?.safeGetObject(it) }
                    if (adjObj == null) TACZGunPropertyResolver.FireModeAdjust()
                    else TACZGunPropertyResolver.FireModeAdjust(
                        damage = adjObj.getAsJsonPrimitive("damage")?.asFloat ?: 0f,
                        rpm = adjObj.getAsJsonPrimitive("rpm")?.asInt ?: 0,
                        ammoSpeed = adjObj.getAsJsonPrimitive("speed")?.asFloat ?: 0f,
                        armorIgnore = adjObj.getAsJsonPrimitive("armor_ignore")?.asFloat ?: 0f,
                        headShot = adjObj.getAsJsonPrimitive("head_shot_multiplier")?.asFloat ?: 0f,
                        knockback = adjObj.getAsJsonPrimitive("knockback")?.asFloat ?: 0f,
                        aimInaccuracy = adjObj.getAsJsonPrimitive("aim_inaccuracy")?.asFloat ?: 0f,
                        otherInaccuracy = adjObj.getAsJsonPrimitive("other_inaccuracy")?.asFloat ?: 0f,
                    )
                }
            val crawlRecoilMultiplier = raw.getAsJsonPrimitive("crawl_recoil_multiplier")?.asFloat ?: 0.5f

            val heatObj = raw.safeGetObject("heat")
            val hasHeat = heatObj != null
            val heatMax = heatObj?.getAsJsonPrimitive("max")?.asFloat ?: 100.0f
            val heatPerShot = heatObj?.getAsJsonPrimitive("per_shot")?.asFloat ?: 3.0f
            val heatCoolingMultiplier = heatObj?.getAsJsonPrimitive("cooling_multiplier")?.asFloat ?: 1.0f
            val heatCoolingDelayMs = heatObj?.getAsJsonPrimitive("cooling_delay")?.asLong ?: 1000L
            val heatOverHeatTimeMs = heatObj?.getAsJsonPrimitive("over_heat_time")?.asLong ?: 3000L
            val heatMinInaccuracy = heatObj?.getAsJsonPrimitive("min_inaccuracy")?.asFloat ?: 1.0f
            val heatMaxInaccuracy = heatObj?.getAsJsonPrimitive("max_inaccuracy")?.asFloat ?: 1.0f
            val heatMinRpmModifier = heatObj?.getAsJsonPrimitive("min_rpm_mod")?.asFloat ?: 1.0f
            val heatMaxRpmModifier = heatObj?.getAsJsonPrimitive("max_rpm_mod")?.asFloat ?: 1.0f

            val scriptId = raw.getAsJsonPrimitive("script")?.asString?.takeIf { it.isNotBlank() }?.let(::ResourceLocation)

            // B4 / X-15：script_param 只把原始类型降成标量；JsonObject/JsonArray 保留原结构，
            // 不能 toString 成字符串（否则脚本侧拿到的是无法索引的 JSON 文本）。
            val scriptParams: Map<String, Any>? = raw.safeGetObject("script_param")?.entrySet()?.associate { (k, v) ->
                k to when {
                    v.isJsonObject || v.isJsonArray -> v
                    v.isJsonPrimitive && v.asJsonPrimitive.isNumber -> v.asDouble
                    v.isJsonPrimitive && v.asJsonPrimitive.isBoolean -> v.asBoolean
                    v.isJsonPrimitive && v.asJsonPrimitive.isString -> v.asString
                    else -> v
                }
            }

            val meleeObj = raw.safeGetObject("melee")
            // 对齐上游 GunData：melee 缺省时仍为 new GunMeleeData()，这里始终构造非 null 数据
            // 默认值对齐上游 GunMeleeData：cooldown=1f、distance=1f
            val meleeCooldown = meleeObj?.getAsJsonPrimitive("cooldown")?.asFloat ?: 1.0f
            val meleeDistance = meleeObj?.getAsJsonPrimitive("distance")?.asFloat ?: 1.0f
            val defaultObj = meleeObj?.safeGetObject("default")
            // 上游 GunMeleeData.defaultMeleeData 是 @Nullable 且默认 null（tacz-upstream-1201
            // GunMeleeData.java:14-16）；只有 data 里显式写了 `melee.default` 才构造。
            // 缺省时上游不做近战伤害结算（doMelee 在 null 处短路），不能伪造一个默认对象，
            // 否则无 default 的第三方枪会多出一次 0 伤害近战，且客户端动画会误播 bayonet_push。
            val defaultData = if (defaultObj != null) {
                GunDefaultMeleeCombatData(
                    animationType = defaultObj.getAsJsonPrimitive("animation_type")?.asString ?: "melee_push",
                    prepTime = defaultObj.getAsJsonPrimitive("prep")?.asFloat
                        ?: defaultObj.getAsJsonPrimitive("prep_time")?.asFloat
                        ?: 0.1f,
                    // 上游 GunDefaultMeleeData.cooldown 默认 0f（与 GunMeleeData 的 1f 不同）
                    cooldown = defaultObj.getAsJsonPrimitive("cooldown")?.asFloat ?: 0.0f,
                    damage = defaultObj.getAsJsonPrimitive("damage")?.asFloat ?: 0.0f,
                    distance = defaultObj.getAsJsonPrimitive("distance")?.asFloat ?: 1.0f,
                    rangeAngle = defaultObj.getAsJsonPrimitive("range_angle")?.asFloat ?: 30.0f,
                    knockback = defaultObj.getAsJsonPrimitive("knockback")?.asFloat ?: 0.2f,
                )
            } else {
                null
            }
            val meleeData = GunMeleeCombatData(
                cooldown = meleeCooldown,
                distance = meleeDistance,
                defaultMeleeData = defaultData,
            )

            val bulletObj = raw.safeGetObject("bullet")
            val extraDamageObj = bulletObj?.safeGetObject("extra_damage")
            val explosionObj = bulletObj?.safeGetObject("explosion")
            // movement_speed: 对齐原版 MoveSpeed（base/aim/reload 三个修正值，默认 0=无修正）
            val moveSpeedObj = raw.safeGetObject("movement_speed")
            val moveSpeedBase = moveSpeedObj?.getAsJsonPrimitive("base")?.asFloat ?: 0f
            val moveSpeedAim = moveSpeedObj?.getAsJsonPrimitive("aim")?.asFloat ?: 0f
            val moveSpeedReload = moveSpeedObj?.getAsJsonPrimitive("reload")?.asFloat ?: 0f
            // hurt_bob_tweak_multiplier: 对齐原版 GunData，被子弹击中时的视角晃动倍率，默认 0.05
            val hurtBobTweakMultiplier = raw.getAsJsonPrimitive("hurt_bob_tweak_multiplier")?.asFloat ?: 0.05f
            // charging: 对齐原版 EnumMap<FireMode, ChargeData>，充能射击数据（默认包不使用）
            val chargeObj = raw.safeGetObject("charging")
            val chargeData = chargeObj?.entrySet()?.mapNotNull { entry ->
                val fm = when (entry.key) {
                    "auto" -> FireMode.AUTO; "semi" -> FireMode.SEMI; "burst" -> FireMode.BURST
                    else -> return@mapNotNull null
                }
                val cd = entry.value?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                fm to ChargeData(
                    chargeType = cd.getAsJsonPrimitive("type")?.asString ?: "auto",
                    increasePerTick = cd.getAsJsonPrimitive("increase_per_tick")?.asFloat ?: 0.2f,
                    decreasePerTick = cd.getAsJsonPrimitive("decrease_per_tick")?.asFloat ?: 0.5f,
                    decreaseOnFire = cd.getAsJsonPrimitive("decrease_on_fire")?.asFloat ?: 0f,
                    maxCharge = cd.getAsJsonPrimitive("max_charge")?.asFloat ?: 1f,
                    fireThreshold = cd.getAsJsonPrimitive("fire_threshold")?.asFloat ?: 0.6f,
                    chargeDuringCooldown = cd.getAsJsonPrimitive("charge_during_cooldown")?.asBoolean ?: true,
                )
            }?.toMap()
            // ignite: 对齐原版 IgniteSerializer（标量 true→entity+block；对象 entity/block），保留旧 ignite_entity/ignite_block 兜底
            val igniteEl = bulletObj?.get("ignite")
            val (igniteEnt, igniteBlk) = when {
                igniteEl?.isJsonPrimitive == true && igniteEl.asJsonPrimitive.isBoolean ->
                    igniteEl.asBoolean to igniteEl.asBoolean
                igniteEl?.isJsonObject == true ->
                    (igniteEl.asJsonObject.getAsJsonPrimitive("entity")?.asBoolean
                        ?: igniteEl.asJsonObject.getAsJsonPrimitive("ignite_entity")?.asBoolean
                        ?: false) to
                    (igniteEl.asJsonObject.getAsJsonPrimitive("block")?.asBoolean
                        ?: igniteEl.asJsonObject.getAsJsonPrimitive("ignite_block")?.asBoolean
                        ?: false)
                else -> false to false
            }
            val bulletCombatData = BulletCombatData(
                damage = bulletObj?.getAsJsonPrimitive("damage")?.asFloat ?: 5.0f,
                speed = bulletObj?.getAsJsonPrimitive("speed")?.asFloat ?: 5.0f,
                gravity = bulletObj?.getAsJsonPrimitive("gravity")?.asFloat ?: 0.0f,
                friction = bulletObj?.getAsJsonPrimitive("friction")?.asFloat ?: 0.01f,
                pierce = bulletObj?.getAsJsonPrimitive("pierce")?.asInt ?: 1,
                lifeSecond = bulletObj?.getAsJsonPrimitive("life")?.asFloat ?: 10.0f,
                bulletAmount = bulletObj?.getAsJsonPrimitive("bullet_amount")?.asInt ?: 1,
                knockback = bulletObj?.getAsJsonPrimitive("knockback")?.asFloat ?: 0.0f,
                tracerCountInterval = bulletObj?.getAsJsonPrimitive("tracer_count_interval")?.asInt ?: -1,
                igniteEntity = igniteEnt || (bulletObj?.getAsJsonPrimitive("ignite_entity")?.asBoolean ?: false),
                igniteEntityTime = bulletObj?.getAsJsonPrimitive("ignite_entity_time")?.asInt ?: 2,
                igniteBlock = igniteBlk || (bulletObj?.getAsJsonPrimitive("ignite_block")?.asBoolean ?: false),
                extraDamageData = extraDamageObj?.let { extra ->
                    BulletExtraDamageData(
                        armorIgnore = extra.getAsJsonPrimitive("armor_ignore")?.asFloat ?: 0.0f,
                        headShotMultiplier = extra.getAsJsonPrimitive("head_shot_multiplier")?.asFloat ?: 1.0f,
                        damageAdjust = extra.getAsJsonArray("damage_adjust")
                            ?.mapNotNull { element ->
                                val obj = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                                val damage = obj.getAsJsonPrimitive("damage")?.asFloat ?: return@mapNotNull null
                                val distanceElement = obj.get("distance") ?: return@mapNotNull null
                                val distance = when {
                                    distanceElement.isJsonPrimitive && distanceElement.asJsonPrimitive.isString -> {
                                        if (distanceElement.asString.equals("infinite", ignoreCase = true)) {
                                            Float.POSITIVE_INFINITY
                                        } else {
                                            return@mapNotNull null
                                        }
                                    }
                                    distanceElement.isJsonPrimitive && distanceElement.asJsonPrimitive.isNumber -> distanceElement.asFloat
                                    else -> return@mapNotNull null
                                }
                                DistanceDamagePoint(distance = distance, damage = damage)
                            }
                            // 按 JSON 顺序遍历（上游 DamageModifier 依赖书写顺序，不排序）；
                            // 旧实现对 distance 排序会在曲线乱序时改变"第一个命中档位"的语义。
                            ?: emptyList(),
                    )
                },
                explosionData = explosionObj?.let {
                    ExplosionData(
                        explode = it.getAsJsonPrimitive("explode")?.asBoolean ?: false,
                        radius = it.getAsJsonPrimitive("radius")?.asFloat ?: 0.0f,
                        damage = it.getAsJsonPrimitive("damage")?.asFloat ?: 0.0f,
                        knockback = it.getAsJsonPrimitive("knockback")?.asBoolean ?: false,
                        destroyBlock = it.getAsJsonPrimitive("destroy_block")?.asBoolean ?: false,
                        delay = it.getAsJsonPrimitive("delay")?.asFloat ?: 0.0f,
                    )
                },
            )

            return GunCombatData(
                ammoId = def.ammoId,
                ammoAmount = def.ammoAmount,
                roundsPerMinute = def.roundsPerMinute,
                fireSoundMultiplier = fireSoundMultiplier,
                silenceSoundMultiplier = silenceSoundMultiplier,
                canSlide = canSlide,
                canCrawl = canCrawl,
                boltType = bolt,
                drawTimeS = drawTime,
                putAwayTimeS = putAwayTime,
                aimTimeS = def.aimTime,
                sprintTimeS = sprintTime,
                reloadFeedingTimeS = feedingTime,
                reloadFinishingTimeS = finishingTime,
                emptyReloadFeedingTimeS = emptyFeedingTime,
                emptyReloadFinishingTimeS = emptyFinishingTime,
                boltTimeS = boltTime,
                boltFeedTimeS = boltFeedTime,
                fireModesSet = fireModes,
                fireModeAdjusts = fireModeAdjusts,
                burstMinInterval = burstInterval,
                burstCount = burstCount,
                burstShootIntervalMillis = burstShootIntervalMs,
                burstContinuousShoot = burstContinuousShoot,
                hasHeatData = hasHeat,
                heatMax = heatMax,
                heatPerShot = heatPerShot,
                heatCoolingMultiplier = heatCoolingMultiplier,
                heatCoolingDelayMs = heatCoolingDelayMs,
                heatOverHeatTimeMs = heatOverHeatTimeMs,
                heatMinInaccuracy = heatMinInaccuracy,
                heatMaxInaccuracy = heatMaxInaccuracy,
                heatMinRpmModifier = heatMinRpmModifier,
                heatMaxRpmModifier = heatMaxRpmModifier,
                crawlRecoilMultiplier = crawlRecoilMultiplier,
                isReloadInfinite = isInfinite,
                reloadType = reloadType,
                scriptId = scriptId,
                scriptParams = scriptParams,
                meleeData = meleeData,
                moveSpeedBase = moveSpeedBase,
                moveSpeedAim = moveSpeedAim,
                moveSpeedReload = moveSpeedReload,
                hurtBobTweakMultiplier = hurtBobTweakMultiplier,
                chargeData = chargeData,
                bulletData = bulletCombatData,
            )
        }
    }
}

public enum class BoltType {
    OPEN_BOLT,
    CLOSED_BOLT,
    MANUAL_ACTION,
}

/**
 * 充能射击数据（对齐原版 ChargeData）。
 * 默认包不使用 charging 字段，但第三方枪包可能用到。
 */
public data class ChargeData(
    public val chargeType: String, // auto/hold/delay
    public val increasePerTick: Float,
    public val decreasePerTick: Float,
    public val decreaseOnFire: Float,
    public val maxCharge: Float,
    public val fireThreshold: Float,
    public val chargeDuringCooldown: Boolean,
)

public class GunMeleeCombatData(
    /** 枪械本体近战冷却（对齐原版 GunMeleeData.cooldown，默认 1s） */
    public val cooldown: Float = 1.0f,
    /** 枪本体近战距离（对齐原版 GunMeleeData.distance，与原版 doMelee 的 gunDistance 对应） */
    public val distance: Float = 1.0f,
    /**
     * 枪械默认近战数据。对齐上游 GunMeleeData.defaultMeleeData：
     * `@Nullable`，缺省为 null；为 null 时上游不执行近战伤害段。
     */
    public val defaultMeleeData: GunDefaultMeleeCombatData? = null,
)

/**
 * 枪械 melee.default 近战数据（对齐上游 GunDefaultMeleeData），所有字段默认值保持一致。
 */
public class GunDefaultMeleeCombatData(
    public val animationType: String = "melee_push",
    public val prepTime: Float = 0.1f,
    public val cooldown: Float = 0.0f,
    public val damage: Float = 0.0f,
    public val distance: Float = 1.0f,
    public val rangeAngle: Float = 30.0f,
    public val knockback: Float = 0.2f,
)

/**
 * 配件近战数据（对齐上游 attachment/MeleeData）。
 * 仅枪口/枪托配件拥有该段，命中后按 effects 逐个施加药水效果。
 */
public class AttachmentMeleeCombatData(
    public val prepTime: Float,
    public val cooldown: Float,
    public val damage: Float,
    public val distance: Float,
    public val rangeAngle: Float,
    public val knockback: Float,
    public val effects: List<MeleeEffectCombatData> = emptyList(),
) {
    public companion object {
        internal fun fromRawJson(raw: JsonObject): AttachmentMeleeCombatData? {
            val melee = raw.safeGetObject("melee") ?: return null
            return AttachmentMeleeCombatData(
                // 默认值对齐上游 attachment/MeleeData：prep=0.1、distance=1、range_angle=30、
                // cooldown=0、damage=0、knockback=0
                prepTime = melee.getAsJsonPrimitive("prep")?.asFloat ?: 0.1f,
                cooldown = melee.getAsJsonPrimitive("cooldown")?.asFloat ?: 0.0f,
                damage = melee.getAsJsonPrimitive("damage")?.asFloat ?: 0.0f,
                distance = melee.getAsJsonPrimitive("distance")?.asFloat ?: 1.0f,
                rangeAngle = melee.getAsJsonPrimitive("range_angle")?.asFloat ?: 30.0f,
                knockback = melee.getAsJsonPrimitive("knockback")?.asFloat ?: 0.0f,
                effects = melee.get("effects")
                    ?.takeIf { it.isJsonArray }
                    ?.asJsonArray
                    ?.mapNotNull { element ->
                        val obj = element.takeIf(JsonElement::isJsonObject)?.asJsonObject
                            ?: return@mapNotNull null
                        MeleeEffectCombatData.fromRawJson(obj)
                    }
                    ?: emptyList(),
            )
        }
    }
}

/**
 * 近战命中后施加的单条效果（对齐上游 attachment/MeleeData.effects 的 EffectData）。
 * 默认包示例字段为 id/time/amplifier/hide_particles；
 * 同时兼容第三方枪包可能使用的 effect/duration 别名。
 */
public class MeleeEffectCombatData(
    /** 药水效果注册名，解析失败时为 null（消费端跳过） */
    public val effectId: ResourceLocation?,
    /** 效果时长，单位：秒（对齐上游 EffectData.time，施加时乘以 20 转为 tick） */
    public val durationSeconds: Float,
    /** 效果等级 */
    public val amplifier: Int,
    /** 对齐上游 EffectData.hide_particles，原样作为粒子显示参数 */
    public val hideParticles: Boolean,
) {
    public companion object {
        internal fun fromRawJson(raw: JsonObject): MeleeEffectCombatData? {
            // 默认包字段是 id；effect 为兼容别名
            val idText = raw.getAsJsonPrimitive("id")?.asString
                ?: raw.getAsJsonPrimitive("effect")?.asString
            val effectId = idText?.let { runCatching { ResourceLocation(it) }.getOrNull() }
                ?: return null
            return MeleeEffectCombatData(
                effectId = effectId,
                // 默认包字段是 time（秒，上游 EffectData.time 是 int 且默认 0）；
                // duration 是第三方包兼容别名。
                durationSeconds = raw.getAsJsonPrimitive("time")?.asInt?.toFloat()
                    ?: raw.getAsJsonPrimitive("duration")?.asFloat
                    ?: 0f,
                amplifier = raw.getAsJsonPrimitive("amplifier")?.asInt ?: 0,
                hideParticles = raw.getAsJsonPrimitive("hide_particles")?.asBoolean ?: false,
            )
        }
    }
}

/**
 * 子弹战斗参数。对应上游 TACZ BulletData 结构。
 */



public data class ExplosionData(
    val explode: Boolean,
    val radius: Float,
    val damage: Float,
    val knockback: Boolean,
    val destroyBlock: Boolean,
    val delay: Float
)

public data class DistanceDamagePoint(
    val distance: Float,
    val damage: Float,
)

public data class BulletDamageSplit(
    val normalDamage: Float,
    val armorPiercingDamage: Float,
)

public class BulletExtraDamageData(
    public val armorIgnore: Float,
    public val headShotMultiplier: Float,
    public val damageAdjust: List<DistanceDamagePoint>,
) {
    public fun resolveDamage(distance: Double, fallbackDamage: Float): Float {
        if (damageAdjust.isEmpty()) {
            return fallbackDamage
        }
        for (pair in damageAdjust) {
            if (distance < pair.distance || pair.distance.isInfinite()) {
                return pair.damage
            }
        }
        // 对齐上游 getDamage(.orig_EntityKineticBullet.java:517-533)：超出最后一个有限档 → 0，
        // 不是回落到末档伤害（审计 B1-10）。
        return 0f
    }

    public fun splitDamage(totalDamage: Float, armorIgnoreRatio: Float = armorIgnore): BulletDamageSplit {
        val clampedRatio = armorIgnoreRatio.coerceIn(0.0f, 1.0f)
        val armorPiercingDamage = (totalDamage * clampedRatio).coerceAtLeast(0.0f)
        val normalDamage = (totalDamage - armorPiercingDamage).coerceAtLeast(0.0f)
        return BulletDamageSplit(normalDamage = normalDamage, armorPiercingDamage = armorPiercingDamage)
    }
}

public class BulletCombatData(
    /** 基础伤害 */
    public val damage: Float,
    /** 速度（m/s 概念值，启动时除以 20 成 tick 速度） */
    public val speed: Float,
    /** 每 tick 重力加速度 */
    public val gravity: Float,
    /** 空气阻力系数 */
    public val friction: Float,
    /** 穿透次数 */
    public val pierce: Int,
    /** 子弹存活时间（秒），会乘以 20 变 tick */
    public val lifeSecond: Float,
    /** 每次射击产生的弹丸数（霰弹>1） */
    public val bulletAmount: Int,
    /** 击退值 */
    public val knockback: Float,
    /** 曳光弹间隔，-1 表示无曳光弹 */
    public val tracerCountInterval: Int,
    /** 是否点燃目标实体 */
    public val igniteEntity: Boolean,
    /** 点燃实体持续时间（tick-概念，上游是秒） */
    public val igniteEntityTime: Int,
    /** 是否点燃方块 */
    public val igniteBlock: Boolean,
    /** 额外伤害数据：距离伤害曲线 / 爆头倍率 / 穿甲比例 */
    public val extraDamageData: BulletExtraDamageData? = null,
    /** 爆炸属性 */
    public val explosionData: ExplosionData? = null,
) {
    /** 是否有曳光弹 */
    public fun hasTracerAmmo(): Boolean = tracerCountInterval >= 0

    /** 计算每 tick 飞行速度：speed / 20，与上游 processedSpeed 一致 */
    public fun getProcessedSpeed(): Float = (speed / 20.0f).coerceAtLeast(0.0f)

    /** 计算子弹生存 tick 数 */
    public fun getLifeTicks(): Int = (lifeSecond * 20).toInt().coerceAtLeast(1)
}
