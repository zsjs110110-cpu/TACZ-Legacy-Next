package com.tacz.legacy.client.event

import com.tacz.legacy.api.entity.IGunOperator
import com.tacz.legacy.api.item.IGun
import com.tacz.legacy.client.renderer.other.GunHurtBobTweak
import com.tacz.legacy.common.resource.GunDataAccessor
import com.tacz.legacy.common.resource.TACZGunPropertyResolver
import net.minecraft.client.Minecraft
import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.item.ItemStack
import net.minecraftforge.client.event.EntityViewRenderEvent
import net.minecraftforge.fml.common.eventhandler.EventPriority
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import net.minecraftforge.fml.relauncher.Side
import net.minecraftforge.fml.relauncher.SideOnly
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction

@SideOnly(Side.CLIENT)
internal object TACZCameraRecoilHandler {
    private var pitchSplineFunction: PolynomialSplineFunction? = null
    private var yawSplineFunction: PolynomialSplineFunction? = null
    private var shootTimestamp: Long = -1L
    private var lastLoggedShootTimestamp: Long = -1L
    private var previousPitchOffset: Double = 0.0
    private var previousYawOffset: Double = 0.0

    @JvmStatic
    internal fun onLocalGunFire(player: EntityPlayerSP, stack: ItemStack) {
        // 兼容层 ③：可回滚开关（默认开启），便于 A/B 对比后坐改动
        if (!com.tacz.legacy.common.config.LegacyConfigManager.client.enableCameraRecoil) {
            return
        }
        val iGun = stack.item as? IGun ?: return
        val gunData = GunDataAccessor.getGunData(iGun.getGunId(stack)) ?: return
        val operator = IGunOperator.fromLivingEntity(player)
        val recoil = TACZGunPropertyResolver.resolveCameraRecoil(
            stack = stack,
            iGun = iGun,
            gunData = gunData,
            aimingProgress = operator.getSynAimingProgress(),
            isCrawling = operator.getDataHolder().isCrawling,
        ) ?: return
        pitchSplineFunction = recoil.pitch
        yawSplineFunction = recoil.yaw
        shootTimestamp = System.currentTimeMillis()
        previousPitchOffset = 0.0
        previousYawOffset = 0.0
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    @JvmStatic
    internal fun onCameraSetup(event: EntityViewRenderEvent.CameraSetup) {
        val mc = Minecraft.getMinecraft()
        val player = mc.player ?: return
        if (event.entity != player) {
            return
        }
        val elapsed = System.currentTimeMillis() - shootTimestamp
        val elapsedMs = elapsed.toDouble()

        var pitch = event.pitch
        var appliedPitchDelta = 0.0
        pitchSplineFunction?.takeIf { it.isValidPoint(elapsedMs) }?.let { spline ->
            val value = spline.value(elapsedMs)
            appliedPitchDelta = value - previousPitchOffset
            pitch -= appliedPitchDelta.toFloat()
            player.rotationPitch = player.rotationPitch - appliedPitchDelta.toFloat()
            previousPitchOffset = value
        }

        var yaw = event.yaw
        var appliedYawDelta = 0.0
        yawSplineFunction?.takeIf { it.isValidPoint(elapsedMs) }?.let { spline ->
            val value = spline.value(elapsedMs)
            appliedYawDelta = value - previousYawOffset
            yaw -= appliedYawDelta.toFloat()
            player.rotationYaw = player.rotationYaw - appliedYawDelta.toFloat()
            previousYawOffset = value
        }

        if (System.getProperty("tacz.focusedSmoke", "false").toBoolean()
            && shootTimestamp > 0L
            && lastLoggedShootTimestamp != shootTimestamp
            && (kotlin.math.abs(appliedPitchDelta) > 1.0E-4 || kotlin.math.abs(appliedYawDelta) > 1.0E-4)
        ) {
            lastLoggedShootTimestamp = shootTimestamp
            com.tacz.legacy.TACZLegacy.logger.info(
                "[FocusedSmoke] CAMERA_RECOIL_APPLIED shootTimestamp={} pitchDelta={} yawDelta={}",
                shootTimestamp,
                "%.3f".format(appliedPitchDelta),
                "%.3f".format(appliedYawDelta),
            )
        }

        event.pitch = pitch
        event.yaw = yaw

        // 被枪械击中时的视角晃动（L1/D-01）：原版 hurtCameraEffect 已在 EntityRendererMixin
        // 里整体 cancel，这里按枪包 hurt_bob_tweak_multiplier 叠加缩放后的倾斜。
        // 只有 roll 一个分量：上游的 yaw 分量取自"受击方向"，1.12.2 没有对应的同步包
        // （客户端 attackedAtYaw 恒为 0），y=0 时共轭退化成正绕视线轴的倾斜。
        GunHurtBobTweak.computeRollDegrees(player, mc.renderPartialTicks)?.let { roll ->
            event.roll += roll
        }
    }
}