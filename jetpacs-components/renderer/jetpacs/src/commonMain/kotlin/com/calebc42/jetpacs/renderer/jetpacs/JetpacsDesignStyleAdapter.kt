// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleScope
import androidx.compose.foundation.style.StyleState
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.checked
import androidx.compose.foundation.style.contentPadding
import androidx.compose.foundation.style.contentPaddingHorizontal
import androidx.compose.foundation.style.contentPaddingVertical
import androidx.compose.foundation.style.disabled
import androidx.compose.foundation.style.fillWidth
import androidx.compose.foundation.style.focused
import androidx.compose.foundation.style.hovered
import androidx.compose.foundation.style.pressed
import androidx.compose.foundation.style.scale
import androidx.compose.foundation.style.selected
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import com.calebc42.ebp.renderer.compose.ComposeChromeStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/**
 * The only AndroidX Style translation boundary. Public Kotlin and Elisp APIs
 * expose the pure [ComputedDesignStyle] model instead.
 */
internal fun ComputedDesignStyle.toFoundationStyle(
    includeTextProperties: Boolean = true,
): Style = Style {
    for (layer in layers) {
        when (layer.state) {
            null -> applyDesignLayer(layer, includeTextProperties)
            DesignState.Disabled -> disabled {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Selected -> selected {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Toggled -> checked {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Hovered -> hovered {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Focused -> focused {
                applyDesignLayer(layer, includeTextProperties)
            }
            DesignState.Pressed -> pressed {
                applyDesignLayer(layer, includeTextProperties)
            }
        }
    }
}

private fun StyleScope.applyDesignLayer(
    layer: DesignStyleLayer,
    includeTextProperties: Boolean,
) {
    val motion = layer.motion
    if (motion == null) {
        applyDesignProperties(layer.properties, includeTextProperties)
    } else {
        animate(motion.animationSpec()) {
            applyDesignProperties(layer.properties, includeTextProperties)
        }
    }
}

private fun StyleScope.applyDesignProperties(
    properties: Map<DesignProperty, DesignValue>,
    includeTextProperties: Boolean,
) {
    // Broad padding setters intentionally run before axis and side setters.
    // This gives the most specific authored property the final Style value.
    for (property in DesignProperty.entries) {
        val value = properties[property] ?: continue
        if (!includeTextProperties && property in textProperties) continue
        when (property) {
            DesignProperty.BackgroundColor ->
                background(tintedBackground(designColor(value), properties))
            DesignProperty.ContentColor -> contentColor(designColor(value))
            DesignProperty.BorderColor -> borderColor(designColor(value))
            DesignProperty.BorderWidth -> borderWidth(value.dp())
            DesignProperty.CornerRadius -> shape(RoundedCornerShape(value.dp()))
            DesignProperty.Padding -> contentPadding(value.dp())
            DesignProperty.PaddingHorizontal -> contentPaddingHorizontal(value.dp())
            DesignProperty.PaddingVertical -> contentPaddingVertical(value.dp())
            DesignProperty.PaddingStart -> contentPaddingStart(value.dp())
            DesignProperty.PaddingTop -> contentPaddingTop(value.dp())
            DesignProperty.PaddingEnd -> contentPaddingEnd(value.dp())
            DesignProperty.PaddingBottom -> contentPaddingBottom(value.dp())
            DesignProperty.Width -> width(value.dp())
            DesignProperty.Height -> height(value.dp())
            DesignProperty.MinWidth -> minWidth(value.dp())
            DesignProperty.MinHeight -> minHeight(value.dp())
            DesignProperty.Alpha -> alpha(value.number())
            DesignProperty.Scale -> scale(value.number())
            DesignProperty.FontSize -> fontSize(value.sp())
            DesignProperty.LineHeight -> lineHeight(value.sp())
            DesignProperty.LetterSpacing -> letterSpacing(value.sp())
            DesignProperty.FontFamily -> fontFamily(value.fontFamily())
            DesignProperty.FontWeight -> fontWeight(FontWeight(value.fontWeight()))
            DesignProperty.TextAlign -> textAlign(value.textAlign())
            DesignProperty.FillWidth -> {
                // Foundation Styles have no "unset width": a NaN fraction
                // collapses the box and an unspecified Dp keeps an inherited
                // fill (both observed on device). `false` therefore only
                // declines to add a fill; a component whose base style fills
                // its row stays full-row, which is why Grove projects its
                // small buttons onto `jetpacs.pressable` instead of Action.
                if (value.boolean()) fillWidth()
            }
            // Applied as a layout modifier by the styled and pressable
            // components: Foundation Styles cannot ask for intrinsic size.
            DesignProperty.IntrinsicHeight -> Unit
            // Read by the pressable, which owns its row's alignment.
            DesignProperty.CenterVertically -> Unit
            // Read by the text renderer, which owns where a line is cut.
            DesignProperty.WordEllipsis -> Unit
            DesignProperty.ParagraphBreaking -> Unit
            DesignProperty.LineSpacingBelow -> Unit
            DesignProperty.LineHeightCentered -> Unit
            // Read by the button renderer, which owns its label's lines.
            DesignProperty.MaxLines -> Unit
            // Read by the text renderer, which owns where a line is cut.
            DesignProperty.MiddleEllipsis -> Unit
            DesignProperty.PlatformDialog -> Unit
            DesignProperty.RippleColor, DesignProperty.RippleRadius -> Unit
            // Read by the menu row, which owns its glyphs.
            DesignProperty.CheckAtEnd, DesignProperty.CheckColor, DesignProperty.IconSize,
            DesignProperty.IconStart, DesignProperty.IconGap -> Unit
            // Read by the popup positioner, which owns where a popup opens.
            DesignProperty.PopupOverlap, DesignProperty.PopupOffsetY,
            DesignProperty.PopupEdgeInset, DesignProperty.PopupBeforeAnchor -> Unit
            // Read by the menu popup, which draws the platform shadow.
            DesignProperty.Elevation -> Unit
            // Folded into background_color by tintedBackground.
            DesignProperty.BackgroundTint -> Unit
            DesignProperty.Italic ->
                fontStyle(if (value.boolean()) FontStyle.Italic else FontStyle.Normal)
            // One decoration carries both lines, so the pair is applied once,
            // at whichever of the two the loop reaches first.
            DesignProperty.Underline -> designTextDecoration(properties)?.let(::textDecoration)
            DesignProperty.StrikeThrough -> if (DesignProperty.Underline !in properties) {
                designTextDecoration(properties)?.let(::textDecoration)
            }
        }
    }
}

private val textProperties = setOf(
    DesignProperty.ContentColor,
    DesignProperty.FontSize,
    DesignProperty.LineHeight,
    DesignProperty.LetterSpacing,
    DesignProperty.FontFamily,
    DesignProperty.FontWeight,
    DesignProperty.TextAlign,
    DesignProperty.Italic,
    DesignProperty.Underline,
    DesignProperty.StrikeThrough,
)

/**
 * The decoration authored by `underline` and `strike_through`, or null when
 * neither is present. An explicit `false` for both yields
 * [TextDecoration.None], which clears an inherited decoration.
 */
internal fun designTextDecoration(
    properties: Map<DesignProperty, DesignValue>,
): TextDecoration? {
    val underline = properties[DesignProperty.Underline] as? DesignValue.BooleanValue
    val strike = properties[DesignProperty.StrikeThrough] as? DesignValue.BooleanValue
    if (underline == null && strike == null) return null
    return TextDecoration.combine(
        listOfNotNull(
            TextDecoration.Underline.takeIf { underline?.value == true },
            TextDecoration.LineThrough.takeIf { strike?.value == true },
        ),
    )
}

private fun DesignMotion.animationSpec(): AnimationSpec<Float> = when (easing) {
    DesignEasing.Linear -> tween(durationMillis, easing = LinearEasing)
    DesignEasing.EaseIn -> tween(durationMillis, easing = FastOutLinearInEasing)
    DesignEasing.EaseOut -> tween(durationMillis, easing = LinearOutSlowInEasing)
    DesignEasing.EaseInOut -> tween(durationMillis, easing = FastOutSlowInEasing)
    DesignEasing.Spring -> spring(dampingRatio = Spring.DampingRatioMediumBouncy)
}

/**
 * BACKGROUND with the layer's `background_tint` fraction of its
 * `content_color` mixed in; unchanged when either is absent.  Pure apart
 * from resolving the content color, so the mix is JVM-tested.
 */
private fun StyleScope.tintedBackground(
    background: Color,
    properties: Map<DesignProperty, DesignValue>,
): Color {
    val tint = (properties[DesignProperty.BackgroundTint] as? DesignValue.NumberValue)?.value
        ?: return background
    val content = properties[DesignProperty.ContentColor] ?: return background
    return mixTint(background, designColor(content), tint)
}

/**
 * [tint] (clamped to 0..1) of [content] mixed into [background], per sRGB
 * channel.  Not Compose's `lerp`, which mixes in Oklab: 7% white into
 * black there is 1/255, a dark page's card would stay black.  The sRGB mix
 * matches the Emacs theme mirror's own blend.  A dark background takes
 * half again the fraction: near black the eye needs a larger step to see a
 * card at all (7% reads as #111 on black, 10.5% as #1b1b1b).
 */
internal fun mixTint(background: Color, content: Color, tint: Double): Color {
    val luminance = 0.2126f * background.red + 0.7152f * background.green +
        0.0722f * background.blue
    val boost = if (luminance < 0.5f) DARK_TINT_BOOST else 1.0
    val t = (tint * boost).coerceIn(0.0, 1.0).toFloat()
    fun mix(a: Float, b: Float) = a + (b - a) * t
    return Color(
        red = mix(background.red, content.red),
        green = mix(background.green, content.green),
        blue = mix(background.blue, content.blue),
        alpha = background.alpha,
    )
}

/** How much further a tint mixes on a dark background. */
internal const val DARK_TINT_BOOST = 1.5

private fun StyleScope.designColor(value: DesignValue): Color = when (value) {
    is DesignValue.ColorValue -> Color(value.argb)
    is DesignValue.ThemeRoleValue -> LocalJetpacsTheme.currentValue.roles[value.value]
    else -> error("Design color property received ${value::class.simpleName}")
}

/** Resolve base text properties for drawing APIs that do not accept Style. */
@Composable
@ReadOnlyComposable
internal fun resolvedDesignTextStyle(
    slot: DesignComponentStyleSlot,
    fallback: TextStyle,
    state: StyleState? = null,
): TextStyle {
    val properties = LocalDesignScope.current
        ?.componentStyle(slot)
        ?.resolve(state.activeDesignStates())
        ?.properties
        ?: return fallback
    return fallback.withDesignTextProperties(properties, JetpacsTheme.roles)
}

/** The properties [slot] resolves to in the current scope and [state]. */
@Composable
@ReadOnlyComposable
internal fun resolvedSlotProperties(
    slot: DesignComponentStyleSlot,
    state: StyleState? = null,
): Map<DesignProperty, DesignValue> = LocalDesignScope.current
    ?.componentStyle(slot)
    ?.resolve(state.activeDesignStates())
    ?.properties
    .orEmpty()

/** Whether resolved text properties ask for `middle_ellipsis`. */
internal fun Map<DesignProperty, DesignValue>.middleEllipsis(): Boolean =
    (this[DesignProperty.MiddleEllipsis] as? DesignValue.BooleanValue)?.value == true

/** The literal `content_color` these properties author, or null. */
internal fun Map<DesignProperty, DesignValue>.literalContentColor(): Color? =
    (this[DesignProperty.ContentColor] as? DesignValue.ColorValue)?.let { Color(it.argb) }

/** An authored `max_lines`, at least one, or null. */
internal fun Map<DesignProperty, DesignValue>.maxLines(): Int? =
    (this[DesignProperty.MaxLines] as? DesignValue.NumberValue)?.value?.toInt()?.coerceAtLeast(1)

/**
 * Overlay authored text properties on [this] fallback text style.
 *
 * Only the ten text-bearing design properties participate; layout and draw
 * properties belong to the Foundation Style applied through modifiers. Pure
 * so text resolution can be unit-tested without a composition.
 */
internal fun TextStyle.withDesignTextProperties(
    properties: Map<DesignProperty, DesignValue>,
    roles: JetpacsThemeRoles,
): TextStyle = copy(
    color = properties[DesignProperty.ContentColor]?.let { value ->
        when (value) {
            is DesignValue.ColorValue -> Color(value.argb)
            is DesignValue.ThemeRoleValue -> roles[value.value]
            else -> color
        }
    } ?: color,
    fontSize = properties[DesignProperty.FontSize]?.sp() ?: fontSize,
    lineHeight = properties[DesignProperty.LineHeight]?.sp() ?: lineHeight,
    letterSpacing = properties[DesignProperty.LetterSpacing]?.sp() ?: letterSpacing,
    fontFamily = properties[DesignProperty.FontFamily]?.fontFamily() ?: fontFamily,
    fontWeight = properties[DesignProperty.FontWeight]?.let {
        FontWeight(it.fontWeight())
    } ?: fontWeight,
    textAlign = properties[DesignProperty.TextAlign]?.textAlign() ?: textAlign,
    fontStyle = (properties[DesignProperty.Italic] as? DesignValue.BooleanValue)?.let {
        if (it.value) FontStyle.Italic else FontStyle.Normal
    } ?: fontStyle,
    textDecoration = designTextDecoration(properties) ?: textDecoration,
    // paragraph_breaking: the balanced breaker and no hyphenation, which is
    // how an Android TextView wraps by default; false keeps the greedy one.
    lineBreak = (properties[DesignProperty.ParagraphBreaking] as? DesignValue.BooleanValue)?.let {
        if (it.value) LineBreak.Paragraph else LineBreak.Simple
    } ?: lineBreak,
    hyphens = (properties[DesignProperty.ParagraphBreaking] as? DesignValue.BooleanValue)
        ?.takeIf { it.value }?.let { Hyphens.None } ?: hyphens,
    // line_spacing_below: a line's extra height goes below it and the last
    // line drops it, as a TextView's line spacing does, instead of being
    // shared above and below every line.
    // line_height_centered: every line, the first and last too, is its full
    // line_height with the text centred in it, as Material's type scale is.
    lineHeightStyle = when {
        (properties[DesignProperty.LineHeightCentered] as? DesignValue.BooleanValue)?.value == true ->
            LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        (properties[DesignProperty.LineSpacingBelow] as? DesignValue.BooleanValue)?.value == true ->
            LineHeightStyle(LineHeightStyle.Alignment.Top, LineHeightStyle.Trim.LastLineBottom)
        else -> lineHeightStyle
    },
)

/**
 * Resolve the chrome slots of [this] scope into the toolkit-neutral value a
 * scaffold renderer reads where it draws tabs, rails, drawers and snackbars.
 *
 * Each field is null while its slot is unbound, so the toolkit keeps its own
 * default there. A label's selected color comes from the slot's `selected`
 * rule. Null when no chrome slot is bound at all, so an inner scope without
 * chrome bindings leaves an outer scope's value in place.
 */
internal fun CompiledDesignScope.composeChromeStyles(
    roles: JetpacsThemeRoles,
): ComposeChromeStyles? {
    fun properties(
        slot: DesignComponentStyleSlot,
        states: Set<DesignState> = emptySet(),
    ): Map<DesignProperty, DesignValue>? = componentStyle(slot)?.resolve(states)?.properties

    fun Map<DesignProperty, DesignValue>.color(property: DesignProperty): Color? =
        when (val value = this[property]) {
            is DesignValue.ColorValue -> Color(value.argb)
            is DesignValue.ThemeRoleValue -> roles[value.value]
            else -> null
        }

    // A container's background, with its background_tint folded in as the
    // Style path does for cards.
    fun Map<DesignProperty, DesignValue>.container(): Color? {
        val background = color(DesignProperty.BackgroundColor) ?: return null
        val tint = (this[DesignProperty.BackgroundTint] as? DesignValue.NumberValue)?.value
            ?: return background
        val content = color(DesignProperty.ContentColor) ?: return background
        return mixTint(background, content, tint)
    }

    fun Map<DesignProperty, DesignValue>.text(): TextStyle? =
        TextStyle.Default.withDesignTextProperties(this, roles)
            .takeIf { it != TextStyle.Default }

    fun Map<DesignProperty, DesignValue>.shape(): Shape? =
        this[DesignProperty.CornerRadius]?.let { RoundedCornerShape(it.dp()) }

    val tabLabel = properties(DesignComponentStyleSlot.ChromeTabLabel)
    val tabSelected = properties(
        DesignComponentStyleSlot.ChromeTabLabel,
        setOf(DesignState.Selected),
    )
    val tabIndicator = properties(DesignComponentStyleSlot.ChromeTabIndicator)
    val railLabel = properties(DesignComponentStyleSlot.ChromeRailLabel)
    val railIndicator = properties(DesignComponentStyleSlot.ChromeRailIndicator)
    val rail = properties(DesignComponentStyleSlot.ChromeRail)
    val drawer = properties(DesignComponentStyleSlot.ChromeDrawer)
    val scrim = properties(DesignComponentStyleSlot.ChromeScrim)
    val bottomBar = properties(DesignComponentStyleSlot.ChromeBottomBar)
    val snackbar = properties(DesignComponentStyleSlot.ChromeSnackbar)
    val styles = ComposeChromeStyles(
        tabLabel = tabLabel?.text(),
        tabSelectedLabelColor = tabSelected?.color(DesignProperty.ContentColor),
        tabIndicator = tabIndicator?.color(DesignProperty.BackgroundColor),
        railLabel = railLabel?.text(),
        railIndicator = railIndicator?.color(DesignProperty.BackgroundColor),
        railContainer = rail?.container(),
        drawerContainer = drawer?.color(DesignProperty.BackgroundColor),
        drawerShape = drawer?.shape(),
        drawerWidth = (drawer?.get(DesignProperty.Width) as? DesignValue.DimensionValue)
            ?.value?.dp,
        drawerScrim = scrim?.color(DesignProperty.BackgroundColor),
        bottomBarContainer = bottomBar?.color(DesignProperty.BackgroundColor),
        bottomBarPadding = bottomBar?.takeIf { props ->
            listOf(DesignProperty.Padding, DesignProperty.PaddingHorizontal, DesignProperty.PaddingVertical)
                .any { it in props }
        }?.let { props ->
            fun dim(p: DesignProperty) = (props[p] as? DesignValue.DimensionValue)?.value
            val all = dim(DesignProperty.Padding) ?: 0.0
            androidx.compose.foundation.layout.PaddingValues(
                horizontal = (dim(DesignProperty.PaddingHorizontal) ?: all).dp,
                vertical = (dim(DesignProperty.PaddingVertical) ?: all).dp,
            )
        },
        snackbarContainer = snackbar?.color(DesignProperty.BackgroundColor),
        snackbarContent = snackbar?.color(DesignProperty.ContentColor),
        snackbarShape = snackbar?.shape(),
    )
    return styles.takeUnless { it.isEmpty }
}

/**
 * Resolve one `editor.syntax.*` slot of [this] scope into the span style the
 * editor overlays on a fontify run, or null when it is unbound or empty.
 */
internal fun CompiledDesignScope.resolvedSyntaxSpanStyle(
    slot: DesignComponentStyleSlot,
    roles: JetpacsThemeRoles,
): SpanStyle? = componentStyle(slot)?.resolve()?.properties
    ?.takeIf { it.isNotEmpty() }
    ?.let { designSyntaxSpanStyle(it, roles) }
    ?.takeIf { it != SpanStyle() }

/**
 * The span-level part of a resolved syntax slot: text color, background,
 * weight, italic, and decoration. Layout properties have no meaning inside a
 * run and are ignored.
 */
internal fun designSyntaxSpanStyle(
    properties: Map<DesignProperty, DesignValue>,
    roles: JetpacsThemeRoles,
): SpanStyle {
    fun color(property: DesignProperty): Color = when (val value = properties[property]) {
        is DesignValue.ColorValue -> Color(value.argb)
        is DesignValue.ThemeRoleValue -> roles[value.value]
        else -> Color.Unspecified
    }
    return SpanStyle(
        color = color(DesignProperty.ContentColor),
        background = color(DesignProperty.BackgroundColor),
        fontWeight = properties[DesignProperty.FontWeight]?.let {
            FontWeight((it as DesignValue.FontWeightValue).value)
        },
        fontStyle = (properties[DesignProperty.Italic] as? DesignValue.BooleanValue)?.let {
            if (it.value) FontStyle.Italic else FontStyle.Normal
        },
        textDecoration = designTextDecoration(properties),
    )
}

/**
 * Resolve one color property of a component slot, or null when it is unbound.
 *
 * Drawing APIs that take a plain color rather than a Style — a swipe cell's
 * background, for one — read the profile through here, so the experimental
 * Style types stay inside this adapter.
 */
@Composable
@ReadOnlyComposable
internal fun resolvedDesignColor(
    slot: DesignComponentStyleSlot,
    property: DesignProperty,
): Color? {
    val value = LocalDesignScope.current
        ?.componentStyle(slot)
        ?.resolve()
        ?.properties
        ?.get(property)
        ?: return null
    return when (value) {
        is DesignValue.ColorValue -> Color(value.argb)
        is DesignValue.ThemeRoleValue -> JetpacsTheme.roles[value.value]
        else -> null
    }
}

internal fun StyleState?.activeDesignStates(): Set<DesignState> {
    if (this == null) return emptySet()
    return buildSet {
        if (!isEnabled) add(DesignState.Disabled)
        if (isSelected) add(DesignState.Selected)
        if (isChecked) add(DesignState.Toggled)
        if (isHovered) add(DesignState.Hovered)
        if (isFocused) add(DesignState.Focused)
        if (isPressed) add(DesignState.Pressed)
    }
}

private fun DesignValue.dp() = (this as DesignValue.DimensionValue).value.dp

private fun DesignValue.sp() = (this as DesignValue.DimensionValue).value.sp

private fun DesignValue.number(): Float = (this as DesignValue.NumberValue).value.toFloat()

private fun DesignValue.boolean(): Boolean = (this as DesignValue.BooleanValue).value

private fun DesignValue.fontWeight(): Int = (this as DesignValue.FontWeightValue).value

private fun DesignValue.textAlign(): TextAlign = when (
    (this as DesignValue.TextAlignValue).value
) {
    DesignTextAlign.Start -> TextAlign.Start
    DesignTextAlign.Center -> TextAlign.Center
    DesignTextAlign.End -> TextAlign.End
}

/**
 * The Compose family for a `font-family` value, through the installed
 * [JetpacsFonts]: a role the user replaced wins over the bundled or platform
 * family, and a named family this device lacks falls back to the sans role.
 */
private fun DesignValue.fontFamily(): FontFamily {
    val installed = JetpacsFonts.current
    return when (val family = (this as DesignValue.FontFamilyValue).value) {
        DesignFontFamily.System -> installed.sans ?: FontFamily.Default
        DesignFontFamily.PlexSans -> installed.sans ?: PlexFonts.sans
        DesignFontFamily.PlexSerif -> installed.serif ?: PlexFonts.serif
        DesignFontFamily.PlexMono -> installed.mono ?: PlexFonts.mono
        DesignFontFamily.SystemMono -> installed.mono ?: FontFamily.Monospace
        is DesignFontFamily.Named ->
            installed.named[family.slug] ?: installed.sans ?: FontFamily.Default
    }
}

/**
 * The layout a style program asks for outside Foundation Styles.
 *
 * `intrinsic_height` sizes the node to its children's intrinsic height, so a
 * child the parent row stretches (`align_self` `stretch`) spans the tallest
 * sibling, as an indent guide spans a wrapped outline row.
 */
internal fun ComputedDesignStyle.intrinsicLayout(): Modifier =
    if ((resolve().properties[DesignProperty.IntrinsicHeight] as? DesignValue.BooleanValue)?.value == true) {
        Modifier.height(IntrinsicSize.Min)
    } else {
        Modifier
    }

/**
 * Whether the style authors its own size on each axis.
 *
 * A pressable keeps a 48dp minimum interaction bound unless its style sizes
 * that axis itself, which is how a dense list row tiles rows shorter than
 * 48dp edge to edge as its upstream design does.
 */
internal fun ComputedDesignStyle.authoredSize(): Pair<Boolean, Boolean> {
    val keys = baseOnly().resolve().properties.keys
    return Pair(
        DesignProperty.Width in keys || DesignProperty.MinWidth in keys,
        DesignProperty.Height in keys || DesignProperty.MinHeight in keys,
    )
}

/**
 * Draws the touch ripple a design style authors: [color]'s alpha is the
 * pressed strength, and a [radius] makes it borderless, as a toolbar
 * action's. This renderer stays Material-free, so the host supplies the
 * platform's ripple through [LocalDesignRippleFactory].
 */
fun interface DesignRippleFactory {
    fun ripple(color: Color, radius: Dp?): IndicationNodeFactory
}

/** The host's [DesignRippleFactory]; without one, authored ripples draw nothing. */
val LocalDesignRippleFactory = staticCompositionLocalOf<DesignRippleFactory?> { null }

/**
 * Draw [style]'s authored ripple for presses on [source] over what follows
 * this modifier, clipped to the style's `corner_radius` when it is bounded,
 * as a shaped button's ripple is. Unchanged without `ripple_color` or a
 * host ripple.
 */
@Composable
internal fun Modifier.authoredRipple(style: ComputedDesignStyle?, source: InteractionSource): Modifier {
    val factory = LocalDesignRippleFactory.current ?: return this
    val properties = style?.resolve()?.properties ?: return this
    val argb = (properties[DesignProperty.RippleColor] as? DesignValue.ColorValue)?.argb ?: return this
    val radius = (properties[DesignProperty.RippleRadius] as? DesignValue.DimensionValue)?.value?.dp
    val ripple = remember(factory, argb, radius) { factory.ripple(Color(argb), radius) }
    val corner = (properties[DesignProperty.CornerRadius] as? DesignValue.DimensionValue)?.value?.dp
    return (if (radius == null && corner != null) clip(RoundedCornerShape(corner)) else this)
        .indication(source, ripple)
}

/** [authoredRipple] for the scope's [slot]. */
@Composable
internal fun Modifier.designRipple(slot: DesignComponentStyleSlot, source: InteractionSource): Modifier =
    authoredRipple(LocalDesignScope.current?.componentStyle(slot), source)

/** Whether a picker face's resolved slot properties ask for `platform_dialog`. */
internal fun Map<DesignProperty, DesignValue>.platformDialog(): Boolean =
    (this[DesignProperty.PlatformDialog] as? DesignValue.BooleanValue)?.value == true

/** Whether resolved text properties ask for `word_ellipsis`. */
internal fun Map<DesignProperty, DesignValue>.wordEllipsis(): Boolean =
    (this[DesignProperty.WordEllipsis] as? DesignValue.BooleanValue)?.value == true

/**
 * What a dialog's root design scope asks of the host's dialog surface.
 *
 * The host draws the dialog window's surface outside the dialog's node tree,
 * so the scope's `chrome.dialog` slot is read here: `background_color`,
 * `corner_radius`, `width`, `padding`, `elevation` as the surface's shadow,
 * and `alpha` as the window's dim behind the dialog. Each is null while
 * unauthored, and the host keeps its own default for it.
 */
data class JetpacsDialogChrome(
    val container: Color?,
    val corner: Dp?,
    val width: Dp?,
    val padding: Dp?,
    val dim: Float? = null,
    val elevation: Dp? = null,
)

/** The [JetpacsDialogChrome] of [spec], or null unless its root is a design scope. */
fun jetpacsDialogChrome(spec: JsonObject): JetpacsDialogChrome? {
    if ((spec["t"] as? JsonPrimitive)?.content != "jetpacs.design_scope") return null
    val scope = runCatching { DesignModel.compileScope(spec, null, "dialog") }.getOrNull()
        ?: return null
    val properties = scope.componentStyle(DesignComponentStyleSlot.ChromeDialog)
        ?.resolve()?.properties ?: return null
    fun color(value: DesignValue?): Color? = when (value) {
        is DesignValue.ColorValue -> Color(value.argb)
        is DesignValue.ThemeRoleValue ->
            (scope.themeRoles[value.value] as? DesignValue.ColorValue)?.let { Color(it.argb) }
        else -> null
    }
    fun dp(property: DesignProperty): Dp? =
        (properties[property] as? DesignValue.DimensionValue)?.value?.dp
    return JetpacsDialogChrome(
        container = color(properties[DesignProperty.BackgroundColor]),
        corner = dp(DesignProperty.CornerRadius),
        width = dp(DesignProperty.Width),
        padding = dp(DesignProperty.Padding),
        dim = (properties[DesignProperty.Alpha] as? DesignValue.NumberValue)?.value?.toFloat(),
        elevation = dp(DesignProperty.Elevation),
    )
}

/**
 * Where an anchored popup opens, from its container slot: `popup_overlap`
 * starts it at the anchor's top instead of its bottom, as an Android
 * overflow menu covers its button; `popup_offset_y` moves it further down;
 * `popup_edge_inset` keeps it that far from the window's sides.
 */
data class JetpacsPopupPlacement(
    val overlap: Boolean = false,
    val offsetY: Dp = 0.dp,
    val edgeInset: Dp = 0.dp,
    /** The popup ends where its anchor starts, as a field's dropdown
     * spans the field before the button that opens it. */
    val beforeAnchor: Boolean = false,
)

internal fun ComputedDesignStyle?.popupPlacement(): JetpacsPopupPlacement {
    val properties = this?.resolve()?.properties ?: return JetpacsPopupPlacement()
    return JetpacsPopupPlacement(
        overlap = (properties[DesignProperty.PopupOverlap] as? DesignValue.BooleanValue)?.value == true,
        offsetY = (properties[DesignProperty.PopupOffsetY] as? DesignValue.DimensionValue)?.value?.dp ?: 0.dp,
        edgeInset = (properties[DesignProperty.PopupEdgeInset] as? DesignValue.DimensionValue)?.value?.dp ?: 0.dp,
        beforeAnchor = (properties[DesignProperty.PopupBeforeAnchor] as? DesignValue.BooleanValue)?.value == true,
    )
}

/** The `elevation` a style authors with its `corner_radius`, or null. */
internal fun ComputedDesignStyle?.authoredElevation(): Pair<Dp, Dp>? {
    val properties = this?.resolve()?.properties ?: return null
    val elevation = (properties[DesignProperty.Elevation] as? DesignValue.DimensionValue)?.value ?: return null
    val corner = (properties[DesignProperty.CornerRadius] as? DesignValue.DimensionValue)?.value ?: 0.0
    return elevation.dp to corner.dp
}

/** The platform shadow an authored `elevation` casts around the style's corners. */
internal fun ComputedDesignStyle.elevationShadow(): Modifier =
    authoredElevation()?.let { (elevation, corner) ->
        Modifier.shadow(elevation, androidx.compose.foundation.shape.RoundedCornerShape(corner), clip = false)
    } ?: Modifier

/** How a menu row draws its glyphs, from the scope's `menu.item` slot. */
internal data class MenuRowGlyphs(
    val checkAtEnd: Boolean,
    val checkColor: Color?,
    val iconSize: Dp?,
    val iconColor: Color?,
    /** How far the leading glyph moves from where the row's padding puts it. */
    val iconShift: Dp = 0.dp,
    val iconGap: Dp? = null,
)

/** The `menu.item` glyph properties of this style: `check_at_end`, `check_color`, `icon_size`, `content_color`. */
@Composable
internal fun ComputedDesignStyle?.menuRowGlyphs(): MenuRowGlyphs {
    val properties = this?.resolve()?.properties.orEmpty()
    val roles = JetpacsTheme.roles
    fun color(p: DesignProperty): Color? = when (val v = properties[p]) {
        is DesignValue.ColorValue -> Color(v.argb)
        is DesignValue.ThemeRoleValue -> roles[v.value]
        else -> null
    }
    fun dimension(p: DesignProperty) = (properties[p] as? DesignValue.DimensionValue)?.value?.dp
    // `icon_start` is measured from the row's outer start; the row's own
    // start padding already puts the glyph that far in.
    val paddingStart = dimension(DesignProperty.PaddingStart)
        ?: dimension(DesignProperty.PaddingHorizontal) ?: dimension(DesignProperty.Padding) ?: 0.dp
    return MenuRowGlyphs(
        checkAtEnd = (properties[DesignProperty.CheckAtEnd] as? DesignValue.BooleanValue)?.value == true,
        checkColor = color(DesignProperty.CheckColor),
        iconSize = dimension(DesignProperty.IconSize),
        iconColor = color(DesignProperty.ContentColor),
        iconShift = dimension(DesignProperty.IconStart)?.let { it - paddingStart } ?: 0.dp,
        iconGap = dimension(DesignProperty.IconGap),
    )
}

/** The `min_width` a style authors, or null. */
internal fun ComputedDesignStyle?.authoredMinWidth(): Dp? =
    (this?.resolve()?.properties?.get(DesignProperty.MinWidth) as? DesignValue.DimensionValue)
        ?.value?.dp
