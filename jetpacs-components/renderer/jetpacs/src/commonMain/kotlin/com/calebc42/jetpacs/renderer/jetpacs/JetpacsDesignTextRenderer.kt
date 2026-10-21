// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import com.calebc42.ebp.renderer.compose.projectSyntaxSpans
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Design-scoped presentation of canonical EBP `text`.
 *
 * Inside a compiled `jetpacs.design_scope` every text node resolves its
 * typography in three layers, each overriding the previous per property:
 *
 * 1. the `text.<style>` component slot bound by the active profile, falling
 *    back to the private Jetpacs text defaults for that canonical style name;
 * 2. the nearest enclosing `jetpacs.styled` or `jetpacs.pressable` program,
 *    resolved against that face's live state; and
 * 3. the node's own authored `color` and `font_weight` members.
 *
 * Validation, semantics, and the universal attributes stay canonical: the
 * dispatcher hands this override the same interaction-owning modifier it
 * would hand the Material text. Only Compose selection changes.
 */
object JetpacsDesignTextRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.text.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("text")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val style = designTextStyle(node)
        val raw = node.string("text")
        val language = node.string("syntax")
        val syntaxColors = JetpacsTheme.syntax
        val text = remember(raw, language, syntaxColors) {
            annotatedText(raw, language, syntaxColors)
        }
        val maxLines = node.positiveInt("max_lines") ?: Int.MAX_VALUE
        val content: @Composable () -> Unit = {
            BasicText(
                text = text,
                modifier = modifier,
                style = style,
                maxLines = maxLines,
                overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
        }
        if (node.boolean("selectable")) SelectionContainer { content() } else content()
    }

    /** Resolve the three-layer text style for [node] in the current composition. */
    @Composable
    @ReadOnlyComposable
    internal fun designTextStyle(node: JsonObject): TextStyle {
        val styleName = node.string("style")
        val slot = slotForStyle(styleName)
        val base = resolvedDesignTextStyle(slot, fallbackFor(styleName))
        val roles = JetpacsTheme.roles
        val enclosing = LocalDesignTextContext.current
        val withFace = if (enclosing == null) {
            base
        } else {
            base.withDesignTextProperties(
                enclosing.computed.resolve(enclosing.state.activeDesignStates()).properties,
                roles,
            )
        }
        return withFace.withCanonicalMembers(node, roles)
    }

    @Composable
    @ReadOnlyComposable
    private fun fallbackFor(styleName: String): TextStyle {
        val typography = JetpacsTheme.typography
        return when (styleName) {
            "title" -> typography.title
            "headline" -> typography.headline
            "caption" -> typography.caption
            "label" -> typography.label
            "mono" -> typography.mono
            else -> typography.body
        }
    }

    private fun annotatedText(
        raw: String,
        language: String,
        colors: JetpacsSyntaxColors,
    ): AnnotatedString {
        if (language.isEmpty()) return AnnotatedString(raw)
        val spans = projectSyntaxSpans(language, raw).mapNotNull { span ->
            val start = span.start.coerceIn(0, raw.length)
            val end = span.end.coerceIn(start, raw.length)
            if (end > start) {
                AnnotatedString.Range(jetpacsLocalSyntaxSpanStyle(span, colors), start, end)
            } else {
                null
            }
        }
        return if (spans.isEmpty()) AnnotatedString(raw) else AnnotatedString(raw, spans)
    }
}

/** Map a canonical §17.2 text style name to its design slot; unknown is body. */
internal fun slotForStyle(styleName: String): DesignComponentStyleSlot = when (styleName) {
    "title" -> DesignComponentStyleSlot.TextTitle
    "headline" -> DesignComponentStyleSlot.TextHeadline
    "caption" -> DesignComponentStyleSlot.TextCaption
    "label" -> DesignComponentStyleSlot.TextLabel
    "mono" -> DesignComponentStyleSlot.TextMono
    else -> DesignComponentStyleSlot.TextBody
}

/**
 * Apply the node's own authored `color` and `font_weight` last.
 *
 * A color is one of the 13 neutral EBP roles or a hex literal, exactly as the
 * canonical renderer accepts it; an unresolvable value leaves the design color
 * in place rather than falling back to an unrelated default.
 */
internal fun TextStyle.withCanonicalMembers(
    node: JsonObject,
    roles: JetpacsThemeRoles,
): TextStyle {
    val authoredColor = node.string("color").takeIf { it.isNotEmpty() }?.let { spec ->
        DesignThemeRole.fromWireName(spec)?.let { roles[it] } ?: parseHexColor(spec)
    }
    val authoredWeight = canonicalFontWeight(node["font_weight"] as? JsonPrimitive)
    return copy(
        color = authoredColor ?: color,
        fontWeight = authoredWeight ?: fontWeight,
    )
}

/** §17.2 `font_weight`: an integer 1..1000 or one of the four named weights. */
internal fun canonicalFontWeight(value: JsonPrimitive?): FontWeight? {
    value ?: return null
    if (!value.isString) {
        return value.doubleOrNull?.toInt()?.takeIf { it in 1..1000 }?.let { FontWeight(it) }
    }
    return when (value.content) {
        "bold" -> FontWeight.Bold
        "medium" -> FontWeight.Medium
        "normal" -> FontWeight.Normal
        "light" -> FontWeight.Light
        else -> null
    }
}

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: false

/** Integral-by-value positive member, matching the canonical validator's floor. */
private fun JsonObject.positiveInt(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        ?.toInt()?.takeIf { it > 0 }

/**
 * Design-scoped selection of the Foundation text field.
 *
 * `jetpacs.scope` already selects [JetpacsTextInputRenderer]; a design scope
 * binds the `text-field.*` slots that only that presentation consumes, so the
 * same override is registered under `jetpacs.design` to make those bindings
 * effective. Presentation, controller, and semantics are unchanged.
 */
object JetpacsDesignTextInputRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.text-input.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = JetpacsTextInputRenderer.nodeTypes

    override fun appliesTo(node: JsonObject): Boolean = JetpacsTextInputRenderer.appliesTo(node)

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) = JetpacsTextInputRenderer.render(node, context, modifier)
}

/** Design-scoped selection of the Foundation editor; see [JetpacsDesignTextInputRenderer]. */
object JetpacsDesignEditorRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.editor.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = JetpacsEditorRenderer.nodeTypes

    override fun appliesTo(node: JsonObject): Boolean = JetpacsEditorRenderer.appliesTo(node)

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) = JetpacsEditorRenderer.render(node, context, modifier)
}

/** Every canonical override the composition root installs under `jetpacs.design`. */
val JetpacsDesignCanonicalOverrides: List<ComposeCanonicalNodeOverride> = listOf(
    JetpacsDesignTextRenderer,
    JetpacsDesignRichTextRenderer,
    JetpacsDesignChartRenderer,
    JetpacsDesignCardRenderer,
    JetpacsDesignIconRenderer,
    JetpacsDesignIconButtonRenderer,
    JetpacsDesignBadgeRenderer,
    JetpacsDesignEmptyStateRenderer,
    JetpacsDesignButtonRenderer,
    JetpacsDesignChipRenderer,
    JetpacsDesignDividerRenderer,
    JetpacsDesignSectionHeaderRenderer,
    JetpacsDesignMenuRenderer,
    JetpacsDesignSwitchRenderer,
    JetpacsDesignCollapsibleRenderer,
    JetpacsDesignMonthGridRenderer,
    JetpacsDesignDropdownRenderer,
    JetpacsDesignTextInputRenderer,
    JetpacsDesignEditorRenderer,
)
