package com.tacz.legacy.common.entity

import com.tacz.legacy.TACZLegacy
import com.tacz.legacy.api.event.EntityHurtByGunEvent
import com.tacz.legacy.api.event.EntityKillByGunEvent
import com.tacz.legacy.client.particle.LegacyBulletClientEffects
import com.tacz.legacy.common.block.LegacyTargetBlock
import com.tacz.legacy.common.resource.BulletCombatData
import com.tacz.legacy.common.resource.DistanceDamagePoint
import com.tacz.legacy.common.config.HeadShotAabbConfigRead
import com.tacz.legacy.common.config.LegacyConfigManager
import com.tacz.legacy.common.item.LegacyItems
import com.tacz.legacy.common.registry.LegacySoundEvents
import com.tacz.legacy.common.network.TACZNetworkHandler
import com.tacz.legacy.common.network.message.event.ServerMessageBulletHitBlock
import com.tacz.legacy.common.network.message.event.ServerMessageGunHurt
import com.tacz.legacy.common.network.message.event.ServerMessageGunKill
import io.netty.buffer.ByteBuf
import net.minecraft.block.BlockGlass
import net.minecraft.block.BlockLeaves
import net.minecraft.block.BlockPane
import net.minecraft.block.BlockStainedGlass
import net.minecraft.block.material.Material
import net.minecraft.block.state.IBlockState
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityList
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.item.EntityMinecartEmpty
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.item.ItemStack
import net.minecraft.entity.projectile.EntityThrowable
import net.minecraft.entity.SharedMonsterAttributes
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagList
import net.minecraft.init.Blocks
import net.minecraft.util.DamageSource
import net.minecraft.util.EntityDamageSource
import net.minecraft.util.EntityDamageSourceIndirect
import net.minecraft.util.EnumParticleTypes
import net.minecraft.util.ResourceLocation
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import net.minecraft.world.World
import net.minecraft.world.WorldServer
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.ForgeEventFactory
import net.minecraftforge.event.world.BlockEvent
import net.minecraftforge.fml.common.registry.EntityEntry
import net.minecraftforge.fml.common.registry.EntityEntryBuilder
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData
import net.minecraftforge.fml.common.network.ByteBufUtils
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import net.minecraftforge.registries.IForgeRegistry
import org.joml.Vector3d
import org.joml.Vector3f
import kotlin.math.max
import kotlin.math.sqrt

internal object LegacyEntities {
    internal val BULLET: EntityEntry = EntityEntryBuilder.create<EntityKineticBullet>()
        .entity(EntityKineticBullet::class.java)
        .id(ResourceLocation(TACZLegacy.MOD_ID, "bullet"), 0)
        .name("bullet")
        .tracker(64, 1, false)
        .build()

    internal val TARGET_MINECART: EntityEntry = EntityEntryBuilder.create<TargetMinecartEntity>()
        .entity(TargetMinecartEntity::class.java)
        .id(ResourceLocation(TACZLegacy.MOD_ID, "target_minecart"), 1)
        .name("target_minecart")
        .tracker(64, 1, true)
        .build()

    internal fun registerAll(registry: IForgeRegistry<EntityEntry>): Unit {
        registry.register(BULLET)
        registry.register(TARGET_MINECART)
    }
}

internal class EntityKineticBullet : EntityThrowable, IEntityAdditionalSpawnData {
    private data class HitFeedbackResult(
        val appliedDamage: Boolean,
        val baseDamage: Float,
        val headShotMultiplier: Float,
        val headShot: Boolean,
    )

    internal companion object {
        private const val DEFAULT_FORWARD_COMPONENT = 8.0
        // 对齐原版 1.20 vanilla AbstractArrow.shoot 的 inaccuracy scale (PI/180 = deg→rad)。
    // 原 0.0075 是 1.12 vanilla EntityArrow 值，导致散布比原版小约 2.33 倍（inaccuracy=4.5 时仅 1.9° vs 原版 4.5°）。
    private const val DEFAULT_INACCURACY_SCALE = 0.017453292F
        private const val FOCUSED_SMOKE_BULLET_SPEED_MULTIPLIER_PROPERTY = "tacz.focusedSmoke.bulletSpeedMultiplier"
        private const val BLOCK_HIT_EFFECT_RANGE = 64.0

        internal fun computeShotDirection(pitch: Double, yaw: Double, spreadX: Double, spreadY: Double): Vec3d {
            val direction = Vector3d(spreadX, spreadY, DEFAULT_FORWARD_COMPONENT)
            direction.rotateX(Math.toRadians(pitch))
            direction.rotateY(Math.toRadians(-yaw))
            val length = direction.length()
            if (length <= 1.0E-8) {
                return Vec3d.ZERO
            }
            return Vec3d(direction.x / length, direction.y / length, direction.z / length)
        }

        private fun resolveFocusedSmokeBulletSpeedMultiplier(): Double {
            val rawValue = System.getProperty(FOCUSED_SMOKE_BULLET_SPEED_MULTIPLIER_PROPERTY)?.toDoubleOrNull()
                ?: return 1.0
            if (!rawValue.isFinite() || rawValue <= 0.0) {
                return 1.0
            }
            return rawValue
        }
    }

    private var damage: Float = 5.0f
    private var bulletSpeed: Float = 5.0f
    private var bulletGravity: Float = 0.0f
    private var friction: Float = 0.01f
    private var pierce: Int = 1
    private var lifespan: Int = 200
    private var knockback: Float = 0.0f
    private var igniteEntity: Boolean = false
    private var igniteEntityTime: Int = 2
    private var igniteBlock: Boolean = false
    private var isTracerAmmo: Boolean = false
    private var damageModifier: Float = 1.0f
    /** 脚本可调用的单发伤害倍率（对齐上游 EntityKineticBullet.shotDamageMultiplier，默认 1 = 无影响） */
    private var shotDamageMultiplier: Float = 1.0f
    private var armorIgnore: Float = 0.0f
    private var headShotMultiplier: Float = 1.0f
    private val damageAdjust: MutableList<DistanceDamagePoint> = mutableListOf()
    /** 配置伤害倍率是否已烘进 damage / damageAdjust（见 bakeDamageBaseMultiplier） */
    private var damageBaseBaked: Boolean = false
    private var startPosX: Double = 0.0
    private var startPosY: Double = 0.0
    private var startPosZ: Double = 0.0
    private var shooterEntityId: Int = -1

    // Explosion properties
    // hasExplosion = "这发子弹会爆炸"；hasExplosionData = "枪本身写了 explosion 段"。
    // 两者必须分开：审计 B1-1 的根因就是旧实现只用 hasExplosion 兼作两个含义，
    // 于是 explode:false 的枪（默认包 M107 / M870 / Deagle / AI AWP）自己的半径、伤害、
    // 击退、延时全被丢掉，装上 HE 弹匣后退回硬编码 0.5 / 2 / 30s（上游会保留原值）。
    private var hasExplosion: Boolean = false
    private var hasExplosionData: Boolean = false
    private var explosionRadius: Float = 0f
    private var explosionDamage: Float = 0f
    private var explosionKnockback: Boolean = false
    private var explosionDestroyBlock: Boolean = false
    private var explosionDelay: Float = 0f

    /** 发射的枪械 ID，供下游渲染/音效使用 */
    internal var gunId: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "unknown")
        private set
    /** 枪械 display ID，供下游渲染使用 */
    internal var gunDisplayId: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "default")
        private set
    /** 弹药 ID，供下游渲染/特效使用  */
    internal var ammoId: ResourceLocation = ResourceLocation(TACZLegacy.MOD_ID, "empty")
        private set
    /** 客户端：是否已经对本地玩家播放过掠过音效 */
    internal var whizPlayed: Boolean = false
    internal val shooterId: Int
        get() = shooterEntityId
    internal var firstPersonRenderOffset: Vector3f? = null
    internal var firstPersonCameraPitch: Float? = null
    internal var firstPersonCameraYaw: Float? = null

    /** NBT / network deserialization constructor */
    constructor(worldIn: World) : super(worldIn) {
        setSize(0.1f, 0.1f)
    }

    /** 数据驱动构造器：从 BulletCombatData 获取所有弹道参数 */
    constructor(
        worldIn: World,
        shooter: EntityLivingBase,
        bulletData: BulletCombatData,
        gunId: ResourceLocation,
        gunDisplayId: ResourceLocation,
        ammoId: ResourceLocation,
        isTracer: Boolean,
    ) : super(worldIn, shooter) {
        setSize(0.1f, 0.1f)
        this.shooterEntityId = shooter.entityId
        this.damage = bulletData.damage
        this.bulletSpeed = bulletData.speed
        // 对齐上游 EntityKineticBullet:187-188 的 Mth.clamp(..., 0, MAX)：
        // 负的 gravity/friction 会让 (1 - friction) > 1 或 -gravity 反向加速子弹
        this.bulletGravity = bulletData.gravity.coerceAtLeast(0.0f)
        this.friction = bulletData.friction.coerceAtLeast(0.0f)
        this.pierce = bulletData.pierce
        this.lifespan = bulletData.getLifeTicks()
        this.knockback = bulletData.knockback
        this.igniteEntity = bulletData.igniteEntity
        this.igniteEntityTime = bulletData.igniteEntityTime
        this.igniteBlock = bulletData.igniteBlock
        this.isTracerAmmo = isTracer
        this.gunId = gunId
        this.gunDisplayId = gunDisplayId
        this.ammoId = ammoId
        resetInitialPosition(shooter)
        this.startPosX = posX
        this.startPosY = posY
        this.startPosZ = posZ

        val extraDamage = bulletData.extraDamageData
        this.armorIgnore = ((extraDamage?.armorIgnore ?: 0.0f) * LegacyConfigManager.server.armorIgnoreBaseMultiplier.toFloat())
            .coerceIn(0.0f, 1.0f)
        this.headShotMultiplier = max((extraDamage?.headShotMultiplier ?: 1.0f) * LegacyConfigManager.server.headShotBaseMultiplier.toFloat(), 0.0f)
        this.damageAdjust.clear()
        this.damageAdjust.addAll(extraDamage?.damageAdjust.orEmpty())
        
        val exp = bulletData.explosionData
        if (exp != null) {
            // 对齐上游 ExplosionModifier.initCache:36-42：只有 explosionData == null 才用默认值，
            // 有数据时**原样保留全部字段**（含 explode:false 的那些），hasExplosion 只表示"是否已点亮"。
            this.hasExplosionData = true
            this.explosionRadius = exp.radius
            this.explosionDamage = exp.damage
            this.explosionKnockback = exp.knockback
            this.explosionDestroyBlock = exp.destroyBlock
            this.explosionDelay = exp.delay
            this.hasExplosion = exp.explode
        }
        normalizeExplosionDelay()
    }

    /**
     * 上游 EntityKineticBullet:207-213 把 `delay < 0` 映射成"永不定时爆炸"（delayTickCount=MAX_VALUE），
     * 且映射发生在 modifier 聚合**之后** —— 所以负 delay 的枪装上 `delay:{addend:5}` 的配件仍能变回 5 秒。
     * 旧实现在构造期就写死 POSITIVE_INFINITY，配件再也拉不回来（审计 B1-7）。
     * 无配件时在这里补做同一次归一，保证两条路径结果一致。
     */
    private fun normalizeExplosionDelay() {
        if (hasExplosion) {
            explosionDelay = TACZExplosionAggregation.normalizeDelay(explosionDelay)
        }
    }

    /** 霰弹伤害分摊。与上游 applyShotgunDamageSpread 一致。 */
    internal fun applyShotgunDamageSpread(bulletCount: Int) {
        if (bulletCount > 1) {
            damageModifier = 1.0f / bulletCount
        }
    }

    /**
     * 脚本单发伤害倍率。对齐上游 EntityKineticBullet:234-236（负数一律抬到 0）。
     * 每次射击由 TACZGunScriptAPI 在生成子弹后调用；未调用时保持 1，不影响无脚本路径。
     */
    internal fun setShotDamageMultiplier(multiplier: Float) {
        shotDamageMultiplier = max(multiplier, 0.0f)
    }

    // 叠加 fire_mode_adjust.damage 到基础伤害（对齐原版 DamageModifier.initCache：
    // 距离衰减曲线的每个采样点都要加上该差值，否则有 damage_adjust 曲线的枪会忽略该调整）
    internal fun applyFireModeDamageAdjust(delta: Float) {
        if (delta == 0f) return
        damage = (damage + delta).coerceAtLeast(0f)
        // 同步把差值烘进距离曲线的每个点（原版行为：pair.getDamage() + fireAdjustDamageAmount）
        for (i in damageAdjust.indices) {
            damageAdjust[i] = damageAdjust[i].copy(
                damage = (damageAdjust[i].damage + delta).coerceAtLeast(0f),
            )
        }
    }

    // 叠加 fire_mode_adjust 的 armor_ignore/head_shot_multiplier/knockback（对齐原版 GunFireModeAdjustData）
    internal fun applyFireModeExtraAdjust(armorIgnoreDelta: Float, headShotDelta: Float, knockbackDelta: Float) {
        if (armorIgnoreDelta != 0f) armorIgnore = (armorIgnore + armorIgnoreDelta).coerceIn(0f, 1f)
        if (headShotDelta != 0f) headShotMultiplier = (headShotMultiplier + headShotDelta).coerceAtLeast(0f)
        if (knockbackDelta != 0f) knockback = (knockback + knockbackDelta).coerceAtLeast(0f)
    }

    // 兼容层 ③：配件 modifier 接入子弹属性（armor_ignore/head_shot/pierce/knockback）
    // 用子弹当前值作为 default，eval 配件 modifier 后覆盖（对齐上游 EntityKineticBullet:177-198 读缓存）
    // 注意这里的四处下限/区间不是"额外 clamp"：上游子弹构造器同样在 eval 之后做
    // armorIgnore.clamp(0,1)、headShot.max(0)、knockback.max(0)、pierce.clamp(1,MAX)。
    internal fun applyAttachmentModifiers(mods: com.tacz.legacy.common.resource.TACZGunPropertyResolver.AttachmentModifierLists) {
        val registry = com.tacz.legacy.common.resource.TACZAttachmentModifierRegistry
        if (mods.armorIgnore.isNotEmpty()) {
            armorIgnore = registry.evalNumeric(mods.armorIgnore, armorIgnore.toDouble()).toFloat().coerceIn(0f, 1f)
        }
        if (mods.headShot.isNotEmpty()) {
            headShotMultiplier = registry.evalNumeric(mods.headShot, headShotMultiplier.toDouble()).toFloat().coerceAtLeast(0f)
        }
        if (mods.pierce.isNotEmpty()) {
            // 上游 PierceModifier：eval 后 clamp(1, MAX)。0 或负数会被上游抬到 1，
            // 这里也必须抬到 1，否则 onUpdate 会在第一只目标后直接销毁子弹。
            pierce = Math.round(registry.evalNumeric(mods.pierce, pierce.toDouble())).toInt().coerceAtLeast(1)
        }
        if (mods.knockback.isNotEmpty()) {
            knockback = registry.evalNumeric(mods.knockback, knockback.toDouble()).toFloat().coerceAtLeast(0f)
        }
    }

    // 兼容层 ③ C1-2：配件 damage modifier 逐距离段 eval（对齐上游 DamageModifier.java:77-86，
    // 对 damageAdjust 列表的每个距离点分别套用 eval 公式），无 modifier 时不覆盖
    internal fun applyAttachmentDamageModifier(modifiers: List<com.tacz.legacy.api.modifier.Modifier>) {
        if (modifiers.isEmpty()) return
        val registry = com.tacz.legacy.common.resource.TACZAttachmentModifierRegistry
        // 审计 B1-9：配置倍率必须先烘进基准值再 eval（上游 DamageModifier.initCache:66-71 先
        // (base + fireAdjust) * DAMAGE_BASE_MULTIPLIER，:77-86 之后才 eval 配件）。
        bakeDamageBaseMultiplier()
        // 上游 DamageModifier.eval 只套公式，不在 eval 层夹 0；负值由 getDamage 的
        // Math.max(modifiedDamage, 0) 在结算时统一兜底（本类 resolveEffectiveDamageAt 同）。
        damage = registry.evalNumeric(modifiers, damage.toDouble()).toFloat()
        for (i in damageAdjust.indices) {
            val point = damageAdjust[i]
            damageAdjust[i] = point.copy(
                damage = registry.evalNumeric(modifiers, point.damage.toDouble()).toFloat(),
            )
        }
    }

    // 兼容层 ③ C2-ext：effective_range 修正距离衰减曲线第一段的距离（对齐上游
    // EffectiveRangeModifier.java:39-51 + EntityKineticBullet.java:195,523）
    internal fun applyAttachmentEffectiveRange(modifiers: List<com.tacz.legacy.api.modifier.Modifier>) {
        if (modifiers.isEmpty() || damageAdjust.isEmpty()) return
        val registry = com.tacz.legacy.common.resource.TACZAttachmentModifierRegistry
        val firstPoint = damageAdjust[0]
        val newDistance = registry.evalNumeric(modifiers, firstPoint.distance.toDouble()).toFloat()
        damageAdjust[0] = firstPoint.copy(distance = newDistance)
    }

    // 兼容层 ③ C2-exp：explosion 聚合（对齐上游 ExplosionModifier.eval:45-79）
    // 计算本体在 TACZExplosionAggregation（纯函数，可单测）；这里只负责把结果写回子弹字段。
    // 审计全局-1：入参是解析期成型的 typed 值，本函数不碰 Gson —— 它在每发子弹的路径上。
    internal fun applyAttachmentExplosion(
        explosions: List<com.tacz.legacy.common.resource.TACZExplosionModifierValue>,
    ) {
        val aggregated = TACZExplosionAggregation.aggregate(
            TACZExplosionAggregation.Base(
                hasData = hasExplosionData,
                explode = hasExplosion,
                radius = explosionRadius,
                damage = explosionDamage,
                knockback = explosionKnockback,
                destroyBlock = explosionDestroyBlock,
                delay = explosionDelay,
            ),
            explosions,
        ) ?: return

        hasExplosion = true
        explosionRadius = aggregated.radius
        explosionDamage = aggregated.damage
        explosionKnockback = aggregated.knockback
        explosionDestroyBlock = aggregated.destroyBlock
        explosionDelay = aggregated.delay
    }

    // 合并配件 ignite modifier（对齐原版 AttachmentCacheProperty：配件 ignite 与子弹默认 ignite 合并）
    internal fun applyIgniteModifier(ignite: com.tacz.legacy.common.resource.TACZIgniteValue) {
        if (ignite.igniteEntity) igniteEntity = true
        if (ignite.igniteBlock) igniteBlock = true
    }

    internal fun shootFromRotation(shooter: Entity, pitch: Float, yaw: Float, velocity: Float, inaccuracy: Float) {
        val spreadX = rand.nextGaussian() * DEFAULT_INACCURACY_SCALE * inaccuracy.toDouble()
        val spreadY = rand.nextGaussian() * DEFAULT_INACCURACY_SCALE * inaccuracy.toDouble()
        shootFromRotation(shooter, pitch, yaw, velocity, spreadX, spreadY)
    }

    internal fun shootFromRotation(shooter: Entity, pitch: Float, yaw: Float, velocity: Float, spreadX: Double, spreadY: Double) {
        val actualVelocity = velocity.toDouble() * resolveFocusedSmokeBulletSpeedMultiplier()
        val direction = computeShotDirection(pitch.toDouble(), yaw.toDouble(), spreadX, spreadY).scale(actualVelocity)
        motionX = direction.x
        motionY = direction.y
        motionZ = direction.z

        val horizontalDistance = sqrt(motionX * motionX + motionZ * motionZ)
        rotationYaw = Math.toDegrees(Math.atan2(motionX, motionZ)).toFloat()
        rotationPitch = Math.toDegrees(Math.atan2(motionY, horizontalDistance)).toFloat()
        prevRotationYaw = rotationYaw
        prevRotationPitch = rotationPitch

        motionX += shooter.motionX
        motionZ += shooter.motionZ
        if (!shooter.onGround) {
            motionY += shooter.motionY
        }
    }

    private fun resetInitialPosition(shooter: EntityLivingBase) {
        val interpolatedX = shooter.lastTickPosX + (shooter.posX - shooter.lastTickPosX) / 2.0
        val interpolatedY = shooter.lastTickPosY + (shooter.posY - shooter.lastTickPosY) / 2.0 + shooter.eyeHeight.toDouble()
        val interpolatedZ = shooter.lastTickPosZ + (shooter.posZ - shooter.lastTickPosZ) / 2.0
        setPosition(interpolatedX, interpolatedY, interpolatedZ)
    }

    /** 获取实际伤害（含霰弹分摊） */
    internal fun getEffectiveDamage(): Float = resolveEffectiveDamageAt(Vec3d(posX, posY, posZ))

    internal fun isTracerAmmo(): Boolean = isTracerAmmo

    internal fun getShooterForRender(): EntityLivingBase? {
        val cached = thrower
        if (cached != null && !cached.isDead) {
            return cached
        }
        if (shooterEntityId < 0) {
            return null
        }
        val resolved = world.getEntityByID(shooterEntityId) as? EntityLivingBase ?: return null
        thrower = resolved
        return resolved
    }

    override fun writeSpawnData(buffer: ByteBuf) {
        buffer.writeFloat(rotationPitch)
        buffer.writeFloat(rotationYaw)
        buffer.writeDouble(motionX)
        buffer.writeDouble(motionY)
        buffer.writeDouble(motionZ)
        ByteBufUtils.writeUTF8String(buffer, gunId.toString())
        ByteBufUtils.writeUTF8String(buffer, gunDisplayId.toString())
        ByteBufUtils.writeUTF8String(buffer, ammoId.toString())
        buffer.writeBoolean(isTracerAmmo)
        buffer.writeInt(shooterEntityId)
        buffer.writeFloat(bulletGravity)
        buffer.writeFloat(friction)
        buffer.writeInt(lifespan)
    }

    override fun readSpawnData(additionalData: ByteBuf) {
        rotationPitch = additionalData.readFloat()
        rotationYaw = additionalData.readFloat()
        prevRotationPitch = rotationPitch
        prevRotationYaw = rotationYaw
        motionX = additionalData.readDouble()
        motionY = additionalData.readDouble()
        motionZ = additionalData.readDouble()
        gunId = ResourceLocation(ByteBufUtils.readUTF8String(additionalData))
        gunDisplayId = ResourceLocation(ByteBufUtils.readUTF8String(additionalData))
        ammoId = ResourceLocation(ByteBufUtils.readUTF8String(additionalData))
        isTracerAmmo = additionalData.readBoolean()
        shooterEntityId = additionalData.readInt()
        bulletGravity = additionalData.readFloat()
        friction = additionalData.readFloat()
        lifespan = additionalData.readInt()
        getShooterForRender()
        if (world.isRemote && java.lang.Boolean.getBoolean("tacz.focusedSmoke")) {
            TACZLegacy.logger.info(
                "[FocusedSmoke] BULLET_CLIENT_SPAWN entityId={} gun={} display={} ammo={} tracer={} shooterId={} shooterResolved={}",
                entityId,
                gunId,
                gunDisplayId,
                ammoId,
                isTracerAmmo,
                shooterEntityId,
                getShooterForRender() != null,
            )
        }
    }

    override fun getGravityVelocity(): Float = bulletGravity

    override fun onImpact(result: RayTraceResult) {
        if (world.isRemote) return

        when (result.typeOfHit) {
            RayTraceResult.Type.ENTITY -> {
                val target = result.entityHit ?: return
                val hitPos = result.hitVec ?: Vec3d(target.posX, target.posY + target.height * 0.5, target.posZ)
                // 射手本身不会被自己的子弹命中，也不应消耗穿透次数
                if (target == thrower) return
                val headShot = target is EntityLivingBase && isHeadShot(target, hitPos)
                val feedback = applyDirectHitDamage(target, hitPos, headShot)
                if (hasExplosion) {
                    triggerExplosion(hitPos)
                    emitHitFeedback(target, feedback)
                    setDead()
                    return
                }
                emitHitFeedback(target, feedback)
                pierce--
                if (pierce <= 0) setDead()
            }
            RayTraceResult.Type.BLOCK -> {
                val hitPos = result.hitVec ?: Vec3d(posX, posY, posZ)
                val blockPos = result.blockPos
                val state = world.getBlockState(blockPos)
                (state.block as? LegacyTargetBlock)?.onBulletHit(world, blockPos, state)
                if (hasExplosion) {
                    triggerExplosion(hitPos)
                    setDead()
                    return
                }
                if (tryBreakFragileBlock(blockPos, state)) {
                    // destroyBlock 已经播放了破碎粒子与音效
                    setDead()
                    return
                }
                TACZNetworkHandler.sendToAllAround(
                    ServerMessageBulletHitBlock(blockPos, result.sideHit, hitPos.x, hitPos.y, hitPos.z),
                    world.provider.dimension,
                    hitPos.x,
                    hitPos.y,
                    hitPos.z,
                    BLOCK_HIT_EFFECT_RANGE,
                )
                if (igniteBlock && thrower != null) {
                    val pos = result.blockPos.offset(result.sideHit)
                    if (world.isAirBlock(pos) && LegacyConfigManager.common.igniteBlock) {
                        world.setBlockState(pos, Blocks.FIRE.defaultState)
                    }
                }
                setDead()
            }
            else -> {}
        }
    }

    /**
     * 玻璃、玻璃板、树叶等易碎方块被子弹直接击碎（受 DestroyGlass 配置控制）。
     * 玩家射手会触发 BreakEvent 以兼容领地保护，非玩家射手遵循 mobGriefing。
     */
    private fun tryBreakFragileBlock(blockPos: BlockPos, state: IBlockState): Boolean {
        if (!LegacyConfigManager.common.destroyGlass) {
            return false
        }
        if (!isFragileBlock(state)) {
            return false
        }
        if (state.getBlockHardness(world, blockPos) < 0.0f) {
            return false
        }
        val shooter = thrower
        if (shooter is EntityPlayerMP) {
            if (!world.isBlockModifiable(shooter, blockPos) || !shooter.capabilities.allowEdit) {
                return false
            }
            if (MinecraftForge.EVENT_BUS.post(BlockEvent.BreakEvent(world, blockPos, state, shooter))) {
                return false
            }
        } else if (!ForgeEventFactory.getMobGriefingEvent(world, shooter)) {
            return false
        }
        return world.destroyBlock(blockPos, false)
    }

    private fun isFragileBlock(state: IBlockState): Boolean {
        val block = state.block
        return block is BlockGlass ||
            block is BlockStainedGlass ||
            (block is BlockPane && state.material == Material.GLASS) ||
            block is BlockLeaves
    }

    override fun writeEntityToNBT(compound: NBTTagCompound) {
        super.writeEntityToNBT(compound)
        compound.setFloat("Damage", damage)
        compound.setFloat("Speed", bulletSpeed)
        compound.setFloat("Gravity", bulletGravity)
        compound.setFloat("Friction", friction)
        compound.setInteger("Pierce", pierce)
        compound.setFloat("Knockback", knockback)
        compound.setBoolean("IgniteEntity", igniteEntity)
        compound.setInteger("IgniteEntityTime", igniteEntityTime)
        compound.setBoolean("IgniteBlock", igniteBlock)
        compound.setBoolean("IsTracer", isTracerAmmo)
        compound.setFloat("ArmorIgnore", armorIgnore)
        compound.setFloat("HeadShotMultiplier", headShotMultiplier)
        compound.setDouble("StartPosX", startPosX)
        compound.setDouble("StartPosY", startPosY)
        compound.setDouble("StartPosZ", startPosZ)
        compound.setInteger("ShooterEntityId", shooterEntityId)
        val damageAdjustList = NBTTagList()
        damageAdjust.forEach { pair ->
            val entry = NBTTagCompound()
            entry.setFloat("Distance", pair.distance)
            entry.setFloat("Damage", pair.damage)
            damageAdjustList.appendTag(entry)
        }
        compound.setTag("DamageAdjust", damageAdjustList)
        
        compound.setBoolean("HasExplosion", hasExplosion)
        if (hasExplosion) {
            compound.setFloat("ExplosionRadius", explosionRadius)
            compound.setFloat("ExplosionDamage", explosionDamage)
            compound.setBoolean("ExplosionKnockback", explosionKnockback)
            compound.setBoolean("ExplosionDestroyBlock", explosionDestroyBlock)
            compound.setBoolean("ExplosionDelayInfinite", explosionDelay.isInfinite())
            if (!explosionDelay.isInfinite()) {
                compound.setFloat("ExplosionDelay", explosionDelay)
            }
        }
        compound.setString("GunId", gunId.toString())
        compound.setString("GunDisplayId", gunDisplayId.toString())
        compound.setString("AmmoId", ammoId.toString())
        compound.setFloat("DamageModifier", damageModifier)
    }

    private fun triggerExplosion(position: Vec3d = Vec3d(posX, posY, posZ)) {
        // 与上游 EntityKineticBullet:203 一致：爆炸伤害在结算时乘配置倍率并抬到 0。
        // 只在调用处乘一次：explosionDamage 字段（含配件 modifier 聚合结果、NBT 读写）始终保持 JSON 原值，
        // 避免 NBT 往返或构造期重复相乘。
        val scaledExplosionDamage =
            (explosionDamage * LegacyConfigManager.server.damageBaseMultiplier.toFloat()).coerceAtLeast(0.0f)
        LegacyProjectileExplosionHelper.createExplosion(
            owner = thrower,
            exploder = this,
            x = position.x,
            y = position.y,
            z = position.z,
            damage = scaledExplosionDamage,
            radius = explosionRadius,
            knockback = explosionKnockback,
            destroyBlock = explosionDestroyBlock,
        )
    }

    override fun readEntityFromNBT(compound: NBTTagCompound) {
        super.readEntityFromNBT(compound)
        damage = compound.getFloat("Damage")
        bulletSpeed = compound.getFloat("Speed")
        bulletGravity = compound.getFloat("Gravity")
        friction = if (compound.hasKey("Friction")) compound.getFloat("Friction") else 0.01f
        pierce = compound.getInteger("Pierce")
        knockback = if (compound.hasKey("Knockback")) compound.getFloat("Knockback") else 0.0f
        igniteEntity = compound.getBoolean("IgniteEntity")
        igniteEntityTime = if (compound.hasKey("IgniteEntityTime")) compound.getInteger("IgniteEntityTime") else 2
        igniteBlock = compound.getBoolean("IgniteBlock")
        isTracerAmmo = compound.getBoolean("IsTracer")
        armorIgnore = if (compound.hasKey("ArmorIgnore")) compound.getFloat("ArmorIgnore") else 0.0f
        headShotMultiplier = if (compound.hasKey("HeadShotMultiplier")) compound.getFloat("HeadShotMultiplier") else 1.0f
        startPosX = if (compound.hasKey("StartPosX")) compound.getDouble("StartPosX") else posX
        startPosY = if (compound.hasKey("StartPosY")) compound.getDouble("StartPosY") else posY
        startPosZ = if (compound.hasKey("StartPosZ")) compound.getDouble("StartPosZ") else posZ
        shooterEntityId = if (compound.hasKey("ShooterEntityId")) compound.getInteger("ShooterEntityId") else -1
        getShooterForRender()
        damageAdjust.clear()
        if (compound.hasKey("DamageAdjust")) {
            val list = compound.getTagList("DamageAdjust", 10)
            for (index in 0 until list.tagCount()) {
                val entry = list.getCompoundTagAt(index)
                damageAdjust += DistanceDamagePoint(
                    distance = entry.getFloat("Distance"),
                    damage = entry.getFloat("Damage"),
                )
            }
        }
        
        hasExplosion = compound.getBoolean("HasExplosion")
        if (hasExplosion) {
            explosionRadius = compound.getFloat("ExplosionRadius")
            explosionDamage = compound.getFloat("ExplosionDamage")
            explosionKnockback = compound.getBoolean("ExplosionKnockback")
            explosionDestroyBlock = compound.getBoolean("ExplosionDestroyBlock")
            explosionDelay = if (compound.getBoolean("ExplosionDelayInfinite")) {
                Float.POSITIVE_INFINITY
            } else {
                compound.getFloat("ExplosionDelay")
            }
        }
        if (compound.hasKey("GunId")) gunId = ResourceLocation(compound.getString("GunId"))
        if (compound.hasKey("GunDisplayId")) gunDisplayId = ResourceLocation(compound.getString("GunDisplayId"))
        if (compound.hasKey("AmmoId")) ammoId = ResourceLocation(compound.getString("AmmoId"))
        damageModifier = if (compound.hasKey("DamageModifier")) compound.getFloat("DamageModifier") else 1.0f
    }

    /**
     * Override to skip rotation sync from server packets.
     * The server sends byte-compressed (256-step) rotation values every tick,
     * which contaminate [rotationYaw]/[rotationPitch] precision. Our [onUpdate]
     * computes precise rotation from motion locally, so we only accept position.
     */
    @SideOnly(Side.CLIENT)
    override fun setPositionAndRotationDirect(
        x: Double, y: Double, z: Double,
        yaw: Float, pitch: Float,
        posRotationIncrements: Int, teleport: Boolean,
    ) {
        setPosition(x, y, z)
    }

    /**
     * Block velocity sync packets from overwriting client motion vectors.
     * Upstream uses [setShouldReceiveVelocityUpdates(false)] so the client
     * computes position/motion locally from spawn data + physics. In 1.12
     * we set tracker to not send velocity, but override this as extra safety.
     */
    @SideOnly(Side.CLIENT)
    override fun setVelocity(x: Double, y: Double, z: Double) {
        // no-op: client computes motion locally
    }

    override fun onUpdate() {
        // 完全绕过 EntityThrowable.onUpdate() 的碰撞检测、0.99f 拖拽与 0.2 旋转插值。
        // 与上游 TACZ EntityKineticBullet.tick() + onBulletTick() 逻辑对齐。
        lastTickPosX = posX
        lastTickPosY = posY
        lastTickPosZ = posZ
        // Entity.onUpdate() — 只跑计时器和骑乘逻辑
        super.onEntityUpdate()

        // ---- 服务端碰撞检测 (对齐上游 onBulletTick) ----
        if (!world.isRemote) {
            if (hasExplosion && explosionDelay.isFinite()) {
                explosionDelay -= 0.05f
                if (explosionDelay <= 0 && !isDead) {
                    triggerExplosion()
                    setDead()
                    return
                }
            }

            val startVec = Vec3d(posX, posY, posZ)
            var endVec = Vec3d(posX + motionX, posY + motionY, posZ + motionZ)

            // 方块碰撞
            val blockResult = world.rayTraceBlocks(startVec, endVec, false, true, false)
            if (blockResult != null && blockResult.typeOfHit == RayTraceResult.Type.BLOCK) {
                endVec = blockResult.hitVec
            }

            // 实体碰撞
            val entitiesInRange = world.getEntitiesWithinAABBExcludingEntity(
                this,
                entityBoundingBox.expand(motionX, motionY, motionZ).grow(1.0),
            )
            val hitEntities = mutableListOf<Pair<Entity, Vec3d>>()
            for (entity in entitiesInRange) {
                if (!entity.canBeCollidedWith()) continue
                if (entity === thrower && ticksExisted < 5) continue
                // 命中检测仍用略扩大的盒做容错，但**命中点必须用真实碰撞箱**计算。
                // 对齐上游 EntityUtil.getHitResult：它用 HitboxHelper.getFixedBoundingBox（真实盒 + 速度补偿）
                // 与射线求交得到 hitPos，并不做固定 inflate。
                // 若直接把扩大盒的交点当 hitPos，该点会落在真实盒面之外 0.3 格，而爆头判定基于真实头部盒，
                // 于是判定永远差 0.3 格失效（T13 根因，2026-10-16 由诊断日志实测确认）。
                val testIntercept = entity.entityBoundingBox.grow(0.3).calculateIntercept(startVec, endVec) ?: continue
                val hitVec = entity.entityBoundingBox.calculateIntercept(startVec, endVec)?.hitVec ?: testIntercept.hitVec
                hitEntities += entity to hitVec
            }
            // 按距离排序
            hitEntities.sortBy { (_, hitVec) -> startVec.squareDistanceTo(hitVec) }

            // 处理实体命中
            for ((entity, hitVec) in hitEntities) {
                val entityHitResult = RayTraceResult(entity, hitVec)
                if (!net.minecraftforge.event.ForgeEventFactory.onProjectileImpact(this, entityHitResult)) {
                    onImpact(entityHitResult)
                }
                if (isDead) return
                if (pierce < 1) {
                    setDead()
                    return
                }
            }

            // 处理方块命中（在实体命中之后，与上游一致）
            if (blockResult != null && blockResult.typeOfHit == RayTraceResult.Type.BLOCK) {
                if (!net.minecraftforge.event.ForgeEventFactory.onProjectileImpact(this, blockResult)) {
                    onImpact(blockResult)
                }
                if (isDead) return
            }
        }

        // ---- 位置与旋转更新 (对齐上游 tick) ----
        posX += motionX
        posY += motionY
        posZ += motionZ

        val horizontalDist = sqrt(motionX * motionX + motionZ * motionZ)
        val targetYaw = (Math.toDegrees(Math.atan2(motionX, motionZ))).toFloat()
        val targetPitch = (Math.toDegrees(Math.atan2(motionY, horizontalDist))).toFloat()
        if (prevRotationPitch == 0.0f && prevRotationYaw == 0.0f) {
            prevRotationYaw = targetYaw
            prevRotationPitch = targetPitch
        }
        rotationYaw = lerpRotation(prevRotationYaw, targetYaw)
        rotationPitch = lerpRotation(prevRotationPitch, targetPitch)
        // Do NOT set prevRotation = rotation here.
        // Entity.onEntityUpdate() sets prev = current at the START of each tick,
        // matching upstream TACZ where super.tick() → Entity.tick() does the same.
        // Setting it here kills render-frame interpolation (prev == current → no lerp).

        setPosition(posX, posY, posZ)

        if (world.isRemote) {
            LegacyBulletClientEffects.tickBulletWhiz(this)
        }

        // ---- 阻力与重力 (只用 TACZ 的参数，不叠加 vanilla 0.99f) ----
        // 使用处再夹一次下限（对齐上游 Mth.clamp(..., 0, MAX)）：负 friction 会让 (1 - friction) > 1
        // 反而加速，负 gravity 会让 -gravity 向上推。水下覆盖仍然优先于这里的夹取结果。
        var frictionFactor = friction.coerceAtLeast(0.0f)
        var gravityValue = bulletGravity.coerceAtLeast(0.0f)
        if (isInWater) {
            frictionFactor = 0.4f
            gravityValue *= 0.6f
        }
        motionX *= (1.0 - frictionFactor)
        motionY *= (1.0 - frictionFactor)
        motionZ *= (1.0 - frictionFactor)
        motionY -= gravityValue

        // ---- 寿命检查 ----
        lifespan--
        if (lifespan <= 0) setDead()
    }

    private fun lerpRotation(previous: Float, current: Float): Float {
        var delta = MathHelper.wrapDegrees(current - previous)
        delta = delta.coerceIn(-180.0f, 180.0f)
        return previous + delta * 0.2f
    }

    private fun applyDirectHitDamage(target: Entity, hitPosition: Vec3d, headShot: Boolean): HitFeedbackResult {
        val totalDamage = resolveEffectiveDamageAt(hitPosition)
        if (totalDamage <= 0.0f) {
            return HitFeedbackResult(false, 0.0f, 1.0f, headShot)
        }
        val headShotDamage = if (headShot) totalDamage * headShotMultiplier else totalDamage
        val damageSplit = splitDamage(headShotDamage)
        if (target is EntityLivingBase) {
            target.hurtResistantTime = 0
        }
        var applied = false
        if (damageSplit.first > 0.0f) {
            applied = target.attackEntityFrom(createBulletDamageSource(ignoreArmor = false, target = target), damageSplit.first) || applied
        }
        if (damageSplit.second > 0.0f) {
            if (target is EntityLivingBase) {
                target.hurtResistantTime = 0
            }
            applied = target.attackEntityFrom(createBulletDamageSource(ignoreArmor = true, target = target), damageSplit.second) || applied
        }
        if (applied) {
            spawnDamageIndicator(target, headShotDamage)
        }
        applyImpactKnockback(target)
        if (igniteEntity && !target.world.isRemote && LegacyConfigManager.common.igniteEntity) {
            target.setFire(igniteEntityTime)
        }
        return HitFeedbackResult(
            appliedDamage = applied,
            baseDamage = totalDamage,
            headShotMultiplier = if (headShot) headShotMultiplier else 1.0f,
            headShot = headShot,
        )
    }

    /**
     * 伤害指示粒子（红色小心心）。1.12 原版的 EntityLivingBase 不会自动生成，
     * 这里补上 1.14+（即上游 1.20）LivingEntity#hurt 的行为：
     * 数量 (int)(damage * 0.5)，位置为目标中心 y + 0.5，散布 0.1 / 0 / 0.1，速度 0.2。
     * onImpact 已保证非 remote，这里再防御一次，避免客户端重复生成。
     */
    private fun spawnDamageIndicator(target: Entity, damage: Float) {
        if (world.isRemote) {
            return
        }
        val count = (damage * 0.5f).toInt()
        if (count <= 0) {
            return
        }
        // 1.12 的「数量 + 散布」批量重载只声明在 WorldServer 上（World 基类没有 count 版本），
        // onImpact 已保证服务端，所以这里的转换不会失败；失败时直接跳过而不是退回单粒子循环。
        val serverWorld = world as? WorldServer ?: return
        serverWorld.spawnParticle(
            EnumParticleTypes.DAMAGE_INDICATOR,
            target.posX,
            target.posY + 0.5,
            target.posZ,
            count,
            0.1,
            0.0,
            0.1,
            0.2,
        )
    }

    private fun emitHitFeedback(target: Entity, feedback: HitFeedbackResult) {
        if (world.isRemote) {
            return
        }
        if (!feedback.appliedDamage) {
            return
        }
        val attacker = thrower
        if (target is EntityLivingBase && !target.isEntityAlive) {
            MinecraftForge.EVENT_BUS.post(
                EntityKillByGunEvent(
                    bullet = this,
                    killedEntity = target,
                    attacker = attacker,
                    gunId = gunId,
                    gunDisplayId = gunDisplayId,
                    baseDamage = feedback.baseDamage,
                    isHeadShot = feedback.headShot,
                    headShotMultiplier = feedback.headShotMultiplier,
                    logicalSide = Side.SERVER,
                ),
            )
            TACZNetworkHandler.sendToDimension(
                ServerMessageGunKill(
                    bulletId = entityId,
                    killedEntityId = target.entityId,
                    attackerId = attacker?.entityId ?: -1,
                    gunId = gunId,
                    gunDisplayId = gunDisplayId,
                    baseDamage = feedback.baseDamage,
                    isHeadShot = feedback.headShot,
                    headShotMultiplier = feedback.headShotMultiplier,
                ),
                world.provider.dimension,
            )
            return
        }

        MinecraftForge.EVENT_BUS.post(
            EntityHurtByGunEvent.Post(
                bullet = this,
                hurtEntity = target,
                attacker = attacker,
                gunId = gunId,
                gunDisplayId = gunDisplayId,
                baseAmount = feedback.baseDamage,
                isHeadShot = feedback.headShot,
                headShotMultiplier = feedback.headShotMultiplier,
                logicalSide = Side.SERVER,
            ),
        )
        TACZNetworkHandler.sendToDimension(
            ServerMessageGunHurt(
                bulletId = entityId,
                hurtEntityId = target.entityId,
                attackerId = attacker?.entityId ?: -1,
                gunId = gunId,
                gunDisplayId = gunDisplayId,
                baseAmount = feedback.baseDamage,
                isHeadShot = feedback.headShot,
                headShotMultiplier = feedback.headShotMultiplier,
                hurtBobTweakMultiplier = com.tacz.legacy.common.resource.GunDataAccessor
                    .getGunData(gunId)?.hurtBobTweakMultiplier ?: 0.05f,
            ),
            world.provider.dimension,
        )
    }

    private fun createBulletDamageSource(ignoreArmor: Boolean, target: Entity): DamageSource {
        val rawType = "tacz_bullet_${ammoId.namespace}_${ammoId.path}"
        val damageType = rawType.replace(Regex("[^a-zA-Z0-9_]"), "_")

        val damageSource = if (isTeleportDodgeProne(target)) {
            EntityDamageSource(damageType, thrower)
        } else {
            EntityDamageSourceIndirect(damageType, this, thrower)
        }.setProjectile()

        return if (ignoreArmor) damageSource.setDamageBypassesArmor() else damageSource
    }

    private fun isTeleportDodgeProne(target: Entity): Boolean {
        val registryId = EntityList.getKey(target) ?: return false
        val configuredIds = LegacyConfigManager.common.teleportDodgeEntityIds
        return configuredIds.any { it.equals(registryId.toString(), ignoreCase = true) }
    }
    
    private fun applyImpactKnockback(target: Entity) {
        if (knockback <= 0.0f) {
            return
        }
        // 与上游 TACZ 一致：额外击退只作用于 LivingEntity（上游通过 KnockBackModifier 修改 LivingEntity.knockback）。
        // 矿车、船等非生物实体几乎没有阻力，直接叠加速度会被打飞。
        if (target !is EntityLivingBase) {
            return
        }
        val horizontalSpeed = sqrt(motionX * motionX + motionZ * motionZ)
        if (horizontalSpeed <= 1.0E-6) {
            return
        }
        val normX = motionX / horizontalSpeed
        val normZ = motionZ / horizontalSpeed

        val resistance = target.getEntityAttribute(SharedMonsterAttributes.KNOCKBACK_RESISTANCE)?.attributeValue ?: 0.0
        if (resistance >= 1.0) {
            return
        }
        val effectiveKnockback = (knockback * (1.0 - resistance)).toFloat()

        target.addVelocity(normX * effectiveKnockback * 0.6, 0.1, normZ * effectiveKnockback * 0.6)
        target.velocityChanged = true
    }

    private fun resolveEffectiveDamageAt(hitPosition: Vec3d): Float {
        bakeDamageBaseMultiplier()
        val origin = Vec3d(startPosX, startPosY, startPosZ)
        val travelDistance = origin.distanceTo(hitPosition)
        val adjustedDamage = if (damageAdjust.isEmpty()) {
            damage
        } else {
            // 审计 B1-10：超出最后一个**有限**距离档 → 上游 getDamage(.orig_EntityKineticBullet.java:517-533)
            // 的 base 保持 0（注释原文"如果忘记写最大值，那我就直接认为你伤害为 0"），
            // 不是回落到末档伤害。末档写成 infinity 的枪两条路径一致。
            damageAdjust.firstOrNull { travelDistance < it.distance || it.distance.isInfinite() }?.damage ?: 0f
        }
        // 顺序对齐上游 getDamage：距离曲线伤害（已含配置倍率）* 霰弹分摊 * 脚本单发倍率，最后统一抬到 0
        return (adjustedDamage * damageModifier * shotDamageMultiplier).coerceAtLeast(0.0f)
    }

    /**
     * 把配置项 `damage_base_multiplier` 烘进基准伤害与距离曲线各档。
     *
     * 审计 B1-9：旧实现在结算处事后相乘，配件的**绝对值** modifier 会被一起乘 ——
     * `damage:{"addend":5}` 在上游是 `base*m + 5`，在移植版是 `(base + 5) * m`。
     * 默认包只用 multiplier/percent（比例不变）且 m 默认 1.0，所以现网不可见，但语义确实偏了。
     * 幂等：只烘一次；无配件的枪在首次结算处补烘，保证任何路径都恰好乘一遍。
     */
    private fun bakeDamageBaseMultiplier() {
        if (damageBaseBaked) return
        damageBaseBaked = true
        val multiplier = LegacyConfigManager.server.damageBaseMultiplier.toFloat()
        if (multiplier == 1.0f) return
        damage *= multiplier
        for (i in damageAdjust.indices) {
            damageAdjust[i] = damageAdjust[i].let { point -> point.copy(damage = point.damage * multiplier) }
        }
    }

    private fun splitDamage(totalDamage: Float): Pair<Float, Float> {
        val armorPiercingDamage = (totalDamage * armorIgnore.coerceIn(0.0f, 1.0f)).coerceAtLeast(0.0f)
        val normalDamage = (totalDamage - armorPiercingDamage).coerceAtLeast(0.0f)
        return normalDamage to armorPiercingDamage
    }

    private fun isHeadShot(target: EntityLivingBase, hitPosition: Vec3d): Boolean {
        return resolveHeadShotBox(target).contains(hitPosition)
    }

    private fun resolveHeadShotBox(target: EntityLivingBase): AxisAlignedBB {
        val configured = EntityList.getKey(target)?.let(HeadShotAabbConfigRead::getAabb)
        if (configured != null) {
            return configured.offset(target.posX, target.posY, target.posZ).grow(0.01)
        }
        val halfWidth = target.width / 2.0
        return AxisAlignedBB(
            target.posX - halfWidth,
            target.posY + target.eyeHeight - 0.25,
            target.posZ - halfWidth,
            target.posX + halfWidth,
            target.posY + target.eyeHeight + 0.25,
            target.posZ + halfWidth,
        ).grow(0.01)
    }
}

internal class TargetMinecartEntity : EntityMinecartEmpty {
    constructor(worldIn: World) : super(worldIn)

    constructor(worldIn: World, x: Double, y: Double, z: Double) : super(worldIn, x, y, z)

    /**
     * 被子弹击中时只播放受击效果，不累积矿车损坏值，否则靶车会被几发子弹直接打掉。
     * 其他伤害（玩家近战等）保持原版矿车行为。
     */
    override fun attackEntityFrom(source: DamageSource, amount: Float): Boolean {
        if (source.immediateSource !is EntityKineticBullet) {
            return super.attackEntityFrom(source, amount)
        }
        if (world.isRemote || isDead) {
            return true
        }
        if (isEntityInvulnerable(source)) {
            return false
        }
        rollingDirection = -rollingDirection
        rollingAmplitude = 10
        markVelocityChanged()
        world.playSound(null, posX, posY, posZ, LegacySoundEvents.TARGET_BLOCK_HIT, SoundCategory.NEUTRAL, 1.0f, 1.0f)
        return true
    }

    /** 掉落靶车物品而不是原版矿车 */
    override fun killMinecart(source: DamageSource) {
        setDead()
        if (world.gameRules.getBoolean("doEntityDrops")) {
            val stack = ItemStack(LegacyItems.TARGET_MINECART)
            if (hasCustomName()) {
                stack.setStackDisplayName(customNameTag)
            }
            entityDropItem(stack, 0.0f)
        }
    }

    override fun getCartItem(): ItemStack = ItemStack(LegacyItems.TARGET_MINECART)
}
