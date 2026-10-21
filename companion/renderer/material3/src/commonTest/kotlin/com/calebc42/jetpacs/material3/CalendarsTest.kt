// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The renderer's calendar is Gregorian and its wire dates ASCII under every
 * default locale (D3 of docs/PLAN-desktop-companion.md). */
class CalendarsTest {
    private fun <T> withDefaultLocale(tag: String, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag(tag))
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private val day = LocalDate.of(2026, 10, 21)
    private val dayMillis = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")

    @Test
    fun isoDatesStayGregorianAndAsciiUnderEveryLocale() {
        // A Buddhist or Japanese calendar locale, and two locales with their
        // own digits: none may reach the wire.
        for (tag in listOf("th-TH-u-ca-buddhist", "ja-JP-u-ca-japanese", "ar-EG-u-nu-arab", "fa-IR", "en-US")) {
            withDefaultLocale(tag) {
                assertEquals(tag, dayMillis, parseIsoDateUtc("2026-10-21"))
                assertEquals(tag, "2026-10-21", isoDateFromUtcMillis(dayMillis))
                assertEquals(tag, 2026, utcYear(dayMillis))
                assertEquals(tag, Calendar.WEDNESDAY, utcCalendarDayOfWeek(dayMillis))
                assertEquals(tag, "2026-11", monthAdd("2026-10", 1))
                assertEquals(tag, "2025-12", monthAdd("2026-01", -1))
                assertTrue(tag, isoDate.matches(localTodayIso()))
                assertTrue(tag, Regex("""\d{4}-\d{2}-\d{2} (Sun|Mon|Tue|Wed|Thu|Fri|Sat)""").matches(localDateStamp()))
                assertTrue(tag, Regex("""\d{2}:\d{2}""").matches(localTimeStamp()))
            }
        }
    }

    @Test
    fun theParseStaysLenientAndRejectsNonDates() {
        assertEquals(parseIsoDateUtc("2026-03-02"), parseIsoDateUtc("2026-02-30"))
        assertEquals(parseIsoDateUtc("2027-01-01"), parseIsoDateUtc("2026-13-01"))
        assertEquals(parseIsoDateUtc("2026-09-30"), parseIsoDateUtc("2026-10-00"))
        assertNull(parseIsoDateUtc("2026-10"))
        assertNull(parseIsoDateUtc("yyyy-mm-dd"))
    }
}
