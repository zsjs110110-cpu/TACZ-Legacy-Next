package com.tacz.legacy.client.particle

import com.tacz.legacy.common.entity.EntityKineticBullet
import com.tacz.legacy.common.registry.LegacySoundEvents
import net.minecraft.block.Block
import net.minecraft.client.Minecraft
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumParticleTypes
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

/**
 * 子弹相关的纯客户端表现：命中方块的弹孔 / 碎屑 / 音效，以及子弹从玩家身边掠过的音效。
 */
@SideOnly(Side.CLIENT)
internal object LegacyBulletClientEffects {
    /** 子弹轨迹与玩家眼睛的距离小于该值时播放掠过音效 */
    private const val WHIZ_DISTANCE: Double = 3.0
    private const val BLOCK_CRACK_PARTICLE_COUNT: Int = 6

    internal fun onBulletHitBlock(blockPos: BlockPos, face: EnumFacing, hitX: Double, hitY: Double, hitZ: Double) {
        val minecraft = Minecraft.getMinecraft()
        val world = minecraft.world ?: return
        val state = world.getBlockState(blockPos)
        if (state.material.isLiquid || world.isAirBlock(blockPos)) {
            return
        }
        val stateId = Block.getStateId(state)

        // 弹孔
        minecraft.effectRenderer.spawnEffectParticle(
            LegacyParticleFactoryRegistry.BULLET_HOLE_ID,
            hitX,
            hitY,
            hitZ,
            0.0,
            0.0,
            0.0,
            stateId,
            face.index,
            blockPos.x,
            blockPos.y,
            blockPos.z,
        )

        // 方块碎屑
        val random = world.rand
        for (i in 0 until BLOCK_CRACK_PARTICLE_COUNT) {
            world.spawnParticle(
                EnumParticleTypes.BLOCK_CRACK,
                hitX,
                hitY,
                hitZ,
                face.xOffset * 0.1 + (random.nextDouble() - 0.5) * 0.15,
                face.yOffset * 0.1 + (random.nextDouble() - 0.5) * 0.15,
                face.zOffset * 0.1 + (random.nextDouble() - 0.5) * 0.15,
                stateId,
            )
        }

        // 击中音效：使用方块自身的击中声音
        val soundType = state.block.getSoundType(state, world, blockPos, null)
        world.playSound(
            hitX,
            hitY,
            hitZ,
            soundType.hitSound,
            SoundCategory.BLOCKS,
            (soundType.getVolume() + 1.0f) * 0.5f,
            soundType.getPitch() * (0.9f + random.nextFloat() * 0.2f),
            false,
        )
    }

    /**
     * 每个客户端 tick 由子弹调用：若子弹本 tick 的轨迹从本地玩家附近经过，则在最近点播放掠过音效。
     * 射手本人不会听到自己的子弹掠过。
     */
    internal fun tickBulletWhiz(bullet: EntityKineticBullet) {
        if (bullet.whizPlayed) {
            return
        }
        val player = Minecraft.getMinecraft().player ?: return
        if (bullet.shooterId == player.entityId || bullet.ticksExisted < 2) {
            return
        }
        val start = Vec3d(bullet.lastTickPosX, bullet.lastTickPosY, bullet.lastTickPosZ)
        val end = Vec3d(bullet.posX, bullet.posY, bullet.posZ)
        val eye = player.getPositionEyes(1.0f)
        val closest = closestPointOnSegment(start, end, eye)
        if (closest.squareDistanceTo(eye) > WHIZ_DISTANCE * WHIZ_DISTANCE) {
            return
        }
        bullet.whizPlayed = true
        val world = bullet.world
        world.playSound(
            closest.x,
            closest.y,
            closest.z,
            LegacySoundEvents.BULLET_WHIZ,
            SoundCategory.PLAYERS,
            1.0f,
            0.9f + world.rand.nextFloat() * 0.2f,
            false,
        )
    }

    internal fun closestPointOnSegment(start: Vec3d, end: Vec3d, point: Vec3d): Vec3d {
        val segment = end.subtract(start)
        val lengthSq = segment.x * segment.x + segment.y * segment.y + segment.z * segment.z
        if (lengthSq <= 1.0E-12) {
            return start
        }
        val t = (point.subtract(start).dotProduct(segment) / lengthSq).coerceIn(0.0, 1.0)
        return start.add(segment.scale(t))
    }
}
