// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 17.5 visualization nodes, ported from poc-v1 with the format-6
// vocabulary: canvas ops carry width/height (not w/h), radius (not r),
// stroke_width (not stroke), and {x,y} path points (not [x,y] pairs); a
// chart's on_point_tap returns the COMPLETE authored point object (§17.5, poc
// returned only {index,y}); month_grid keys its shown month on the §16.1
// presentation identity. Unknown canvas ops are skipped, never fatal.
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.renderer.model.vocabulary.BooleanOrAuto
import com.calebc42.ebp.renderer.model.vocabulary.NodeProperties
import com.calebc42.ebp.renderer.model.vocabulary.StringOrNumber
import com.calebc42.ebp.renderer.model.vocabulary.generated.*
import com.calebc42.ebp.renderer.model.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.rememberTextMeasurer
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// -------------------------------------------------------------------- chart

private val CHART_PALETTE = listOf(
    Color(0xFF4C6FFF), Color(0xFF00A676), Color(0xFFFF8A3D),
    Color(0xFFB05CE6), Color(0xFFE64980), Color(0xFF12B5CB))

private class ChartSeriesData(val points: List<JsonObject>, val ys: DoubleArray, val color: Color)

@Composable
internal fun RenderChart(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val p = NodeProperties(node, ChartMembers)
    val kind = (p[ChartMembers.Kind]?.wire ?: "line")
    val seriesArr = p[ChartMembers.Series] ?: JsonArray(emptyList())
    val onPointTap = p[ChartMembers.OnPointTap]
    // C6: `height` is FIELD_TYPES "number" and the validator gates it as
    // positive-finite only, so 12.5 is legal traffic; dimInt truncates it the
    // way org.json's optInt did instead of defaulting the chart to 160dp.
    val heightDp = (p[ChartMembers.Height]?.toInt() ?: 160).coerceAtLeast(0)
    val scheme = MaterialTheme.colorScheme

    val series = ArrayList<ChartSeriesData>()
    var yMin = Double.POSITIVE_INFINITY
    var yMax = Double.NEGATIVE_INFINITY
    var maxLen = 0
    for (s in 0 until seriesArr.size) {
        val so = seriesArr[s] as? JsonObject ?: continue
        val ptsArr = so.arrOrNull("points") ?: JsonArray(emptyList())
        val pts = ArrayList<JsonObject>(ptsArr.size)
        val ys = DoubleArray(ptsArr.size)
        for (i in 0 until ptsArr.size) {
            val p = ptsArr[i] as? JsonObject ?: JsonObject(emptyMap())
            pts.add(p)
            val y = p.doubleOr("y", 0.0)
            ys[i] = y
            if (y < yMin) yMin = y
            if (y > yMax) yMax = y
        }
        maxLen = maxOf(maxLen, ys.size)
        val color = resolveColorIn(scheme, so.stringOr("color").takeIf { it.isNotEmpty() })
            ?: CHART_PALETTE[s % CHART_PALETTE.size]
        series.add(ChartSeriesData(pts, ys, color))
    }
    // A two-number array: the typed read is null unless exactly two finite
    // numbers are authored, which keeps the old length guard load-bearing.
    p[ChartMembers.YRange]?.let { (lo, hi) ->
        yMin = lo
        yMax = hi
    }
    // SPEC 17.5: bars/areas read against a zero baseline — but an explicit
    // y_range is authoritative, so only default the baseline when none was given.
    if ((kind == "bar" || kind == "area") && "y_range" !in node && yMin > 0.0) yMin = 0.0
    if (!yMin.isFinite() || !yMax.isFinite()) { yMin = 0.0; yMax = 1.0 }
    if (yMax == yMin) yMax += 1.0

    // C4a: keyed on the node's identity. The engine shares an untouched
    // chart's instance across revisions, so the grow animation restarts only
    // when the chart really changed — and the node is no longer serialized
    // to a string on every recomposition to find that out.
    var started by remember(ref(node)) { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f, animationSpec = tween(600), label = "chart-grow")

    var mod = m.fillMaxWidth().height(heightDp.dp)
    if (onPointTap != null) {
        mod = mod.pointerInput(ref(node)) {
            detectTapGestures { off ->
                val s0 = series.firstOrNull() ?: return@detectTapGestures
                if (s0.points.isEmpty()) return@detectTapGestures
                // Map tap-x on the SAME denominator the layout uses (maxLen),
                // then select the first series' point at that index (clamped) so
                // the tapped x-position and the returned point agree.
                val idx = if (maxLen <= 1) 0
                    else ((off.x / size.width.toFloat()) * (maxLen - 1)).roundToInt()
                        .coerceIn(0, s0.points.size - 1)
                // SPEC 17.5/14.3 (amendment #114): the COMPLETE authored
                // point object as `value`, AND the resolved ordinal `index`.
                // The echoed point is not an identity — §17.5 does not require
                // authored points to be distinct and §4.3 makes 1 and 1.0
                // equal, so without the index a duplicate point is
                // unresolvable by any comparator.
                ctx.actionInjecting(onPointTap, buildJsonObject { put("index", idx) },
                    s0.points.getOrNull(idx))
            }
        }
    }
    val desc = p[ChartMembers.Summary].orEmpty().ifEmpty { "$kind chart" }
    Canvas(modifier = mod.semantics { contentDescription = desc }) {
        val w = size.width
        val h = size.height
        val n = maxLen
        fun xLine(i: Int): Float = if (n <= 1) w / 2f else w * i / (n - 1)
        fun yAt(v: Double): Float {
            val t = ((v - yMin) / (yMax - yMin)).toFloat().coerceIn(0f, 1f)
            return h - t * h
        }
        val baseY = yAt(if (yMin <= 0.0 && yMax >= 0.0) 0.0 else yMin)
        for (cs in series) {
            if (cs.ys.isEmpty()) continue
            when (kind) {
                "bar" -> {
                    val slot = w / cs.ys.size
                    val bw = slot * 0.6f
                    for (i in cs.ys.indices) {
                        val cx = slot * (i + 0.5f)
                        val top = baseY + (yAt(cs.ys[i]) - baseY) * progress
                        drawRect(cs.color, Offset(cx - bw / 2f, minOf(top, baseY)),
                            Size(bw, abs(baseY - top)))
                    }
                }
                else -> {
                    val path = Path()
                    for (i in cs.ys.indices) {
                        val x = xLine(i)
                        val y = baseY + (yAt(cs.ys[i]) - baseY) * progress
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    if (kind == "area") {
                        val fill = Path().apply {
                            addPath(path); lineTo(xLine(cs.ys.size - 1), baseY)
                            lineTo(xLine(0), baseY); close()
                        }
                        drawPath(fill, cs.color.copy(alpha = 0.18f))
                    }
                    val stroke = if (kind == "sparkline") 2.dp.toPx() else 2.5.dp.toPx()
                    drawPath(path, cs.color, style = Stroke(width = stroke))
                }
            }
        }
    }
}

// ------------------------------------------------------------------- canvas

@Composable
internal fun RenderCanvas(node: JsonObject, m: Modifier) {
    val p = NodeProperties(node, CanvasMembers)
    // C6: canvas width/height are validated positive-finite (not integral), so
    // they read through dimInt — truncating, exactly as optInt did.
    val wDp = (p[CanvasMembers.Width]?.toInt() ?: 100).coerceAtLeast(0)
    val hDp = (p[CanvasMembers.Height]?.toInt() ?: 100).coerceAtLeast(0)
    val ops = p[CanvasMembers.Ops] ?: JsonArray(emptyList())
    val scheme = MaterialTheme.colorScheme
    val fallback = scheme.onSurface
    // Resolve each op colour up front — the DrawScope below is not composable.
    val colors: List<Color> = (0 until ops.size).map { i ->
        resolveColorIn(scheme, (ops[i] as? JsonObject)?.stringOr("color")
            ?.takeIf { it.isNotEmpty() }) ?: fallback
    }
    // Likewise the measurer for `text` ops: created here, used in the DrawScope.
    val textMeasurer = rememberTextMeasurer()
    Canvas(modifier = m.size(wDp.dp, hDp.dp)) {
        // C6: org.json's ONE-arg optDouble defaulted to NaN, and a NaN
        // coordinate draws nothing — so an absent or non-numeric op member
        // arrives here as null and stays NaN. It must never become 0.0, which
        // would paint a malformed op at the origin instead of skipping it.
        fun px(v: Double?): Float = (v ?: Double.NaN).toFloat().dp.toPx()
        for (i in 0 until ops.size) {
            val o = ops[i] as? JsonObject ?: continue
            val color = colors[i]
            // SPEC 17.5: `fill` is a Color; its presence means fill the interior
            // (omission = stroke only). A stroked shape uses the op's `color`.
            val fillColor = resolveColorIn(scheme,
                o.stringOr("fill").takeIf { it.isNotEmpty() })
            val filled = fillColor != null
            val strokeW = px(o.doubleOr("stroke_width", 1.0))
            when (o.stringOr("op")) {
                "line" -> drawLine(color,
                    Offset(px(o["x1"]?.numOrNull()), px(o["y1"]?.numOrNull())),
                    Offset(px(o["x2"]?.numOrNull()), px(o["y2"]?.numOrNull())),
                    strokeWidth = px(o.doubleOr("width", 1.0)))
                "rect" -> {
                    val tl = Offset(px(o["x"]?.numOrNull()), px(o["y"]?.numOrNull()))
                    val sz = Size(px(o["width"]?.numOrNull()), px(o["height"]?.numOrNull()))
                    if (filled) drawRect(fillColor ?: color, tl, sz)
                    else drawRect(color, tl, sz, style = Stroke(strokeW))
                }
                "circle" -> {
                    val center = Offset(px(o["cx"]?.numOrNull()), px(o["cy"]?.numOrNull()))
                    val r = px(o["radius"]?.numOrNull())
                    if (filled) drawCircle(fillColor ?: color, r, center)
                    else drawCircle(color, r, center, style = Stroke(strokeW))
                }
                "path" -> {
                    val pts = o.arrOrNull("points") ?: JsonArray(emptyList())
                    if (pts.size >= 2) {
                        val path = Path()
                        for (j in 0 until pts.size) {
                            val p = pts[j] as? JsonObject ?: continue
                            val x = px(p["x"]?.numOrNull()); val y = px(p["y"]?.numOrNull())
                            if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        if (o.boolOr("closed")) path.close()
                        if (filled) drawPath(path, fillColor ?: color)
                        else drawPath(path, color, style = Stroke(strokeW))
                    }
                }
                "text" -> drawCanvasText(textMeasurer, o.stringOr("text"),
                    px(o["x"]?.numOrNull()), px(o["y"]?.numOrNull()),
                    px(o.doubleOr("size", 12.0)), color)
                else -> {} // SPEC 17.5: unknown op skipped, never fatal
            }
        }
    }
}

// --------------------------------------------------------------- month_grid

/** Saveable month-grid presentation plus the authored value it was derived
 * from. The second member alone cannot distinguish a retained user browse
 * from an authored month change received while this variant was inactive. */
internal data class MonthGridPresentation(
    val authoredMonth: String,
    val shownMonth: String,
)

internal fun reconcileMonthGridPresentation(
    saved: MonthGridPresentation,
    authoredMonth: String,
): MonthGridPresentation =
    if (saved.authoredMonth == authoredMonth) saved
    else MonthGridPresentation(authoredMonth, authoredMonth)

/** Presentation-only colors authored for one ISO calendar date. */
internal data class MonthGridDayStyle(
    val background: String,
    val foreground: String,
)

/** Return DATE's exact authored day style without inferring date semantics. */
internal fun monthGridDayStyle(styles: JsonObject?, date: String): MonthGridDayStyle? {
    val style = styles?.objOrNull(date) ?: return null
    val background = style.stringOr("background").takeIf { it.isNotEmpty() } ?: return null
    val foreground = style.stringOr("foreground").takeIf { it.isNotEmpty() } ?: return null
    return MonthGridDayStyle(background, foreground)
}

/** Resolve dot-color precedence from §17.5 independently of Compose layout. */
internal fun monthGridDotColor(
    explicit: Color?,
    styledForeground: Color?,
    filled: Boolean,
    primary: Color,
    onPrimary: Color,
): Color = explicit ?: styledForeground ?: if (filled) onPrimary else primary

/** Material calendar geometry stays touchable without growing with tablet width. */
internal val MonthGridDayCellHeight = 48.dp
internal val MonthGridDayVisualSize = 40.dp

private val MonthGridPresentationSaver =
    listSaver<MonthGridPresentation, String>(
        save = { listOf(it.authoredMonth, it.shownMonth) },
        restore = { MonthGridPresentation(it[0], it[1]) },
    )

@Composable
internal fun RenderMonthGrid(node: JsonObject, ctx: RenderCtx, m: Modifier) {
    val p = NodeProperties(node, MonthGridMembers)
    val specMonth = p[MonthGridMembers.Month].orEmpty().takeIf { it.matches(Regex("""\d{4}-\d{2}""")) }
        ?: return
    val marks = p[MonthGridMembers.Marks]
    val dayStyles = p[MonthGridMembers.DayStyles]
    val selected = p[MonthGridMembers.Selected].orEmpty()
    val minMonth = p[MonthGridMembers.MinMonth].orEmpty().ifEmpty { null }
    val maxMonth = p[MonthGridMembers.MaxMonth].orEmpty().ifEmpty { null }
    // §17.5 day-level bounds: out-of-range and listed weekdays render
    // disabled and never dispatch; min_month/max_month keep gating only
    // the month arrows, as before. The range pair shades the inclusive
    // span; ISO dates compare as strings.
    val minDate = p[MonthGridMembers.MinDate].orEmpty().ifEmpty { null }
    val maxDate = p[MonthGridMembers.MaxDate].orEmpty().ifEmpty { null }
    val disabledWeekdays = p[MonthGridMembers.DisabledWeekdays]
        ?.mapNotNull { it.numOrNull()?.toInt() } ?: emptyList()
    val rangeStart = p[MonthGridMembers.RangeStart].orEmpty().ifEmpty { null }
    val rangeEnd = p[MonthGridMembers.RangeEnd].orEmpty().ifEmpty { null }
    val onDayTap = p[MonthGridMembers.OnDayTap]
    val onMonthChange = p[MonthGridMembers.OnMonthChange]

    // §16.1: the shown month keys on the presentation identity, re-seeded from
    // the spec when the authored month changes; mark-only re-pushes keep it.
    key(ctx.path) {
        var presentation by rememberSaveable(
            ctx.path,
            stateSaver = MonthGridPresentationSaver,
        ) {
            mutableStateOf(MonthGridPresentation(specMonth, specMonth))
        }
        val reconciled = reconcileMonthGridPresentation(presentation, specMonth)
        if (presentation != reconciled) {
            // Conditional same-composition reset: the first reactivated frame
            // already uses the new authored month instead of flashing the
            // stale saved month and correcting in a later side effect.
            presentation = reconciled
        }
        val shownMonth = reconciled.shownMonth
        val changeMonth: (Int) -> Unit = { delta ->
            val next = monthAdd(shownMonth, delta)
            if ((minMonth == null || next >= minMonth) && (maxMonth == null || next <= maxMonth)) {
                presentation = MonthGridPresentation(specMonth, next)
                // §17.5 new month value
                onMonthChange?.let { ctx.action(it, JsonPrimitive(next)) }
            }
        }
        val year = shownMonth.substring(0, 4).toInt()
        val month = shownMonth.substring(5, 7).toInt()
        val cal = localCalendar()
        val weekStart = cal.firstDayOfWeek
        cal.clear(); cal.set(year, month - 1, 1)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val leadingBlanks = (cal.get(Calendar.DAY_OF_WEEK) - weekStart + 7) % 7
        val today = localTodayIso()
        val symbols = remember { DateFormatSymbols() }
        val density = LocalDensity.current
        val swipeThresholdPx = with(density) { 60.dp.toPx() }
        var dragTotal by remember { mutableFloatStateOf(0f) }

        Column(modifier = m.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { changeMonth(-1) },
                    enabled = minMonth == null || monthAdd(shownMonth, -1) >= minMonth) {
                    Icon(IconMap.get("chevron_left"), contentDescription = "Previous month")
                }
                Text("${symbols.months[month - 1]} $year",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                IconButton(onClick = { changeMonth(1) },
                    enabled = maxMonth == null || monthAdd(shownMonth, 1) <= maxMonth) {
                    Icon(IconMap.get("chevron_right"), contentDescription = "Next month")
                }
            }
            Row(Modifier.fillMaxWidth()) {
                for (i in 0 until 7) {
                    val dow = (weekStart - 1 + i) % 7 + 1
                    Text(symbols.shortWeekdays[dow].take(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Column(Modifier.fillMaxWidth().pointerInput(shownMonth) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onDragEnd = {
                        when {
                            dragTotal <= -swipeThresholdPx -> changeMonth(1)
                            dragTotal >= swipeThresholdPx -> changeMonth(-1)
                        }
                    },
                    onHorizontalDrag = { _, a -> dragTotal += a })
            }) {
                val weeks = (leadingBlanks + daysInMonth + 6) / 7
                for (week in 0 until weeks) {
                    Row(Modifier.fillMaxWidth()) {
                        for (col in 0 until 7) {
                            val day = week * 7 + col - leadingBlanks + 1
                            if (day < 1 || day > daysInMonth) {
                                Spacer(Modifier.weight(1f).height(MonthGridDayCellHeight))
                            } else {
                                val date = String.format(Locale.ROOT, "%s-%02d", shownMonth, day)
                                // 0 = Sunday, matching the contract.
                                val weekday = (weekStart - 1 + col) % 7
                                val dayEnabled =
                                    (minDate == null || date >= minDate) &&
                                    (maxDate == null || date <= maxDate) &&
                                    weekday !in disabledWeekdays
                                val inRange = rangeStart != null && rangeEnd != null &&
                                    date >= rangeStart && date <= rangeEnd
                                MonthGridDay(day, date, marks?.objOrNull(date),
                                    monthGridDayStyle(dayStyles, date),
                                    date == today, date == selected,
                                    // §17.5 ISO date; a disabled day never
                                    // dispatches.
                                    if (dayEnabled) onDayTap?.let {
                                        { ctx.action(it, JsonPrimitive(date)) }
                                    } else null,
                                    Modifier.weight(1f),
                                    enabled = dayEnabled,
                                    inRange = inRange,
                                    rangeStart = inRange && date == rangeStart,
                                    rangeEnd = inRange && date == rangeEnd)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGridDay(
    day: Int, date: String, mark: JsonObject?, dayStyle: MonthGridDayStyle?,
    isToday: Boolean, isSelected: Boolean,
    onTap: (() -> Unit)?, modifier: Modifier,
    enabled: Boolean = true, inRange: Boolean = false,
    rangeStart: Boolean = false, rangeEnd: Boolean = false,
) {
    // C6: `dots` is validated integral BY VALUE upstream (asDoubleOrNull +
    // Math.floor), so "dots": 2.0 is accepted traffic and must still read 2.
    val dots = (mark?.intByValue("dots", 0) ?: 0).coerceIn(0, 3)
    val explicitDotColor = resolveColor(mark?.stringOr("color")?.takeIf { it.isNotEmpty() })
    val styledBackground = resolveColor(dayStyle?.background)
    val styledForeground = resolveColor(dayStyle?.foreground)
    val hasDayStyle = styledBackground != null && styledForeground != null
    val desc = date + if (dots > 0) ", $dots marked" else ""
    // A range's interior cells fill edge to edge (the span reads as one
    // band); its two caps and a plain selection stay circles.
    val rangeCap = rangeStart || rangeEnd
    val filled = hasDayStyle || isSelected || rangeCap
    val dotColor = monthGridDotColor(
        explicitDotColor,
        styledForeground.takeIf { hasDayStyle },
        filled,
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.onPrimary,
    )
    Box(
        modifier
            .height(MonthGridDayCellHeight)
            .then(if (onTap != null) Modifier.clickable { onTap() } else Modifier)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center,
    ) {
        if (inRange && !(rangeStart && rangeEnd)) {
            val rangeBand = when {
                rangeStart -> Modifier.fillMaxWidth(0.5f).align(Alignment.CenterEnd)
                rangeEnd -> Modifier.fillMaxWidth(0.5f).align(Alignment.CenterStart)
                else -> Modifier.fillMaxWidth()
            }
            Box(
                rangeBand
                    .height(MonthGridDayVisualSize)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
        }
        Box(
            Modifier
                .size(MonthGridDayVisualSize)
                .clip(CircleShape)
                .then(
                    when {
                        hasDayStyle -> Modifier.background(styledBackground)
                        filled -> Modifier.background(MaterialTheme.colorScheme.primary)
                        isToday -> Modifier.border(
                            1.5.dp,
                            MaterialTheme.colorScheme.primary,
                            CircleShape,
                        )
                        else -> Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(day.toString(), style = MaterialTheme.typography.bodySmall,
                    color = when {
                        hasDayStyle -> styledForeground
                        filled -> MaterialTheme.colorScheme.onPrimary
                        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        inRange -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    })
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(dots) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(dotColor))
                    }
                }
            }
        }
    }
}

/** MONTH ("YYYY-MM") shifted by DELTA months. */
internal fun monthAdd(month: String, delta: Int): String {
    val y = month.substring(0, 4).toInt()
    val mo = month.substring(5, 7).toInt()
    val total = y * 12 + (mo - 1) + delta
    return String.format(Locale.ROOT, "%04d-%02d", total / 12, total % 12 + 1)
}
