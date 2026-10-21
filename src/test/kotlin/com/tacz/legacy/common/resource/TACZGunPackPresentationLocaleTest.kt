package com.tacz.legacy.common.resource

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TACZGunPackPresentationLocaleTest {
    @After
    fun tearDown() {
        TACZGunPackPresentation.setCurrentLanguageCodeResolverForTests(null)
    }

    @Test
    fun `locale candidates prefer current minecraft language over system locale`() {
        val candidates = TACZGunPackPresentation.localeCandidates(currentLanguageCode = "zh_cn", fallbackLocale = Locale.US)

        assertEquals(listOf("zh_cn", "zh", "en_us"), candidates.take(3))
    }

    @Test
    fun `localized text defaults to current minecraft language`() {
        TACZGunPackPresentation.setCurrentLanguageCodeResolverForTests { "zh_cn" }
        val snapshot = TACZRuntimeSnapshot.EMPTY.copy(
            translations = mapOf(
                "en_us" to mapOf("demo.gun.name" to "Demo Rifle"),
                "zh_cn" to mapOf("demo.gun.name" to "演示步枪"),
            ),
        )

        assertEquals("演示步枪", TACZGunPackPresentation.localizedText(snapshot, "demo.gun.name"))
    }

    @Test
    fun `locale candidates fall back to system locale when client language is unavailable`() {
        val candidates = TACZGunPackPresentation.localeCandidates(currentLanguageCode = null, fallbackLocale = Locale.SIMPLIFIED_CHINESE)

        assertTrue(candidates.first() == "zh_cn")
        assertTrue(candidates.contains("en_us"))
    }
}