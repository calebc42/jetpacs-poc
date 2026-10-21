// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

// One calendar for the renderer (D3 of docs/PLAN-desktop-companion.md). Wire
// dates are ISO Gregorian, so the calendar is Gregorian by name: a default
// locale with a Buddhist or Japanese calendar must not read an ISO year as
// its own era, and a locale with its own digits must not put them into a
// wire value, so every format here uses Locale.ROOT. The parse stays
// lenient, as it has always been: 2026-02-30 rolls into March and day 0 is
// the previous month's last day.

private fun utcCalendar(): Calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"))

/** The local calendar: the default zone and the locale's week conventions. */
internal fun localCalendar(): Calendar =
    GregorianCalendar(TimeZone.getDefault(), Locale.getDefault())

/** Parse YYYY-MM-DD to UTC-midnight millis for the picker; null if invalid. */
internal fun parseIsoDateUtc(iso: String): Long? {
    val parts = iso.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toIntOrNull() ?: return null
    val mo = parts[1].toIntOrNull() ?: return null
    val d = parts[2].toIntOrNull() ?: return null
    return utcCalendar().apply { clear(); set(y, mo - 1, d) }.timeInMillis
}

/** Format the picker's UTC-midnight millis back to YYYY-MM-DD. */
internal fun isoDateFromUtcMillis(millis: Long): String {
    val cal = utcCalendar().apply { timeInMillis = millis }
    return String.format(Locale.ROOT, "%04d-%02d-%02d",
        cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1,
        cal.get(Calendar.DAY_OF_MONTH))
}

/** The day of week of a UTC instant, 1 = Sunday through 7 = Saturday. */
internal fun utcCalendarDayOfWeek(utcMillis: Long): Int =
    utcCalendar().apply { timeInMillis = utcMillis }.get(Calendar.DAY_OF_WEEK)

/** The calendar year of a UTC instant. */
internal fun utcYear(utcMillis: Long): Int =
    utcCalendar().apply { timeInMillis = utcMillis }.get(Calendar.YEAR)

/** Today in the local zone as YYYY-MM-DD, the month grid's `today` mark. */
internal fun localTodayIso(): String = localCalendar().let {
    String.format(Locale.ROOT, "%04d-%02d-%02d", it.get(Calendar.YEAR),
        it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
}
