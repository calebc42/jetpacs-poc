// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3.ui

import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.StyleScope
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.border
import androidx.compose.foundation.style.contentPadding
import androidx.compose.foundation.style.disabled
import androidx.compose.foundation.style.externalPadding
import androidx.compose.foundation.style.fillWidth
import androidx.compose.foundation.style.focused
import androidx.compose.foundation.style.hovered
import androidx.compose.foundation.style.pressed
import androidx.compose.foundation.style.scale
import androidx.compose.foundation.style.selected
import androidx.compose.foundation.style.then
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/**
 * Theme tokens consumed by Jetpacs' custom Styles layer.
 *
 * Material components continue to consume [androidx.compose.material3.MaterialTheme]
 * directly. This projection exists only so custom components can resolve the
 * active EBP-mirrored palette while a [Style] is evaluated outside composition.
 */
@Immutable
internal data class JetpacsStyleTokens(
    val colors: ColorScheme,
    val shapes: Shapes,
)

private val LocalJetpacsStyleTokens = staticCompositionLocalOf {
    JetpacsStyleTokens(lightColorScheme(), Shapes())
}

private val StyleScope.jetpacsTokens: JetpacsStyleTokens
    get() = LocalJetpacsStyleTokens.currentValue

/** Provides the current Material/EBP token projection to custom Styles. */
@Composable
internal fun ProvideJetpacsStyleTokens(
    colors: ColorScheme,
    shapes: Shapes,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalJetpacsStyleTokens provides JetpacsStyleTokens(colors, shapes),
        content = content,
    )
}

/**
 * Receiver-owned component Styles.
 *
 * These definitions are deliberately limited to custom layout components;
 * Material components retain their supported parameter and token APIs.
 */
internal object JetpacsComponentStyles {
    private val interactiveContainer = Style {
        fillWidth()
        shape(jetpacsTokens.shapes.large)
        background(jetpacsTokens.colors.surfaceContainerLow)
        border(1.dp, jetpacsTokens.colors.outlineVariant)
        hovered {
            animate {
                background(jetpacsTokens.colors.surfaceContainer)
            }
        }
        focused {
            animate {
                border(2.dp, jetpacsTokens.colors.primary)
            }
        }
        pressed {
            animate {
                background(jetpacsTokens.colors.surfaceContainerHigh)
                scale(0.985f)
            }
        }
        disabled {
            alpha(0.38f)
        }
    }

    /** Style for one receiver-owned surface entry in the app catalog. */
    val catalogAction = interactiveContainer then Style {
        externalPadding(horizontal = 12.dp, vertical = 4.dp)
        contentPadding(horizontal = 16.dp, vertical = 14.dp)
    }

    /** Style for a full-row single-choice setting. */
    val choiceRow = interactiveContainer then Style {
        externalPadding(horizontal = 0.dp, vertical = 4.dp)
        contentPadding(horizontal = 12.dp, vertical = 8.dp)
        selected {
            animate {
                background(jetpacsTokens.colors.secondaryContainer)
                border(1.dp, jetpacsTokens.colors.secondary)
            }
        }
    }
}

/** Stable access point for receiver-owned design-system Styles. */
internal object JetpacsTheme {
    val components: JetpacsComponentStyles = JetpacsComponentStyles
}
