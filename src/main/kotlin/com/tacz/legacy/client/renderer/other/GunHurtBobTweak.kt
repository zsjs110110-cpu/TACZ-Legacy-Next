package com.tacz.legacy.client.renderer.other

import net.minecraft.client.entity.EntityPlayerSP
import net.minecraft.util.math.MathHelper

/**
 * 被枪械击中时的视角晃动修正（对齐上游 `com.tacz.guns.client.renderer.other.GunHurtBobTweak`）。
 *
 * 原版受伤时长 500ms，若超过 500ms 则不是子弹造成的伤害，回退原版晃动。
 * `tweakMultiplier` 缩放晃动幅度（默认 0.05，由枪包 `hurt_bob_tweak_multiplier` 指定）。
 *
 * 与上游的差异只在接线方式：上游在 1.20 用 `GameRendererMixin` 接管 `bobView` 的 poseStack，
 * 1.12.2 的受伤晃动写死在 `EntityRenderer#hurtCameraEffect` 里。因此拆成两步：
 * 1. `EntityRendererMixin` 在 `hurtCameraEffect` 入口直接 cancel。该方法只有三个调用点：
 *    `setupCameraTransform:738`、`renderHand:813`（`renderItemInFirstPerson` 之前）、
 *    `renderHand:835`（`renderOverlays` 之后）——在入口拦截一次就覆盖了世界、手部、叠层；
 * 2. 相机朝向确定后，在 `EntityViewRenderEvent.CameraSetup` 里叠加缩放后的倾斜
 *    （见 `TACZCameraRecoilHandler#onCameraSetup`）。
 *
 * 只保留 roll 分量，与上游的形态差异说明：上游是 `YP(-y) XP(-z*14) YP(y)`，
 * 其中 y 来自 `LivingEntity#getHurtDir()`——1.20 用 `ClientboundHurtAnimationPacket`
 * 把这个"受击方向"同步给客户端。1.12.2 **没有对应的同步包**：
 * `EntityLivingBase.attackedAtYaw` 只在服务端 `attackEntityFrom` 里赋值，
 * 客户端的 `handleStatusUpdate`（受伤状态包）反而会把它清成 0，
 * 所以客户端读到的永远是 0，按上游公式算出的 y 恒为 0，共轭项退化掉，
 * 剩下的就是一个纯倾斜量。
 *
 * 为什么这份倾斜正好可以塞进 `CameraSetup.roll`（不是"近似"，是同一个旋转）：
 * 这里容易想反，写清楚。`GlStateManager.rotate` 是后乘（`M = M * R`），
 * `setupCameraTransform` 先 `loadIdentity` 再调 `hurtCameraEffect`，随后 `orientCamera`
 * 继续后乘，最终 `M = R_hurt * R_bob * R_orient`。顶点先被 `R_orient` 转到相机系，
 * 所以**最先 push 的 `R_hurt` 反而是最外层**——`hurtCameraEffect` 里的
 * `rotate(-f2, Y) / rotate(-f*14, Z) / rotate(f2, Y)` 作用在**视空间**，
 * 中间那一项就是绕相机自身 Z 轴（`0, 0, 1`）转 `-f*14` 度。
 * 而 Forge 的 `CameraSetup` 在 `orientCamera` 里是 `rotate(event.getRoll(), 0, 0, 1)`
 * 后接 pitch / yaw，`R_roll` 与 `R_hurt` 落在同一个轴、同一个位置，
 * 两者的角度直接相加。所以 y=0 时把 `-sin(p⁴π)*mult*14` 加到 `event.roll` 上，
 * 就是在复现原版那个旋转，只是幅度换成枪包倍率。
 */
public object GunHurtBobTweak {
    private const val HURT_WINDOW_MS = 500L
    private const val DEFAULT_MULTIPLIER = 0.05f

    /** 网络线程写入、渲染线程读取，用 @Volatile 保证可见性。 */
    @Volatile
    private var hurtByGunTimeStamp: Long = -1L

    @Volatile
    private var lastTweakMultiplier: Float = DEFAULT_MULTIPLIER

    /**
     * 子弹命中本地玩家时调用，记录时间戳与晃动倍率。
     */
    @JvmStatic
    public fun markTimestamp(tweakMultiplier: Float) {
        hurtByGunTimeStamp = System.currentTimeMillis()
        lastTweakMultiplier = tweakMultiplier
    }

    /**
     * 当前这次受伤是否仍算作"被枪击中"（原版窗口 500ms）。
     *
     * 供 `EntityRendererMixin` 判断是否要抑制原版受伤晃动。
     */
    @JvmStatic
    public fun isActive(): Boolean = System.currentTimeMillis() - hurtByGunTimeStamp <= HURT_WINDOW_MS

    /**
     * 需要叠加到相机 roll 上的角度（单位：度），null 表示不接管。
     *
     * 返回 null 的三种情况：不在"被枪击中"窗口内、受伤进度已走完（此时原版晃动
     * 同样不产生位移）、或受伤计时尚未初始化。
     *
     * 曲线与上游一致：`sin(progress^4 * PI) * multiplier * 14`，progress 按
     * `maxHurtTime` 归一化，从 1 递减到 0，峰值出现在 progress = 0.5^(1/4) ≈ 0.84。
     */
    @JvmStatic
    public fun computeRollDegrees(player: EntityPlayerSP, partialTicks: Float): Float? {
        if (!isActive()) {
            return null
        }
        if (player.hurtTime <= 0) {
            return null
        }
        var progress = player.hurtTime.toFloat() - partialTicks
        if (progress < 0f) {
            return null
        }
        val maxHurtTime = player.maxHurtTime.toFloat()
        if (maxHurtTime <= 0f) {
            return null
        }
        progress /= maxHurtTime
        val zRot = MathHelper.sin(progress * progress * progress * progress * Math.PI.toFloat())
        val multiplier = if (lastTweakMultiplier.isFinite()) lastTweakMultiplier else DEFAULT_MULTIPLIER
        return -zRot * multiplier * 14.0f
    }
}
