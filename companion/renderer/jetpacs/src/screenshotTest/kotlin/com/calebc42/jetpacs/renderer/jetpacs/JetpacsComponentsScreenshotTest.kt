// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "compact", widthDp = 400, heightDp = 620)
@Preview(name = "expanded", widthDp = 900, heightDp = 620)
@Composable
private fun JetpacsComponentsAcrossWidths() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(
    name = "dark",
    widthDp = 400,
    heightDp = 620,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsComponentsDark() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(name = "large-text", widthDp = 400, heightDp = 720, fontScale = 1.5f)
@Composable
private fun JetpacsComponentsLargeText() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(name = "focus-hover", widthDp = 400, heightDp = 260)
@Composable
private fun JetpacsComponentsFocusAndHover() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsActionFocusFixture("Keyboard focus")
            JetpacsChoiceHoverFixture("Pointer hover")
        }
    }
}

/** Deterministic first-slice gallery shared by all baseline configurations. */
@Composable
private fun JetpacsComponentsGallery() {
    ProvideJetpacsTheme(null) {
        Column(
            Modifier
                .fillMaxSize()
                .background(JetpacsTheme.colors.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            JetpacsAction("Run ordinary action", onClick = {})
            JetpacsAction("Disabled action", onClick = {}, enabled = false)
            JetpacsChoice("Checked choice", checked = true, onCheckedChange = {})
            JetpacsChoice("Unchecked choice", checked = false, onCheckedChange = {})
            JetpacsPanel("STATUS") {
                BasicText("Ready", style = JetpacsTheme.typography.choice)
                JetpacsAction("Nested action", onClick = {})
            }
        }
    }
}
