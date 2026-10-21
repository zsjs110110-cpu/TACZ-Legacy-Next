package com.tacz.legacy.common.resource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兼容层 ③ 治理项：**注册表 ⊆ 消费面**的守门测试。
 *
 * 为什么需要：modifier 消费层曾经脱钩过一次 —— 16 个 modifier 注册在案，但只有 4 个真正进了玩法
 * （其余只影响 GUI 显示、或者完全没人读）。这种缺陷单测和启动都不会报错，只能靠人肉核对。
 *
 * 这个测试的作用是：以后任何人往 `TACZAttachmentModifierRegistry.readers` 里新增一个 id
 * 却没有在玩法或 GUI 里消费它，[consumers] 与注册表就不再相等，测试立刻变红。
 *
 * 维护方式：**新增 modifier 时，同时在这里登记它的消费点**。这不是重复劳动 ——
 * 它是"注册了但没人读"这个缺陷唯一能被机器发现的入口。
 */
class ModifierConsumptionCoverageTest {

    /**
     * 每个已注册 modifier 的**玩法侧**消费点（GUI 单列的情况在备注里说明）。
     *
     * 只写"谁在读这个值"，不写具体行号 —— 行号会漂移，类名与方法名相对稳定。
     */
    private val consumers: Map<String, String> = mapOf(
        "ads" to "LivingEntityAim.tickAimingProgress / 改装台属性面板",
        "ammo_speed" to "LivingEntityShoot + TACZGunScriptAPI（弹速初值）",
        "armor_ignore" to "LegacyEntities.applyAttachmentModifiers",
        "damage" to "LegacyEntities.applyAttachmentDamageModifier（逐距离点）",
        "effective_range" to "LegacyEntities.applyAttachmentEffectiveRange（改写首个距离段）",
        "explosion" to "LegacyEntities.applyAttachmentExplosion（OR 语义）",
        "head_shot" to "LegacyEntities.applyAttachmentModifiers",
        "ignite" to "TACZGunPropertyResolver.resolveIgnite → applyIgniteModifier",
        "inaccuracy" to "TACZBulletSpreadResolver.applySpread（两条射击路径共用）",
        "knockback" to "LegacyEntities.applyAttachmentModifiers",
        "movement_speed" to "LivingEntitySpeedModifier（base/aim/reload 三档）",
        "pierce" to "LegacyEntities.applyAttachmentModifiers",
        "recoil" to "TACZGunPropertyResolver.resolveCameraRecoil → TACZCameraRecoilHandler",
        "rpm" to "GunDataAccessor.getShootIntervalMs（服务端两条路径 + 客户端预测）",
        "silence" to "TACZGunSoundRouting（枪声距离与静音）",
        "weight_modifier" to "LivingEntitySpeedModifier（第二条 MULTIPLY_BASE modifier）",
    )

    @Test
    fun `every registered modifier id has a gameplay consumer`() {
        val registered = TACZAttachmentModifierRegistry.registeredIds()
        val unclaimed = registered - consumers.keys
        assertTrue(
            "这些 modifier 只注册了、没有任何玩法消费点：$unclaimed。" +
                "要么接进玩法，要么从读者注册表里删掉 —— 不要留下\"注册了但没人读\"的假象。",
            unclaimed.isEmpty(),
        )
    }

    @Test
    fun `consumer list has no stale entries`() {
        val registered = TACZAttachmentModifierRegistry.registeredIds()
        val stale = consumers.keys - registered
        assertTrue(
            "消费清单里出现了未注册的 modifier id：$stale（注册表或消费方被改过，二者要同步）。",
            stale.isEmpty(),
        )
    }

    @Test
    fun `registry keeps the upstream modifier count`() {
        // 上游 @SerializedName 路径对应的 16 个 modifier。
        // 明确排除 AimInaccuracyModifier：上游自己已 @Deprecated 且未注册，不是移植遗漏。
        assertEquals(
            "注册的 modifier 数量变了：如果是有意新增，请同步上面 consumers 里的消费点登记。",
            16,
            TACZAttachmentModifierRegistry.registeredIds().size,
        )
    }
}
