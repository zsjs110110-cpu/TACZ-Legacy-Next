package com.tacz.legacy.client.gui

import com.tacz.legacy.api.item.IAttachment
import com.tacz.legacy.api.item.IAmmo
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.client.event.FirstPersonRenderMatrices
import com.tacz.legacy.client.model.BedrockAnimatedModel
import com.tacz.legacy.client.model.BedrockAttachmentModel
import com.tacz.legacy.client.model.BedrockGunModel
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.model.functional.BeamRenderer
import com.tacz.legacy.client.renderer.bloom.TACZBloomBridge
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.pojo.TransformScale
import com.tacz.legacy.client.resource.pojo.display.ammo.AmmoDisplay
import com.tacz.legacy.client.resource.pojo.display.attachment.AttachmentDisplay
import com.tacz.legacy.client.resource.pojo.display.block.BlockDisplay
import com.tacz.legacy.client.resource.pojo.display.gun.GunDisplay
import com.tacz.legacy.common.item.LegacyBlockItem
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZRuntimeSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11

internal object TACZGuiPreviewResolver {
    internal enum class PreviewKind {
        GUN,
        ATTACHMENT,
        AMMO,
        BLOCK,
    }

    internal data class PreviewTarget(
        val kind: PreviewKind,
        val displayId: ResourceLocation,
    )

    fun resolve(
        stack: ItemStack,
        snapshot: TACZRuntimeSnapshot = TACZGunPackRuntimeRegistry.getSnapshot(),
    ): PreviewTarget? {
        if (stack.isEmpty) {
            return null
        }
        val item = stack.item
        return when (item) {
            is IGun -> TACZGunPackPresentation.resolveGunDisplayId(snapshot, item.getGunId(stack))
                ?.let { PreviewTarget(PreviewKind.GUN, it) }
            is IAttachment -> TACZGunPackPresentation.resolveAttachmentDisplayId(snapshot, item.getAttachmentId(stack))
                ?.let { PreviewTarget(PreviewKind.ATTACHMENT, it) }
            is IAmmo -> TACZGunPackPresentation.resolveAmmoDisplayId(snapshot, item.getAmmoId(stack))
                ?.let { PreviewTarget(PreviewKind.AMMO, it) }
            is LegacyBlockItem -> TACZGunPackPresentation.resolveBlockDisplayId(snapshot, item.getBlockId(stack))
                ?.let { PreviewTarget(PreviewKind.BLOCK, it) }
            else -> null
        }
    }
}

internal object TACZGuiModelPreviewRenderer {
    private data class ResolvedPreviewModel(
        val modelId: ResourceLocation,
        val modelData: TACZClientAssetManager.ModelData,
        val textureLocation: ResourceLocation,
        val scaleMultiplier: Float,
        val verticalOffset: Float,
        /**
         * 附件专用：ClientAttachmentIndex 里的 BedrockAttachmentModel 会在构造器隐藏 division
         * 等光学节点。若回退到裸 BedrockModel，瞄具的黑色遮罩与十字分划会被当普通几何体渲染出来。
         */
        val attachmentModel: BedrockAttachmentModel? = null,
    )

    /** 模型几何中心（基岩版像素坐标），按 ModelData 缓存 */
    private val modelCenters = java.util.WeakHashMap<TACZClientAssetManager.ModelData, FloatArray?>()

    fun renderStackPreview(
        stack: ItemStack,
        centerX: Float,
        centerY: Float,
        scale: Float,
        yaw: Float = -30.0f,
        pitch: Float = 12.0f,
        refitAttachmentType: AttachmentType = AttachmentType.NONE,
    ): Boolean {
        if (stack.isEmpty || scale <= 0.0f) {
            return false
        }
        val target = TACZGuiPreviewResolver.resolve(stack) ?: return debugFail(null, "resolve ${stack.item.registryName}")
        if (target.kind == TACZGuiPreviewResolver.PreviewKind.GUN && renderGunStackPreview(stack, target.displayId, centerX, centerY, scale, yaw, pitch, refitAttachmentType)) {
            return true
        }
        val resolved = when (target.kind) {
            TACZGuiPreviewResolver.PreviewKind.GUN -> resolveGun(target.displayId)
            TACZGuiPreviewResolver.PreviewKind.ATTACHMENT -> resolveAttachment(stack, target.displayId)
            TACZGuiPreviewResolver.PreviewKind.AMMO -> resolveAmmo(target.displayId)
            TACZGuiPreviewResolver.PreviewKind.BLOCK -> resolveBlock(target.displayId)
        } ?: return false
        // renderModel 返回 false 表示模型取不到（此时不能返回 true，否则调用方不会回退 2D）
        return renderModel(stack, resolved, centerX, centerY, scale, yaw, pitch)
    }

    private fun renderGunStackPreview(
        stack: ItemStack,
        displayId: ResourceLocation,
        centerX: Float,
        centerY: Float,
        scale: Float,
        yaw: Float,
        pitch: Float,
        refitAttachmentType: AttachmentType,
    ): Boolean {
        val display: GunDisplay = TACZClientAssetManager.getGunDisplay(displayId) ?: return debugFail(displayId, "display")
        val displayInstance = TACZClientAssetManager.getGunDisplayInstance(displayId) ?: return debugFail(displayId, "instance")
        val model = displayInstance.gunModel ?: return debugFail(displayId, "model")
        val textureId = displayInstance.modelTexture ?: return debugFail(displayId, "textureId")
        val textureLocation = TACZClientAssetManager.getTextureLocation(textureId) ?: return debugFail(displayId, "texture $textureId")
        val scaleMultiplier = transformScaleMultiplier(display.transform?.scale, 1.0f)

        val mc = Minecraft.getMinecraft()
        mc.textureManager.bindTexture(textureLocation)

        GlStateManager.pushMatrix()
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)
        GlStateManager.translate(centerX, centerY, 300.0f)
        GlStateManager.scale(scale * scaleMultiplier, scale * scaleMultiplier, scale * scaleMultiplier)
        GlStateManager.rotate(180.0f, 0.0f, 0.0f, 1.0f)
        GlStateManager.rotate(yaw, 0.0f, 1.0f, 0.0f)
        GlStateManager.rotate(pitch, 1.0f, 0.0f, 0.0f)
        // 工作台预览（不对准某个配件槽位）时按枪模型几何中心居中，且不套用改装界面的视角矩阵
        // （该矩阵带有平移，原先与固定的 1.8 偏移一起让枪画在预览框左上方、压到搜索框）
        val gunCenter = if (refitAttachmentType == AttachmentType.NONE) {
            display.modelLocation?.let(TACZClientAssetManager::getModel)?.let(::modelCenter)
        } else {
            null
        }
        if (gunCenter == null) {
            applyPreviewViewMatrix(model, refitAttachmentType)
        } else {
            // 基岩模型的枪管朝 -Z，转 90° 让枪以侧面展示（与改装界面视角下的观感一致）
            GlStateManager.rotate(GUN_PREVIEW_SIDE_YAW, 0.0f, 1.0f, 0.0f)
        }
        if (gunCenter != null) {
            GlStateManager.translate(gunCenter[0] / 16.0f, 1.5f - gunCenter[1] / 16.0f, -gunCenter[2] / 16.0f)
        } else {
            GlStateManager.translate(0.0f, 1.8f, 0.0f)
        }
        GlStateManager.scale(-1.0f, -1.0f, 1.0f)

        RenderHelper.enableGUIStandardItemLighting()
        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.disableCull()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )
        // enable_transparency：半透明枪模型在 GUI 预览里同样需要关闭 alpha test，
        // 否则贴图 alpha 会被裁切成硬边。
        if (displayInstance.isEnableTransparency) {
            GlStateManager.disableAlpha()
        } else {
            GlStateManager.enableAlpha()
        }

        displayInstance.setActiveGunTexture(textureLocation)
        model.renderHand = false
        val previousBeamContext = BeamRenderer.pushRenderContext(BeamRenderer.RenderContext.GUI_PREVIEW)
        try {
            model.render(stack)
        } finally {
            BeamRenderer.popRenderContext(previousBeamContext)
        }
        captureBloomIfSupported(textureLocation, model) {
            model.renderHand = false
            model.renderBloom(stack)
        }
        model.cleanAnimationTransform()
        model.cleanCameraAnimationTransform()

        GlStateManager.enableCull()
        GlStateManager.enableAlpha()
        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        RenderHelper.disableStandardItemLighting()
        GlStateManager.popMatrix()
        return true
    }

    private fun applyPreviewViewMatrix(model: BedrockGunModel, refitAttachmentType: AttachmentType) {
        val matrix = previewViewMatrix(model, refitAttachmentType)
        GlStateManager.translate(0.0f, 1.5f, 0.0f)
        multiplyMatrix(matrix)
        GlStateManager.translate(0.0f, -1.5f, 0.0f)
    }

    private fun previewViewMatrix(model: BedrockGunModel, refitAttachmentType: AttachmentType): Matrix4f {
        val nodePath = FirstPersonRenderMatrices.fromBedrockPath(model.getRefitAttachmentViewPath(refitAttachmentType))
        return FirstPersonRenderMatrices.buildPositioningNodeInverse(nodePath)
    }

    private fun multiplyMatrix(matrix: Matrix4f) {
        MATRIX_BUFFER.clear()
        matrix.get(MATRIX_BUFFER)
        MATRIX_BUFFER.rewind()
        GL11.glMultMatrix(MATRIX_BUFFER)
    }

    private fun resolveGun(displayId: ResourceLocation): ResolvedPreviewModel? {
        val display: GunDisplay = TACZClientAssetManager.getGunDisplay(displayId) ?: return null
        val modelId = display.modelLocation ?: return null
        val textureId = display.modelTexture ?: return null
        val modelData = TACZClientAssetManager.getModel(modelId) ?: return null
        val textureLocation = TACZClientAssetManager.getTextureLocation(textureId) ?: return null
        return ResolvedPreviewModel(
            modelId = modelId,
            modelData = modelData,
            textureLocation = textureLocation,
            scaleMultiplier = transformScaleMultiplier(display.transform?.scale, 1.0f),
            verticalOffset = 1.8f,
        )
    }

    private fun resolveAttachment(stack: ItemStack, displayId: ResourceLocation): ResolvedPreviewModel? {
        val display: AttachmentDisplay = TACZClientAssetManager.getAttachmentDisplay(displayId) ?: return null
        val modelId = display.model ?: return null
        val textureId = display.texture ?: return null
        val modelData = TACZClientAssetManager.getModel(modelId) ?: return null
        val textureLocation = TACZClientAssetManager.getTextureLocation(textureId) ?: return null
        // 优先取 ClientAttachmentIndex 的 BedrockAttachmentModel：其构造器隐藏 division 等光学节点
        val attachmentId = (stack.item as? IAttachment)?.getAttachmentId(stack)
        val attachmentModel = attachmentId?.let { TACZClientAssetManager.getAttachmentIndex(it)?.attachmentModel }
        return ResolvedPreviewModel(
            modelId = modelId,
            modelData = modelData,
            textureLocation = textureLocation,
            scaleMultiplier = 1.0f,
            verticalOffset = 1.65f,
            attachmentModel = attachmentModel,
        )
    }

    private fun resolveAmmo(displayId: ResourceLocation): ResolvedPreviewModel? {
        val display: AmmoDisplay = TACZClientAssetManager.getAmmoDisplay(displayId) ?: return null
        val modelId = display.modelLocation ?: return null
        val textureId = display.modelTexture ?: return null
        val modelData = TACZClientAssetManager.getModel(modelId) ?: return null
        val textureLocation = TACZClientAssetManager.getTextureLocation(textureId) ?: return null
        return ResolvedPreviewModel(
            modelId = modelId,
            modelData = modelData,
            textureLocation = textureLocation,
            scaleMultiplier = transformScaleMultiplier(display.transform?.scale, 0.95f),
            verticalOffset = 1.6f,
        )
    }

    private fun resolveBlock(displayId: ResourceLocation): ResolvedPreviewModel? {
        val display: BlockDisplay = TACZClientAssetManager.getBlockDisplay(displayId) ?: return null
        val modelId = display.modelLocation ?: return null
        val textureId = display.modelTexture ?: return null
        val modelData = TACZClientAssetManager.getModel(modelId) ?: return null
        val textureLocation = TACZClientAssetManager.getTextureLocation(textureId) ?: return null
        return ResolvedPreviewModel(
            modelId = modelId,
            modelData = modelData,
            textureLocation = textureLocation,
            scaleMultiplier = 1.0f,
            verticalOffset = 1.4f,
        )
    }

    private fun renderModel(
        stack: ItemStack,
        resolved: ResolvedPreviewModel,
        centerX: Float,
        centerY: Float,
        scale: Float,
        yaw: Float,
        pitch: Float,
    ): Boolean {
        // 附件优先用 BedrockAttachmentModel（隐藏 division 光学节点），否则回退裸 BedrockModel
        val model: BedrockModel = resolved.attachmentModel
            ?: TACZClientAssetManager.getStaticBlockModel(resolved.modelId)
            ?: return false
        val mc = Minecraft.getMinecraft()
        mc.textureManager.bindTexture(resolved.textureLocation)

        GlStateManager.pushMatrix()
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f)
        GlStateManager.translate(centerX, centerY, 300.0f)
        GlStateManager.scale(scale * resolved.scaleMultiplier, scale * resolved.scaleMultiplier, scale * resolved.scaleMultiplier)
        GlStateManager.rotate(180.0f, 0.0f, 0.0f, 1.0f)
        GlStateManager.rotate(yaw, 0.0f, 1.0f, 0.0f)
        GlStateManager.rotate(pitch, 1.0f, 0.0f, 0.0f)
        // 以模型几何包围盒中心为旋转/显示中心，避免非 1x1 模型（如 2 格宽的枪械工作台、2 格高的配件工作台）偏移
        val center = modelCenter(resolved.modelData)
        if (center != null) {
            GlStateManager.translate(center[0] / 16.0f, 1.5f - center[1] / 16.0f, -center[2] / 16.0f)
        } else {
            GlStateManager.translate(0.0f, resolved.verticalOffset, 0.0f)
        }
        GlStateManager.scale(-1.0f, -1.0f, 1.0f)

        RenderHelper.enableGUIStandardItemLighting()
        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.disableCull()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

        if (model is BedrockAttachmentModel) {
            model.render(stack, null)
        } else {
            model.render()
        }
        captureBloomIfSupported(resolved.textureLocation, model) {
            if (model is BedrockAttachmentModel) {
                model.renderBloom(stack, null)
            } else {
                model.renderBloom()
            }
        }

        GlStateManager.enableCull()
        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        RenderHelper.disableStandardItemLighting()
        GlStateManager.popMatrix()
        return true
    }

    /**
     * 计算模型所有方块（cube）中心点的**逐轴中位数**，单位为基岩版像素，作为预览的居中基准。
     *
     * 不能用包围盒中心：第三方模型里常有远离主体的骨骼（例如 daffas_arsenal 的
     * `taurus_geo.json` 把 `soeedloader` / `rotatorial` 两个装弹器骨骼放在 y≈66，
     * 而枪身主体在 y≈8~22），包围盒会被这些离群骨骼拉偏 —— 实测 taubus 的
     * 包围盒中心是 (0, 37.81, 36.27)，中位数中心是 (0, 20.20, -4.46)，前者会让枪
     * 在预览框里往下偏 30 像素、往左偏 80 像素（z 平移经 GUN_PREVIEW_SIDE_YAW 旋转
     * 后落在屏幕水平方向）。
     *
     * 中位数对这类离群点不敏感，且对左右对称的模型（附件、方块）与包围盒中心等价。
     * 忽略骨骼旋转，作为显示用近似值已足够。
     */
    private fun modelCenter(modelData: TACZClientAssetManager.ModelData): FloatArray? {
        if (modelCenters.containsKey(modelData)) {
            return modelCenters[modelData]
        }
        val bones = modelData.pojo.geometryModelNew?.bones ?: modelData.pojo.geometryModelLegacy?.bones
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val zs = ArrayList<Float>()
        bones.orEmpty().forEach { bone ->
            bone.cubes.orEmpty().forEach { cube ->
                val origin = cube.origin
                val size = cube.size
                if (origin != null && size != null && origin.size >= 3 && size.size >= 3) {
                    xs.add(origin[0] + size[0] / 2.0f)
                    ys.add(origin[1] + size[1] / 2.0f)
                    zs.add(origin[2] + size[2] / 2.0f)
                }
            }
        }
        val center = if (xs.isEmpty()) null else floatArrayOf(median(xs), median(ys), median(zs))
        modelCenters[modelData] = center
        return center
    }

    /** 逐轴中位数；入参会被原地排序（调用方都是临时列表）。 */
    private fun median(values: ArrayList<Float>): Float {
        values.sort()
        val size = values.size
        if (size % 2 == 1) {
            return values[size / 2]
        }
        return (values[size / 2 - 1] + values[size / 2]) / 2.0f
    }

    private val debugReported = HashSet<String>()
    private fun debugFail(id: ResourceLocation?, reason: String): Boolean {
        if (debugReported.add("$id/$reason")) {
            com.tacz.legacy.TACZLegacy.logger.info("[PreviewDebug] gun preview failed id={} reason={}", id, reason)
        }
        return false
    }

    private fun captureBloomIfSupported(
        texture: ResourceLocation,
        model: BedrockModel,
        renderBloom: () -> Unit,
    ) {
        when (model) {
            is BedrockAnimatedModel -> {
                val snapshot = model.captureRenderState()
                TACZBloomBridge.captureCurrentModelBloom(texture) {
                    model.restoreRenderState(snapshot)
                    renderBloom()
                    model.cleanAnimationTransform()
                    model.cleanCameraAnimationTransform()
                }
            }
            else -> {
                TACZBloomBridge.captureCurrentModelBloom(texture, renderBloom)
            }
        }
    }

    private fun transformScaleMultiplier(scale: TransformScale?, fallback: Float): Float {
        val vector = scale?.fixed ?: scale?.thirdPerson ?: scale?.ground ?: return fallback
        return averageScale(vector).coerceIn(0.35f, 2.25f)
    }

    private fun averageScale(vector: Vector3f): Float = (vector.x + vector.y + vector.z) / 3.0f

    private val MATRIX_BUFFER = BufferUtils.createFloatBuffer(16)
    private const val GUN_PREVIEW_SIDE_YAW: Float = 90.0f
}
