package com.tacz.legacy.common.entity

import com.tacz.legacy.api.modifier.Modifier
import com.tacz.legacy.common.resource.TACZExplosionModifierValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计 B1-1 / B1-7 的回归护栏：爆炸聚合的数值语义。
 *
 * 抽成纯函数就是为了把这几条写进单测 —— 之前的错误（默认包 M107 装 HE 弹匣后
 * 爆炸伤害 100→2、半径 1.5→0.5、延时 1s→30s）在游戏里很难量化，审计靠读 JSON 才定位到。
 */
class TACZExplosionAggregationTest {

    private fun neutral(explode: Boolean = true) = TACZExplosionModifierValue(explode = explode)

    /** 默认包真实数据：m107_data.json 的 explosion 段（explode:false，但数值都写了） */
    private val m107Base = TACZExplosionAggregation.Base(
        hasData = true,
        explode = false,
        radius = 1.5f,
        damage = 100f,
        knockback = true,
        destroyBlock = false,
        delay = 1f,
    )

    /** 默认包 ammo_mod_he_data.json 只有 {"explosion":{"explode":true}}，三个数值都是中性 modifier */
    @Test
    fun `he attachment lights up a non-exploding gun but keeps the gun's own explosion values`() {
        val result = TACZExplosionAggregation.aggregate(m107Base, listOf(neutral()))
        assertTrue(result != null)
        result!!
        // 旧实现在这里退回 0.5f / 2f / 30f，因为基线判断用的是 hasExplosion（explode:false）
        assertEquals(1.5f, result.radius, 0.001f)
        assertEquals(100f, result.damage, 0.001f)
        assertEquals(1f, result.delay, 0.001f)
        assertTrue(result.knockback)
        assertFalse(result.destroyBlock)
    }

    /** 上游 initCache:38-39：只有 explosionData == null 才用 new ExplosionData(...) 的默认值 */
    @Test
    fun `gun without any explosion section falls back to the upstream defaults`() {
        val noData = TACZExplosionAggregation.Base(
            hasData = false, explode = false,
            radius = 0f, damage = 0f, knockback = false, destroyBlock = false, delay = 0f,
        )
        val result = TACZExplosionAggregation.aggregate(noData, listOf(neutral()))
        result!!
        assertEquals(TACZExplosionAggregation.DEFAULT_RADIUS, result.radius, 0.001f)
        assertEquals(TACZExplosionAggregation.DEFAULT_DAMAGE, result.damage, 0.001f)
        assertEquals(TACZExplosionAggregation.DEFAULT_DELAY, result.delay, 0.001f)
        assertFalse(result.knockback)
    }

    @Test
    fun `no explosions and no base explosion means no explosion`() {
        assertNull(TACZExplosionAggregation.aggregate(m107Base, emptyList()))
        assertNull(
            TACZExplosionAggregation.aggregate(
                m107Base,
                listOf(neutral(explode = false)),
            ),
        )
    }

    /** 枪自己会爆炸时配件仍然可以改数值（上游 eval:67-71 不因 isExplode 提前返回） */
    @Test
    fun `attachment modifiers apply on top of an already exploding gun`() {
        val base = m107Base.copy(explode = true)
        val result = TACZExplosionAggregation.aggregate(
            base,
            listOf(
                TACZExplosionModifierValue(
                    explode = false,
                    radius = Modifier(multiplier = 2.0),
                    damage = Modifier(addend = 20.0),
                ),
            ),
        )
        result!!
        assertEquals(3.0f, result.radius, 0.001f)
        assertEquals(120f, result.damage, 0.001f)
    }

    /** 审计 B1-7：delay 的负值映射必须发生在聚合之后，配件才可能把它拉回有限值 */
    @Test
    fun `negative delay can be pulled back by an attachment addend`() {
        val base = m107Base.copy(delay = -1f)
        val result = TACZExplosionAggregation.aggregate(
            base,
            listOf(TACZExplosionModifierValue(explode = true, delay = Modifier(addend = 5.0))),
        )
        result!!
        // 上游是 (delay + 5) 秒后定时爆炸；旧实现先写成 +INF，配件再也加不回来
        assertEquals(4.0f, result.delay, 0.001f)
        assertTrue(result.delay.isFinite())
    }

    @Test
    fun `still negative delay after aggregation means never detonate on a timer`() {
        val base = m107Base.copy(delay = -1f)
        val result = TACZExplosionAggregation.aggregate(base, listOf(neutral()))
        result!!
        assertTrue(result.delay.isInfinite())
    }

    /** 上游 :53/:55 —— 基线布尔**不以 isExplode 为条件**进 OR 列表 */
    @Test
    fun `base knockback and destroy_block contribute even when the gun does not explode`() {
        val result = TACZExplosionAggregation.aggregate(
            m107Base.copy(knockback = true, destroyBlock = true),
            listOf(neutral()),
        )
        result!!
        assertTrue(result.knockback)
        assertTrue(result.destroyBlock)
    }

    @Test
    fun `attachment can light up knockback even when the gun has no explosion section`() {
        val noData = TACZExplosionAggregation.Base(
            hasData = false, explode = false,
            radius = 0f, damage = 0f, knockback = false, destroyBlock = false, delay = 0f,
        )
        val result = TACZExplosionAggregation.aggregate(
            noData,
            listOf(TACZExplosionModifierValue(explode = true, knockback = true, destroyBlock = true)),
        )
        result!!
        assertTrue(result.knockback)
        assertTrue(result.destroyBlock)
    }

    /** 上游 EntityKineticBullet:204 的 clamp(radius, 0, MAX) */
    @Test
    fun `radius is floored at zero`() {
        val result = TACZExplosionAggregation.aggregate(
            m107Base,
            listOf(
                TACZExplosionModifierValue(
                    explode = true,
                    radius = Modifier(addend = -50.0),
                ),
            ),
        )
        result!!
        assertEquals(0.0f, result.radius, 0.001f)
    }

    /** 多个配件依次聚合：后一个的 addend 作用在前一个的结果上（同一条 Modifier 列表） */
    @Test
    fun `several attachments aggregate into one modifier list`() {
        val result = TACZExplosionAggregation.aggregate(
            m107Base.copy(radius = 1.0f),
            listOf(
                TACZExplosionModifierValue(explode = true, radius = Modifier(addend = 1.0)),
                TACZExplosionModifierValue(explode = false, radius = Modifier(multiplier = 2.0)),
            ),
        )
        result!!
        assertEquals(4.0f, result.radius, 0.001f)
    }
}
