package io.github.golangsupport.ide.inspections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure layout functions. The sample is Saturday 2026-03-07 15:09:08.123456789 +03:00 MSK (day of year 66); every expected text
 * was derived by hand from the rules of time/format.go.
 */
class GoTimeLayoutTest {
    private fun r(layout: String) = GoTimeLayout.render(layout)

    @Test
    fun stdlibConstants() {
        assertEquals("Sat Mar  7 15:09:08 2026", r("Mon Jan _2 15:04:05 2006")) // ANSIC
        assertEquals("Sat Mar  7 15:09:08 MSK 2026", r("Mon Jan _2 15:04:05 MST 2006")) // UnixDate
        assertEquals("Sat Mar 07 15:09:08 +0300 2026", r("Mon Jan 02 15:04:05 -0700 2006")) // RubyDate
        assertEquals("07 Mar 26 15:09 MSK", r("02 Jan 06 15:04 MST")) // RFC822
        assertEquals("07 Mar 26 15:09 +0300", r("02 Jan 06 15:04 -0700")) // RFC822Z
        assertEquals("Saturday, 07-Mar-26 15:09:08 MSK", r("Monday, 02-Jan-06 15:04:05 MST")) // RFC850
        assertEquals("Sat, 07 Mar 2026 15:09:08 MSK", r("Mon, 02 Jan 2006 15:04:05 MST")) // RFC1123
        assertEquals("Sat, 07 Mar 2026 15:09:08 +0300", r("Mon, 02 Jan 2006 15:04:05 -0700")) // RFC1123Z
        assertEquals("2026-03-07T15:09:08+03:00", r("2006-01-02T15:04:05Z07:00")) // RFC3339
        assertEquals("2026-03-07T15:09:08.123456789+03:00", r("2006-01-02T15:04:05.999999999Z07:00")) // RFC3339Nano
        assertEquals("3:09PM", r("3:04PM")) // Kitchen
        assertEquals("Mar  7 15:09:08", r("Jan _2 15:04:05")) // Stamp
        assertEquals("Mar  7 15:09:08.123", r("Jan _2 15:04:05.000")) // StampMilli
        assertEquals("2026-03-07 15:09:08", r("2006-01-02 15:04:05")) // DateTime
        assertEquals("2026-03-07", r("2006-01-02")) // DateOnly
        assertEquals("15:09:08", r("15:04:05")) // TimeOnly
    }

    @Test
    fun elements() {
        assertEquals("Saturday, March 7, 2026", r("Monday, January 2, 2006"))
        assertEquals("07/03/26 03:09:08 pm +0300", r("02/01/06 03:04:05 pm -0700"))
        assertEquals("3/7 3:9:8 PM", r("1/2 3:4:5 PM"))
        assertEquals(" 66 066", r("__2 002"))
        assertEquals("Mar7", r("Jan2"))
        assertEquals("Janet", r("Janet"))
        assertEquals("_2026", r("_2006"))
        assertEquals("+03 +03:00 +030000 +03:00:00", r("-07 -07:00 -070000 -07:00:00"))
        assertEquals("08.123456 08,123 08.123", r("05.000000 05,000 05.999"))
    }

    @Test
    fun otherSamples() {
        val midnightUtc = GoTimeLayout.Sample(2026, 1, 1, 0, 0, 0, 120_000_000, 0, "UTC")
        assertEquals("12:00 AM", GoTimeLayout.render("3:04 PM", midnightUtc))
        assertEquals("Z Z +00:00", GoTimeLayout.render("Z0700 Z07:00 -07:00", midnightUtc))
        assertEquals("00.12", GoTimeLayout.render("05.999", midnightUtc))
        assertEquals("00", GoTimeLayout.render("05.999", GoTimeLayout.Sample(2026, 1, 1, 0, 0, 0, 0, 0, "UTC")))
        assertEquals("Thursday 001", GoTimeLayout.render("Monday 002", midnightUtc))
    }

    @Test
    fun elementCounts() {
        assertEquals(0, GoTimeLayout.elementCount("date"))
        assertEquals(0, GoTimeLayout.elementCount("yyyy-MM-dd"))
        assertEquals(3, GoTimeLayout.elementCount("2006-01-02"))
    }

    @Test
    fun foreignNotation() {
        assertEquals("2006-01-02 15:04:05", GoTimeLayout.foreignToGo("yyyy-MM-dd HH:mm:ss"))
        assertEquals("2006-01-02", GoTimeLayout.foreignToGo("YYYY-MM-DD"))
        assertEquals("02/01/06 03:04:05.000", GoTimeLayout.foreignToGo("dd/MM/yy hh:mm:ss.SSS"))
        assertNull(GoTimeLayout.foreignToGo("2006-MM-dd"))
        assertNull(GoTimeLayout.foreignToGo("date"))
        assertNull(GoTimeLayout.foreignToGo("2006-01-02"))
    }

    @Test
    fun swappedIso() {
        assertTrue(GoTimeLayout.isSwappedIsoDate("2006-02-01"))
        assertTrue(GoTimeLayout.isSwappedIsoDate("2006-02-01 15:04"))
        assertTrue(GoTimeLayout.isSwappedIsoDate("2006-02-01T15:04:05Z07:00"))
        assertFalse(GoTimeLayout.isSwappedIsoDate("2006-01-02"))
        assertFalse(GoTimeLayout.isSwappedIsoDate("2006-02-015"))
        assertFalse(GoTimeLayout.isSwappedIsoDate("02-01-2006"))
    }
}
