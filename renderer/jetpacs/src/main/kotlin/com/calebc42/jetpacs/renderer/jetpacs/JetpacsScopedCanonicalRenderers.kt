// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import kotlinx.serialization.json.JsonArray
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.contentPadding
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import com.calebc42.ebp.renderer.compose.LocalComposeIconResolver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * Design-scoped Foundation presentation of the canonical nodes an applet
 * reaches for most: icon, button, chip, divider, and section_header.
 *
 * Each override keeps the canonical member vocabulary and semantics and
 * changes only Compose selection. Base visuals come from the private Jetpacs
 * theme, the profile's component slots layer on top, and an authored `color`
 * member wins last, exactly like the text override. A member this
 * presentation cannot honor (a toggle button, an icon badge, a chip avatar)
 * makes the override decline, so the node falls through to the canonical
 * Material renderer instead of silently losing state.
 */

/** Resolve the tint for a glyph: authored color, else the enclosing face's content color. */
@Composable
@ReadOnlyComposable
private fun designGlyphTint(node: JsonObject, fallback: Color): Color {
    val roles = JetpacsTheme.roles
    val authored = node.member("color").takeIf { it.isNotEmpty() }?.let { spec ->
        DesignThemeRole.fromWireName(spec)?.let { roles[it] } ?: parseHexColor(spec)
    }
    if (authored != null) return authored
    val enclosing = LocalDesignTextContext.current ?: return fallback
    return TextStyle(color = fallback)
        .withDesignTextProperties(
            enclosing.computed.resolve(enclosing.state.activeDesignStates()).properties,
            roles,
        ).color
}

/** Draw the named glyph through the installed resolver, or reserve its space. */
@Composable
internal fun DesignGlyph(
    name: String,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val vector = LocalComposeIconResolver.current?.resolve(name)
    if (vector == null) {
        Box(modifier.size(size))
        return
    }
    Image(
        painter = rememberVectorPainter(vector),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}

/** Design-scoped `icon`: the named glyph, tinted by the surrounding face. */
object JetpacsDesignIconRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.icon.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("icon")

    /** A badged icon keeps its Material badge presentation. */
    override fun appliesTo(node: JsonObject): Boolean = node.member("badge").isEmpty()

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val size = node.number("size")?.takeIf { it > 0 }?.dp ?: 24.dp
        DesignGlyph(
            name = node.member("name"),
            tint = designGlyphTint(node, JetpacsTheme.colors.content),
            size = size,
            modifier = modifier,
            // The dispatcher's semantics already carry content_description as
            // the accessible name; a second description would announce twice.
            contentDescription = null,
        )
    }
}

/**
 * Design-scoped `button`: one full-target pill per variant.
 *
 * Handles label, icon, enabled, variant, size, shape, and color. Toggle
 * buttons (`checked`/`on_change`), collapsing FABs (`expanded`), and
 * connected-group positions (`shape_role`) decline to the Material renderer,
 * which owns their state and shape morphs.
 */
object JetpacsDesignButtonRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.button.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("button")

    override fun appliesTo(node: JsonObject): Boolean =
        "checked" !in node && "on_change" !in node && "expanded" !in node &&
            "shape_role" !in node

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val enabled = node.boolean("enabled", true)
        val variant = node.member("variant")
        val slot = containerSlotFor(variant)
        val base = baseStyleFor(variant)
        val metrics = sizeMetrics(node.member("size"))
        val square = node.member("shape") == "square"
        val source = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(source) { it.isEnabled = enabled }
        val colors = JetpacsTheme.colors
        val authored = node.member("color").takeIf { it.isNotEmpty() }?.let { spec ->
            DesignThemeRole.fromWireName(spec)?.let { JetpacsTheme.roles[it] }
                ?: parseHexColor(spec)
        }
        // Layout properties the wire names directly, applied after the
        // profile so `size` and `shape` keep their canonical meaning.
        val nodeStyle = remember(metrics, square, authored, variant) {
            Style {
                minHeight(metrics.height)
                contentPadding(horizontal = metrics.horizontalPadding, vertical = metrics.verticalPadding)
                if (square) shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                if (authored != null) {
                    if (variant == "filled" || variant.isEmpty()) background(authored)
                    else contentColor(authored)
                }
            }
        }
        val labelStyle = resolvedDesignTextStyle(
            DesignComponentStyleSlot.ButtonLabel,
            resolvedDesignTextStyle(slot, labelFallback(variant, colors), styleState),
            styleState,
        ).let { style ->
            if (authored != null && variant != "filled" && variant.isNotEmpty()) style.copy(color = authored) else style
        }.copy(fontSize = metrics.fontSize)
        val icon = node.member("icon")
        Row(
            modifier = modifier
                .heightIn(min = 48.dp)
                .hoverable(source, enabled)
                .focusable(enabled, source)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) { context.action(node["on_tap"] as? JsonObject) }
                .semantics(mergeDescendants = true) {}
                .styleable(styleState, base, designComponentStyle(slot), nodeStyle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon.isNotEmpty()) {
                DesignGlyph(icon, labelStyle.color, metrics.iconSize)
                Spacer(Modifier.width(metrics.iconGap))
            }
            BasicText(
                text = node.member("label"),
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    private fun containerSlotFor(variant: String): DesignComponentStyleSlot = when (variant) {
        "tonal" -> DesignComponentStyleSlot.ButtonTonal
        "elevated" -> DesignComponentStyleSlot.ButtonElevated
        "outlined" -> DesignComponentStyleSlot.ButtonOutlined
        "text" -> DesignComponentStyleSlot.ButtonText
        else -> DesignComponentStyleSlot.ButtonFilled
    }

    private fun baseStyleFor(variant: String): Style = when (variant) {
        "tonal" -> JetpacsComponentStyles.buttonTonal
        "elevated" -> JetpacsComponentStyles.buttonElevated
        "outlined" -> JetpacsComponentStyles.buttonOutlined
        "text" -> JetpacsComponentStyles.buttonText
        else -> JetpacsComponentStyles.buttonFilled
    }

    @Composable
    @ReadOnlyComposable
    private fun labelFallback(variant: String, colors: JetpacsColors): TextStyle {
        val color = when (variant) {
            "tonal" -> colors.content
            "elevated", "outlined", "text" -> colors.accent
            else -> colors.onAccent
        }
        return JetpacsTheme.typography.action.copy(color = color)
    }
}

/** Container height, padding, and glyph metrics for the closed `size` ladder. */
internal data class ButtonSizeMetrics(
    val height: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val iconSize: Dp,
    val iconGap: Dp,
    val fontSize: TextUnit,
)

internal fun sizeMetrics(size: String): ButtonSizeMetrics = when (size) {
    "xsmall" -> ButtonSizeMetrics(32.dp, 12.dp, 6.dp, 20.dp, 8.dp, 14.sp)
    "small" -> ButtonSizeMetrics(40.dp, 16.dp, 8.dp, 20.dp, 8.dp, 14.sp)
    "medium" -> ButtonSizeMetrics(56.dp, 24.dp, 16.dp, 24.dp, 8.dp, 16.sp)
    "large" -> ButtonSizeMetrics(96.dp, 48.dp, 32.dp, 32.dp, 12.dp, 24.sp)
    "xlarge" -> ButtonSizeMetrics(136.dp, 64.dp, 48.dp, 40.dp, 16.dp, 32.sp)
    else -> ButtonSizeMetrics(40.dp, 16.dp, 8.dp, 18.dp, 8.dp, 15.sp)
}


/**
 * Design-scoped `chip`: a selectable compact pill.
 *
 * Handles label, icon, trailing_icon, selected, enabled, and on_tap. Input
 * chips with an avatar decline to the Material renderer.
 */
object JetpacsDesignChipRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.chip.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("chip")

    override fun appliesTo(node: JsonObject): Boolean = node.member("avatar").isEmpty()

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val enabled = node.boolean("enabled", true)
        val selected = node.boolean("selected", false)
        val onTap = node["on_tap"] as? JsonObject
        val source = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(source) {
            it.isEnabled = enabled
            it.isSelected = selected
        }
        val labelStyle = resolvedDesignTextStyle(
            DesignComponentStyleSlot.ChipLabel,
            resolvedDesignTextStyle(
                DesignComponentStyleSlot.ChipContainer,
                JetpacsTheme.typography.choice.copy(
                    color = if (selected) JetpacsTheme.colors.accent else JetpacsTheme.colors.content,
                ),
                styleState,
            ),
            styleState,
        )
        val interactive = if (onTap != null) {
            Modifier.selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Checkbox,
                interactionSource = source,
                indication = null,
            ) { context.action(onTap) }
        } else {
            Modifier
        }
        Row(
            modifier = modifier
                .heightIn(min = 32.dp)
                .hoverable(source, enabled && onTap != null)
                .then(interactive)
                .semantics(mergeDescendants = true) {}
                .styleable(
                    styleState,
                    JetpacsComponentStyles.chip,
                    designComponentStyle(DesignComponentStyleSlot.ChipContainer),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val leading = node.member("icon")
            if (leading.isNotEmpty()) {
                DesignGlyph(leading, labelStyle.color, 18.dp)
                Spacer(Modifier.width(8.dp))
            }
            BasicText(node.member("label"), style = labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val trailing = node.member("trailing_icon")
            if (trailing.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                DesignGlyph(trailing, labelStyle.color, 18.dp)
            }
        }
    }
}

/** Design-scoped `divider`: a hairline whose color comes from the profile or the node. */
object JetpacsDesignDividerRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.divider.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("divider")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val thickness = node.number("thickness")?.takeIf { it > 0 }?.dp ?: 1.dp
        val authored = node.member("color").takeIf { it.isNotEmpty() }?.let { spec ->
            DesignThemeRole.fromWireName(spec)?.let { JetpacsTheme.roles[it] }
                ?: parseHexColor(spec)
        }
        val source = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(source)
        val nodeStyle = remember(authored) {
            Style { if (authored != null) background(authored) }
        }
        Box(
            modifier
                .fillMaxWidth()
                .height(thickness)
                .styleable(
                    styleState,
                    JetpacsComponentStyles.divider,
                    designComponentStyle(DesignComponentStyleSlot.DividerLine),
                    nodeStyle,
                ),
        )
    }
}

/** Design-scoped `section_header`: a heading row with an optional trailing node. */
object JetpacsDesignSectionHeaderRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.section-header.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("section_header")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val source = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(source)
        val titleStyle = resolvedDesignTextStyle(
            DesignComponentStyleSlot.SectionHeaderTitle,
            JetpacsTheme.typography.panelLabel.copy(color = JetpacsTheme.colors.accent),
            styleState,
        )
        Row(
            modifier = modifier
                .fillMaxWidth()
                .styleable(
                    styleState,
                    JetpacsComponentStyles.sectionHeader,
                    designComponentNonTextStyle(DesignComponentStyleSlot.SectionHeaderContainer),
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                BasicText(node.member("title"), style = titleStyle)
            }
            (node["trailing"] as? JsonObject)?.let { context.renderChild(it, 0) }
        }
    }
}

/**
 * Design-scoped `card`: the container an applet's rows are built from.
 *
 * The incoming modifier already carries the node's universal attributes and
 * its EBP semantics — including the swipe sides as accessibility custom
 * actions — so it goes on the card surface INSIDE the swipe foreground, and
 * this renderer adds no custom actions of its own. Interaction mirrors the
 * Material card exactly: while a side is revealed a tap closes it rather than
 * running `on_tap`, so an open row can never fire the action underneath it.
 */
object JetpacsDesignCardRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.card.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("card")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val onTap = node["on_tap"] as? JsonObject
        val onLongTap = node["on_long_tap"] as? JsonObject
        val variant = node.member("variant")
        val slot = when (variant) {
            "filled" -> DesignComponentStyleSlot.CardFilled
            "outlined" -> DesignComponentStyleSlot.CardOutlined
            else -> DesignComponentStyleSlot.CardElevated
        }
        val base = when (variant) {
            "filled" -> JetpacsComponentStyles.cardFilled
            "outlined" -> JetpacsComponentStyles.cardOutlined
            else -> JetpacsComponentStyles.cardElevated
        }
        JetpacsSwipeRow(
            identity = context.path,
            swipeStart = node["swipe_start"] as? JsonObject,
            swipeEnd = node["swipe_end"] as? JsonObject,
            context = context,
        ) { isOpen, close ->
            val source = remember { MutableInteractionSource() }
            val styleState = rememberUpdatedStyleState(source)
            val interactive = when {
                isOpen -> Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    onClick = close,
                )
                onLongTap != null -> Modifier.combinedClickable(
                    interactionSource = source,
                    indication = null,
                    onLongClick = { context.action(onLongTap) },
                ) { context.action(onTap) }
                onTap != null -> Modifier.clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                ) { context.action(onTap) }
                else -> Modifier
            }
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .hoverable(source, onTap != null || onLongTap != null)
                    .then(interactive)
                    .styleable(styleState, base, designComponentStyle(slot)),
            ) {
                val children = node["children"] as? JsonArray ?: JsonArray(emptyList())
                children.forEachIndexed { index, child ->
                    (child as? JsonObject)?.let { context.renderChild(it, index) }
                }
            }
        }
    }
}

/**
 * Design-scoped `icon_button`: one glyph target.
 *
 * Toggles and badged buttons decline to Material, which owns the checked
 * state, the shape morph and the badge anchor. Everything else — variant,
 * size, shape, enabled, colour — is presentation this can state directly.
 */
object JetpacsDesignIconButtonRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.icon-button.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("icon_button")

    override fun appliesTo(node: JsonObject): Boolean =
        "checked" !in node && "on_change" !in node && node.member("badge").isEmpty()

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val enabled = node.boolean("enabled", true)
        val variant = node.member("variant")
        val metrics = iconButtonMetrics(node.member("size"))
        val square = node.member("shape") == "square"
        val source = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(source) { it.isEnabled = enabled }
        val authored = node.member("color").takeIf { it.isNotEmpty() }?.let { spec ->
            DesignThemeRole.fromWireName(spec)?.let { JetpacsTheme.roles[it] }
                ?: parseHexColor(spec)
        }
        val face = when (variant) {
            "filled", "filled_tonal" -> JetpacsComponentStyles.iconButtonFilled
            "outlined" -> JetpacsComponentStyles.iconButtonOutlined
            else -> Style
        }
        val nodeStyle = remember(metrics, square, authored) {
            Style {
                width(metrics.container)
                height(metrics.container)
                if (square) shape(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                if (authored != null) contentColor(authored)
            }
        }
        val tint = resolvedDesignTextStyle(
            DesignComponentStyleSlot.IconButtonContainer,
            TextStyle(
                color = when (variant) {
                    "filled", "filled_tonal" -> JetpacsTheme.colors.onAccent
                    "outlined" -> JetpacsTheme.colors.accent
                    else -> JetpacsTheme.colors.content
                },
            ),
            styleState,
        ).color.let { authored ?: it }
        Box(
            modifier = modifier
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .hoverable(source, enabled)
                .focusable(enabled, source)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                ) { context.action(node["on_tap"] as? JsonObject) }
                .styleable(
                    styleState,
                    JetpacsComponentStyles.iconButton,
                    face,
                    designComponentStyle(DesignComponentStyleSlot.IconButtonContainer),
                    nodeStyle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            DesignGlyph(node.member("icon"), tint, metrics.glyph)
        }
    }
}

/** Container and glyph sizes for the closed `size` ladder. */
internal data class IconButtonMetrics(val container: Dp, val glyph: Dp)

internal fun iconButtonMetrics(size: String): IconButtonMetrics = when (size) {
    "xsmall" -> IconButtonMetrics(32.dp, 20.dp)
    "small" -> IconButtonMetrics(40.dp, 20.dp)
    "medium" -> IconButtonMetrics(56.dp, 24.dp)
    "large" -> IconButtonMetrics(96.dp, 32.dp)
    else -> IconButtonMetrics(40.dp, 24.dp)
}

/**
 * Design-scoped `badge`: a compact status pill, or a bare dot when unlabelled.
 *
 * A badge decorating children is Material's anchored `BadgedBox`; this draws
 * the standalone form only.
 */
object JetpacsDesignBadgeRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.badge.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("badge")

    override fun appliesTo(node: JsonObject): Boolean = node["children"] == null

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val label = node.member("label")
        val source = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(source)
        val authored = node.member("color").takeIf { it.isNotEmpty() }?.let { spec ->
            DesignThemeRole.fromWireName(spec)?.let { JetpacsTheme.roles[it] }
                ?: parseHexColor(spec)
        }
        // The canonical `color` names the badge's own ink, as the Material
        // renderer reads it; the slot supplies the resting container.
        val nodeStyle = remember(authored) {
            Style { if (authored != null) contentColor(authored) }
        }
        val labelStyle = resolvedDesignTextStyle(
            DesignComponentStyleSlot.BadgeLabel,
            resolvedDesignTextStyle(
                DesignComponentStyleSlot.BadgeContainer,
                JetpacsTheme.typography.fieldLabel.copy(
                    color = authored ?: JetpacsTheme.colors.content,
                ),
                styleState,
            ),
            styleState,
        ).let { if (authored != null) it.copy(color = authored) else it }
        val icon = node.member("icon")
        Row(
            modifier = modifier
                .then(if (label.isEmpty() && icon.isEmpty()) Modifier.size(8.dp) else Modifier)
                .styleable(
                    styleState,
                    JetpacsComponentStyles.badge,
                    designComponentStyle(DesignComponentStyleSlot.BadgeContainer),
                    nodeStyle,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon.isNotEmpty()) DesignGlyph(icon, labelStyle.color, 14.dp)
            if (label.isNotEmpty()) {
                BasicText(label, style = labelStyle, maxLines = 1)
            }
        }
    }
}

/**
 * Design-scoped `empty_state`: the centred glyph, title, caption and action
 * a screen shows when it has nothing to list.
 */
object JetpacsDesignEmptyStateRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.empty-state.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("empty_state")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val source = remember { MutableInteractionSource() }
        val styleState = rememberUpdatedStyleState(source)
        val titleStyle = resolvedDesignTextStyle(
            DesignComponentStyleSlot.EmptyStateTitle,
            JetpacsTheme.typography.choice.copy(color = JetpacsTheme.colors.content),
            styleState,
        )
        val captionStyle = resolvedDesignTextStyle(
            DesignComponentStyleSlot.EmptyStateCaption,
            JetpacsTheme.typography.fieldSupporting.copy(
                color = JetpacsTheme.colors.mutedContent,
            ),
            styleState,
        )
        val title = node.member("title")
        val caption = node.member("caption")
        val actionLabel = node.member("action_label")
        val onTap = node["on_tap"] as? JsonObject
        Column(
            modifier = modifier
                .fillMaxWidth()
                .styleable(
                    styleState,
                    JetpacsComponentStyles.emptyState,
                    designComponentNonTextStyle(DesignComponentStyleSlot.EmptyStateContainer),
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DesignGlyph(
                node.member("icon").ifEmpty { "inbox" },
                captionStyle.color,
                48.dp,
            )
            if (title.isNotEmpty()) BasicText(title, style = titleStyle)
            if (caption.isNotEmpty()) {
                BasicText(
                    caption,
                    style = captionStyle.copy(textAlign = TextAlign.Center),
                )
            }
            // SPEC 17.2 validates that the label and the descriptor arrive
            // together, so one guard covers the pair.
            if (onTap != null && actionLabel.isNotEmpty()) {
                JetpacsDesignButtonRenderer.render(
                    buildJsonObject {
                        put("t", "button")
                        put("label", actionLabel)
                        put("variant", "outlined")
                        put("on_tap", onTap)
                    },
                    context,
                    Modifier,
                )
            }
        }
    }
}

private fun JsonObject.member(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.boolean(name: String, default: Boolean): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default

private fun JsonObject.number(name: String): Double? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
