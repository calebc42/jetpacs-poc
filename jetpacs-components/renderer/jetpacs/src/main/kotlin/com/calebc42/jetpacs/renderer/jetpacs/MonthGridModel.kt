// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.DayOfWeek
import java.time.YearMonth

/*
 * The month grid's arithmetic, with no Compose in it.
 *
 * SPEC 17.5 dates are zero-padded ISO strings, so string order is
 * chronological and every bound below is a plain comparison. Weekdays on
 * the wire are 0..6 with 0 = Sunday.
 */

/** The month a grid was authored with and the one the user is looking at. */
data class MonthGridPresentation(val authoredMonth: String, val shownMonth: String)

/**
 * A snapshot that changes only marks or styles keeps the month the user is
 * viewing; a changed authored month adopts it (SPEC 17.5). Comparing the
 * saved authored month is what tells a retained browse from an authored
 * change that arrived while the grid was not on screen.
 */
fun reconcileMonthGridPresentation(
    saved: MonthGridPresentation,
    authoredMonth: String,
): MonthGridPresentation =
    if (saved.authoredMonth == authoredMonth) saved
    else MonthGridPresentation(authoredMonth, authoredMonth)

/** `YYYY-MM` shifted by [delta] months, across year boundaries either way. */
fun monthAdd(month: String, delta: Int): String {
    val total = month.substring(0, 4).toInt() * 12 + (month.substring(5, 7).toInt() - 1) + delta
    return "%04d-%02d".format(Math.floorDiv(total, 12), Math.floorMod(total, 12) + 1)
}

/** One day of the shown month, with its wire weekday. */
data class MonthGridDay(val date: String, val dayOfMonth: Int, val weekday: Int)

/** The month laid out in weeks of seven; null is a blank cell. */
data class MonthGridLayout(val weeks: List<List<MonthGridDay?>>, val weekdayOrder: List<Int>)

/** Sunday-based wire index for a `java.time` day. */
fun DayOfWeek.wireWeekday(): Int = value % 7

/** Lay [month] out starting each week on [weekStart]; null when unparseable. */
fun monthGridLayout(month: String, weekStart: DayOfWeek): MonthGridLayout? {
    val parsed = runCatching { YearMonth.parse(month) }.getOrNull() ?: return null
    val start = weekStart.wireWeekday()
    val leading = (parsed.atDay(1).dayOfWeek.wireWeekday() - start + 7) % 7
    val cells = List<MonthGridDay?>(leading) { null } + (1..parsed.lengthOfMonth()).map { day ->
        val date = "%s-%02d".format(month, day)
        MonthGridDay(date, day, parsed.atDay(day).dayOfWeek.wireWeekday())
    }
    val weeks = cells.chunked(7).map { week -> week + List(7 - week.size) { null } }
    return MonthGridLayout(weeks, List(7) { (start + it) % 7 })
}

/** A day outside the bounds or on a disabled weekday is disabled (SPEC 17.5). */
fun monthGridDayEnabled(
    date: String,
    minDate: String?,
    maxDate: String?,
    disabledWeekdays: Set<Int>,
    weekday: Int,
): Boolean =
    (minDate == null || date >= minDate) &&
        (maxDate == null || date <= maxDate) &&
        weekday !in disabledWeekdays

/** Where a day sits in an inclusive range, if anywhere. */
data class MonthGridRange(val inRange: Boolean, val isStart: Boolean, val isEnd: Boolean) {
    val isCap: Boolean get() = isStart || isEnd
}

fun monthGridRange(date: String, rangeStart: String?, rangeEnd: String?): MonthGridRange {
    if (rangeStart == null || rangeEnd == null) return MonthGridRange(false, false, false)
    val inRange = date >= rangeStart && date <= rangeEnd
    return MonthGridRange(inRange, inRange && date == rangeStart, inRange && date == rangeEnd)
}

/** An authored mark: a dot count and, optionally, its own color. */
data class MonthGridMark(val dots: Int, val color: String?)

/**
 * The mark for [date], or null. `dots` is read by VALUE: the validator
 * floors a double, so `2.0` is legal traffic and must still read 2.
 */
fun monthGridMark(marks: JsonObject?, date: String): MonthGridMark? {
    val mark = marks?.get(date) as? JsonObject ?: return null
    val dots = (mark["dots"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ?.toInt()?.coerceIn(0, 3) ?: 0
    val color = (mark["color"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() }
    return MonthGridMark(dots, color)
}

/** Exactly the two colors an authored day style carries, or nothing. */
data class MonthGridDayStyle(val background: String, val foreground: String)

/** [date]'s own style, without inferring anything about the date. */
fun monthGridDayStyle(styles: JsonObject?, date: String): MonthGridDayStyle? {
    val style = styles?.get(date) as? JsonObject ?: return null
    val background = (style["background"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() } ?: return null
    val foreground = (style["foreground"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() } ?: return null
    return MonthGridDayStyle(background, foreground)
}
