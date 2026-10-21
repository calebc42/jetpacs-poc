// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/** The calendar arithmetic that neither renderer had a test for. */
class MonthGridModelTest {
    private fun obj(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun aMonthLaysOutFromTheWeekStartWithBlanksBefore() {
        // September 2026 begins on a Tuesday.
        val sunday = monthGridLayout("2026-09", DayOfWeek.SUNDAY)!!
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), sunday.weekdayOrder)
        assertEquals(listOf(null, null), sunday.weeks[0].take(2))
        assertEquals("2026-09-01", sunday.weeks[0][2]!!.date)
        assertEquals(2, sunday.weeks[0][2]!!.weekday)
        assertEquals(5, sunday.weeks.size)
        assertEquals(30, sunday.weeks.flatten().count { it != null })
        // Every week is seven wide, the last one padded.
        assertTrue(sunday.weeks.all { it.size == 7 })

        val monday = monthGridLayout("2026-09", DayOfWeek.MONDAY)!!
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 0), monday.weekdayOrder)
        assertEquals(1, monday.weeks[0].indexOfFirst { it != null })
    }

    @Test
    fun aMonthStartingOnTheWeekStartHasNoBlanks() {
        // November 2026 begins on a Sunday.
        val layout = monthGridLayout("2026-11", DayOfWeek.SUNDAY)!!
        assertEquals("2026-11-01", layout.weeks[0][0]!!.date)
        assertEquals(0, layout.weeks[0][0]!!.weekday)
    }

    @Test
    fun anUnparseableMonthIsNullRatherThanAnEmptyGrid() {
        assertNull(monthGridLayout("2026-13", DayOfWeek.SUNDAY))
        assertNull(monthGridLayout("not a month", DayOfWeek.SUNDAY))
    }

    @Test
    fun monthAddCrossesYearsInBothDirections() {
        assertEquals("2027-01", monthAdd("2026-12", 1))
        assertEquals("2025-12", monthAdd("2026-01", -1))
        assertEquals("2028-03", monthAdd("2026-09", 18))
        assertEquals("2025-03", monthAdd("2026-09", -18))
    }

    @Test
    fun anUnchangedAuthoredMonthKeepsTheBrowsedOne() {
        val saved = MonthGridPresentation(authoredMonth = "2026-01", shownMonth = "2026-02")
        assertSame(saved, reconcileMonthGridPresentation(saved, "2026-01"))
    }

    @Test
    fun aChangedAuthoredMonthIsAdoptedEvenAfterABrowse() {
        val saved = MonthGridPresentation(authoredMonth = "2026-01", shownMonth = "2026-02")
        assertEquals(
            MonthGridPresentation("2026-03", "2026-03"),
            reconcileMonthGridPresentation(saved, "2026-03"),
        )
    }

    @Test
    fun dayBoundsAreInclusiveAndWeekdaysDisable() {
        assertTrue(monthGridDayEnabled("2026-09-04", "2026-09-04", "2026-09-08", emptySet(), 5))
        assertTrue(monthGridDayEnabled("2026-09-08", "2026-09-04", "2026-09-08", emptySet(), 2))
        assertFalse(monthGridDayEnabled("2026-09-03", "2026-09-04", null, emptySet(), 4))
        assertFalse(monthGridDayEnabled("2026-09-09", null, "2026-09-08", emptySet(), 3))
        // 0 = Sunday, 6 = Saturday on the wire.
        assertFalse(monthGridDayEnabled("2026-09-05", null, null, setOf(0, 6), 6))
        assertTrue(monthGridDayEnabled("2026-09-07", null, null, setOf(0, 6), 1))
    }

    @Test
    fun aRangeIsInclusiveWithTwoCapsAndAOneDayRangeIsBothCaps() {
        assertEquals(MonthGridRange(true, true, false), monthGridRange("2026-09-04", "2026-09-04", "2026-09-08"))
        assertEquals(MonthGridRange(true, false, false), monthGridRange("2026-09-06", "2026-09-04", "2026-09-08"))
        assertEquals(MonthGridRange(true, false, true), monthGridRange("2026-09-08", "2026-09-04", "2026-09-08"))
        assertEquals(MonthGridRange(false, false, false), monthGridRange("2026-09-09", "2026-09-04", "2026-09-08"))
        assertEquals(MonthGridRange(true, true, true), monthGridRange("2026-09-04", "2026-09-04", "2026-09-04"))
        assertFalse(monthGridRange("2026-09-04", null, null).inRange)
    }

    @Test
    fun dotsAreReadByValueAndCapped() {
        val marks = obj("""{"2026-09-04":{"dots":2.0,"color":"primary"},"2026-09-06":{"dots":1},"2026-09-07":{"dots":9}}""")
        assertEquals(MonthGridMark(2, "primary"), monthGridMark(marks, "2026-09-04"))
        assertEquals(MonthGridMark(1, null), monthGridMark(marks, "2026-09-06"))
        assertEquals(3, monthGridMark(marks, "2026-09-07")!!.dots)
        assertNull(monthGridMark(marks, "2026-09-05"))
    }

    @Test
    fun aDayStyleIsExactlyItsTwoColorsOrNothing() {
        val styles = obj("""{"2026-09-04":{"background":"primary","foreground":"on_primary"},"2026-09-08":{"background":"error"}}""")
        assertEquals(MonthGridDayStyle("primary", "on_primary"), monthGridDayStyle(styles, "2026-09-04"))
        // Half a style is no style: the cell keeps its ordinary face.
        assertNull(monthGridDayStyle(styles, "2026-09-08"))
        assertNull(monthGridDayStyle(styles, "2026-09-05"))
    }
}
