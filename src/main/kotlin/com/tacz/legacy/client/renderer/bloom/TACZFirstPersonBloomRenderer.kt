package com.tacz.legacy.client.renderer.bloom

import github.kasuminova.lumenized.client.ShaderManager
import gregtech.client.shader.Shaders
import gregtech.client.shader.postprocessing.BloomEffect
import gregtech.client.utils.BloomEffectUtil
import gregtech.client.utils.DepthTextureUtil
import gregtech.client.utils.RenderUtil
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.client.shader.Framebuffer
import net.minecraft.util.ResourceLocation
import org.lwjgl.opengl.GL11

/**
 * First-person bloom renderer that runs inline with hand rendering instead of GT's early
 * world callback. This keeps base gun rendering and bloom rendering on the same matrices,
 * partialTicks, and animation state, while also ensuring bloom is composited after the gun.
 */
internal object TACZFirstPersonBloomRenderer {
    private const val DEFAULT_BLOOM_STYLE = 2

    private data class BloomConfig(
        val style: Int,
        val strength: Float,
        val baseBrightness: Float,
        val highBrightnessThreshold: Float,
        val lowBrightnessThreshold: Float,
        val step: Float,
    )

    private var firstPersonBloomFramebuffer: Framebuffer? = null

    fun render(
        texture: ResourceLocation,
        renderBloomPass: () -> Unit,
    ) {
        val mc = Minecraft.getMinecraft()
        val mainFramebuffer = mc.framebuffer ?: return
        if (!OpenGlHelper.framebufferSupported || ShaderManager.isOptifineShaderPackLoaded()) {
            return
        }

        ensureShadersInitialized()

        BloomEffectUtil.storeCommonGlStates()
        try {
            val bloomFramebuffer = preRenderBloom(mainFramebuffer)
            mc.textureManager.bindTexture(texture)
            renderBloomPass()
            postRenderBloom(mainFramebuffer, bloomFramebuffer)
        } finally {
            BloomEffectUtil.restoreCommonGlStates()
            mainFramebuffer.bindFramebuffer(true)
        }
    }

    private fun ensureShadersInitialized() {
        val initialized = runCatching {
            val shadersClass = Shaders::class.java
            val imageF = shadersClass.getField("IMAGE_F").get(null)
            val bloomCombine = shadersClass.getField("BLOOM_COMBINE").get(null)
            imageF != null && bloomCombine != null
        }.getOrDefault(false)
        if (!initialized) {
            Shaders.initShaders()
        }
    }

    private fun preRenderBloom(mainFramebuffer: Framebuffer): Framebuffer {
        val bloomFramebuffer = ensureBloomFramebuffer(mainFramebuffer)
        GlStateManager.depthMask(true)
        bloomFramebuffer.framebufferClear()
        bloomFramebuffer.bindFramebuffer(true)
        return bloomFramebuffer
    }

    private fun postRenderBloom(
        mainFramebuffer: Framebuffer,
        bloomFramebuffer: Framebuffer,
    ) {
        GlStateManager.depthMask(false)
        mainFramebuffer.bindFramebufferTexture()
        GlStateManager.enableBlend()
        GlStateManager.blendFunc(GL11.GL_DST_ALPHA, GL11.GL_ZERO)
        Shaders.renderFullImageInFBO(bloomFramebuffer, Shaders.IMAGE_F, null)
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)

        val config = readBloomConfig()
        applyBloomConfig(config)
        when (config.style) {
            0 -> BloomEffect.renderLOG(bloomFramebuffer, mainFramebuffer)
            1 -> BloomEffect.renderUnity(bloomFramebuffer, mainFramebuffer)
            else -> BloomEffect.renderUnreal(bloomFramebuffer, mainFramebuffer)
        }

        GlStateManager.disableBlend()
        Shaders.renderFullImageInFBO(mainFramebuffer, Shaders.IMAGE_F, null)
    }

    private fun ensureBloomFramebuffer(mainFramebuffer: Framebuffer): Framebuffer {
        val width = mainFramebuffer.framebufferWidth
        val height = mainFramebuffer.framebufferHeight
        val mainStencilEnabled = mainFramebuffer.isStencilEnabled()
        val current = firstPersonBloomFramebuffer
        if (
            current != null &&
            current.framebufferWidth == width &&
            current.framebufferHeight == height &&
            (!mainStencilEnabled || current.isStencilEnabled())
        ) {
            return current
        }

        current?.deleteFramebuffer()
        return Framebuffer(width, height, false).also { framebuffer ->
            framebuffer.setFramebufferColor(0.0f, 0.0f, 0.0f, 0.0f)
            framebuffer.setFramebufferFilter(9729)
            if (mainStencilEnabled && !framebuffer.isStencilEnabled()) {
                framebuffer.enableStencil()
            }
            if (DepthTextureUtil.isLastBind() && DepthTextureUtil.isUseDefaultFBO()) {
                RenderUtil.hookDepthTexture(framebuffer, DepthTextureUtil.framebufferDepthTexture)
            } else {
                RenderUtil.hookDepthBuffer(framebuffer, mainFramebuffer.depthBuffer)
            }
            firstPersonBloomFramebuffer = framebuffer
        }
    }

    private fun readBloomConfig(): BloomConfig {
        return runCatching {
            val configClass = Class.forName(
                "github.kasuminova.lumenized.common.config.LumenizedConfig",
                false,
                javaClass.classLoader,
            )
            BloomConfig(
                style = configClass.getField("bloomStyle").getInt(null),
                strength = configClass.getField("strength").getDouble(null).toFloat(),
                baseBrightness = configClass.getField("baseBrightness").getDouble(null).toFloat(),
                highBrightnessThreshold = configClass.getField("highBrightnessThreshold").getDouble(null).toFloat(),
                lowBrightnessThreshold = configClass.getField("lowBrightnessThreshold").getDouble(null).toFloat(),
                step = configClass.getField("step").getDouble(null).toFloat(),
            )
        }.getOrElse {
            BloomConfig(
                style = DEFAULT_BLOOM_STYLE,
                strength = BloomEffect.strength,
                baseBrightness = BloomEffect.baseBrightness,
                highBrightnessThreshold = BloomEffect.highBrightnessThreshold,
                lowBrightnessThreshold = BloomEffect.lowBrightnessThreshold,
                step = BloomEffect.step,
            )
        }
    }

    private fun applyBloomConfig(config: BloomConfig) {
        BloomEffect.strength = config.strength
        BloomEffect.baseBrightness = config.baseBrightness
        BloomEffect.highBrightnessThreshold = config.highBrightnessThreshold
        BloomEffect.lowBrightnessThreshold = config.lowBrightnessThreshold
        BloomEffect.step = config.step
    }
}