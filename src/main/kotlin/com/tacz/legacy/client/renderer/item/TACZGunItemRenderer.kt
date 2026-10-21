package com.tacz.legacy.client.renderer.item

import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.client.model.BedrockAnimatedModel
import com.tacz.legacy.client.model.BedrockGunModel
import com.tacz.legacy.client.model.SlotModel
import com.tacz.legacy.client.model.TACZPerspectiveAwareBakedModel
import com.tacz.legacy.client.model.bedrock.BedrockModel
import com.tacz.legacy.client.model.bedrock.BedrockPart
import com.tacz.legacy.client.model.functional.BeamRenderer
import com.tacz.legacy.client.model.functional.MuzzleFlashRender
import com.tacz.legacy.client.model.functional.ShellRender
import com.tacz.legacy.client.renderer.bloom.TACZBloomBridge
import com.tacz.legacy.client.resource.TACZClientAssetManager
import com.tacz.legacy.client.resource.GunDisplayInstance
import com.tacz.legacy.client.resource.pojo.TransformScale
import com.tacz.legacy.client.resource.pojo.display.gun.GunDisplay
import com.tacz.legacy.client.resource.pojo.display.gun.GunTransform
import com.tacz.legacy.common.resource.TACZGunPackPresentation
import com.tacz.legacy.common.resource.TACZGunPackRuntimeRegistry
import com.tacz.legacy.common.resource.TACZMissingIdReporter
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.block.model.ItemCameraTransforms
import net.minecraft.client.renderer.tileentity.TileEntityItemStackRenderer
import net.minecraft.item.ItemStack
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import java.nio.FloatBuffer

/**
 * TEISR (TileEntityItemStackRenderer) for gun items.
 *
 * Context-aware rendering via [TACZPerspectiveAwareBakedModel]:
 * - **Item presentation contexts** (GUI, dropped, fixed, head) → flat slot texture
 * - **First person** → skipped (handled by FirstPersonRenderGunEvent)
 * - **Third person right hand** → 3D bedrock model with gun pack texture
 * - **Third person left hand** → skipped (upstream convention)
 *
 * Positioning for FIXED / GROUND / THIRD_PERSON contexts is derived at render time
 * from the gun model's own positioning nodes (root/positioning/fixed/ground/thirdperson_hand),
 * instead of relying on a per-item-model `display` block. See [applyPositioningTransform].
 */
@SideOnly(Side.CLIENT)
internal object TACZGunItemRenderer : TileEntityItemStackRenderer() {
    private val SLOT_MODEL = SlotModel()
    private val POSITIONING_MATRIX_BUFFER: FloatBuffer = BufferUtils.createFloatBuffer(16)

    override fun renderByItem(stack: ItemStack) {
        renderByItem(stack, 1.0f)
    }

    override fun renderByItem(stack: ItemStack, partialTicks: Float) {
        val item = stack.item
        if (item !is IGun) return

        val snapshot = TACZGunPackRuntimeRegistry.getSnapshot()
        val gunId = item.getGunId(stack)

        val displayId = TACZGunPackPresentation.resolveGunDisplayId(snapshot, gunId)
            ?: run {
                // D-5：远程客户端"枪不可见"最常见的原因是数据没同步到，这里必须留一条可归因的日志
                TACZMissingIdReporter.report("gun display", gunId, "gun item renderer")
                return
            }
        val display: GunDisplay = TACZClientAssetManager.getGunDisplay(displayId) ?: return
        val displayInstance = TACZClientAssetManager.getGunDisplayInstance(displayId)

        val transformType = TACZPerspectiveAwareBakedModel.getCurrentTransformType()

        // First person is usually handled by FirstPersonRenderGunEvent.
        // If that hook bails out early (missing display instance, texture, etc.),
        // allow the TEISR to act as a last-resort visual fallback instead of
        // making the gun disappear entirely.

        // Third person left hand — skip per upstream convention
        if (transformType == ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND) {
            return
        }

        // 副手枪械显示开关：offhand_show 为布尔 false 时不再绘制副手的枪模型。
        // 不能只看 FIRST_PERSON_LEFT_HAND：左手模式（leftHand）下副手的 transform 会变成
        // RIGHT_HAND，只看 transform 会漏掉；这里用“渲染的栈就是本地玩家副手栈”判断，
        // 并限制在手部上下文，避免把物品栏里的副手图标也一起隐藏。
        val localPlayer = Minecraft.getMinecraft().player
        val isHeldOffhand = localPlayer != null && stack === localPlayer.heldItemOffhand
        val isHandContext = transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND ||
            transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND ||
            transformType == ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND ||
            transformType == ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND
        if (isHandContext && isHeldOffhand && !display.offhandShow.isVisible) {
            return
        }

        // Item presentation contexts → flat slot texture
        if (TACZPerspectiveAwareBakedModel.isItemPresentationContext(transformType)) {
            renderSlotTexture(display)
            return
        }

        // Third person right hand → 3D model
        renderGunModel(stack, display, displayInstance)
    }

    private fun renderSlotTexture(display: GunDisplay) {
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

    private fun renderGunModel(stack: ItemStack, display: GunDisplay, displayInstance: GunDisplayInstance?) {
        val textureLocation: ResourceLocation = displayInstance?.modelTexture ?: display.modelTexture ?: return
        val registeredTexture: ResourceLocation = TACZClientAssetManager.getTextureLocation(textureLocation) ?: return
        val transformType = TACZPerspectiveAwareBakedModel.getCurrentTransformType()
        val beamRenderContext = when (transformType) {
            ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND -> BeamRenderer.RenderContext.THIRD_PERSON_RIGHT_HAND
            ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND,
            ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND -> BeamRenderer.RenderContext.FIRST_PERSON
            else -> BeamRenderer.RenderContext.NONE
        }

        val runtimeModel = displayInstance?.gunModel
        val fallbackModel = if (runtimeModel == null) {
            val modelLocation: ResourceLocation = display.modelLocation ?: return
            val modelData = TACZClientAssetManager.getModel(modelLocation) ?: return
            BedrockModel(modelData.pojo, modelData.version)
        } else {
            null
        }

        val transform: GunTransform = displayInstance?.transform ?: display.transform ?: GunTransform.getDefault()
        val scale: TransformScale? = transform.scale

        Minecraft.getMinecraft().textureManager.bindTexture(registeredTexture)

        GlStateManager.pushMatrix()
        GlStateManager.translate(0.5f, 2.0f, 0.5f)
        GlStateManager.scale(-1f, -1f, 1f)

        if (runtimeModel is BedrockGunModel) {
            applyPositioningTransform(transformType, scale, runtimeModel)
        }
        applyScaleTransform(transformType, scale)

        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

        displayInstance?.setActiveGunTexture(registeredTexture)
        // enable_transparency：半透明枪模型需要关闭 alpha test，让贴图 alpha 参与混合；
        // 普通枪保持 alpha test，避免与方块/粒子产生错误的透明排序。
        val enableTransparency = displayInstance?.isEnableTransparency ?: display.enablesTransparency()
        // 审计 B8：进来时先记下调用方的 alpha test 状态，出口按原值恢复 ——
        // 无条件 enableAlpha 会把"调用方本来关着的 alpha test"打开，污染后续渲染。
        val previousAlphaTest = GL11.glGetBoolean(GL11.GL_ALPHA_TEST)
        if (enableTransparency) {
            GlStateManager.disableAlpha()
        } else {
            GlStateManager.enableAlpha()
        }
        if (runtimeModel != null) {
            runtimeModel.renderHand = false
            // 第三人称不经过 FirstPersonRenderGunEvent，枪口火焰的 display 配置必须在这里补上，
            // 否则 MuzzleFlashRender.activeMuzzleFlash 仍为 null，即使 isSelf 为 true 也不会绘制。
            runtimeModel.muzzleFlashRender?.setActiveMuzzleFlash(display.muzzleFlash)
            // 该 TEISR 服务所有实例（含掉落枪与其他玩家的枪），因此不能无条件显示特效：
            // 只有「本地玩家第三人称手持的这把枪」才保留枪口火焰与弹壳，其余实例一律屏蔽，
            // 否则会把本地玩家的射击特效错误地画到掉落物、展示框或其他玩家的枪上。
            val localPlayer = Minecraft.getMinecraft().player
            // 左手上下文已在 renderByItem 开头按上游约定跳过，这里只需判断右手
            val isLocalThirdPerson = transformType == ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND &&
                localPlayer != null &&
                (stack === localPlayer.heldItemMainhand || stack === localPlayer.heldItemOffhand)
            val previousShellSelf = ShellRender.isSelf
            val previousMuzzleSelf = MuzzleFlashRender.isSelf
            ShellRender.isSelf = isLocalThirdPerson
            MuzzleFlashRender.isSelf = isLocalThirdPerson
            val previousBeamContext = BeamRenderer.pushRenderContext(beamRenderContext)
            try {
                runtimeModel.render(stack)
                captureBloomIfSupported(transformType, registeredTexture, runtimeModel) {
                    runtimeModel.renderHand = false
                    runtimeModel.renderBloom(stack)
                }
            } finally {
                BeamRenderer.popRenderContext(previousBeamContext)
                ShellRender.isSelf = previousShellSelf
                MuzzleFlashRender.isSelf = previousMuzzleSelf
            }
            runtimeModel.cleanAnimationTransform()
            runtimeModel.cleanCameraAnimationTransform()
        } else {
            val staticModel = fallbackModel ?: return
            staticModel.render()
            captureBloomIfSupported(transformType, registeredTexture, staticModel) {
                staticModel.renderBloom()
            }
        }

        if (previousAlphaTest) GlStateManager.enableAlpha() else GlStateManager.disableAlpha()
        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()
    }

    /**
     * Walks the gun model's own positioning node (fixed/ground/thirdperson_hand)
     * backwards from leaf to root, applying an approximate inverse of each bone's
     * transform so that the node's local origin lands at the current origin
     * (i.e. "the hand"/"the display stand hook"/etc).
     */
    private fun applyPositioningTransform(
        transformType: ItemCameraTransforms.TransformType,
        scale: TransformScale?,
        model: BedrockGunModel,
    ) {
        val vectorScale = resolveScaleVector(transformType, scale) ?: Vector3f(1f, 1f, 1f)
        when (transformType) {
            ItemCameraTransforms.TransformType.FIXED ->
                applyPositioningNodeTransform(model.fixedOriginPath, vectorScale)
            ItemCameraTransforms.TransformType.GROUND ->
                applyPositioningNodeTransform(model.groundOriginPath, vectorScale)
            ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND,
            ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND ->
                applyPositioningNodeTransform(model.thirdPersonHandOriginPath, vectorScale)
            else -> Unit
        }
    }

    /**
     * Applies the gun pack's configured per-context scale (transform.scale.fixed/ground/thirdperson)
     * around the Y=1.5 pivot used throughout this renderer.
     */
    private fun applyScaleTransform(transformType: ItemCameraTransforms.TransformType, scale: TransformScale?) {
        val vector = resolveScaleVector(transformType, scale) ?: return
        GlStateManager.translate(0f, 1.5f, 0f)
        GlStateManager.scale(vector.x(), vector.y(), vector.z())
        GlStateManager.translate(0f, -1.5f, 0f)
    }

    private fun resolveScaleVector(
        transformType: ItemCameraTransforms.TransformType,
        scale: TransformScale?,
    ): Vector3f? {
        if (scale == null) {
            return null
        }
        return when (transformType) {
            ItemCameraTransforms.TransformType.FIXED -> scale.fixed
            ItemCameraTransforms.TransformType.GROUND -> scale.ground
            ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND,
            ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND -> scale.thirdPerson
            else -> null
        }
    }

    private fun applyPositioningNodeTransform(nodePath: List<BedrockPart>?, scale: Vector3f) {
        if (nodePath.isNullOrEmpty()) {
            return
        }
        // 校验缩放分量非零，否则无法构造 S⁻¹
        if (scale.x() == 0f || scale.y() == 0f || scale.z() == 0f) {
            return
        }
        // 构建完整静态定位逆矩阵 I：沿骨骼链逐骨骼求逆（先反向旋转，再以模型单位反向平移 -pos/16）。
        // 逆矩阵按 leaf→root 依次后置相乘，得到 I = Bn⁻¹ ··· B0⁻¹，等价于渲染时 root→node 累积变换的逆。
        val inverse = Matrix4f()
        for (index in nodePath.size - 1 downTo 0) {
            val part = nodePath[index]
            if (part.xRot != 0f) {
                inverse.rotate(-part.xRot, 1f, 0f, 0f)
            }
            if (part.yRot != 0f) {
                inverse.rotate(-part.yRot, 0f, 1f, 0f)
            }
            if (part.zRot != 0f) {
                inverse.rotate(-part.zRot, 0f, 0f, 1f)
            }
            inverse.translate(-part.x / 16f, -part.y / 16f, -part.z / 16f)
        }
        // 定位与缩放均围绕 Y=1.5 锚点进行；待求逆的模型变换实为 T(-P)·B，其逆为 B⁻¹·T(P)。
        // 缺少这一步会使无旋转时定位节点落到 Y=0（旧实现为 Y=1.5）。
        inverse.translate(0f, 1.5f, 0f)
        // 定位在模型本地单位求解，但随后会套用非均匀的显示缩放 S；平移与旋转不可交换，
        // 因此需把 I 共轭到缩放坐标系 S·I·S⁻¹，使节点原点仍落在锚点处。
        val scaleMatrix = Matrix4f().scale(scale)
        val inverseScale = Matrix4f().scale(1f / scale.x(), 1f / scale.y(), 1f / scale.z())
        val positioned = Matrix4f(scaleMatrix).mul(inverse).mul(inverseScale)

        // 保留 Y=1.5 高度枢轴
        GlStateManager.translate(0f, 1.5f, 0f)
        POSITIONING_MATRIX_BUFFER.clear()
        positioned.get(POSITIONING_MATRIX_BUFFER)
        POSITIONING_MATRIX_BUFFER.rewind()
        GL11.glMultMatrix(POSITIONING_MATRIX_BUFFER)
        GlStateManager.translate(0f, -1.5f, 0f)
    }

    private fun captureBloomIfSupported(
        transformType: ItemCameraTransforms.TransformType,
        texture: ResourceLocation,
        model: BedrockModel?,
        renderBloom: () -> Unit,
    ) {
        if (model == null) {
            return
        }
        if (transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_LEFT_HAND ||
            transformType == ItemCameraTransforms.TransformType.FIRST_PERSON_RIGHT_HAND
        ) {
            return
        }

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
}