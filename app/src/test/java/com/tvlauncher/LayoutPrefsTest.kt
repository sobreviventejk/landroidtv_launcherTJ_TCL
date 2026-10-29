package com.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPrefsTest {

    @Test
    fun greetingSizes_growWithEachLevel() {
        for (level in 1 until LayoutPrefs.GREETING_MAX) {
            assertTrue(LayoutPrefs.greetingTitleSp(level) < LayoutPrefs.greetingTitleSp(level + 1))
            assertTrue(LayoutPrefs.greetingBodySp(level) < LayoutPrefs.greetingBodySp(level + 1))
        }
    }

    @Test
    fun greetingBody_isNeverBiggerThanTitle() {
        for (level in 1..LayoutPrefs.GREETING_MAX) {
            assertTrue(LayoutPrefs.greetingBodySp(level) <= LayoutPrefs.greetingTitleSp(level))
        }
    }

    @Test
    fun greetingHidden_hasNoTextSize() {
        assertEquals(0f, LayoutPrefs.greetingTitleSp(LayoutPrefs.GREETING_HIDDEN), 0f)
        assertEquals(0f, LayoutPrefs.greetingBodySp(LayoutPrefs.GREETING_HIDDEN), 0f)
    }

    @Test
    fun defaultColumns_areInsideTheAllowedRange() {
        assertTrue(LayoutPrefs.DEFAULT_COLUMNS in LayoutPrefs.MIN_COLUMNS..LayoutPrefs.MAX_COLUMNS)
    }

    @Test
    fun homeOverrideOptions_areOnByDefault() {
        assertTrue(TvHomeOverrideService.DEFAULT_HOME_OVERRIDE)
        assertTrue(TvHomeOverrideService.DEFAULT_SUPPRESS_STOCK)
    }
}
