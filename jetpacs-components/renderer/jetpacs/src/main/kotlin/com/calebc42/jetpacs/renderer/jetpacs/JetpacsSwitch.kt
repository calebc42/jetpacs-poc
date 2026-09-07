// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.ebp.renderer.compose.ComposeNodeRenderContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/** Inset between the thumb and the track edge at either end of its travel. */
private val SwitchThumbInset = 4.dp

/** The glyph on a checked thumb, sized to sit inside a 24-dp thumb. */
private val SwitchThumbGlyphSize = 16.dp

/**
 * Jetpacs' switch: a label and a sliding thumb on a track.
 *
 * The whole row is the one toggle target, so the label is part of what a
 * screen reader announces and a tap anywhere on the row flips it. The track's
 * selected face carries the "on" signal; the thumb slides between the ends
 * and, while checked, shows [thumbIcon] if there is one. The travel is
 * measured, not assumed, so a profile that resizes the track or thumb keeps
 * the thumb on it.
 *
 * [style] and the three slot styles change visual properties only and are
 * layered after the theme and the active design profile.
 */
@Composable
fun JetpacsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    thumbIcon: String? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    style: Style = Style,
    trackStyle: Style = Style,
    thumbStyle: Style = Style,
    labelStyle: Style = Style,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) {
        it.isEnabled = enabled
        it.isSelected = checked
    }
    val resolvedLabel = resolvedDesignTextStyle(
        DesignComponentStyleSlot.SwitchLabel,
        JetpacsTheme.typography.choice,
        styleState,
    )
    val thumbContent = resolvedDesignTextStyle(
        DesignComponentStyleSlot.SwitchThumb,
        TextStyle(color = JetpacsTheme.colors.accent),
        styleState,
    ).color
    Row(
        modifier = modifier
            .hoverable(source, enabled)
            .jetpacsFocusable(enabled, source)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = source,
                indication = null,
                onValueChange = onCheckedChange,
            )
            .styleable(styleState, JetpacsTheme.styles.switchRow, style),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!label.isNullOrEmpty()) {
            Box(
                Modifier
                    .weight(1f)
                    .styleable(styleState, Style, labelStyle),
            ) {
                BasicText(label, style = resolvedLabel, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
        }
        JetpacsSwitchTrack(
            checked = checked,
            thumbIcon = thumbIcon,
            thumbContent = thumbContent,
            styleState = styleState,
            trackStyle = trackStyle,
            thumbStyle = thumbStyle,
        )
    }
}

/** The track and its thumb, drawn from the two slots and nothing else. */
@Composable
private fun JetpacsSwitchTrack(
    checked: Boolean,
    thumbIcon: String?,
    thumbContent: androidx.compose.ui.graphics.Color,
    styleState: androidx.compose.foundation.style.StyleState,
    trackStyle: Style,
    thumbStyle: Style,
) {
    val density = LocalDensity.current
    var trackWidth by remember { mutableIntStateOf(0) }
    var thumbWidth by remember { mutableIntStateOf(0) }
    val fraction by animateFloatAsState(if (checked) 1f else 0f, label = "switch-thumb")
    val insetPx = with(density) { SwitchThumbInset.roundToPx() }
    Box(
        modifier = Modifier
            .onSizeChanged { trackWidth = it.width }
            .styleable(
                styleState,
                JetpacsTheme.styles.switchTrack,
                designComponentStyle(DesignComponentStyleSlot.SwitchTrack),
                trackStyle,
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset {
                    // Measured travel: whatever size the profile gave the two
                    // parts, the thumb rests inset from each end.
                    val travel = (trackWidth - thumbWidth - 2 * insetPx).coerceAtLeast(0)
                    IntOffset((insetPx + fraction * travel).roundToInt(), 0)
                }
                .onSizeChanged { thumbWidth = it.width }
                .styleable(
                    styleState,
                    JetpacsTheme.styles.switchThumb,
                    designComponentStyle(DesignComponentStyleSlot.SwitchThumb),
                    thumbStyle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The glyph is decoration over a control that already has a
            // name, and it is drawn only while the live value is on.
            if (checked && !thumbIcon.isNullOrEmpty()) {
                DesignGlyph(thumbIcon, thumbContent, SwitchThumbGlyphSize)
            }
        }
    }
}

/**
 * Design-scoped `switch`: the row, the track and the thumb on Foundation.
 *
 * Every member is honored, so this never declines. State is the host's:
 * the live value is seeded from the store at the node's epoch and falls back
 * to the authored `checked`, and a flip publishes `state.changed` before
 * `on_change`, in that order, exactly as the Material renderer does.
 */
object JetpacsDesignSwitchRenderer : ComposeCanonicalNodeOverride {
    override val id: String = "jetpacs.design.switch.compose"
    override val designScope: String = JETPACS_DESIGN_EXTENSION
    override val nodeTypes: Set<String> = setOf("switch")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        val id = node.switchText("id")
        val epoch = context.epochOf(id)
        var checked by rememberSaveable(context.surface, id, epoch) {
            mutableStateOf(
                (context.storeValue(id) as? JsonPrimitive)
                    ?.takeIf { !it.isString }?.booleanOrNull
                    ?: node.switchFlag("checked", false),
            )
        }
        val onChange = node["on_change"] as? JsonObject
        JetpacsSwitch(
            checked = checked,
            onCheckedChange = { next ->
                checked = next
                val value = JsonPrimitive(next)
                context.state(id, value)
                context.action(onChange, value)
            },
            modifier = modifier,
            label = node.switchText("label").takeIf { it.isNotEmpty() },
            thumbIcon = node.switchText("thumb_icon").takeIf { it.isNotEmpty() },
            enabled = node.switchFlag("enabled", true),
        )
    }
}

private fun JsonObject.switchText(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.switchFlag(name: String, default: Boolean): Boolean =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: default
