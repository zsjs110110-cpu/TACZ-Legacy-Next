package com.tacz.legacy.common.entity

import com.google.common.collect.Sets
import com.tacz.legacy.common.config.LegacyConfigManager
import net.minecraft.block.material.Material
import net.minecraft.block.state.IBlockState
import net.minecraft.enchantment.EnchantmentProtection
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.entity.player.EntityPlayerMP
import net.minecraft.network.play.server.SPacketExplosion
import net.minecraft.util.DamageSource
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
import net.minecraft.util.math.Vec3d
import net.minecraft.world.Explosion
import net.minecraft.world.WorldServer
import net.minecraftforge.event.ForgeEventFactory
import kotlin.math.sqrt

internal object LegacyProjectileExplosionHelper {
    internal fun createExplosion(
        owner: Entity?,
        exploder: Entity,
        x: Double,
        y: Double,
        z: Double,
        damage: Float,
        radius: Float,
        knockback: Boolean,
        destroyBlock: Boolean,
    ) {
        val worldServer = exploder.world as? WorldServer ?: return
        val actualRadius = radius.coerceAtLeast(0.0f)
        val actualDamage = damage.coerceAtLeast(0.0f)
        val damagesTerrain = destroyBlock && LegacyConfigManager.common.explosiveAmmoDestroysBlock
        val allowKnockback = knockback && LegacyConfigManager.common.explosiveAmmoKnockBack
        val explosion = LegacyProjectileExplosion(
            world = worldServer,
            owner = owner,
            exploder = exploder,
            centerX = x,
            centerY = y,
            centerZ = z,
            damagePower = actualDamage,
            radius = actualRadius,
            applyKnockback = allowKnockback,
            damagesTerrain = damagesTerrain,
        )
        if (ForgeEventFactory.onExplosionStart(worldServer, explosion)) {
            return
        }
        explosion.doExplosionA()
        explosion.doExplosionB(false)
        if (!damagesTerrain) {
            explosion.clearAffectedBlockPositions()
        }
        for (player in worldServer.playerEntities) {
            if (player.getDistanceSq(x, y, z) < 4096.0) {
                (player as EntityPlayerMP).connection.sendPacket(
                    SPacketExplosion(
                        x,
                        y,
                        z,
                        actualRadius,
                        explosion.getAffectedBlockPositions(),
                        explosion.getPlayerKnockbackMap()[player],
                    )
                )
            }
        }
    }
}

internal class LegacyProjectileExplosion(
    private val world: WorldServer,
    private val owner: Entity?,
    private val exploder: Entity,
    private val centerX: Double,
    private val centerY: Double,
    private val centerZ: Double,
    private val damagePower: Float,
    private val radius: Float,
    private val applyKnockback: Boolean,
    damagesTerrain: Boolean,
) : Explosion(world, exploder, centerX, centerY, centerZ, radius, false, damagesTerrain) {
    private val center: Vec3d = Vec3d(centerX, centerY, centerZ)

    override fun getExplosivePlacedBy(): EntityLivingBase? {
        return owner as? EntityLivingBase ?: super.getExplosivePlacedBy()
    }

    override fun doExplosionA() {
        if (radius > 0.0f) {
            collectAffectedBlocks()
        }
        if (radius <= 0.0f && damagePower <= 0.0f) {
            return
        }
        val doubledRadius = radius * 2.0f
        val minX = MathHelper.floor(centerX - doubledRadius - 1.0)
        val maxX = MathHelper.floor(centerX + doubledRadius + 1.0)
        val minY = MathHelper.floor(centerY - doubledRadius - 1.0)
        val maxY = MathHelper.floor(centerY + doubledRadius + 1.0)
        val minZ = MathHelper.floor(centerZ - doubledRadius - 1.0)
        val maxZ = MathHelper.floor(centerZ + doubledRadius + 1.0)
        val nearbyEntities = world.getEntitiesWithinAABBExcludingEntity(
            exploder,
            AxisAlignedBB(minX.toDouble(), minY.toDouble(), minZ.toDouble(), maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())
        )
        ForgeEventFactory.onExplosionDetonate(world, this, nearbyEntities, doubledRadius.toDouble())

        val knockbackScale = (damagePower * radius / 500.0f).coerceAtLeast(0.0f).toDouble()
        for (entity in nearbyEntities) {
            if (entity.isImmuneToExplosions) {
                continue
            }
            val distanceRatio = if (doubledRadius <= 0.0f) {
                0.0
            } else {
                entity.getDistance(centerX, centerY, centerZ) / doubledRadius.toDouble()
            }
            if (distanceRatio > 1.0) {
                continue
            }

            val dx = entity.posX - centerX
            val dy = entity.posY + entity.eyeHeight.toDouble() - centerY
            val dz = entity.posZ - centerZ
            val length = sqrt(dx * dx + dy * dy + dz * dz)
            if (length == 0.0) {
                continue
            }
            val exposure = world.getBlockDensity(center, entity.entityBoundingBox)
            val impact = (1.0 - distanceRatio) * exposure
            if (impact <= 0.0) {
                continue
            }

            val damageAmount = (impact * damagePower).toFloat().coerceAtLeast(0.0f)
            if (damageAmount > 0.0f) {
                entity.attackEntityFrom(DamageSource.causeExplosionDamage(this), damageAmount)
            }

            if (!applyKnockback || knockbackScale <= 0.0) {
                continue
            }

            val normX = dx / length
            val normY = dy / length
            val normZ = dz / length
            var knockbackAmount = impact
            if (entity is EntityLivingBase) {
                knockbackAmount = EnchantmentProtection.getBlastDamageReduction(entity, impact)
            }
            entity.motionX += normX * knockbackAmount * knockbackScale
            entity.motionY += normY * knockbackAmount * knockbackScale
            entity.motionZ += normZ * knockbackAmount * knockbackScale

            if (entity is EntityPlayer) {
                // 玩家的击退与原版一样只通过 SPacketExplosion 下发；若同时标记 velocityChanged，
                // 客户端会再收到一次速度同步，导致玩家受到双倍击退
                if (!entity.isSpectator && (!entity.isCreative || !entity.capabilities.isFlying)) {
                    getPlayerKnockbackMap()[entity] = Vec3d(normX * knockbackAmount * knockbackScale, normY * knockbackAmount * knockbackScale, normZ * knockbackAmount * knockbackScale)
                }
            } else {
                entity.velocityChanged = true
            }
        }
    }

    private fun collectAffectedBlocks() {
        val affectedBlocks = Sets.newHashSet<BlockPos>()
        for (xIndex in 0 until 16) {
            for (yIndex in 0 until 16) {
                for (zIndex in 0 until 16) {
                    if (xIndex != 0 && xIndex != 15 && yIndex != 0 && yIndex != 15 && zIndex != 0 && zIndex != 15) {
                        continue
                    }
                    var dx = (xIndex.toFloat() / 15.0f * 2.0f - 1.0f).toDouble()
                    var dy = (yIndex.toFloat() / 15.0f * 2.0f - 1.0f).toDouble()
                    var dz = (zIndex.toFloat() / 15.0f * 2.0f - 1.0f).toDouble()
                    val directionLength = sqrt(dx * dx + dy * dy + dz * dz)
                    dx /= directionLength
                    dy /= directionLength
                    dz /= directionLength
                    var remaining = radius * (0.7f + world.rand.nextFloat() * 0.6f)
                    var currentX = centerX
                    var currentY = centerY
                    var currentZ = centerZ

                    while (remaining > 0.0f) {
                        val blockPos = BlockPos(currentX, currentY, currentZ)
                        val blockState = world.getBlockState(blockPos)
                        if (blockState.material != Material.AIR) {
                            val resistance = exploder.getExplosionResistance(this, world, blockPos, blockState)
                            remaining -= (resistance + 0.3f) * 0.3f
                        }
                        if (remaining > 0.0f && exploder.canExplosionDestroyBlock(this, world, blockPos, blockState, remaining)) {
                            affectedBlocks.add(blockPos)
                        }
                        currentX += dx * 0.30000001192092896
                        currentY += dy * 0.30000001192092896
                        currentZ += dz * 0.30000001192092896
                        remaining -= 0.22500001f
                    }
                }
            }
        }
        getAffectedBlockPositions().addAll(affectedBlocks)
    }
}