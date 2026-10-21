package com.tacz.legacy.client.animation.screen

import com.tacz.legacy.api.item.attachment.AttachmentType
import com.tacz.legacy.client.gui.GunRefitScreen
import net.minecraft.client.Minecraft
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly

@SideOnly(Side.CLIENT)
internal object RefitTransform {
    private const val REFIT_SCREEN_TRANSFORM_TIME_S: Float = 0.25f

    private var refitScreenTransformProgress: Float = 1.0f
    private var refitScreenTransformTimestamp: Long = -1L
    private var oldTransformType: AttachmentType = AttachmentType.NONE
    private var currentTransformType: AttachmentType = AttachmentType.NONE
    private var refitScreenOpeningProgress: Float = 0.0f
    private var refitScreenOpeningTimestamp: Long = -1L

    @JvmStatic
    fun init() {
        refitScreenTransformProgress = 1.0f
        refitScreenTransformTimestamp = System.currentTimeMillis()
        oldTransformType = AttachmentType.NONE
        currentTransformType = AttachmentType.NONE
    }

    @JvmStatic
    fun getOpeningProgress(): Float = refitScreenOpeningProgress

    @JvmStatic
    fun getOldTransformType(): AttachmentType = oldTransformType

    @JvmStatic
    fun getCurrentTransformType(): AttachmentType = currentTransformType

    @JvmStatic
    fun getTransformProgress(): Float = refitScreenTransformProgress

    @JvmStatic
    fun changeRefitScreenView(attachmentType: AttachmentType): Boolean {
        if (refitScreenTransformProgress != 1.0f || refitScreenOpeningProgress != 1.0f) {
            return false
        }
        oldTransformType = currentTransformType
        currentTransformType = attachmentType
        refitScreenTransformProgress = 0.0f
        refitScreenTransformTimestamp = System.currentTimeMillis()
        return true
    }

    @SubscribeEvent
    @JvmStatic
    fun tickInterpolation(event: TickEvent.RenderTickEvent) {
        if (event.phase != TickEvent.Phase.START) {
            return
        }
        val now = System.currentTimeMillis()

        if (refitScreenOpeningTimestamp == -1L) {
            refitScreenOpeningTimestamp = now
        }
        val openingDelta = (now - refitScreenOpeningTimestamp).toFloat() / (REFIT_SCREEN_TRANSFORM_TIME_S * 1000.0f)
        // 时间戳被外部重置（系统时间回拨、跨存档重进）可能得到负数/非有限增量，
        // 一旦写进进度就会让后续的定位插值拿到非有限值。非有限时丢掉这一帧；
        // 有限但为负（时间回拨）时靠下面的双向钳制兜底，保持 [0,1] 不变式。
        if (openingDelta.isFinite()) {
            refitScreenOpeningProgress = if (Minecraft.getMinecraft().currentScreen is GunRefitScreen) {
                (refitScreenOpeningProgress + openingDelta).coerceIn(0.0f, 1.0f)
            } else {
                (refitScreenOpeningProgress - openingDelta).coerceIn(0.0f, 1.0f)
            }
        }
        refitScreenOpeningTimestamp = now

        if (refitScreenTransformTimestamp == -1L) {
            refitScreenTransformTimestamp = now
        }
        val transformDelta = (now - refitScreenTransformTimestamp).toFloat() / (REFIT_SCREEN_TRANSFORM_TIME_S * 1000.0f)
        if (transformDelta.isFinite()) {
            refitScreenTransformProgress += transformDelta
        }
        if (refitScreenTransformProgress < 0.0f) {
            refitScreenTransformProgress = 0.0f
        }
        if (refitScreenTransformProgress > 1.0f) {
            refitScreenTransformProgress = 1.0f
        }
        refitScreenTransformTimestamp = now
    }
}
