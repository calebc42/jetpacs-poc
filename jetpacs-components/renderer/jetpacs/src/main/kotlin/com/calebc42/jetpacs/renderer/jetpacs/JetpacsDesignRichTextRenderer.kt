// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Canonical rich text under a design scope, rendered by Foundation BasicText.
 * The base inherits the same profile and enclosing-face typography as plain
 * text. Authored span attributes win locally; links use the current action
 * pipeline after recomposition and do not create a second gesture target.
 */
object JetpacsDesignRichTextRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.rich-text.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("rich_text")

    @Composable
    override fun render(node: JsonObject, context: ComposeNodeRenderContext, modifier: Modifier) {
        val roles = JetpacsTheme.roles
        val linkColor = JetpacsTheme.colors.accent
        val currentContext = rememberUpdatedState(context)
        val spans = node["spans"] as? JsonArray
        val text = remember(spans, roles, linkColor) {
            buildDesignRichText(spans, linkColor, { name ->
                DesignThemeRole.fromWireName(name)?.let { roles[it] } ?: parseHexColor(name)
            }) { descriptor -> currentContext.value.action(descriptor) }
        }
        BasicText(text, modifier, style = JetpacsDesignTextRenderer.designTextStyle(node))
    }
}

/** Project the admitted canonical span vocabulary without depending on Material. */
internal fun buildDesignRichText(
    spans: JsonArray?,
    linkColor: Color,
    resolve: (String) -> Color?,
    dispatch: (JsonObject) -> Unit,
): AnnotatedString = buildAnnotatedString {
    spans?.forEachIndexed { index, element ->
        val span = element as? JsonObject ?: return@forEachIndexed
        fun string(name: String) = (span[name] as? JsonPrimitive)?.content.orEmpty()
        fun flag(name: String) = (span[name] as? JsonPrimitive)?.booleanOrNull == true
        val text = string("text")
        if (text.isEmpty()) return@forEachIndexed
        val style = SpanStyle(
            fontWeight = canonicalFontWeight(span["font_weight"] as? JsonPrimitive),
            fontStyle = if (flag("italic")) FontStyle.Italic else null,
            fontFamily = if (flag("mono")) FontFamily.Monospace else null,
            textDecoration = if (flag("underline")) TextDecoration.Underline else null,
            color = resolve(string("color")) ?: Color.Unspecified,
            background = resolve(string("bg")) ?: Color.Unspecified,
        )
        val action = span["on_tap"] as? JsonObject
        if (action == null) withStyle(style) { append(text) }
        else withLink(LinkAnnotation.Clickable(
            tag = "span$index",
            styles = TextLinkStyles(style = if (style.color == Color.Unspecified) style.copy(color = linkColor) else style),
            linkInteractionListener = { dispatch(action) },
        )) { append(text) }
    }
}
