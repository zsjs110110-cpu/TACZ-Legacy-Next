package com.tacz.legacy.client.renderer.item

import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.client.model.BedrockAttachmentModel
import com.tacz.legacy.client.model.SlotModel
import com.tacz.legacy.client.model.TACZPerspectiveAwareBakedModel
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.pojo.display.attachment.AttachmentDisplay
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZMissingIdReporter
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.block.model.ItemCameraTransforms
import net.minecraft.client.renderer.block.model.ItemCameraTransforms.TransformType
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

/**
 * TEISR for attachment items.
 *
 * Context-aware via [TACZPerspectiveAwareBakedModel]:
 * - **Item presentation contexts** (GUI, dropped, fixed, head) → flat slot texture
 * - **Hand / 3D contexts** → bedrock model
 */
@SideOnly(Side.CLIENT)
internal object TACZAttachmentItemRenderer : TileEntityItemStackRenderer() {
    private val SLOT_MODEL = SlotModel()

    override fun renderByItem(stack: ItemStack) {
        renderByItem(stack, 1.0f)
    }

    override fun renderByItem(stack: ItemStack, partialTicks: Float) {
        val item = stack.item
        if (item !is IAttachment) return

        val attachmentId = item.getAttachmentId(stack)
        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()

        val displayId = TACZGunPackPresentation.resolveAttachmentDisplayId(snapshot, attachmentId)
            ?: run {
                TACZMissingIdReporter.report("attachment display", attachmentId, "attachment item renderer")
                return
            }
        val display: AttachmentDisplay = TACZClientAssetManager.getAttachmentDisplay(displayId) ?: return

        val transformType = TACZPerspectiveAwareBakedModel.getCurrentTransformType()

        // Item presentation contexts → always use flat slot texture。
        // 注意：GUI 渲染走 ItemRenderer.renderItemModelIntoGUI，该路径不调用 handleCameraTransforms，
        // 因此 currentTransformType 可能是上一次渲染遗留的值（如 FIRST_PERSON），仅凭它判断会把瞄具
        // 当成 3D 上下文渲染，division 骨骼（黑色遮罩+十字分划）就会出现在 GUI 里。
        // 用 currentScreen 兜底：GUI 内一律走平面槽位贴图。
        val inGui = Minecraft.getMinecraft().currentScreen != null
        if (inGui || TACZPerspectiveAwareBakedModel.isItemPresentationContext(transformType)) {
            renderSlotTexture(display)
            return
        }

        // Hand / 3D contexts → try 3D model, else fall back to slot
        // 必须走 ClientAttachmentIndex 里的 BedrockAttachmentModel：其构造器会隐藏 division
        // 等光学节点，裸 BedrockModel 会把黑色遮罩和十字分划当普通几何体全量渲染出来
        val index = TACZClientAssetManager.getAttachmentIndex(attachmentId)
        val model: BedrockAttachmentModel? = index?.attachmentModel
        val textureLoc: ResourceLocation? = index?.modelTexture
        val registeredTexture: ResourceLocation? = if (textureLoc != null) TACZClientAssetManager.getTextureLocation(textureLoc) else null

        if (model == null || registeredTexture == null) {
            renderSlotTexture(display)
            return
        }

        GlStateManager.pushMatrix()
        Minecraft.getMinecraft().textureManager.bindTexture(registeredTexture)

        GlStateManager.translate(0.5f, 2.0f, 0.5f)
        GlStateManager.scale(-1f, -1f, 1f)

        // Fixed context: rotate 90° like upstream
        if (transformType == TransformType.FIXED) {
            GlStateManager.rotate(-90f, 0f, 1f, 0f)
        }

        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

        model.render(stack, null)
        model.cleanAnimationTransform()
        model.cleanCameraAnimationTransform()

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    private fun renderSlotTexture(display: AttachmentDisplay) {
        val slotTexLoc = display.slotTextureLocation ?: return
        val registeredSlot = TACZClientAssetManager.getTextureLocation(slotTexLoc) ?: return
        Minecraft.getMinecraft().textureManager.bindTexture(registeredSlot)

        GlStateManager.pushMatrix()
        GlStateManager.translate(0.5f, 1.5f, 0.5f)
        GlStateManager.rotate(180f, 0f, 0f, 1f)

        // GUI 中的槽位贴图是平面图标，应与原版 2D 物品一样不受光照影响，否则会显得发暗
        if (TACZPerspectiveAwareBakedModel.getCurrentTransformType() == ItemCameraTransforms.TransformType.GUI) {
            GlStateManager.disableLighting()
        } else {
            GlStateManager.enableLighting()
        }
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

        SLOT_MODEL.render()

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }
}
