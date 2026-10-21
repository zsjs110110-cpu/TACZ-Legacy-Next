package com.tacz.legacy.client.renderer.bloom

import gregtech.client.renderer.IRenderSetup
import gregtech.client.shader.postprocessing.BloomType
import gregtech.client.utils.BloomEffectUtil
import gregtech.client.utils.EffectRenderContext
import gregtech.client.utils.IBloomEffect
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.BufferBuilder
import net.minecraft.client.renderer.texture.TextureMap
import java.util.function.Predicate

/**
 * Optional GT/Lumenized bloom hook.
 *
 * This object must only be classloaded when GregTech/Lumenized is present.
 * [TACZBloomBridge] performs the mod-loaded guard before calling into it.
 */
internal object TACZBloomHooks : IRenderSetup, IBloomEffect {
    @Volatile
    private var registered: Boolean = false

    fun ensureRegistered() {
        if (registered) {
            return
        }
        registered = true
        BloomEffectUtil.registerBloomRender(
            this,
            BloomType.UNREAL,
            this,
            Predicate { true },
        )
    }

    override fun preDraw(bufferBuilder: BufferBuilder) {
        // no-op: TACZ controls its own GL state when replaying captured model renders.
    }

    override fun postDraw(bufferBuilder: BufferBuilder) {
        // no-op
    }

    override fun shouldRenderBloomEffect(context: EffectRenderContext): Boolean {
        return TACZBloomBridge.hasPendingWork()
    }

    override fun renderBloomEffect(bufferBuilder: BufferBuilder, ctx: EffectRenderContext) {
        TACZBloomBridge.renderPendingBloom(ctx.partialTicks())
        Minecraft.getMinecraft().renderEngine.bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE)
    }
}
