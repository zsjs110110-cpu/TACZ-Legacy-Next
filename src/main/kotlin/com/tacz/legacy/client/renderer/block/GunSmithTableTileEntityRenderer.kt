package com.tacz.legacy.client.renderer.block

import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.pojo.display.block.BlockDisplay
import com.tacz.legacy.common.block.LegacyGunSmithTableBlock
import com.tacz.legacy.common.block.entity.GunSmithTableTileEntity
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer
import net.minecraft.util.EnumFacing
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

/**
 * World-space renderer for gun smith table / workbench blocks.
 *
 * Port of upstream TACZ [GunSmithTableRenderer]:
 * - Resolves block display from the TileEntity's blockId
 * - Renders the bedrock model at the block position with Z-180 flip
 */
@SideOnly(Side.CLIENT)
internal class GunSmithTableTileEntityRenderer : TileEntitySpecialRenderer<GunSmithTableTileEntity>() {

    override fun render(
        te: GunSmithTableTileEntity,
        x: Double, y: Double, z: Double,
        partialTicks: Float,
        destroyStage: Int,
        alpha: Float,
    ) {
        val blockId: ResourceLocation = te.resolvedBlockId()
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()

        val displayId = TACZGunPackPresentation.resolveBlockDisplayId(snapshot, blockId) ?: return
        val display: BlockDisplay = TACZClientAssetManager.getBlockDisplay(displayId) ?: return

        val modelLocation: ResourceLocation = display.modelLocation ?: return
        val model: BedrockModel = TACZClientAssetManager.getStaticBlockModel(modelLocation) ?: return

        val textureLocation: ResourceLocation = display.modelTexture ?: return
        val registeredTexture: ResourceLocation = TACZClientAssetManager.getTextureLocation(textureLocation) ?: return

        Minecraft.getMinecraft().textureManager.bindTexture(registeredTexture)

        GlStateManager.pushMatrix()
        GlStateManager.translate(x.toFloat() + 0.5f, y.toFloat() + 1.5f, z.toFloat() + 0.5f)
        GlStateManager.rotate(180f, 0f, 0f, 1f)
        // 根据方块朝向旋转模型。Z 轴 180° 翻转后 Y 轴旋转方向与世界坐标相反，
        // 因此这里取正角度，使模型的延伸方向与 LegacyGunSmithTableBlock.partPos 一致
        GlStateManager.rotate(resolveFacing(te).horizontalIndex * 90f, 0f, 1f, 0f)

        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

        model.render()

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    override fun isGlobalRenderer(te: GunSmithTableTileEntity): Boolean = true

    private fun resolveFacing(te: GunSmithTableTileEntity): EnumFacing {
        if (!te.hasWorld()) {
            return EnumFacing.NORTH
        }
        val state = te.world.getBlockState(te.pos)
        if (state.block !is LegacyGunSmithTableBlock) {
            return EnumFacing.NORTH
        }
        return state.getValue(LegacyGunSmithTableBlock.FACING)
    }
}
