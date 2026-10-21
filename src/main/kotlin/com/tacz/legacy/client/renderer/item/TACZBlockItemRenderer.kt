package com.tacz.legacy.client.renderer.item

import com.tacz.legacy.client.model.TACZPerspectiveAwareBakedModel
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.pojo.display.block.BlockDisplay
import com.tacz.legacy.client.resource.pojo.display.block.BlockTransform
import com.tacz.legacy.common.item.LegacyBlockItem
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

/**
 * TEISR for block items (GunSmithTable, Workbench variants).
 *
 * Applies per-context transforms from [BlockDisplay.getTransforms], then
 * renders the bedrock model. Block displays do not have a slot texture.
 */
@SideOnly(Side.CLIENT)
internal object TACZBlockItemRenderer : TileEntityItemStackRenderer() {

    override fun renderByItem(stack: ItemStack) {
        renderByItem(stack, 1.0f)
    }

    override fun renderByItem(stack: ItemStack, partialTicks: Float) {
        val item = stack.item
        if (item !is LegacyBlockItem) return

        val blockId = item.getBlockId(stack)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()

        val displayId = TACZGunPackPresentation.resolveBlockDisplayId(snapshot, blockId) ?: return
        val display: BlockDisplay = TACZClientAssetManager.getBlockDisplay(displayId) ?: return

        val modelLocation: ResourceLocation = display.modelLocation ?: return
        val model: BedrockModel = TACZClientAssetManager.getStaticBlockModel(modelLocation) ?: return

        val textureLocation: ResourceLocation = display.modelTexture ?: return
        val registeredTexture: ResourceLocation = TACZClientAssetManager.getTextureLocation(textureLocation) ?: return

        Minecraft.getMinecraft().textureManager.bindTexture(registeredTexture)

        val transformType = TACZPerspectiveAwareBakedModel.getCurrentTransformType()

        GlStateManager.pushMatrix()

        // Apply per-context transforms from block display JSON (upstream GunSmithTableItemRenderer pattern)
        val transforms: BlockTransform? = display.transforms
        val entry: BlockTransform.Entry? = transforms?.getForType(transformType)
        if (entry != null) {
            GlStateManager.translate(0.5f, 0.5f, 0.5f)
            entry.apply()
            GlStateManager.translate(-0.5f, -0.5f, -0.5f)
        }

        GlStateManager.translate(0.5f, 1.5f, 0.5f)
        GlStateManager.rotate(180f, 0f, 0f, 1f)

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
}
