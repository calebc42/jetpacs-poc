// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Everything a month grid knows about its days, besides the month itself. */
@Immutable
data class MonthGridDays(
    val marks: Map<String, MonthGridMark> = emptyMap(),
    val dayStyles: Map<String, MonthGridDayStyle> = emptyMap(),
    val selected: String? = null,
    val rangeStart: String? = null,
    val rangeEnd: String? = null,
    val minDate: String? = null,
    val maxDate: String? = null,
    val disabledWeekdays: Set<Int> = emptySet(),
    val minMonth: String? = null,
    val maxMonth: String? = null,
)

/** How far a horizontal drag travels before it turns the month. */
private val MonthSwipeThreshold = 60.dp

/** The band and the day face are this tall; the cell keeps its 48 dp target. */
private val MonthGridDayVisualHeight = 40.dp

/**
 * Jetpacs' month grid: a header with two arrows, the weekday initials, and
 * the month in weeks of seven.
 *
 * This is a controlled view of [month]: the arrows and a horizontal swipe
 * report the next month through [onMonthChange] and the caller decides what
 * is shown, which is how the viewed month stays the caller's presentation
 * state. Every day that can be tapped reports its ISO date through
 * [onDayTap]; a day outside the bounds or on a disabled weekday is drawn
 * disabled and never reports.
 *
 * Faces layer in the order SPEC 17.5 gives them: an authored day style wins
 * its cell outright, then a selection or range cap fills it, then today is
 * ringed. [style] and the slot styles change visual properties only and are
 * layered after the theme and the active design profile.
 */
@Composable
fun JetpacsMonthGrid(
    month: String,
    modifier: Modifier = Modifier,
    days: MonthGridDays = MonthGridDays(),
    today: String = LocalDate.now().toString(),
    locale: Locale = Locale.getDefault(),
    fallback: (@Composable () -> Unit)? = null,
    onDayTap: ((String) -> Unit)? = null,
    onMonthChange: ((String) -> Unit)? = null,
    style: Style = Style,
    headerStyle: Style = Style,
    navStyle: Style = Style,
    weekdayStyle: Style = Style,
    dayStyle: Style = Style,
    todayStyle: Style = Style,
    rangeStyle: Style = Style,
    markStyle: Style = Style,
) {
    val weekStart = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val layout = remember(month, weekStart) { monthGridLayout(month, weekStart) }
    if (layout == null) {
        // The validator makes this unreachable; the authored fallback is
        // still the honest thing to draw rather than nothing.
        fallback?.invoke()
        return
    }
    val previous = monthAdd(month, -1)
    val next = monthAdd(month, 1)
    val canGoBack = days.minMonth == null || previous >= days.minMonth
    val canGoForward = days.maxMonth == null || next <= days.maxMonth
    val density = LocalDensity.current
    val threshold = with(density) { MonthSwipeThreshold.toPx() }
    var dragTotal by remember(month) { mutableStateOf(0f) }

    Column(
        modifier = modifier
            .styleable(
                remember { MutableStyleState(null) },
                JetpacsTheme.styles.monthGrid,
                designComponentStyle(DesignComponentStyleSlot.MonthGridContainer),
                style,
            )
            .pointerInput(month, canGoBack, canGoForward) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        when {
                            dragTotal <= -threshold && canGoForward -> onMonthChange?.invoke(next)
                            dragTotal >= threshold && canGoBack -> onMonthChange?.invoke(previous)
                        }
                        dragTotal = 0f
                    },
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                )
            },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MonthGridHeader(
            month = month,
            locale = locale,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onPrevious = { onMonthChange?.invoke(previous) },
            onNext = { onMonthChange?.invoke(next) },
            headerStyle = headerStyle,
            navStyle = navStyle,
        )
        val weekdayText = resolvedDesignTextStyle(
            DesignComponentStyleSlot.MonthGridWeekday,
            JetpacsTheme.typography.caption,
        )
        Row(Modifier.fillMaxWidth()) {
            layout.weekdayOrder.forEach { weekday ->
                val name = java.time.DayOfWeek.of(if (weekday == 0) 7 else weekday)
                    .getDisplayName(DateTextStyle.NARROW, locale)
                Box(
                    Modifier
                        .weight(1f)
                        .styleable(
                            remember { MutableStyleState(null) },
                            JetpacsTheme.styles.monthGridWeekday,
                            designComponentNonTextStyle(DesignComponentStyleSlot.MonthGridWeekday),
                            weekdayStyle,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(name, style = weekdayText.copy(textAlign = TextAlign.Center))
                }
            }
        }
        Column(
            Modifier.semantics {
                collectionInfo = CollectionInfo(rowCount = layout.weeks.size, columnCount = 7)
            },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            layout.weeks.forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        if (day == null) {
                            Spacer(Modifier.weight(1f).height(MonthGridDayVisualHeight))
                        } else {
                            MonthGridDayCell(
                                day = day,
                                days = days,
                                isToday = day.date == today,
                                onTap = onDayTap,
                                dayStyle = dayStyle,
                                todayStyle = todayStyle,
                                rangeStyle = rangeStyle,
                                markStyle = markStyle,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGridHeader(
    month: String,
    locale: Locale,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    headerStyle: Style,
    navStyle: Style,
) {
    val parsed = remember(month) { java.time.YearMonth.parse(month) }
    val label = parsed.month.getDisplayName(DateTextStyle.SHORT, locale) + " " + parsed.year
    val labelText = resolvedDesignTextStyle(
        DesignComponentStyleSlot.MonthGridHeader,
        JetpacsTheme.typography.choice,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MonthGridNavButton("chevron_left", "Previous month", canGoBack, onPrevious, navStyle)
        Box(
            Modifier
                .weight(1f)
                .styleable(
                    remember { MutableStyleState(null) },
                    JetpacsTheme.styles.monthGridHeader,
                    designComponentNonTextStyle(DesignComponentStyleSlot.MonthGridHeader),
                    headerStyle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = labelText.copy(textAlign = TextAlign.Center))
        }
        MonthGridNavButton("chevron_right", "Next month", canGoForward, onNext, navStyle)
    }
}

/** One month arrow: a full target that the bounds may disable. */
@Composable
private fun MonthGridNavButton(
    icon: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    navStyle: Style,
) {
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) { it.isEnabled = enabled }
    val tint = resolvedDesignTextStyle(
        DesignComponentStyleSlot.MonthGridNav,
        TextStyle(color = JetpacsTheme.colors.content),
        styleState,
    ).color
    Box(
        Modifier
            .size(48.dp)
            .hoverable(source, enabled)
            .jetpacsFocusable(enabled, source)
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .styleable(
                styleState,
                JetpacsTheme.styles.monthGridNav,
                designComponentStyle(DesignComponentStyleSlot.MonthGridNav),
                navStyle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        DesignGlyph(icon, tint, 24.dp)
    }
}

/** One day: its band behind, its face, its number, its dots. */
@Composable
private fun RowScope.MonthGridDayCell(
    day: MonthGridDay,
    days: MonthGridDays,
    isToday: Boolean,
    onTap: ((String) -> Unit)?,
    dayStyle: Style,
    todayStyle: Style,
    rangeStyle: Style,
    markStyle: Style,
) {
    val roles = JetpacsTheme.roles
    val colors = JetpacsTheme.colors
    val enabled = monthGridDayEnabled(
        day.date, days.minDate, days.maxDate, days.disabledWeekdays, day.weekday,
    )
    val range = monthGridRange(day.date, days.rangeStart, days.rangeEnd)
    val mark = days.marks[day.date]
    val authored = days.dayStyles[day.date]
    val authoredBackground = authored?.let { resolveAuthoredColor(it.background, roles) }
    val authoredForeground = authored?.let { resolveAuthoredColor(it.foreground, roles) }
    val hasDayStyle = authoredBackground != null && authoredForeground != null
    val isSelected = day.date == days.selected
    // A styled cell, a selection and a range cap are all "filled": the face
    // that carries the number is painted, and today's ring gives way.
    val filled = hasDayStyle || isSelected || range.isCap
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        // An authored day style overrides the selected and cap faces outright
        // (SPEC 17.5). State rules resolve over the merged base whatever the
        // layer order, so a styled cell must not carry the selected state at
        // all or a profile's selected rule would repaint it.
        it.isSelected = (isSelected || range.isCap) && !hasDayStyle
    }

    // The number: base, the day slot, then the authored foreground last.
    val baseText = JetpacsTheme.typography.choice.copy(
        color = if (filled) colors.onAccent else colors.content,
        textAlign = TextAlign.Center,
    )
    val slotText = resolvedDesignTextStyle(DesignComponentStyleSlot.MonthGridDay, baseText, styleState)
    val withToday = if (isToday && !filled) {
        resolvedDesignTextStyle(DesignComponentStyleSlot.MonthGridToday, slotText, styleState)
    } else {
        slotText
    }
    val numberText = if (hasDayStyle) withToday.copy(color = authoredForeground!!) else withToday

    val dots = mark?.dots ?: 0
    val explicitDot = mark?.color?.let { resolveAuthoredColor(it, roles) }
    val dotColor = explicitDot
        ?: authoredForeground.takeIf { hasDayStyle }
        ?: resolvedDesignTextStyle(
            DesignComponentStyleSlot.MonthGridMark,
            TextStyle(color = if (filled) colors.onAccent else colors.accent),
            styleState,
        ).color
    val bandColor = resolvedDesignColor(
        DesignComponentStyleSlot.MonthGridRange,
        DesignProperty.BackgroundColor,
    ) ?: colors.selectedSurface

    val authoredFace = remember(authoredBackground, authoredForeground) {
        Style {
            if (authoredBackground != null) background(authoredBackground)
            if (authoredForeground != null) contentColor(authoredForeground)
        }
    }
    val description = day.date + if (dots > 0) ", $dots marked" else ""
    val state = when {
        isSelected || range.isCap -> "Selected"
        range.inRange -> "In range"
        isToday -> "Today"
        else -> null
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .semantics {
                contentDescription = description
                if (state != null) stateDescription = state
                if (!enabled) disabled()
            }
            .then(
                if (onTap != null) {
                    Modifier
                        .hoverable(source, enabled)
                        .jetpacsFocusable(enabled, source)
                        .clickable(
                            interactionSource = source,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                        ) { onTap(day.date) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // One band across the interior; each cap shows the half that
        // faces the interior. A one-day range has no interior to show.
        if (range.inRange && !(range.isStart && range.isEnd)) {
            MonthGridBand(range, bandColor, rangeStyle)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .styleable(
                    styleState,
                    JetpacsTheme.styles.monthGridDay,
                    designComponentStyle(DesignComponentStyleSlot.MonthGridDay),
                    // Today's ring layers above the day face and below the
                    // profile's own today slot, and only while nothing fills.
                    if (isToday && !filled) JetpacsTheme.styles.monthGridToday else Style,
                    if (isToday && !filled) {
                        designComponentStyle(DesignComponentStyleSlot.MonthGridToday)
                    } else {
                        Style
                    },
                    if (isToday && !filled) todayStyle else Style,
                    dayStyle,
                    authoredFace,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BasicText(day.dayOfMonth.toString(), style = numberText)
                if (dots > 0) {
                    Row(
                        Modifier
                            .padding(top = 2.dp)
                            .styleable(styleState, Style, markStyle),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        repeat(dots) {
                            Box(Modifier.size(4.dp).clip(CircleShape).background(dotColor))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MonthGridBand(range: MonthGridRange, color: Color, rangeStyle: Style) {
    val base = Modifier
        .height(MonthGridDayVisualHeight)
        .styleable(remember { MutableStyleState(null) }, Style, rangeStyle)
        .background(color)
    when {
        range.isStart -> Box(base.fillMaxWidth(0.5f).align(Alignment.CenterEnd))
        range.isEnd -> Box(base.fillMaxWidth(0.5f).align(Alignment.CenterStart))
        else -> Box(base.fillMaxWidth())
    }
}

/** A SPEC 16.6 color: a theme role name, or a hex literal. */
private fun resolveAuthoredColor(spec: String, roles: JetpacsThemeRoles): Color? =
    DesignThemeRole.fromWireName(spec)?.let { roles[it] } ?: parseHexColor(spec)

private val MonthGridPresentationSaver = listSaver<MonthGridPresentation, String>(
    save = { listOf(it.authoredMonth, it.shownMonth) },
    restore = { MonthGridPresentation(it[0], it[1]) },
)

/**
 * Design-scoped `month_grid`, honoring every member so it never declines.
 *
 * The viewed month is the only local state, keyed on the presentation
 * identity: a snapshot that changes only marks or styles keeps the month the
 * user is viewing, and a changed authored month adopts it in the same frame
 * (SPEC 17.5). A day tap and a month change dispatch with the §14.3 injected
 * values, the ISO date and the new month.
 */
object JetpacsDesignMonthGridRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.month-grid.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("month_grid")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val authoredMonth = node.gridText("month")
        var presentation by rememberSaveable(context.path, stateSaver = MonthGridPresentationSaver) {
            mutableStateOf(MonthGridPresentation(authoredMonth, authoredMonth))
        }
        val reconciled = reconcileMonthGridPresentation(presentation, authoredMonth)
        if (presentation != reconciled) presentation = reconciled

        val marks = node["marks"] as? JsonObject
        val styles = node["day_styles"] as? JsonObject
        val days = remember(node) {
            MonthGridDays(
                marks = marks?.keys.orEmpty().mapNotNull { date ->
                    monthGridMark(marks, date)?.let { date to it }
                }.toMap(),
                dayStyles = styles?.keys.orEmpty().mapNotNull { date ->
                    monthGridDayStyle(styles, date)?.let { date to it }
                }.toMap(),
                selected = node.gridText("selected").takeIf { it.isNotEmpty() },
                rangeStart = node.gridText("range_start").takeIf { it.isNotEmpty() },
                rangeEnd = node.gridText("range_end").takeIf { it.isNotEmpty() },
                minDate = node.gridText("min_date").takeIf { it.isNotEmpty() },
                maxDate = node.gridText("max_date").takeIf { it.isNotEmpty() },
                disabledWeekdays = (node["disabled_weekdays"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.toInt() }
                    ?.toSet().orEmpty(),
                minMonth = node.gridText("min_month").takeIf { it.isNotEmpty() },
                maxMonth = node.gridText("max_month").takeIf { it.isNotEmpty() },
            )
        }
        val onDayTap = node["on_day_tap"] as? JsonObject
        val onMonthChange = node["on_month_change"] as? JsonObject
        val children = (node["children"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

        JetpacsMonthGrid(
            month = reconciled.shownMonth,
            modifier = modifier,
            days = days,
            fallback = children.takeIf { it.isNotEmpty() }?.let { list ->
                { Column { list.forEachIndexed { index, child -> context.renderChild(child, index) } } }
            },
            onDayTap = onDayTap?.let { descriptor -> { date -> context.action(descriptor, JsonPrimitive(date)) } },
            onMonthChange = { next ->
                presentation = MonthGridPresentation(authoredMonth, next)
                onMonthChange?.let { context.action(it, JsonPrimitive(next)) }
            },
        )
    }
}

private fun JsonObject.gridText(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
