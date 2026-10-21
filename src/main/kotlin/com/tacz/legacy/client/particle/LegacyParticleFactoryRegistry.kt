package com.tacz.legacy.client.particle

import com.tacz.legacy.common.config.LegacyConfigManager
import net.minecraft.block.state.IBlockState
import net.minecraft.client.Minecraft
import net.minecraft.client.particle.IParticleFactory
import net.minecraft.client.particle.Particle
import net.minecraft.client.renderer.BufferBuilder
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.block.Block
import net.minecraft.entity.Entity
import net.minecraft.util.EnumFacing
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

@SideOnly(Side.CLIENT)
internal object LegacyParticleFactoryRegistry {
    internal const val BULLET_HOLE_ID: Int = 2001

    internal fun register(): Unit {
        Minecraft.getMinecraft().effectRenderer.registerParticle(BULLET_HOLE_ID, BulletHoleParticleFactory())
    }
}

/**
 * 参数：`[blockStateId, faceIndex, blockX, blockY, blockZ]`。
 */
@SideOnly(Side.CLIENT)
internal class BulletHoleParticleFactory : IParticleFactory {
    override fun createParticle(
        particleID: Int,
        worldIn: World,
        xCoordIn: Double,
        yCoordIn: Double,
        zCoordIn: Double,
        xSpeedIn: Double,
        ySpeedIn: Double,
        zSpeedIn: Double,
        vararg parameters: Int,
    ): Particle? {
        if (parameters.size < 5) {
            return null
        }
        val state = Block.getStateById(parameters[0])
        val face = EnumFacing.byIndex(parameters[1])
        val blockPos = BlockPos(parameters[2], parameters[3], parameters[4])
        return BulletHoleParticle(worldIn, xCoordIn, yCoordIn, zCoordIn, face, blockPos, state)
    }
}

/**
 * 弹孔粒子：贴在被击中方块表面的一小块暗色方块材质，与上游 TACZ BulletHoleParticle 的表现一致。
 * 方块被破坏或替换后自动消失。
 */
@SideOnly(Side.CLIENT)
internal class BulletHoleParticle(
    worldIn: World,
    xCoordIn: Double,
    yCoordIn: Double,
    zCoordIn: Double,
    private val face: EnumFacing,
    private val blockPos: BlockPos,
    private val blockState: IBlockState,
) : Particle(worldIn, xCoordIn, yCoordIn, zCoordIn) {
    private val uo: Float
    private val vo: Float

    init {
        canCollide = false
        motionX = 0.0
        motionY = 0.0
        motionZ = 0.0
        particleGravity = 0.0f
        particleMaxAge = LegacyConfigManager.client.bulletHoleParticleLife.coerceAtLeast(1)
        particleAlpha = 0.9f
        particleScale = 0.05f
        particleRed = 0.1f
        particleGreen = 0.1f
        particleBlue = 0.1f
        val sprite: TextureAtlasSprite = Minecraft.getMinecraft().blockRendererDispatcher.blockModelShapes.getTexture(blockState)
        setParticleTexture(sprite)
        uo = rand.nextFloat() * 3.0f
        vo = rand.nextFloat() * 3.0f
    }

    override fun getFXLayer(): Int = 1

    override fun onUpdate(): Unit {
        prevPosX = posX
        prevPosY = posY
        prevPosZ = posZ
        if (particleAge++ >= particleMaxAge || world.getBlockState(blockPos) !== blockState) {
            setExpired()
            return
        }
        val threshold = LegacyConfigManager.client.bulletHoleParticleFadeThreshold.coerceIn(0.0, 1.0)
        val progress = particleAge.toDouble() / particleMaxAge.toDouble()
        if (progress >= threshold && threshold < 1.0) {
            val remaining = ((1.0 - progress) / (1.0 - threshold)).coerceIn(0.0, 1.0)
            particleAlpha = 0.9f * remaining.toFloat()
        }
    }

    override fun renderParticle(
        buffer: BufferBuilder,
        entityIn: Entity,
        partialTicks: Float,
        rotationX: Float,
        rotationZ: Float,
        rotationYZ: Float,
        rotationXY: Float,
        rotationXZ: Float,
    ) {
        val sprite = particleTexture ?: return
        // 取方块材质中 4x4 像素的一小块
        val minU = sprite.getInterpolatedU((uo * 4.0f).toDouble())
        val maxU = sprite.getInterpolatedU(((uo + 1.0f) * 4.0f).toDouble())
        val minV = sprite.getInterpolatedV((vo * 4.0f).toDouble())
        val maxV = sprite.getInterpolatedV(((vo + 1.0f) * 4.0f).toDouble())

        val size = particleScale.toDouble()
        // 略微偏离表面防止 Z-fighting
        val offset = 0.005
        val cx = posX - Particle.interpPosX + face.xOffset * offset
        val cy = posY - Particle.interpPosY + face.yOffset * offset
        val cz = posZ - Particle.interpPosZ + face.zOffset * offset

        // 在击中面所在平面内构造两条正交轴
        val (ax, ay, az, bx, by, bz) = when (face.axis) {
            EnumFacing.Axis.X -> Axes(0.0, size, 0.0, 0.0, 0.0, size)
            EnumFacing.Axis.Y -> Axes(size, 0.0, 0.0, 0.0, 0.0, size)
            EnumFacing.Axis.Z -> Axes(size, 0.0, 0.0, 0.0, size, 0.0)
        }

        val light = getBrightnessForRender(partialTicks)
        val skyLight = light shr 16 and 65535
        val blockLight = light and 65535
        val corners = arrayOf(
            doubleArrayOf(-1.0, -1.0, maxU.toDouble(), maxV.toDouble()),
            doubleArrayOf(-1.0, 1.0, maxU.toDouble(), minV.toDouble()),
            doubleArrayOf(1.0, 1.0, minU.toDouble(), minV.toDouble()),
            doubleArrayOf(1.0, -1.0, minU.toDouble(), maxV.toDouble()),
        )
        // 正面与背面各绘制一次，保证任意朝向的面都不会被背面剔除
        val order = if (face.axisDirection == EnumFacing.AxisDirection.POSITIVE) intArrayOf(0, 1, 2, 3) else intArrayOf(3, 2, 1, 0)
        for (pass in 0 until 2) {
            val indices = if (pass == 0) order else order.reversedArray()
            for (index in indices) {
                val corner = corners[index]
                buffer.pos(
                    cx + ax * corner[0] + bx * corner[1],
                    cy + ay * corner[0] + by * corner[1],
                    cz + az * corner[0] + bz * corner[1],
                )
                    .tex(corner[2], corner[3])
                    .color(particleRed, particleGreen, particleBlue, particleAlpha)
                    .lightmap(skyLight, blockLight)
                    .endVertex()
            }
        }
    }

    override fun getBrightnessForRender(partialTick: Float): Int {
        // 使用被击中面外侧方块的光照，而不是方块内部（通常为 0）
        return world.getCombinedLight(blockPos.offset(face), 0)
    }

    private data class Axes(
        val ax: Double,
        val ay: Double,
        val az: Double,
        val bx: Double,
        val by: Double,
        val bz: Double,
    )
}
