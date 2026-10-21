package com.tacz.legacy.common.resource

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.item.gun.FireMode
import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.api.modifier.ParameterizedCachePair
import net.minecraft.entity.EntityLivingBase
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.apache.commons.math3.analysis.interpolation.SplineInterpolator
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

internal object TACZGunPropertyResolver {
    internal data class FireModeAdjust(
        val damage: Float = 0f,
        val rpm: Int = 0,
        val ammoSpeed: Float = 0f,
        val armorIgnore: Float = 0f,
        val headShot: Float = 0f,
        val knockback: Float = 0f,
        val aimInaccuracy: Float = 0f,
        val otherInaccuracy: Float = 0f,
    )

    internal data class RecoilFunctions(
        val pitch: PolynomialSplineFunction?,
        val yaw: PolynomialSplineFunction?,
    )

    private val SLUG_TAG: ResourceLocation = ResourceLocation("tacz", "intrinsic/slug")
    private val RECOIL_SPLINE_INTERPOLATOR = SplineInterpolator()

    // 服务端玩家的 prevPos 在 onUpdate 开头被设为 pos（此时 pos 已被网络包更新），
    // 导致 posX - prevPosX 恒为 0，无法检测移动。用上一 tick 记录的位置做差值替代。
    private val lastTickPositions = ConcurrentHashMap<UUID, DoubleArray>()

    /**
     * 在 ShooterTickHandler.onLivingTick 末尾调用，记录当前 tick 的位置，
     * 供下一 tick 的 isMoving 判断移动状态。
     */
    internal fun recordTickPosition(entity: EntityLivingBase) {
        lastTickPositions[entity.uniqueID] = doubleArrayOf(entity.posX, entity.posZ)
    }

    /**
     * 定期清空所有位置记录，防止已移除实体的条目堆积造成内存泄漏。
     * 下一 tick 持枪实体会被 onLivingTick 自动重新记录。
     */
    internal fun clearAllTickPositions() {
        lastTickPositions.clear()
    }

    internal fun collectAttachmentIds(stack: ItemStack, iGun: IGun): List<ResourceLocation> {
        return AttachmentType.values().mapNotNull { type ->
            if (type == AttachmentType.NONE) {
                return@mapNotNull null
            }
            val installed = iGun.getAttachmentId(stack, type)
            when {
                installed != DefaultAssets.EMPTY_ATTACHMENT_ID -> installed
                else -> iGun.getBuiltInAttachmentId(stack, type).takeIf { it != DefaultAssets.EMPTY_ATTACHMENT_ID }
            }
        }
    }

    internal fun resolveBulletAmount(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Int {
        val extendedMagId = resolveCurrentAttachmentId(stack, iGun, AttachmentType.EXTENDED_MAG)
        return if (matchesAttachmentTag(extendedMagId, SLUG_TAG)) {
            1
        } else {
            gunData.bulletData.bulletAmount.coerceAtLeast(1)
        }
    }

    internal fun resolveInaccuracyProfile(stack: ItemStack, iGun: IGun): Map<String, Float> {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = iGun.getGunId(stack)
        val gunEntry = snapshot.guns[gunId]
        val raw = gunEntry?.data?.raw ?: return DEFAULT_INACCURACY
        val exclusiveMap = gunEntry.data.exclusiveAttachments
        val adjust = parseFireModeAdjust(raw, iGun.getFireMode(stack))
        val defaults = parseInaccuracyDefaults(raw, adjust)
        // 兼容层 ③ C1-1：与 GUI 共用同一份收集逻辑
        val modifiers = collectInaccuracyModifiers(snapshot, collectAttachmentIds(stack, iGun), exclusiveMap)
        return TACZAttachmentModifierRegistry.evalInaccuracy(modifiers, defaults)
    }

    /**
     * 合并配件 ignite modifier 与子弹默认 ignite（对齐原版 AttachmentCacheProperty 的 ignite 合并）。
     * 默认包无配件 ignite，第三方枪包的燃烧弹配件依赖此合并。
     */
    internal fun resolveIgnite(stack: ItemStack, iGun: IGun, gunData: GunCombatData): TACZIgniteValue {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        // C10：独占命中时整份替换配件 data，ignite 也必须走同一份数据，否则第三方用 exclusive 关点火会失效
        val exclusiveMap = snapshot.guns[iGun.getGunId(stack)]?.data?.exclusiveAttachments ?: emptyMap()
        val defaultIgnite = TACZIgniteValue(
            igniteEntity = gunData.bulletData.igniteEntity,
            igniteBlock = gunData.bulletData.igniteBlock,
        )
        val modifiers = collectAttachmentIds(stack, iGun).mapNotNull { attachmentId ->
            attachmentDataFor(snapshot, attachmentId, exclusiveMap)
                ?.modifiers?.get("ignite")?.getValue() as? TACZIgniteValue
        }
        return TACZAttachmentModifierRegistry.evalIgnite(modifiers, defaultIgnite)
    }

    internal fun resolveInaccuracy(
        shooter: EntityLivingBase,
        stack: ItemStack,
        iGun: IGun,
        gunData: GunCombatData,
    ): Float {
        val profile = resolveInaccuracyProfile(stack, iGun)
        val stateKey = resolveInaccuracyStateKey(shooter)
        val base = profile[stateKey] ?: profile[INACCURACY_STAND] ?: 0f
        val heatMultiplier = resolveHeatInaccuracyMultiplier(stack, iGun, gunData)
        val result = (base * heatMultiplier).coerceAtLeast(0f)
        // 散布量化诊断：T23。默认关闭，加 JVM 参数 -Dtacz.spreadDebug=true 开启，
        // 用于验收 inaccuracy 默认值/配件倍率（桥的 /state 不暴露散布值）。
        if (SPREAD_DEBUG) {
            com.tacz.legacy.TACZLegacy.logger.info(
                "[spread] gun={} state={} base={} heat={} final={} profile={}",
                iGun.getGunId(stack), stateKey, base, heatMultiplier, result,
                profile.entries.joinToString(",") { "${it.key}=${it.value}" },
            )
        }
        return result
    }

    private val SPREAD_DEBUG: Boolean =
        System.getProperty("tacz.spreadDebug")?.toBooleanStrictOrNull() ?: false

    /**
     * heat 对散布的插值倍率（按过热进度在 min_inaccuracy~max_inaccuracy 间线性插值）。
     *
     * 唯一消费点是 [resolveInaccuracy]；`IGun#lerpInaccuracy` 仅为上游 API 对齐，
     * 不要再把它接进散布路径，否则会与这里的缩放叠加造成重复缩放。
     */
    internal fun resolveHeatInaccuracyMultiplier(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Float {
        if (!gunData.hasHeatData || gunData.heatMax <= 0f) {
            return 1f
        }
        val heatPercentage = (iGun.getHeatAmount(stack) / gunData.heatMax).coerceIn(0f, 1f)
        return lerp(gunData.heatMinInaccuracy, gunData.heatMaxInaccuracy, heatPercentage)
    }

    /**
     * heat 对射速的插值倍率（按过热进度在 min_rpm_mod~max_rpm_mod 间线性插值）。
     *
     * 消费点：三处 `getShootIntervalMs` 调用点（服务端射击冷却、脚本 API、客户端冷却预测）
     * 以及 `IGun#lerpRPM`；冷枪/无 heat 数据返回 1.0，不影响基线射速。
     */
    internal fun resolveHeatRpmModifier(stack: ItemStack, iGun: IGun, gunData: GunCombatData): Float {
        if (!gunData.hasHeatData || gunData.heatMax <= 0f) {
            return 1f
        }
        val heatPercentage = (iGun.getHeatAmount(stack) / gunData.heatMax).coerceIn(0f, 1f)
        return lerp(gunData.heatMinRpmModifier, gunData.heatMaxRpmModifier, heatPercentage)
    }

    internal fun resolveCameraRecoil(
        stack: ItemStack,
        iGun: IGun,
        gunData: GunCombatData,
        aimingProgress: Float,
        isCrawling: Boolean,
    ): RecoilFunctions? {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = iGun.getGunId(stack)
        val gunEntry = snapshot.guns[gunId]
        val raw = gunEntry?.data?.raw ?: return null
        val exclusiveMap = gunEntry.data.exclusiveAttachments
        val recoilObject = raw.jsonObject("recoil") ?: return null
        val recoilDefaults = parseRecoilDefaults(raw)
        // 兼容层 ③ C1-1：与 GUI 共用同一份收集逻辑
        val recoilModifiers = collectRecoilModifiers(snapshot, collectAttachmentIds(stack, iGun), exclusiveMap)
        val recoilCache = TACZAttachmentModifierRegistry.evalRecoil(recoilModifiers, recoilDefaults.first, recoilDefaults.second)
        val zoom = iGun.getAimingZoom(stack).coerceAtLeast(1f)
        val aimDenominator = min(sqrt(zoom), 1.5f)
        var aimingRecoilModifier = 1f - aimingProgress + aimingProgress / aimDenominator
        if (isCrawling) {
            aimingRecoilModifier *= gunData.crawlRecoilMultiplier
        }

        val pitchModifier = recoilCache.left().eval(aimingRecoilModifier.toDouble()).toFloat()
        val yawModifier = recoilCache.right().eval(aimingRecoilModifier.toDouble()).toFloat()
        val pitchSpline = buildRecoilSpline(recoilObject.getAsJsonArray("pitch"), pitchModifier)
        val yawSpline = buildRecoilSpline(recoilObject.getAsJsonArray("yaw"), yawModifier)
        if (pitchSpline == null && yawSpline == null) {
            return null
        }
        return RecoilFunctions(pitch = pitchSpline, yaw = yawSpline)
    }

    internal fun matchesAttachmentTag(
        attachmentId: ResourceLocation,
        tagId: ResourceLocation,
        snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot(),
    ): Boolean {
        if (attachmentId == DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return false
        }
        return matchesAttachmentEntries(snapshot, snapshot.attachmentTags[tagId] ?: return false, attachmentId, mutableSetOf())
    }

    internal fun parseRecoilDefaults(raw: JsonObject): Pair<Float, Float> {
        val recoilObject = raw.jsonObject("recoil") ?: return 0f to 0f
        return curveMagnitude(recoilObject.getAsJsonArray("pitch")) to curveMagnitude(recoilObject.getAsJsonArray("yaw"))
    }

    private fun resolveCurrentAttachmentId(stack: ItemStack, iGun: IGun, type: AttachmentType): ResourceLocation {
        val installed = iGun.getAttachmentId(stack, type)
        if (installed != DefaultAssets.EMPTY_ATTACHMENT_ID) {
            return installed
        }
        return iGun.getBuiltInAttachmentId(stack, type)
    }

    private fun resolveInaccuracyStateKey(shooter: EntityLivingBase): String {
        val operator = IGunOperator.fromLivingEntity(shooter)
        if (operator.getSynAimingProgress() == 1.0f) {
            return INACCURACY_AIM
        }
        if (operator.getDataHolder().isCrawling) {
            return INACCURACY_LIE
        }
        if (shooter.isSneaking) {
            return INACCURACY_SNEAK
        }
        if (isMoving(shooter)) {
            return INACCURACY_MOVE
        }
        return INACCURACY_STAND
    }

    // 对齐原版 InaccuracyType.isMove：服务端玩家 motionX/Z 与 posX-prevPosX 均恒为 0
    // （prevPos 在 onUpdate 开头被设为网络包更新后的 pos），改用 ShooterTickHandler
    // 每 tick 记录的上一帧位置做差值；首次无记录时回退到 motionX/Z（对怪物有效）
    private fun isMoving(shooter: EntityLivingBase): Boolean {
        val lastPos = lastTickPositions[shooter.uniqueID]
        val deltaX: Double
        val deltaZ: Double
        if (lastPos != null) {
            deltaX = shooter.posX - lastPos[0]
            deltaZ = shooter.posZ - lastPos[1]
        } else {
            deltaX = shooter.motionX
            deltaZ = shooter.motionZ
        }
        val velocity = sqrt(deltaX * deltaX + deltaZ * deltaZ)
        return velocity > 0.05
    }

    private fun matchesAttachmentEntries(
        snapshot: TACZRuntimeSnapshot,
        entries: Set<String>,
        attachmentId: ResourceLocation,
        visitedTags: MutableSet<ResourceLocation>,
    ): Boolean {
        entries.forEach { value ->
            if (value.startsWith(TAG_PREFIX)) {
                val nestedTagId = runCatching { ResourceLocation(value.substring(TAG_PREFIX.length)) }.getOrNull() ?: return@forEach
                if (!visitedTags.add(nestedTagId)) {
                    return@forEach
                }
                val nestedEntries = snapshot.attachmentTags[nestedTagId] ?: return@forEach
                if (matchesAttachmentEntries(snapshot, nestedEntries, attachmentId, visitedTags)) {
                    return true
                }
                return@forEach
            }
            val targetId = runCatching { ResourceLocation(value) }.getOrNull() ?: return@forEach
            if (targetId == attachmentId) {
                return true
            }
        }
        return false
    }

    internal fun parseFireModeAdjust(raw: JsonObject, fireMode: FireMode): FireModeAdjust {
        val modeKey = when (fireMode) {
            FireMode.AUTO -> "auto"
            FireMode.SEMI -> "semi"
            FireMode.BURST -> "burst"
            FireMode.UNKNOWN -> return FireModeAdjust()
        }
        val adjustObject = raw.jsonObject("fire_mode_adjust")?.jsonObject(modeKey) ?: return FireModeAdjust()
        return FireModeAdjust(
            damage = adjustObject.floatValue("damage"),
            rpm = adjustObject.intValue("rpm"),
            ammoSpeed = adjustObject.floatValue("speed"),
            armorIgnore = adjustObject.floatValue("armor_ignore"),
            headShot = adjustObject.floatValue("head_shot_multiplier"),
            knockback = adjustObject.floatValue("knockback"),
            aimInaccuracy = adjustObject.floatValue("aim_inaccuracy"),
            otherInaccuracy = adjustObject.floatValue("other_inaccuracy"),
        )
    }

    private fun parseInaccuracyDefaults(raw: JsonObject, adjust: FireModeAdjust): Map<String, Float> =
        parseInaccuracyDefaults(raw, adjust.otherInaccuracy, adjust.aimInaccuracy)

    /**
     * 兼容层 ③：GUI 与玩法共用的散布缺省值（同一份 [DEFAULT_INACCURACY]）。
     *
     * GUI 旧实现把缺省值全部当 0，导致枪 data 未写 `inaccuracy` 时改装台显示"腰射散布 0"，
     * 而实际按 stand=5 开火。两个入口必须共用这一份实现。
     */
    internal fun parseInaccuracyDefaults(raw: JsonObject, otherAdjust: Float, aimAdjust: Float): Map<String, Float> {
        val inaccuracy = raw.jsonObject("inaccuracy")
        // 上游 CommonGunIndex.checkInaccuracy 是逐 key 合并：
        //   defaultInaccuracy.forEach(readInaccuracy::putIfAbsent)
        // 所以 {"inaccuracy":{"aim":0.2}} 的 stand/move/sneak/lie 应取默认值，而不是塌成 0。
        // 注意本文件的 floatValue(key) 缺 key 返回 0f，这里必须用 floatValueOrNull。
        fun pick(key: String): Float = inaccuracy?.floatValueOrNull(key) ?: DEFAULT_INACCURACY[key]!!
        return linkedMapOf(
            INACCURACY_STAND to (pick(INACCURACY_STAND) + otherAdjust).coerceAtLeast(0f),
            INACCURACY_MOVE to (pick(INACCURACY_MOVE) + otherAdjust).coerceAtLeast(0f),
            INACCURACY_SNEAK to (pick(INACCURACY_SNEAK) + otherAdjust).coerceAtLeast(0f),
            INACCURACY_LIE to (pick(INACCURACY_LIE) + otherAdjust).coerceAtLeast(0f),
            INACCURACY_AIM to (pick(INACCURACY_AIM) + aimAdjust).coerceAtLeast(0f),
        )
    }

    private fun curveMagnitude(curve: JsonArray?): Float {
        val first = curve?.firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return 0f
        val values = first.getAsJsonArray("value")?.mapNotNull { value -> runCatching { abs(value.asFloat) }.getOrNull() }.orEmpty()
        return values.maxOrNull() ?: 0f
    }

    private fun buildRecoilSpline(curve: JsonArray?, modifier: Float): PolynomialSplineFunction? {
        val frames = curve
            ?.mapNotNull { element ->
                val frame = element.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val time = frame.get("time")?.takeIf { !it.isJsonNull }?.asDouble ?: return@mapNotNull null
                val values = frame.getAsJsonArray("value")?.mapNotNull { value -> runCatching { value.asDouble }.getOrNull() }.orEmpty()
                if (values.size < 2) {
                    return@mapNotNull null
                }
                RecoilKeyFrame(time = time, min = values[0], max = values[1])
            }
            .orEmpty()
        if (frames.isEmpty()) {
            return null
        }
        val times = DoubleArray(frames.size + 1)
        val values = DoubleArray(frames.size + 1)
        times[0] = 0.0
        values[0] = 0.0
        frames.forEachIndexed { index, frame ->
            times[index + 1] = frame.time * 1000.0 + 30.0
            values[index + 1] = (frame.min + Math.random() * (frame.max - frame.min)) * modifier
        }
        return RECOIL_SPLINE_INTERPOLATOR.interpolate(times, values)
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress.coerceIn(0f, 1f)
    }

    private data class RecoilKeyFrame(
        val time: Double,
        val min: Double,
        val max: Double,
    )

    private const val TAG_PREFIX: String = "#"
    private const val INACCURACY_STAND: String = "stand"
    private const val INACCURACY_MOVE: String = "move"
    private const val INACCURACY_SNEAK: String = "sneak"
    private const val INACCURACY_LIE: String = "lie"
    private const val INACCURACY_AIM: String = "aim"

    private val DEFAULT_INACCURACY: Map<String, Float> = linkedMapOf(
        INACCURACY_STAND to 5f,
        INACCURACY_MOVE to 5.75f,
        INACCURACY_SNEAK to 3.5f,
        INACCURACY_LIE to 2.5f,
        INACCURACY_AIM to 0.15f,
    )

    // ========== 兼容层 ③：配件 modifier 接入玩法 ==========

    /**
     * 兼容层 ③ C10：取"该配件在当前枪上实际生效的数据"。
     *
     * 对齐上游 exclusive_attachments 语义（`AttachmentDataUtils#getModifiers` 等 5 处）：
     * 枪的 exclusive_attachments 命中时，**整份 AttachmentData 替换**配件自身的 data；
     * 未命中才回落到配件索引里的 data。
     */
    internal fun attachmentDataFor(
        snapshot: TACZRuntimeSnapshot,
        attachmentId: ResourceLocation,
        exclusiveMap: Map<ResourceLocation, TACZAttachmentDataDefinition>,
    ): TACZAttachmentDataDefinition? =
        exclusiveMap[attachmentId] ?: snapshot.attachments[attachmentId]?.data

    /**
     * 兼容层 ③ C1-1：收集枪上所有配件对某属性的 numeric modifier（GUI 与玩法共用同一实现）
     *
     * exclusive_attachments 走 [attachmentDataFor] 的整份替换语义，不再逐字段特判。
     * 旧实现只认 ads_addend / weight 两个字段名，独占对象里写的 damage / rpm / pierce /
     * armor_ignore 等 modifier 会被整体忽略（上游不会），这是 C10 的核心差异。
     */
    internal fun collectNumericModifiers(
        snapshot: TACZRuntimeSnapshot,
        attachmentIds: List<ResourceLocation>,
        key: String,
        exclusiveMap: Map<ResourceLocation, TACZAttachmentDataDefinition> = emptyMap(),
    ): List<Modifier> {
        return attachmentIds.mapNotNull { attachmentId ->
            attachmentDataFor(snapshot, attachmentId, exclusiveMap)
                ?.modifiers?.get(key)?.getValue() as? Modifier
        }
    }

    /** 兼容层 ③ C1-1：收集配件 inaccuracy modifier（GUI 与玩法共用） */
    internal fun collectInaccuracyModifiers(
        snapshot: TACZRuntimeSnapshot,
        attachmentIds: List<ResourceLocation>,
        exclusiveMap: Map<ResourceLocation, TACZAttachmentDataDefinition> = emptyMap(),
    ): List<Map<String, Modifier>> {
        return attachmentIds.mapNotNull { attachmentId ->
            // C10：独占命中时整份替换配件 data，独占对象里的 inaccuracy_addend 由 reader 解析成完整档位表
            @Suppress("UNCHECKED_CAST")
            attachmentDataFor(snapshot, attachmentId, exclusiveMap)
                ?.modifiers?.get("inaccuracy")?.getValue() as? Map<String, Modifier>
        }
    }

    /** 兼容层 ③ C1-1：收集配件 recoil modifier（GUI 与玩法共用） */
    internal fun collectRecoilModifiers(
        snapshot: TACZRuntimeSnapshot,
        attachmentIds: List<ResourceLocation>,
        exclusiveMap: Map<ResourceLocation, TACZAttachmentDataDefinition> = emptyMap(),
    ): List<TACZRecoilModifierValue> {
        return attachmentIds.mapNotNull { attachmentId ->
            // C10：独占命中时整份替换配件 data。
            // recoil_modifier 的 pitch/yaw 语义是 percent（对齐上游 RecoilModifier.java:54-55），
            // 由 TACZRecoilModifierReader 在解析期完成区分，这里不再特判字段名。
            @Suppress("UNCHECKED_CAST")
            attachmentDataFor(snapshot, attachmentId, exclusiveMap)
                ?.modifiers?.get("recoil")?.getValue() as? TACZRecoilModifierValue
        }
    }

    /**
     * 兼容层 ③ C1-3/C1-4：按单个属性键收集当前枪配件的 numeric modifier，
     * 供玩法侧（射速、开镜时长等）eval 后覆盖基准值。无枪包数据或无配件时返回空列表。
     */
    internal fun collectGunNumericModifiers(stack: ItemStack, iGun: IGun, key: String): List<Modifier> {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val exclusiveMap = snapshot.guns[iGun.getGunId(stack)]?.data?.exclusiveAttachments ?: return emptyList()
        val attachmentIds = collectAttachmentIds(stack, iGun)
        if (attachmentIds.isEmpty()) {
            return emptyList()
        }
        return collectNumericModifiers(snapshot, attachmentIds, key, exclusiveMap)
    }

    /** 收集枪上所有配件的战斗属性 modifier（armor_ignore/head_shot/pierce/knockback/damage/ammo_speed） */
    internal data class AttachmentModifierLists(
        val armorIgnore: List<Modifier>,
        val headShot: List<Modifier>,
        val pierce: List<Modifier>,
        val knockback: List<Modifier>,
        val damage: List<Modifier>,
        val ammoSpeed: List<Modifier>,
        val effectiveRange: List<Modifier>,
        // 兼容层 ③ C2-exp：explosion 在解析期已由 TACZExplosionModifierReader 转成 typed 值
        val explosion: List<TACZExplosionModifierValue>,
    )

    /** 收集配件 modifier，供玩法侧 eval 后覆盖子弹属性 */
    internal fun collectAttachmentModifiers(
        stack: ItemStack,
        iGun: IGun,
    ): AttachmentModifierLists? {
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = iGun.getGunId(stack)
        val gunEntry = snapshot.guns[gunId] ?: return null
        val exclusiveMap = gunEntry.data.exclusiveAttachments
        val attachmentIds = collectAttachmentIds(stack, iGun)
        if (attachmentIds.isEmpty()) return null
        return AttachmentModifierLists(
            armorIgnore = collectNumericModifiers(snapshot, attachmentIds, "armor_ignore", exclusiveMap),
            headShot = collectNumericModifiers(snapshot, attachmentIds, "head_shot", exclusiveMap),
            pierce = collectNumericModifiers(snapshot, attachmentIds, "pierce", exclusiveMap),
            knockback = collectNumericModifiers(snapshot, attachmentIds, "knockback", exclusiveMap),
            // 兼容层 ③ C1-2/C1-5：damage 与 ammo_speed 一并收集，射击路径共用一次收集结果
            damage = collectNumericModifiers(snapshot, attachmentIds, "damage", exclusiveMap),
            ammoSpeed = collectNumericModifiers(snapshot, attachmentIds, "ammo_speed", exclusiveMap),
            // 兼容层 ③ C2-ext：effective_range 影响距离衰减曲线第一段的有效射程
            effectiveRange = collectNumericModifiers(snapshot, attachmentIds, "effective_range", exclusiveMap),
            // 兼容层 ③ C2-exp：explosion 布尔 OR + 三组 Modifier（配件可点亮爆炸）
            // C10：独占数据同样整份替换，故统一走 attachmentDataFor 的生效 data
            explosion = attachmentIds.mapNotNull { attachmentId ->
                attachmentDataFor(snapshot, attachmentId, exclusiveMap)
                    ?.modifiers?.get("explosion")?.getValue() as? TACZExplosionModifierValue
            },
        )
    }
}

private fun JsonObject.jsonObject(key: String): JsonObject? =
    get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.floatValue(key: String): Float =
    get(key)?.takeIf { !it.isJsonNull }?.asFloat ?: 0f

/** 与 [floatValue] 的区别：缺 key 返回 null，用于"逐 key 缺省"场景。 */
private fun JsonObject.floatValueOrNull(key: String): Float? =
    get(key)?.takeIf { !it.isJsonNull }?.asFloat

private fun JsonObject.intValue(key: String): Int =
    get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0