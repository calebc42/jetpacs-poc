// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calebc42.ebp.renderer.compose.ComposeExtensionRenderContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.min

private fun JsonObject.member(name: String): String = (this[name] as? JsonPrimitive)?.content.orEmpty()

private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

/**
 * `jetpacs.glyph`: an app's own vector icon. Every path is filled nonzero in
 * viewport units, the viewport fitted into a `size` dp square and centred,
 * as a toolkit icon paints an ImageVector into its bounds.
 */
internal object JetpacsGlyphComponent : JetpacsComponent("jetpacs.glyph", JetpacsGlyphMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val sources = (node["paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val paths = remember(sources) {
            sources.mapNotNull { d -> runCatching { PathParser().parsePathString(d).toPath() }.getOrNull() }
        }
        val viewportWidth = (node.number("viewport_width") ?: 1.0).toFloat()
        val viewportHeight = (node.number("viewport_height") ?: 1.0).toFloat()
        val size = (node.number("size") ?: 24.0).dp
        val color = glyphColor(node.member("color"))
        val description = node.member("content_description")
        Canvas(
            modifier
                .size(size)
                .let { if (description.isEmpty()) it else it.semantics { contentDescription = description } },
        ) {
            val fit = min(this.size.width / viewportWidth, this.size.height / viewportHeight)
            translate(
                left = (this.size.width - viewportWidth * fit) / 2f,
                top = (this.size.height - viewportHeight * fit) / 2f,
            ) {
                scale(fit, pivot = Offset.Zero) {
                    paths.forEach { path: Path -> drawPath(path, color) }
                }
            }
        }
    }

    /** An authored hex color or theme role, else the enclosing content color. */
    @Composable
    private fun glyphColor(spec: String): Color {
        if (spec.isNotEmpty()) {
            DesignThemeRole.fromWireName(spec)?.let { return JetpacsTheme.roles[it] }
            parseHexColor(spec)?.let { return it }
        }
        val enclosing = LocalDesignTextContext.current
        val face = enclosing?.computed?.resolve(enclosing.state.activeDesignStates())?.properties
        return face?.literalContentColor() ?: JetpacsTheme.colors.content
    }
}

/**
 * `jetpacs.inline_text`: one paragraph in which `text` children flow as
 * text and `jetpacs.inline` children ride as placeholders that wrap with
 * the words, as a Compose Text with inline content lays out chips in a
 * sentence. The paragraph takes the design text style its `style` names.
 */
internal object JetpacsInlineTextComponent :
    JetpacsComponent("jetpacs.inline_text", JetpacsInlineTextMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        val children = (node["children"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val styleName = node.member("style")
        val stub = remember(styleName) {
            buildJsonObject {
                put("t", "text")
                put("text", "")
                if (styleName.isNotEmpty()) put("style", styleName)
            }
        }
        val style = JetpacsDesignTextRenderer.designTextStyle(stub)
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val text = remember(children) {
            buildAnnotatedString {
                children.forEachIndexed { index, child ->
                    when (child.member("t")) {
                        "text" -> append(child.member("text"))
                        "jetpacs.inline" -> appendInlineContent("inline_$index", " ")
                    }
                }
            }
        }
        val inlineContent = children.withIndex()
            .filter { (_, child) -> child.member("t") == "jetpacs.inline" }
            .associate { (index, child) ->
                val placeholder = remember(child, density) {
                    with(density) {
                        val measured = measurer.measure(
                            child.member("reserve_text"),
                            TextStyle(fontSize = (child.number("reserve_text_size") ?: 14.0).sp),
                        ).size.width.toDp()
                        var width = measured + (child.number("reserve_extra") ?: 0.0).dp
                        child.number("reserve_max")?.let { width = minOf(width, it.dp) }
                        Placeholder(
                            width = width.toSp(),
                            height = (child.number("height") ?: 24.0).sp,
                            placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                        )
                    }
                }
                "inline_$index" to InlineTextContent(placeholder) {
                    (child["child"] as? JsonObject)?.let { context.renderScopedChild(it, index) }
                }
            }
        BasicText(text = text, modifier = modifier, style = style, inlineContent = inlineContent)
    }
}

/** `jetpacs.inline` outside a paragraph: its child alone. */
internal object JetpacsInlineComponent : JetpacsComponent("jetpacs.inline", JetpacsInlineMembers) {
    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        (node["child"] as? JsonObject)?.let { context.renderScopedChild(it, 0, modifier) }
    }
}
