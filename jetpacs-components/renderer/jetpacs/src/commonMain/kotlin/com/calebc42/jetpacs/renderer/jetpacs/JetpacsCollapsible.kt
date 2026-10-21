// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import com.calebc42.ebp.renderer.compose.ebpSemantics
import com.calebc42.ebp.renderer.model.SemanticStateOverride
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The disclosure header of a Jetpacs collapsible: a chevron, then [header].
 *
 * The chevron points along the reading direction while collapsed and turns
 * to point down while expanded. The row is the toggle target; anything
 * interactive inside [header] keeps its own target, since an inner click
 * consumes before this row sees it -- which is how an outline heading can
 * open on tap and still expand from its chevron.
 *
 * Accessibility for the node lands on [modifier], with the live expanded
 * state, so a screen reader hears one disclosure row rather than a chevron
 * and some text.
 */
@Composable
fun JetpacsCollapsibleHeader(
    expanded: Boolean,
    onToggle: () -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    style: Style = Style,
    chevronStyle: Style = Style,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        // Toggled is the design state that means "open" here, so a profile
        // can give an expanded header its own face.
        it.isChecked = expanded
    }
    val chevronColor = resolvedDesignTextStyle(
        DesignComponentStyleSlot.CollapsibleChevron,
        TextStyle(color = JetpacsTheme.colors.mutedContent),
        styleState,
    ).color
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(source, enabled)
            .jetpacsFocusable(enabled, source)
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick,
                onClick = onToggle,
            )
            .styleable(
                styleState,
                JetpacsTheme.styles.collapsibleHeader,
                designComponentStyle(DesignComponentStyleSlot.CollapsibleHeader),
                style,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JetpacsDisclosureChevron(
            expanded = expanded,
            color = chevronColor,
            modifier = Modifier.styleable(
                styleState,
                JetpacsTheme.styles.collapsibleChevron,
                designComponentNonTextStyle(DesignComponentStyleSlot.CollapsibleChevron),
                chevronStyle,
            ),
        )
        header()
    }
}

/** The revealed children of a collapsible, styled as one body region. */
@Composable
fun JetpacsCollapsibleBody(
    modifier: Modifier = Modifier,
    style: Style = Style,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.styleable(
            remember { MutableStyleState(null) },
            JetpacsTheme.styles.collapsibleBody,
            designComponentStyle(DesignComponentStyleSlot.CollapsibleBody),
            style,
        ),
        content = content,
    )
}

/**
 * Jetpacs' collapsible: a disclosure header over children shown while
 * [expanded]. The two pieces are public on their own so a caller that wraps
 * the header in something else -- a swipe -- can still compose the whole.
 */
@Composable
fun JetpacsCollapsible(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    header: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    headerModifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    style: Style = Style,
    headerStyle: Style = Style,
    chevronStyle: Style = Style,
    bodyStyle: Style = Style,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().styleable(remember { MutableStyleState(null) }, Style, style)) {
        JetpacsCollapsibleHeader(
            expanded = expanded,
            onToggle = { onExpandedChange(!expanded) },
            header = header,
            modifier = headerModifier,
            onLongClick = onLongClick,
            enabled = enabled,
            style = headerStyle,
            chevronStyle = chevronStyle,
        )
        if (expanded) JetpacsCollapsibleBody(style = bodyStyle, content = content)
    }
}

/** A filled triangle, reading-direction aware, turning down when expanded. */
@Composable
private fun JetpacsDisclosureChevron(
    expanded: Boolean,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val turn by animateFloatAsState(if (expanded) 90f else 0f, label = "disclosure")
    Box(modifier.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(10.dp)
                .graphicsLayer { rotationZ = if (rtl) -turn else turn },
        ) {
            val path = Path().apply {
                if (rtl) {
                    moveTo(size.width, 0f)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height / 2f)
                } else {
                    moveTo(0f, 0f)
                    lineTo(0f, size.height)
                    lineTo(size.width, size.height / 2f)
                }
                close()
            }
            drawPath(path, color)
        }
    }
}

/**
 * Design-scoped `collapsible`.
 *
 * Every member is honored, so this never declines. The host hands this node
 * a modifier WITHOUT the semantics projection, because the header owns it
 * with the live expanded state; that projection is applied here on the
 * header row. Expansion is Companion-local presentation state: `collapsed`
 * seeds only a new presentation identity, and the user's state survives
 * later snapshots at the same path (SPEC 17.3).
 */
object JetpacsDesignCollapsibleRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.collapsible.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("collapsible")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val collapsed = node.collapsibleFlag("collapsed", false)
        var expanded by rememberSaveable(context.path) { mutableStateOf(!collapsed) }
        val header = node["header"] as? JsonObject
        val children = (node["children"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val onLongTap = node["on_long_tap"] as? JsonObject

        Column(modifier.fillMaxWidth()) {
            JetpacsSwipeRow(
                identity = context.path,
                swipeStart = node["swipe_start"] as? JsonObject,
                swipeEnd = node["swipe_end"] as? JsonObject,
                context = context,
            ) { isOpen, close ->
                JetpacsCollapsibleHeader(
                    expanded = expanded,
                    // An open swipe strip closes on tap; the row's own toggle
                    // waits until the strip is away, as every swipe row does.
                    onToggle = if (isOpen) close else ({ expanded = !expanded }),
                    header = { header?.let { context.renderChild(it, 0) } },
                    modifier = Modifier.ebpSemantics(
                        node = node,
                        stateOverride = SemanticStateOverride(expanded = expanded),
                        onAction = { context.action(it) },
                    ),
                    onLongClick = if (isOpen) null else onLongTap?.let { { context.action(it) } },
                )
            }
            if (expanded) {
                JetpacsCollapsibleBody {
                    children.forEachIndexed { index, child -> context.renderChild(child, index) }
                }
            }
        }
    }
}

private fun JsonObject.collapsibleFlag(name: String, default: Boolean): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default
