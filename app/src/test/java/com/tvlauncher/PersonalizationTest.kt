package com.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizationTest {

    // ---- Random messages --------------------------------------------------------------------

    @Test
    fun alternateTarget_rotatesThroughThielJessicaAndBoth() {
        assertEquals("Thiel", RandomMessages.nameFor(RandomMessages.Target.ALTERNATE, 0))
        assertEquals("Jéssica", RandomMessages.nameFor(RandomMessages.Target.ALTERNATE, 1))
        assertEquals("Thiel e Jéssica", RandomMessages.nameFor(RandomMessages.Target.ALTERNATE, 2))
        assertEquals("Thiel", RandomMessages.nameFor(RandomMessages.Target.ALTERNATE, 3))
    }

    @Test
    fun fixedTargets_alwaysUseTheChosenName() {
        assertEquals("Thiel", RandomMessages.nameFor(RandomMessages.Target.THIEL, 7))
        assertEquals("Jéssica", RandomMessages.nameFor(RandomMessages.Target.JESSICA, 7))
        assertEquals("Thiel e Jéssica", RandomMessages.nameFor(RandomMessages.Target.BOTH, 7))
    }

    @Test
    fun pick_replacesTheNamePlaceholder() {
        val message = RandomMessages.pick(
            RandomMessages.ALL_KINDS_MASK,
            RandomMessages.Target.BOTH,
            0L
        )
        assertNotNull(message)
        assertFalse(message!!.contains(RandomMessages.PLACEHOLDER))
        assertTrue(message.contains("Thiel e Jéssica"))
    }

    @Test
    fun pick_staysStableInsideTheSameRotationSlot() {
        val first = RandomMessages.pick(RandomMessages.ALL_KINDS_MASK, RandomMessages.Target.BOTH, 0L)
        val sameSlot = RandomMessages.pick(
            RandomMessages.ALL_KINDS_MASK,
            RandomMessages.Target.BOTH,
            GreetingText.ROTATE_MS - 1
        )
        assertEquals(first, sameSlot)
    }

    @Test
    fun pick_onlyUsesTheEnabledKinds() {
        val jokeMask = RandomMessages.maskOf(setOf(RandomMessages.Kind.JOKE))
        val expected = RandomMessages.ALL
            .filter { it.kind == RandomMessages.Kind.JOKE }
            .map { it.template.replace(RandomMessages.PLACEHOLDER, "Thiel e Jéssica") }

        for (slot in 0L until 30L) {
            val message = RandomMessages.pick(jokeMask, RandomMessages.Target.BOTH, slot * GreetingText.ROTATE_MS)
            assertNotNull(message)
            assertTrue(expected.contains(message))
        }
    }

    @Test
    fun pick_returnsNullWhenNoKindIsEnabled() {
        assertNull(RandomMessages.pick(0, RandomMessages.Target.BOTH, 0L))
    }

    @Test
    fun maskRoundTrip_keepsEveryKind() {
        val kinds = setOf(RandomMessages.Kind.MESSAGE, RandomMessages.Kind.POEM)
        assertEquals(kinds, RandomMessages.kindsOf(RandomMessages.maskOf(kinds)))
    }

    // ---- Weather ----------------------------------------------------------------------------

    @Test
    fun weekdayLabel_usesPortugueseAbbreviations() {
        assertEquals("qui", Weather.weekdayLabel("2026-10-29"))
        assertEquals("sex", Weather.weekdayLabel("2026-10-30"))
        assertEquals("dom", Weather.weekdayLabel("2026-11-01"))
    }

    @Test
    fun conditionLabel_describesTheMainCodes() {
        assertEquals("Céu limpo", Weather.conditionLabel(0))
        assertEquals("Nublado", Weather.conditionLabel(3))
        assertEquals("Chuva", Weather.conditionLabel(63))
        assertEquals("Tempestade", Weather.conditionLabel(95))
        assertEquals("Tempo variável", Weather.conditionLabel(1234))
    }

    @Test
    fun iconShapeList_hasUniqueNames() {
        val names = IconShapes.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals(IconShapes.DEFAULT, 0)
    }
}
