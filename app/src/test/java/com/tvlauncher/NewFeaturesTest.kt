package com.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {

    @Test
    fun timeGreeting_coversTheDay() {
        assertEquals("Bom dia", GreetingText.timeGreeting(8))
        assertEquals("Boa tarde", GreetingText.timeGreeting(15))
        assertEquals("Boa noite", GreetingText.timeGreeting(22))
        assertEquals("Boa noite", GreetingText.timeGreeting(3))
    }

    @Test
    fun title_replacesOlaOnlyWhenEnabled() {
        val raw = "Olá, Thiel e Jéssica\nfrase"
        assertEquals("Olá, Thiel e Jéssica", GreetingText.title(raw, 9, false))
        assertEquals("Bom dia, Thiel e Jéssica", GreetingText.title(raw, 9, true))
        assertEquals("Outro título", GreetingText.title("Outro título", 9, true))
    }

    @Test
    fun phrases_rotateEveryTenMinutes() {
        val list = listOf("a", "b", "c")
        assertEquals("a", GreetingText.pick(list, 0))
        assertEquals("b", GreetingText.pick(list, GreetingText.ROTATE_MS))
        assertEquals("a", GreetingText.pick(list, 3 * GreetingText.ROTATE_MS))
        assertNull(GreetingText.pick(emptyList(), 0))
    }

    @Test
    fun compose_withoutPhrasesIsJustTheTitle() {
        assertEquals("Olá", GreetingText.compose("Olá", 10, 0, false))
        assertEquals("Olá\nx", GreetingText.compose("Olá\nx", 10, 0, false))
    }

    @Test
    fun rank_ordersByCountAndIgnoresUnknownApps() {
        val counts = mapOf("a" to 3, "b" to 7, "c" to 5, "gone" to 99, "zero" to 0)
        val valid = setOf("a", "b", "c", "zero")
        assertEquals(listOf("b", "c"), UsageTracker.rank(counts, valid, 2))
        assertEquals(listOf("b", "c", "a"), UsageTracker.rank(counts, valid, 10))
    }

    @Test
    fun pin_validationAndHash() {        assertTrue(PinPrefs.isValidPin("1234"))
        assertTrue(PinPrefs.isValidPin("12345678"))
        assertFalse(PinPrefs.isValidPin("123"))
        assertFalse(PinPrefs.isValidPin("123456789"))
        assertFalse(PinPrefs.isValidPin("12a4"))
        assertEquals(PinPrefs.hash("s", "1234"), PinPrefs.hash("s", "1234"))
        assertFalse(PinPrefs.hash("s", "1234") == PinPrefs.hash("t", "1234"))
        assertFalse(PinPrefs.hash("s", "1234").contains("1234"))
    }

    @Test
    fun search_ignoresAccentsAndCase() {
        assertEquals("configuracoes", SearchAdapter.normalize("Configurações"))
        assertEquals("sao paulo", SearchAdapter.normalize("  São   Paulo "))
        val apps = listOf(
            AppInfo("p1", "Netflix"),
            AppInfo("p2", "YouTube"),
            AppInfo("p3", "Configurações")
        )
        assertEquals(listOf("p1"), SearchAdapter.filter(apps, "NETFLIX").map { it.packageName })
        assertEquals(
            listOf("p3"),
            SearchAdapter.filter(apps, "configuracoes").map { it.packageName }
        )
        assertEquals(3, SearchAdapter.filter(apps, "").size)
        assertTrue(SearchAdapter.filter(apps, "nada").isEmpty())
    }

    @Test
    fun lockDuration_startsAfterFiveFailuresAndStopsAtMaximum() {
        assertEquals(0L, PinPrefs.lockDurationMs(0))
        assertEquals(0L, PinPrefs.lockDurationMs(4))
        assertEquals(30_000L, PinPrefs.lockDurationMs(5))
        assertEquals(60_000L, PinPrefs.lockDurationMs(7))
        assertEquals(15 * 60 * 1000L, PinPrefs.lockDurationMs(200))
    }
}
