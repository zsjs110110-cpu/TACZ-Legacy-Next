package com.tacz.legacy.client.renderer.bloom

import com.tacz.legacy.TACZLegacy
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.ResourceLocation
import net.minecraftforge.fml.common.Loader
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11

internal object TACZBloomBridge {
    private const val MODID_GREGTECH = "gregtech"
    private const val MODID_LUMENIZED = "lumenized"

    private data class CapturedBloomRender(
        val texture: ResourceLocation,
        val projectionMatrix: FloatArray,
        val modelViewMatrix: FloatArray,
        val renderAction: () -> Unit,
    )

    private val capturedRenders = ArrayList<CapturedBloomRender>()

    @Volatile
    private var initialized: Boolean = false

    val isEnabled: Boolean
        get() = Loader.isModLoaded(MODID_GREGTECH) || Loader.isModLoaded(MODID_LUMENIZED)

    fun initIfPresent() {
        if (!isEnabled || initialized) {
            return
        }
        initialized = true
        TACZBloomHooks.ensureRegistered()
        TACZLegacy.logger.info("[RenderBloom] GT/Lumenized bloom bridge active.")
    }

    fun beginRenderFrame() {
        capturedRenders.clear()
    }

    fun captureCurrentModelBloom(
        texture: ResourceLocation,
        renderAction: () -> Unit,
    ): Boolean {
        if (!isEnabled) {
            return false
        }
        if (!initialized) {
            initIfPresent()
        }
        if (!initialized) {
            return false
        }
        capturedRenders += CapturedBloomRender(
            texture = texture,
            projectionMatrix = captureMatrix(GL11.GL_PROJECTION_MATRIX),
            modelViewMatrix = captureMatrix(GL11.GL_MODELVIEW_MATRIX),
            renderAction = renderAction,
        )
        return true
    }

    fun renderInlineFirstPersonBloom(
        texture: ResourceLocation,
        renderAction: () -> Unit,
    ): Boolean {
        if (!isEnabled) {
            return false
        }
        if (!initialized) {
            initIfPresent()
        }
        if (!initialized) {
            return false
        }
        return runCatching {
            TACZFirstPersonBloomRenderer.render(texture, renderAction)
        }.onFailure { error ->
            TACZLegacy.logger.warn("[RenderBloom] Failed to render inline first-person bloom pass {}", texture, error)
        }.isSuccess
    }

    internal fun hasPendingWork(): Boolean {
        return capturedRenders.isNotEmpty()
    }

    internal fun renderPendingBloom(partialTicks: Float) {
        if (capturedRenders.isNotEmpty()) {
            val renders = ArrayList(capturedRenders)
            for (captured in renders) {
                runCatching {
                    renderCapturedBloom(captured)
                }.onFailure { error ->
                    TACZLegacy.logger.warn("[RenderBloom] Failed to replay captured bloom render {}", captured.texture, error)
                }
            }
        }
    }

    private fun renderCapturedBloom(captured: CapturedBloomRender) {
        val mc = Minecraft.getMinecraft()
        GlStateManager.matrixMode(GL11.GL_PROJECTION)
        GlStateManager.pushMatrix()
        GL11.glLoadIdentity()
        GL11.glMultMatrix(toBuffer(captured.projectionMatrix))

        GlStateManager.matrixMode(GL11.GL_MODELVIEW)
        GlStateManager.pushMatrix()
        GL11.glLoadIdentity()
        GL11.glMultMatrix(toBuffer(captured.modelViewMatrix))

        mc.textureManager.bindTexture(captured.texture)
        GlStateManager.enableLighting()
        GlStateManager.enableRescaleNormal()
        GlStateManager.enableBlend()
        GlStateManager.tryBlendFuncSeparate(
            GlStateManager.SourceFactor.SRC_ALPHA,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ZERO,
        )

        captured.renderAction.invoke()

        GlStateManager.disableBlend()
        GlStateManager.disableRescaleNormal()
        GlStateManager.popMatrix()

        GlStateManager.matrixMode(GL11.GL_PROJECTION)
        GlStateManager.popMatrix()
        GlStateManager.matrixMode(GL11.GL_MODELVIEW)
    }

    private fun captureMatrix(matrixId: Int): FloatArray {
        val buffer = BufferUtils.createFloatBuffer(16)
        GL11.glGetFloat(matrixId, buffer)
        val result = FloatArray(16)
        buffer.get(result)
        return result
    }

    private fun toBuffer(values: FloatArray) = BufferUtils.createFloatBuffer(16).apply {
        put(values)
        rewind()
    }
}
