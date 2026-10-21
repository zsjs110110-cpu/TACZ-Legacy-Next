package com.tacz.legacy.common.foundation

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.DefaultAssets
import com.tacz.legacy.api.event.EntityHurtByGunEvent
import com.tacz.legacy.api.event.EntityKillByGunEvent
import com.tacz.legacy.api.event.GunFireEvent
import com.tacz.legacy.api.event.GunShootEvent
import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.api.item.gun.FireMode
import com.tacz.legacy.common.entity.EntityKineticBullet
import net.minecraft.entity.monster.EntityPigZombie
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.resource.BoltType
import com.tacz.legacy.common.resource.GunCombatData
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZDisplayDefinition
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZLoadedAttachment
import com.tacz.legacy.common.resource.TACZLoadedGun
import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.util.ResourceLocation
import net.minecraftforge.event.entity.EntityJoinWorldEvent
import net.minecraftforge.event.world.ExplosionEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.PlayerEvent
import net.minecraftforge.fml.relauncher.Side
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class FocusedSmokeDisplaySummary(
    val displayId: ResourceLocation,
    val defaultAnimationType: String?,
    val defaultAnimationId: ResourceLocation?,
    val stateMachineId: ResourceLocation?,
    val soundKeys: List<String>,
) {
    fun hasAnimationSignals(): Boolean =
        defaultAnimationType != null || defaultAnimationId != null || stateMachineId != null || soundKeys.isNotEmpty()
}

internal data class FocusedSmokePlan(
    val regularGunId: ResourceLocation,
    val explosiveGunId: ResourceLocation?,
    val attachmentIds: List<ResourceLocation>,
    val regularDisplay: FocusedSmokeDisplaySummary?,
    val explosiveDisplay: FocusedSmokeDisplaySummary?,
) {
    fun attachmentSummary(): String = attachmentIds.joinToString(",").ifBlank { "none" }
}

private data class FocusedSmokeAttachmentCandidate(
    val attachment: TACZLoadedAttachment,
    val priority: Int,
) {
    val id: ResourceLocation
        get() = attachment.id

    val typeKey: String
        get() = attachment.index.type.lowercase()
}

internal object FocusedSmokePlanner {
    fun buildPlan(snapshot: TACZRuntimeSnapshot): FocusedSmokePlan? {
        val attachments = TACZGunPackPresentation.sortedAttachments(snapshot)
        val gunCandidates = TACZGunPackPresentation.sortedGuns(snapshot).map { gun ->
            val combatData = GunCombatData.fromRawJson(gun.data.raw, gun.data)
            val displaySummary = describeGunDisplay(snapshot, gun.id)
            val compatibleAttachments = selectAttachmentsForGun(snapshot, gun.id, attachments)
            FocusedSmokeGunCandidate(
                gun = gun,
                combatData = combatData,
                displaySummary = displaySummary,
                compatibleAttachments = compatibleAttachments,
            )
        }

        val regular = gunCandidates
            .asSequence()
            .filterNot(FocusedSmokeGunCandidate::isExplosive)
            .sortedWith(
                compareByDescending<FocusedSmokeGunCandidate> { it.regularPriority }
                    .thenBy { it.gun.index.sort }
                    .thenBy { it.gun.id.toString() }
            )
            .firstOrNull()
            ?: return null

        val explosive = gunCandidates
            .filter(FocusedSmokeGunCandidate::isExplosive)
            .maxByOrNull(FocusedSmokeGunCandidate::explosivePriority)

        return FocusedSmokePlan(
            regularGunId = regular.gun.id,
            explosiveGunId = explosive?.gun?.id,
            attachmentIds = regular.compatibleAttachments.map(FocusedSmokeAttachmentCandidate::id),
            regularDisplay = regular.displaySummary,
            explosiveDisplay = explosive?.displaySummary,
        )
    }

    private fun selectAttachmentsForGun(
        snapshot: TACZRuntimeSnapshot,
        gunId: ResourceLocation,
        attachments: List<TACZLoadedAttachment>,
    ): List<FocusedSmokeAttachmentCandidate> {
        val compatible = attachments
            .asSequence()
            .filter { attachment -> TACZGunPackPresentation.allowsAttachment(snapshot, gunId, attachment.id) }
            .map { attachment ->
                FocusedSmokeAttachmentCandidate(
                    attachment = attachment,
                    priority = scoreAttachment(snapshot, attachment),
                )
            }
            .sortedWith(
                compareByDescending<FocusedSmokeAttachmentCandidate> { it.priority }
                    .thenBy { it.attachment.index.sort }
                    .thenBy { it.id.toString() }
            )
            .toList()
        val preferred = compatible
            .asSequence()
            .filter { it.priority > 0 }
            .distinctBy(FocusedSmokeAttachmentCandidate::typeKey)
            .take(MAX_RENDER_ATTACHMENT_SAMPLES)
            .toList()
        return if (preferred.isNotEmpty()) preferred else compatible.take(1)
    }

    private fun scoreAttachment(snapshot: TACZRuntimeSnapshot, attachment: TACZLoadedAttachment): Int {
        val type = attachment.index.type.lowercase()
        val displayId = TACZGunPackPresentation.resolveAttachmentDisplayId(snapshot, attachment.id)
        val raw = displayId?.let(snapshot.attachmentDisplays::get)?.raw
        var score = 0
        if (displayId != null) {
            score += 20
        }
        if (raw?.safeBoolean("scope") == true) {
            score += 1000
        }
        if (type == AttachmentType.SCOPE.serializedName) {
            score += 650
        }
        if (!raw?.safeString("adapter").isNullOrBlank()) {
            score += 500
        }
        if (raw?.safeBoolean("show_muzzle") == true) {
            score += 360
        }
        if (raw?.safeBoolean("sight") == true) {
            score += 220
        }
        if ((raw?.jsonArraySize("views") ?: 0) > 0) {
            score += 180
        }
        if ((raw?.jsonArraySize("zoom") ?: 0) > 0) {
            score += 140
        }
        if (type == AttachmentType.MUZZLE.serializedName) {
            score += 320
        }
        if (type == AttachmentType.GRIP.serializedName) {
            score += 280
        }
        if (type == AttachmentType.STOCK.serializedName) {
            score += 260
        }
        return score
    }

    fun describeGunDisplay(snapshot: TACZRuntimeSnapshot, gunId: ResourceLocation): FocusedSmokeDisplaySummary? {
        val displayId = TACZGunPackPresentation.resolveGunDisplayId(snapshot, gunId) ?: return null
        return describeDisplay(snapshot.gunDisplays[displayId])
    }

    private fun describeDisplay(definition: TACZDisplayDefinition?): FocusedSmokeDisplaySummary? {
        val raw = definition?.raw ?: return null
        val displayId = definition.id
        val sounds = raw.safeObject("sounds")
            ?.entrySet()
            ?.map { it.key }
            ?.sorted()
            .orEmpty()
        return FocusedSmokeDisplaySummary(
            displayId = displayId,
            defaultAnimationType = raw.safeString("use_default_animation"),
            defaultAnimationId = raw.safeResourceLocation("default_animation"),
            stateMachineId = raw.safeResourceLocation("state_machine"),
            soundKeys = sounds,
        )
    }

    private data class FocusedSmokeGunCandidate(
        val gun: TACZLoadedGun,
        val combatData: GunCombatData,
        val displaySummary: FocusedSmokeDisplaySummary?,
        val compatibleAttachments: List<FocusedSmokeAttachmentCandidate>,
    ) {
        val isExplosive: Boolean
            get() = combatData.bulletData.explosionData?.explode == true

        val regularPriority: Int
            get() {
                var score = 0
                if (displaySummary?.hasAnimationSignals() == true) {
                    score += 100_000
                }
                if (combatData.boltType != BoltType.MANUAL_ACTION) {
                    score += 10_000
                }
                if (compatibleAttachments.isNotEmpty()) {
                    score += 1_000
                }
                score += compatibleAttachments.sumOf(FocusedSmokeAttachmentCandidate::priority).coerceAtMost(5_000)
                return score
            }

        val explosivePriority: Int
            get() {
                val tags = buildList {
                    add(gun.id.path)
                    add(gun.index.type)
                    add(displaySummary?.displayId?.path)
                }.filterNotNull().joinToString(" ").lowercase()
                val keywordScore = when {
                    "rpg" in tags || "rocket" in tags -> 300
                    "heavy" in tags -> 220
                    "launcher" in tags || "grenade" in tags -> 160
                    else -> 0
                }
                val animationScore = if (displaySummary?.hasAnimationSignals() == true) 10 else 0
                return keywordScore + animationScore
            }
    }

    private const val MAX_RENDER_ATTACHMENT_SAMPLES: Int = 4
}

internal object FocusedSmokeRuntime {
    private const val ENABLED_PROPERTY: String = "tacz.focusedSmoke"
    private const val AUTO_WORLD_PROPERTY: String = "tacz.focusedSmoke.autoWorld"
    private const val REGULAR_GUN_PROPERTY: String = "tacz.focusedSmoke.regularGun"
    private const val WORLD_FOLDER_PROPERTY: String = "tacz.focusedSmoke.worldFolder"
    private const val WORLD_NAME_PROPERTY: String = "tacz.focusedSmoke.worldName"
    private const val WORLD_TIME_PROPERTY: String = "tacz.focusedSmoke.worldTime"
    private const val FREEZE_WORLD_TIME_PROPERTY: String = "tacz.focusedSmoke.freezeWorldTime"
    private const val CLEAR_WEATHER_PROPERTY: String = "tacz.focusedSmoke.clearWeather"
    private const val EXPLOSIVE_GUN_PROPERTY: String = "tacz.focusedSmoke.explosiveGun"
    private const val DISABLE_EXPLOSIVE_PROPERTY: String = "tacz.focusedSmoke.disableExplosive"
    private const val DISABLE_ATTACHMENTS_PROPERTY: String = "tacz.focusedSmoke.disableAttachments"
    private const val REFIT_PREVIEW_PROPERTY: String = "tacz.focusedSmoke.refitPreview"
    private const val REFIT_ATTACHMENT_PROPERTY: String = "tacz.focusedSmoke.refitAttachment"
    private const val AUTO_AIM_PROPERTY: String = "tacz.focusedSmoke.autoAim"
    private const val PASS_AFTER_ANIMATION_PROPERTY: String = "tacz.focusedSmoke.passAfterAnimation"
    private const val PASS_AFTER_AIM_PROPERTY: String = "tacz.focusedSmoke.passAfterAim"
    private const val PASS_AFTER_REFIT_PROPERTY: String = "tacz.focusedSmoke.passAfterRefit"
    private const val REQUIRE_TRACER_FRAME_PROPERTY: String = "tacz.focusedSmoke.requireTracerFrame"
    private const val SKIP_INSPECT_PROPERTY: String = "tacz.focusedSmoke.skipInspect"
    private const val SKIP_RELOAD_PROPERTY: String = "tacz.focusedSmoke.skipReload"
    private const val REGULAR_SHOT_PITCH_PROPERTY: String = "tacz.focusedSmoke.regularShotPitch"
    private const val REGULAR_SHOT_YAW_PROPERTY: String = "tacz.focusedSmoke.regularShotYaw"
    private const val BULLET_SPEED_MULTIPLIER_PROPERTY: String = "tacz.focusedSmoke.bulletSpeedMultiplier"
    private const val TRACER_SIZE_MULTIPLIER_PROPERTY: String = "tacz.focusedSmoke.tracerSizeMultiplier"
    private const val TRACER_LENGTH_MULTIPLIER_PROPERTY: String = "tacz.focusedSmoke.tracerLengthMultiplier"
    private const val HIT_FEEDBACK_TARGET_PROPERTY: String = "tacz.focusedSmoke.hitFeedbackTarget"
    private const val DUPLICATE_GUN_PROPERTY: String = "tacz.focusedSmoke.duplicateGun"
    internal const val DUPLICATE_GUN_SLOT: Int = 2
    private const val GHOST_STRESS_PROPERTY: String = "tacz.focusedSmoke.ghostShotStress"
    private const val REAL_AMMO_PROPERTY: String = "tacz.focusedSmoke.realAmmo"
    private const val EMPTY_START_PROPERTY: String = "tacz.focusedSmoke.emptyStart"
    private const val CRAFT_PROPERTY: String = "tacz.focusedSmoke.craft"
    internal const val REAL_AMMO_FIRST_SLOT: Int = 9
    internal const val GHOST_STRESS_GUN_SLOT: Int = 3

    private val loggedKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var plannedScenario: FocusedSmokePlan? = null

    @Volatile
    private var focusedPlayerId: UUID? = null

    @Volatile
    internal var serverGearReady: Boolean = false
        private set

    @Volatile
    internal var animationObserved: Boolean = false
        private set

    @Volatile
    internal var regularProjectileObserved: Boolean = false
        private set

    /** 启用命中目标时，目标是否真的受到了枪械伤害（服务端事件） */
    @Volatile
    internal var hitTargetDamaged: Boolean = false
    @Volatile
    private var hitTargetEntityId: Int = -1

    /** 未启用命中目标时恒为 true；启用时要求目标确实受到伤害 */
    internal fun hitTargetSatisfied(): Boolean = !hitFeedbackTargetEnabled || hitTargetDamaged

    @Volatile
    private var ghostStressCompleted: Boolean = false

    /** 客户端完成 ghost shot 压力测试后调用（集成服务端与客户端同一 JVM） */
    internal fun notifyGhostStressCompleted() {
        ghostStressCompleted = true
        maybeLogPass()
    }

    @Volatile
    internal var tracerFrameObserved: Boolean = false
        private set

    @Volatile
    internal var regularGunFireCount: Int = 0
        private set

    @Volatile
    internal var audioPlaybackObserved: Boolean = false
        private set

    @Volatile
    internal var explosiveProjectileObserved: Boolean = false
        private set

    @Volatile
    internal var explosionObserved: Boolean = false
        private set

    @Volatile
    private var explosiveProjectileSeenAtMs: Long = 0L

    @Volatile
    private var passLogged: Boolean = false

    @Volatile
    private var failureReason: String? = null

    @Volatile
    private var lastAnimationDetails: String? = null

    @Volatile
    private var forcedAimActive: Boolean = false

    internal val enabled: Boolean
        get() = System.getProperty(ENABLED_PROPERTY, "false").toBoolean()

    internal val autoWorldLaunchEnabled: Boolean
        get() = System.getProperty(AUTO_WORLD_PROPERTY, "true").toBoolean()

    internal val worldFolderName: String
        get() = System.getProperty(WORLD_FOLDER_PROPERTY, "tacz_focused_smoke_auto")

    internal val worldDisplayName: String
        get() = System.getProperty(WORLD_NAME_PROPERTY, "tacz_focused_smoke_auto")

    internal val forcedWorldTime: Long?
        get() = System.getProperty(WORLD_TIME_PROPERTY)?.toLongOrNull()

    internal val freezeWorldTimeEnabled: Boolean
        get() = System.getProperty(FREEZE_WORLD_TIME_PROPERTY, if (forcedWorldTime != null) "true" else "false").toBoolean()

    internal val clearWeatherEnabled: Boolean
        get() = System.getProperty(CLEAR_WEATHER_PROPERTY, if (forcedWorldTime != null) "true" else "false").toBoolean()

    internal val forcedRegularGunId: ResourceLocation?
        get() = parseResourceLocation(System.getProperty(REGULAR_GUN_PROPERTY))

    internal val forcedExplosiveGunId: ResourceLocation?
        get() = parseResourceLocation(System.getProperty(EXPLOSIVE_GUN_PROPERTY))

    internal val disableExplosive: Boolean
        get() = System.getProperty(DISABLE_EXPLOSIVE_PROPERTY, "false").toBoolean()

    internal val disableAttachments: Boolean
        get() = System.getProperty(DISABLE_ATTACHMENTS_PROPERTY, "false").toBoolean()

    internal val refitPreviewEnabled: Boolean
        get() = System.getProperty(REFIT_PREVIEW_PROPERTY, "false").toBoolean()

    internal val preferredRefitAttachmentId: ResourceLocation?
        get() = parseResourceLocation(System.getProperty(REFIT_ATTACHMENT_PROPERTY))

    internal val autoAimEnabled: Boolean
        get() = System.getProperty(AUTO_AIM_PROPERTY, "false").toBoolean()

    internal val passAfterAnimationEnabled: Boolean
        get() = System.getProperty(PASS_AFTER_ANIMATION_PROPERTY, "false").toBoolean()

    internal val passAfterAimEnabled: Boolean
        get() = System.getProperty(PASS_AFTER_AIM_PROPERTY, "false").toBoolean()

    internal val passAfterRefitEnabled: Boolean
        get() = System.getProperty(PASS_AFTER_REFIT_PROPERTY, "false").toBoolean()

    internal val skipInspectEnabled: Boolean
        get() = System.getProperty(SKIP_INSPECT_PROPERTY, "false").toBoolean()

    internal val skipReloadEnabled: Boolean
        get() = System.getProperty(SKIP_RELOAD_PROPERTY, "false").toBoolean()

    internal val requireTracerFrameEnabled: Boolean
        get() = System.getProperty(REQUIRE_TRACER_FRAME_PROPERTY, "false").toBoolean()

    internal val regularShotPitchOverride: Float?
        get() = System.getProperty(REGULAR_SHOT_PITCH_PROPERTY)?.toFloatOrNull()

    internal val regularShotYawOverride: Float?
        get() = System.getProperty(REGULAR_SHOT_YAW_PROPERTY)?.toFloatOrNull()

    /** 在 2 号快捷栏放一把与常规枪完全相同的枪，并用它完成常规射击（回归：背包中有两把相同的枪时无法开火）。 */
    internal val duplicateGunEnabled: Boolean
        get() = java.lang.Boolean.getBoolean(DUPLICATE_GUN_PROPERTY)

    /** Ghost shot 压力测试：额外放一把装满的同款枪到 3 号槽，并在常规射击后反复“疾跑→开火 / 切枪→开火” */
    /** 不使用 dummy 弹药，而是在背包放入真实弹药物品，验证换弹时消耗背包弹药的生存模式流程 */
    internal val realAmmoEnabled: Boolean
        get() = java.lang.Boolean.getBoolean(REAL_AMMO_PROPERTY)

    /** 常规枪以完全打空（弹匣 0、枪膛无弹）的状态开始，验证空仓换弹 */
    /** 放置枪械工作台、通过真实 GUI/网络流程制作一把枪，验证材料消耗与成品发放 */
    internal val craftEnabled: Boolean
        get() = java.lang.Boolean.getBoolean(CRAFT_PROPERTY)

    @Volatile
    internal var craftTablePos: net.minecraft.util.math.BlockPos? = null
        private set
    @Volatile
    internal var craftRecipe: com.tacz.legacy.common.application.gunsmith.LegacyGunSmithRecipe? = null
        private set
    @Volatile
    private var craftCompleted: Boolean = false

    @Volatile
    private var craftStructurePositions: List<net.minecraft.util.math.BlockPos> = emptyList()

    /**
     * 制作完成后在服务端线程验证多方块工作台的破坏：以生存模式挖掉离主方块最远的部位，
     * 其余部位应全部随之消失，并且只掉落一个带 BlockId 的工作台物品。
     */
    internal fun notifyCraftCompleted() {
        val server = net.minecraftforge.fml.common.FMLCommonHandler.instance().minecraftServerInstance
        val playerId = focusedPlayerId
        val mainPos = craftTablePos
        val structure = craftStructurePositions
        if (server == null || playerId == null || mainPos == null || structure.size < 2) {
            craftCompleted = true
            maybeLogPass()
            return
        }
        server.addScheduledTask {
            val player = server.playerList.getPlayerByUUID(playerId)
            if (player == null) {
                markFailure("craft_break_player_missing")
                return@addScheduledTask
            }
            val world = player.serverWorld
            val searchBox = net.minecraft.util.math.AxisAlignedBB(mainPos).grow(3.0)
            val dropsBefore = world.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem::class.java, searchBox).size
            player.interactionManager.tryHarvestBlock(structure.last())
            val drops = world.getEntitiesWithinAABB(net.minecraft.entity.item.EntityItem::class.java, searchBox)
                .filter { it.item.item === com.tacz.legacy.common.item.LegacyItems.GUN_SMITH_TABLE }
            val remaining = structure.filterNot { world.isAirBlock(it) }
            val creative = player.capabilities.isCreativeMode
            log("CRAFT_TABLE_BREAK broken=${structure.last()} remaining=$remaining tableDrops=${drops.size} dropsBefore=$dropsBefore creative=$creative")
            if (remaining.isNotEmpty()) {
                markFailure("craft_table_break_incomplete")
                return@addScheduledTask
            }
            if (!creative && drops.size != 1) {
                markFailure("craft_table_break_drops_${drops.size}")
                return@addScheduledTask
            }
            craftCompleted = true
            maybeLogPass()
        }
    }

    internal val emptyStartEnabled: Boolean
        get() = java.lang.Boolean.getBoolean(EMPTY_START_PROPERTY)

    internal val ghostShotStressEnabled: Boolean
        get() = java.lang.Boolean.getBoolean(GHOST_STRESS_PROPERTY)

    internal val hitFeedbackTargetEnabled: Boolean
        get() = System.getProperty(HIT_FEEDBACK_TARGET_PROPERTY, "false").toBoolean()

    internal val bulletSpeedMultiplier: Float
        get() = parsePositiveFloatProperty(BULLET_SPEED_MULTIPLIER_PROPERTY)

    internal val tracerSizeMultiplier: Float
        get() = parsePositiveFloatProperty(TRACER_SIZE_MULTIPLIER_PROPERTY)

    internal val tracerLengthMultiplier: Float
        get() = parsePositiveFloatProperty(TRACER_LENGTH_MULTIPLIER_PROPERTY)

    internal val isForcedAimActive: Boolean
        get() = enabled && forcedAimActive

    internal fun setForcedAimActive(active: Boolean) {
        forcedAimActive = enabled && active
    }

    @Synchronized
    internal fun currentPlan(snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot()): FocusedSmokePlan? {
        if (!enabled) {
            return null
        }
        val cached = plannedScenario
        if (cached != null) {
            return cached
        }
        val built = FocusedSmokePlanner.buildPlan(snapshot)?.let { applyOverrides(snapshot, it) }
        if (built == null) {
            logOnce("plan-missing", "FAIL reason=no_regular_gun_plan")
        }
        plannedScenario = built
        return built
    }

    @Synchronized
    internal fun resetScenarioState() {
        plannedScenario = null
        focusedPlayerId = null
        serverGearReady = false
        animationObserved = false
        regularProjectileObserved = false
        hitTargetDamaged = false
        hitTargetEntityId = -1
        tracerFrameObserved = false
        regularGunFireCount = 0
        audioPlaybackObserved = false
        explosiveProjectileObserved = false
        explosionObserved = false
        explosiveProjectileSeenAtMs = 0L
        passLogged = false
        failureReason = null
        lastAnimationDetails = null
        forcedAimActive = false
        ghostStressCompleted = false
        craftCompleted = false
        craftTablePos = null
        craftRecipe = null
        craftStructurePositions = emptyList()
        loggedKeys.clear()
    }

    private fun applyOverrides(snapshot: TACZRuntimeSnapshot, plan: FocusedSmokePlan): FocusedSmokePlan {
        var updatedPlan = plan
        if (disableAttachments && updatedPlan.attachmentIds.isNotEmpty()) {
            logOnce("attachments-disabled", "ATTACHMENTS_DISABLED for_focused_smoke")
            updatedPlan = updatedPlan.copy(attachmentIds = emptyList())
        }

        forcedRegularGunId?.let { forcedRegular ->
            if (!snapshot.guns.containsKey(forcedRegular)) {
                logOnce("forced-regular-missing", "REGULAR_OVERRIDE_MISSING gun=$forcedRegular")
            } else {
                logOnce("forced-regular-using", "REGULAR_OVERRIDE gun=$forcedRegular")
                updatedPlan = updatedPlan.copy(
                    regularGunId = forcedRegular,
                    attachmentIds = emptyList(),
                    regularDisplay = FocusedSmokePlanner.describeGunDisplay(snapshot, forcedRegular),
                )
            }
        }

        if (disableExplosive) {
            if (updatedPlan.explosiveGunId != null) {
                logOnce("explosive-disabled", "EXPLOSIVE_DISABLED for_focused_smoke")
            }
            return updatedPlan.copy(explosiveGunId = null, explosiveDisplay = null)
        }

        val forcedExplosive = forcedExplosiveGunId ?: return updatedPlan
        if (!snapshot.guns.containsKey(forcedExplosive)) {
            logOnce("forced-explosive-missing", "EXPLOSIVE_OVERRIDE_MISSING gun=$forcedExplosive")
            return updatedPlan
        }
        logOnce("forced-explosive-using", "EXPLOSIVE_OVERRIDE gun=$forcedExplosive")
        return updatedPlan.copy(
            explosiveGunId = forcedExplosive,
            explosiveDisplay = FocusedSmokePlanner.describeGunDisplay(snapshot, forcedExplosive),
        )
    }

    private fun parseResourceLocation(value: String?): ResourceLocation? {
        if (value.isNullOrBlank()) {
            return null
        }
        return runCatching { ResourceLocation(value.trim()) }.getOrNull()
    }

    private fun parsePositiveFloatProperty(name: String): Float {
        val rawValue = System.getProperty(name)?.toFloatOrNull() ?: return 1.0f
        if (!rawValue.isFinite() || rawValue <= 0.0f) {
            return 1.0f
        }
        return rawValue
    }

    internal fun rememberFocusedPlayer(player: EntityPlayerMP) {
        if (!enabled) {
            return
        }
        focusedPlayerId = player.uniqueID
    }

    internal fun markAnimationObserved(details: String) {
        if (!enabled) {
            return
        }
        animationObserved = true
        lastAnimationDetails = details
        logOnce("animation-observed", "ANIMATION_OBSERVED $details")
        if (passAfterAnimationEnabled) {
            forcePass("animation_only")
            return
        }
        maybeLogPass()
    }

    internal fun markLeftClickSuppressed(details: String) {
        if (!enabled) {
            return
        }
        logOnce("left-click-suppressed", "LEFT_CLICK_SUPPRESSED $details")
    }

    internal fun markRegularShootResult(result: String, gunId: ResourceLocation) {
        if (!enabled) {
            return
        }
        log("REGULAR_SHOOT_RESULT gun=$gunId result=$result")
    }

    internal fun markTracerFrameObserved(details: String) {
        if (!enabled) {
            return
        }
        tracerFrameObserved = true
        logOnce("tracer-frame-observed", "TRACER_FRAME_OBSERVED $details")
        maybeLogPass()
    }

    internal fun markAudioPlaybackObserved(details: String = "") {
        if (!enabled) {
            return
        }
        audioPlaybackObserved = true
        logOnce("audio-playback", "AUDIO_PLAYBACK_OBSERVED $details".trim())
    }

    internal fun expectedRegularFireCount(): Int = 1

    internal fun hasObservedExpectedRegularFireCount(): Boolean =
        regularGunFireCount >= expectedRegularFireCount()

    internal fun markExplosiveShootResult(result: String, gunId: ResourceLocation) {
        if (!enabled) {
            return
        }
        log("EXPLOSIVE_SHOOT_RESULT gun=$gunId result=$result")
    }

    internal fun markFailure(reason: String) {
        if (!enabled || failureReason != null) {
            return
        }
        failureReason = reason
        log("FAIL reason=$reason")
    }

    internal fun hasFailed(): Boolean = failureReason != null

    internal fun hasPassed(): Boolean = passLogged

    internal fun forcePass(mode: String) {
        if (!enabled || passLogged) {
            return
        }
        passLogged = true
        val plan = plannedScenario
        val attachment = plan?.attachmentSummary() ?: "skipped"
        val explosive = plan?.explosiveGunId?.toString() ?: "skipped"
        log(
            "PASS mode=$mode animation=$animationObserved projectile=$regularProjectileObserved " +
                "explosion=${plan?.explosiveGunId == null || explosionObserved} regularGun=${plan?.regularGunId ?: "unknown"} " +
                "explosiveGun=$explosive attachment=$attachment"
        )
    }

    internal fun statusSummary(): String {
        val plan = plannedScenario
        return buildString {
            append("enabled=").append(enabled)
            append(", gear=").append(serverGearReady)
            append(", animation=").append(animationObserved)
            append(", projectile=").append(regularProjectileObserved)
            append(", tracerFrame=").append(tracerFrameObserved)
            append(", explosion=").append(explosionObserved)
            append(", plan=").append(plan?.regularGunId ?: "<none>")
            plan?.explosiveGunId?.let { append("/").append(it) }
            failureReason?.let { append(", failure=").append(it) }
        }
    }

    private fun maybeLogPass() {
        if (!enabled || passLogged) {
            return
        }
        val plan = plannedScenario ?: return
        if (!animationObserved || !regularProjectileObserved || !hitTargetSatisfied()) {
            return
        }
        if (ghostShotStressEnabled && !ghostStressCompleted) {
            return
        }
        if (craftEnabled && !craftCompleted) {
            return
        }
        if (requireTracerFrameEnabled && !tracerFrameObserved) {
            return
        }
        if (plan.explosiveGunId != null && !explosionObserved) {
            return
        }
        passLogged = true
        val attachment = plan.attachmentSummary()
        val explosive = plan.explosiveGunId?.toString() ?: "skipped"
        log("PASS animation=true projectile=true explosion=${plan.explosiveGunId == null || explosionObserved} regularGun=${plan.regularGunId} explosiveGun=$explosive attachment=$attachment")
    }

    private fun log(message: String) {
        TACZLegacy.logger.info("[FocusedSmoke] {}", message)
    }

    private fun logOnce(key: String, message: String) {
        if (loggedKeys.add(key)) {
            log(message)
        }
    }

    private fun matchesFocusedPlayer(entityId: UUID?): Boolean {
        val expected = focusedPlayerId ?: return false
        return entityId == expected
    }

    private fun prepareSmokeGear(player: EntityPlayerMP, plan: FocusedSmokePlan) {
        val regularStack = createGunStack(plan.regularGunId)
        if (plan.attachmentIds.isNotEmpty()) {
            val iGun = regularStack.item as? IGun
            plan.attachmentIds.forEach { attachmentId ->
                val attachmentStack = ItemStack(LegacyItems.ATTACHMENT)
                LegacyItems.ATTACHMENT.setAttachmentId(attachmentStack, attachmentId)
                iGun?.installAttachment(regularStack, attachmentStack)
            }
        }
        player.inventory.setInventorySlotContents(0, regularStack)
        if (craftEnabled) {
            prepareCraftScenario(player)
        }
        if (emptyStartEnabled) {
            (regularStack.item as? IGun)?.let { gun ->
                gun.setCurrentAmmoCount(regularStack, 0)
                gun.setBulletInBarrel(regularStack, false)
            }
        }
        if (realAmmoEnabled) {
            regularStack.tagCompound?.removeTag(IGun.DUMMY_AMMO_TAG)
            val ammoId = GunDataAccessor.getGunData(plan.regularGunId)?.ammoId
            if (ammoId != null) {
                // 放两组真实弹药（每组按该弹药的最大堆叠数），用于验证换弹消耗
                repeat(2) { index ->
                    val ammoStack = ItemStack(LegacyItems.AMMO)
                    LegacyItems.AMMO.setAmmoId(ammoStack, ammoId)
                    ammoStack.count = ammoStack.maxStackSize
                    player.inventory.setInventorySlotContents(REAL_AMMO_FIRST_SLOT + index, ammoStack)
                }
            }
        }
        if (ghostShotStressEnabled) {
            player.inventory.setInventorySlotContents(GHOST_STRESS_GUN_SLOT, regularStack.copy())
        }
        if (duplicateGunEnabled) {
            // 复制品去掉 dummy 弹药，和从创造栏/工作台得到的普通枪一致
            val duplicate = regularStack.copy()
            duplicate.tagCompound?.removeTag(IGun.DUMMY_AMMO_TAG)
            player.inventory.setInventorySlotContents(DUPLICATE_GUN_SLOT, duplicate)
            // 0 号槽的枪打空（弹匣与枪膛都没有子弹），复现“同款枪打空后切到另一把同款枪”
            (regularStack.item as? IGun)?.let { emptiedGun ->
                emptiedGun.setCurrentAmmoCount(regularStack, 0)
                emptiedGun.setBulletInBarrel(regularStack, false)
                if (emptiedGun.useDummyAmmo(regularStack)) {
                    emptiedGun.setDummyAmmoAmount(regularStack, 0)
                }
            }
        }

        plan.explosiveGunId?.let { explosiveGunId ->
            player.inventory.setInventorySlotContents(1, createGunStack(explosiveGunId, fullyLoaded = true))
        }

        player.inventory.markDirty()
        player.inventoryContainer.detectAndSendChanges()
        if (hitFeedbackTargetEnabled) {
            spawnHitFeedbackTarget(player, plan.regularGunId)
        }
        applyWorldVisualOverrides(player.server)
        logCaptureOverrides()
        serverGearReady = true
        val attachmentText = plan.attachmentSummary()
        logOnce(
            "server-gear-ready",
            "SERVER_GEAR_READY regularGun=${plan.regularGunId} explosiveGun=${plan.explosiveGunId ?: "none"} attachment=$attachmentText"
        )
    }

    private fun prepareCraftScenario(player: EntityPlayerMP) {
        val blockId = com.tacz.legacy.api.DefaultAssets.DEFAULT_BLOCK_ID
        val recipe = com.tacz.legacy.common.application.gunsmith.LegacyGunSmithingRuntime.visibleRecipes(
            blockId = blockId,
            selectedTab = null,
            selectedNamespaces = emptySet(),
            searchText = "",
            heldStack = ItemStack.EMPTY,
            byHandOnly = false,
        ).firstOrNull { candidate ->
            candidate.result.item is IGun && candidate.materials.isNotEmpty() &&
                candidate.materials.all { it.ingredient.getMatchingStacks().isNotEmpty() }
        }
        if (recipe == null) {
            markFailure("craft_no_recipe")
            return
        }
        // 发放刚好够用的材料
        recipe.materials.forEach { material ->
            var remaining = material.count
            val template = material.ingredient.getMatchingStacks().first()
            while (remaining > 0) {
                val give = template.copy()
                give.count = minOf(remaining, give.maxStackSize)
                remaining -= give.count
                player.inventory.addItemStackToInventory(give)
            }
        }
        val look = player.horizontalFacing
        val pos = player.position.offset(look, 2)
        // 与玩家手动放置相同：通过 getStateForPlacement 决定朝向
        val block = com.tacz.legacy.common.block.LegacyBlocks.GUN_SMITH_TABLE
        var placedState = block.getStateForPlacement(
            player.serverWorld, pos, net.minecraft.util.EnumFacing.UP, 0.5f, 1.0f, 0.5f, 0, player, net.minecraft.util.EnumHand.MAIN_HAND,
        )
        // 截图用：强制工作台朝向
        System.getProperty("tacz.focusedSmoke.placeFacing")?.let(net.minecraft.util.EnumFacing::byName)?.let { forced ->
            placedState = placedState.withProperty(com.tacz.legacy.common.block.LegacyGunSmithTableBlock.FACING, forced)
        }
        player.serverWorld.setBlockState(pos, placedState)
        // 与物品放置相同：由 onBlockPlacedBy 放置多方块工作台的附属方块
        block.onBlockPlacedBy(player.serverWorld, pos, placedState, player, ItemStack.EMPTY)
        val placedFacing = placedState.getValue(com.tacz.legacy.common.block.LegacyGunSmithTableBlock.FACING)
        val structure = block.structurePositions(pos, placedFacing)
        craftStructurePositions = structure
        for (partPos in structure) {
            val partState = player.serverWorld.getBlockState(partPos)
            // 每个部位都应是普通的完整方块碰撞箱
            val collision = partState.getCollisionBoundingBox(player.serverWorld, partPos)
            log("CRAFT_TABLE_PART pos=$partPos state=$partState fullCollision=${collision == net.minecraft.block.Block.FULL_BLOCK_AABB}")
            if (partState.block !== block || collision != net.minecraft.block.Block.FULL_BLOCK_AABB) {
                markFailure("craft_table_part_invalid")
            }
        }
        val frontView = java.lang.Boolean.getBoolean("tacz.focusedSmoke.frontView")
        if ((java.lang.Boolean.getBoolean("tacz.focusedSmoke.topDownView") || frontView) && structure.size > 1) {
            // 截图用：在工作台宽度方向两端外侧放与结构等高的金块柱，模型应正好夹在两列金块之间
            val side = placedFacing.rotateY()
            for (dy in 0 until block.layout.height) {
                player.serverWorld.setBlockState(pos.offset(side.opposite).up(dy), net.minecraft.init.Blocks.GOLD_BLOCK.defaultState)
                player.serverWorld.setBlockState(pos.offset(side, block.layout.width).up(dy), net.minecraft.init.Blocks.GOLD_BLOCK.defaultState)
            }
        }
        if (frontView) {
            // 截图用：站在工作台正前方 4 格处平视
            val side = placedFacing.rotateY()
            val centerX = pos.x + 0.5 + side.xOffset * (block.layout.width - 1) * 0.5 - placedFacing.xOffset * 4.0
            val centerZ = pos.z + 0.5 + side.zOffset * (block.layout.width - 1) * 0.5 - placedFacing.zOffset * 4.0
            player.connection.setPlayerLocation(centerX, pos.y.toDouble(), centerZ, placedFacing.horizontalAngle, 10.0f)
        }
        if (java.lang.Boolean.getBoolean("tacz.focusedSmoke.topDownView")) {
            // 截图用：把玩家移到工作台正上方，面朝南并垂直向下看（屏幕上方 = 南）
            player.connection.setPlayerLocation(pos.x + 0.5, pos.y + 7.0, pos.z + 0.5, 0.0f, 90.0f)
            player.capabilities.isFlying = true
            player.sendPlayerAbilities()
        }
        log("CRAFT_TABLE_PLACED facing=${placedState.getValue(com.tacz.legacy.common.block.LegacyGunSmithTableBlock.FACING)} playerFacing=${player.horizontalFacing}")
        craftTablePos = pos
        craftRecipe = recipe
        log("CRAFT_READY recipe=${recipe.id} result=${(recipe.result.item as IGun).getGunId(recipe.result)} materials=${recipe.materials.size} pos=$pos")
    }

    private fun logCaptureOverrides() {
        val bulletSpeed = bulletSpeedMultiplier
        val tracerSize = tracerSizeMultiplier
        val tracerLength = tracerLengthMultiplier
        if (bulletSpeed == 1.0f && tracerSize == 1.0f && tracerLength == 1.0f) {
            return
        }
        logOnce(
            "capture-overrides",
            "TRACER_CAPTURE_OVERRIDES bulletSpeedMultiplier=$bulletSpeed tracerSizeMultiplier=$tracerSize tracerLengthMultiplier=$tracerLength"
        )
    }

    private fun applyWorldVisualOverrides(server: MinecraftServer) {
        val forcedTime = forcedWorldTime
        val freezeTime = freezeWorldTimeEnabled
        val clearWeather = clearWeatherEnabled
        if (forcedTime == null && !clearWeather) {
            return
        }
        server.worlds
            .asSequence()
            .filterNotNull()
            .forEach { world ->
                if (forcedTime != null) {
                    world.setWorldTime(forcedTime)
                    world.gameRules.setOrCreateGameRule("doDaylightCycle", (!freezeTime).toString())
                }
                if (clearWeather) {
                    world.worldInfo.setCleanWeatherTime(Int.MAX_VALUE)
                    world.worldInfo.setRainTime(0)
                    world.worldInfo.setRaining(false)
                    world.worldInfo.setThunderTime(0)
                    world.worldInfo.setThundering(false)
                    world.gameRules.setOrCreateGameRule("doWeatherCycle", "false")
                }
                logOnce(
                    "world-visual-${world.provider.dimension}",
                    "WORLD_VISUAL_OVERRIDES dimension=${world.provider.dimension} worldTime=${forcedTime ?: "unchanged"} freezeTime=$freezeTime clearWeather=$clearWeather"
                )
            }
    }

    private fun createGunStack(gunId: ResourceLocation, fullyLoaded: Boolean = false): ItemStack {
        val stack = ItemStack(LegacyItems.MODERN_KINETIC_GUN)
        val iGun = stack.item as IGun
        val gunData = GunDataAccessor.getGunData(gunId)
        val ammoAmount = gunData?.ammoAmount?.coerceAtLeast(1) ?: 1
        // 常规枪少装一发，保证换弹流程一定会被触发（单发装填的枪从 0 发开始）；爆炸物直接装满
        val initialAmmo = if (fullyLoaded) ammoAmount else ammoAmount - 1
        iGun.setGunId(stack, gunId)
        iGun.setCurrentAmmoCount(stack, initialAmmo)
        iGun.setDummyAmmoAmount(stack, (ammoAmount * 8).coerceAtLeast(96))
        val fireMode = gunData?.fireModesSet?.firstOrNull()?.let(::parseFireMode) ?: FireMode.UNKNOWN
        iGun.setFireMode(stack, fireMode)
        if ((gunData?.boltType ?: BoltType.OPEN_BOLT) != BoltType.OPEN_BOLT) {
            iGun.setBulletInBarrel(stack, true)
        }
        return stack
    }

    private fun spawnHitFeedbackTarget(player: EntityPlayerMP, regularGunId: ResourceLocation) {
        val world = player.serverWorld
        val look = player.lookVec.normalize()
        val spawnDistance = 8.0
        val target = EntityPigZombie(world).apply {
            setNoAI(true)
            setPosition(
                player.posX + look.x * spawnDistance,
                player.posY,
                player.posZ + look.z * spawnDistance,
            )
            rotationYaw = player.rotationYaw + 180.0f
            renderYawOffset = rotationYaw
            val baseDamage = GunDataAccessor.getGunData(regularGunId)?.bulletData?.damage ?: 4.0f
            // Set health low so a single bullet kills the target (avoids hurtResistantTime blocking follow-up damage)
            val desiredHealth = (baseDamage * 0.5f).coerceIn(1.0f, maxHealth - 0.5f)
            health = desiredHealth
        }
        world.spawnEntity(target)
        hitTargetEntityId = target.entityId
        logOnce(
            "hit-feedback-target",
            "HIT_FEEDBACK_TARGET_READY entityId=${target.entityId} type=${target.javaClass.simpleName} gun=$regularGunId health=${"%.2f".format(target.health)} pos=${"%.2f".format(target.posX)},${"%.2f".format(target.posY)},${"%.2f".format(target.posZ)}",
        )
    }

    private fun parseFireMode(rawValue: String): FireMode =
        runCatching { FireMode.valueOf(rawValue.uppercase()) }.getOrDefault(FireMode.UNKNOWN)

    @SubscribeEvent
    fun onEntityHurtByGun(event: EntityHurtByGunEvent.Post) {
        onHitTargetDamaged(event.logicalSide, event.hurtEntity?.entityId, killed = false)
    }

    @SubscribeEvent
    fun onEntityKillByGun(event: EntityKillByGunEvent) {
        onHitTargetDamaged(event.logicalSide, event.killedEntity?.entityId, killed = true)
    }

    private fun onHitTargetDamaged(side: Side, entityId: Int?, killed: Boolean) {
        if (!enabled || side != Side.SERVER || entityId == null || entityId != hitTargetEntityId) {
            return
        }
        hitTargetDamaged = true
        logOnce("hit-target-damaged", "HIT_TARGET_DAMAGED entityId=$entityId killed=$killed")
        maybeLogPass()
    }

    @SubscribeEvent
    fun onPlayerLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        if (!enabled) {
            return
        }
        val player = event.player as? EntityPlayerMP ?: return
        if (!player.server.isSinglePlayer) {
            logOnce("non-singleplayer", "FAIL reason=focused_smoke_requires_integrated_server")
            return
        }
        rememberFocusedPlayer(player)
        val plan = currentPlan() ?: return
        prepareSmokeGear(player, plan)
    }

    @SubscribeEvent
    fun onGunShoot(event: GunShootEvent) {
        if (!enabled || !matchesFocusedPlayer(event.shooter.uniqueID)) {
            return
        }
        val gunId = IGun.getIGunOrNull(event.gunItemStack)?.getGunId(event.gunItemStack) ?: return
        logOnce("shoot-${event.logicalSide}-$gunId", "GUN_SHOOT side=${event.logicalSide} gun=$gunId")
    }

    @SubscribeEvent
    fun onGunFire(event: GunFireEvent) {
        if (!enabled || !matchesFocusedPlayer(event.shooter.uniqueID)) {
            return
        }
        val gunId = IGun.getIGunOrNull(event.gunItemStack)?.getGunId(event.gunItemStack) ?: return
        logOnce("fire-${event.logicalSide}-$gunId", "GUN_FIRE side=${event.logicalSide} gun=$gunId")
    }

    @SubscribeEvent
    fun onEntityJoinWorld(event: EntityJoinWorldEvent) {
        if (!enabled || event.world.isRemote) {
            return
        }
        val bullet = event.entity as? EntityKineticBullet ?: return
        if (!matchesFocusedPlayer(bullet.thrower?.uniqueID)) {
            return
        }
        val plan = plannedScenario ?: return
        val gunId = bullet.gunId
        val explosive = plannedScenario?.explosiveGunId == gunId || GunDataAccessor.getGunData(gunId)?.bulletData?.explosionData?.explode == true
        if (plan.regularGunId == gunId) {
            regularProjectileObserved = true
            regularGunFireCount++
            logOnce("regular-projectile", "REGULAR_PROJECTILE_OBSERVED gun=$gunId side=SERVER")
        }
        if (explosive) {
            explosiveProjectileObserved = true
            explosiveProjectileSeenAtMs = System.currentTimeMillis()
            logOnce("explosive-projectile", "EXPLOSIVE_PROJECTILE_OBSERVED gun=$gunId side=SERVER")
        }
        maybeLogPass()
    }

    @SubscribeEvent
    fun onExplosionStart(event: ExplosionEvent.Start) {
        if (!enabled || event.world.isRemote) {
            return
        }
        if (!explosiveProjectileObserved) {
            return
        }
        if (System.currentTimeMillis() - explosiveProjectileSeenAtMs > 15_000L) {
            return
        }
        explosionObserved = true
        logOnce("explosion-start", "EXPLOSION_OBSERVED side=SERVER x=${"%.2f".format(event.explosion.position.x)} y=${"%.2f".format(event.explosion.position.y)} z=${"%.2f".format(event.explosion.position.z)}")
        maybeLogPass()
    }
}

private fun JsonObject.safeObject(key: String): JsonObject? =
    get(key)?.takeIf(JsonElement::isJsonObject)?.asJsonObject

private fun JsonObject.safeString(key: String): String? =
    get(key)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asString
        ?.takeIf(String::isNotBlank)

private fun JsonObject.safeBoolean(key: String, defaultValue: Boolean = false): Boolean =
    get(key)
        ?.takeIf { !it.isJsonNull }
        ?.asBoolean
        ?: defaultValue

private fun JsonObject.safeResourceLocation(key: String): ResourceLocation? =
    safeString(key)?.let { raw ->
        runCatching { ResourceLocation(raw) }.getOrNull()
    }

private fun JsonObject.jsonArraySize(key: String): Int =
    getAsJsonArray(key)?.size() ?: 0
