// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import com.calebc42.ebp.renderer.model.*
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs

/**
 * Passive canonical charts inside a design scope, using Foundation Canvas and
 * profile colors. Point-action charts retain the host renderer: its typed
 * injection seam owns the required ordinal index and complete point payload.
 */
object JetpacsDesignChartRenderer : ComposeCanonicalNodeOverride {
    override val id = "jetpacs.design.chart.compose"
    override val designScope = JETPACS_DESIGN_EXTENSION
    override val nodeTypes = setOf("chart")
    override fun appliesTo(node: JsonObject) = node.objOrNull("on_point_tap") == null

    @Composable
    override fun render(node: JsonObject, context: ComposeNodeRenderContext, modifier: Modifier) {
        val plot = remember(node) { designChartData(node) }
        val roles = JetpacsTheme.roles
        val colors = plot.series.map { series ->
            series.color?.let { name ->
                DesignThemeRole.fromWireName(name)?.let { roles[it] } ?: parseHexColor(name)
            } ?: JetpacsTheme.colors.accent
        }
        Canvas(modifier.fillMaxWidth().height(node.doubleOr("height", 160.0).toFloat().dp)
            .semantics { contentDescription = node.stringOr("summary", "${plot.kind} chart") }) {
            fun x(i: Int) = if (plot.count <= 1) size.width / 2 else size.width * i / (plot.count - 1)
            fun y(value: Double) = size.height * (1 - ((value - plot.min) / (plot.max - plot.min)).coerceIn(0.0, 1.0)).toFloat()
            val base = y(if (plot.min <= 0 && plot.max >= 0) 0.0 else plot.min)
            plot.series.forEachIndexed { index, series ->
                val color = colors[index]
                if (plot.kind == "bar") {
                    val slot = size.width / plot.count.coerceAtLeast(1)
                    series.values.forEachIndexed { i, value ->
                        val top = y(value)
                        drawRect(color, Offset(slot * (i + .2f), minOf(top, base)), Size(slot * .6f, abs(base - top)))
                    }
                } else {
                    val path = Path()
                    series.values.forEachIndexed { i, value ->
                        if (i == 0) path.moveTo(x(i), y(value)) else path.lineTo(x(i), y(value))
                    }
                    if (plot.kind == "area" && series.values.isNotEmpty()) {
                        val fill = Path().apply { addPath(path); lineTo(x(series.values.lastIndex), base); lineTo(x(0), base); close() }
                        drawPath(fill, color.copy(alpha = .18f))
                    }
                    drawPath(path, color, style = Stroke(2.dp.toPx()))
                    if (series.values.size == 1) drawCircle(color, 3.dp.toPx(), Offset(x(0), y(series.values[0])))
                }
            }
        }
    }
}

/** Pure numeric projection; x values remain labels, never scaled coordinates. */
internal data class DesignChartSeries(val values: List<Double>, val color: String?)
/** Shared ordinal extent and finite vertical domain for all admitted series. */
internal data class DesignChartData(val kind: String, val series: List<DesignChartSeries>, val count: Int, val min: Double, val max: Double)

/** Derive automatic bounds while preserving an explicit canonical y_range. */
internal fun designChartData(node: JsonObject): DesignChartData {
    val kind = node.stringOr("kind", "line")
    val series = node.arrOrNull("series").orEmpty().mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        DesignChartSeries(item.arrOrNull("points").orEmpty().map { (it as JsonObject).doubleOr("y", 0.0) }, item.stringOr("color").takeIf { it.isNotEmpty() })
    }
    val values = series.flatMap { it.values }
    var min = values.minOrNull() ?: 0.0
    var max = values.maxOrNull() ?: 1.0
    val explicit = node.arrOrNull("y_range")
    if (explicit != null) { min = explicit[0].numOrNull() ?: min; max = explicit[1].numOrNull() ?: max }
    else if (kind == "bar" || kind == "area") { min = minOf(0.0, min); max = maxOf(0.0, max) }
    if (max == min) max = min + 1.0
    return DesignChartData(kind, series, series.maxOfOrNull { it.values.size } ?: 0, min, max)
}
