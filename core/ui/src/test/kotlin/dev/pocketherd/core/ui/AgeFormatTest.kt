package dev.pocketherd.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AgeFormatTest {

    @Test
    fun `thresholds match the spec`() {
        assertEquals("now", formatAge(0))
        assertEquals("1m", formatAge(1))
        assertEquals("59m", formatAge(59))
        assertEquals("1h", formatAge(60))
        assertEquals("2h 8m", formatAge(128))
        assertEquals("23h 59m", formatAge(1439))
        assertEquals("1d", formatAge(1440))
    }

    @Test
    fun `a whole number of hours drops the minutes`() {
        assertEquals("3h", formatAge(180))
    }

    @Test
    fun `an inferred age is marked so it cannot be read as measured`() {
        assertEquals("14m", formatAgeDisplay(14, exact = true))
        assertEquals("≥2h 8m", formatAgeDisplay(128, exact = false))
    }

    @Test
    fun `spoken form avoids the symbol and gets plurals right`() {
        assertEquals("for 14 minutes", formatAgeSpoken(14, exact = true))
        assertEquals("for at least 2 hours 8 minutes", formatAgeSpoken(128, exact = false))
        assertEquals("for 1 minute", formatAgeSpoken(1, exact = true))
        assertEquals("for 1 hour", formatAgeSpoken(60, exact = true))
        assertEquals("for 1 day", formatAgeSpoken(1440, exact = true))
        assertEquals("for just now", formatAgeSpoken(0, exact = true))
    }
}
