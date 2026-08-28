// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.calebc42.ebp.companion.render.EbpTheme

/** Compact/medium/expanded width crossed with short/medium/tall height. */
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
@Preview(name = "compact-short", widthDp = 400, heightDp = 400)
@Preview(name = "compact-medium", widthDp = 400, heightDp = 500)
@Preview(name = "compact-tall", widthDp = 400, heightDp = 1000)
@Preview(name = "medium-short", widthDp = 610, heightDp = 400)
@Preview(name = "medium-medium", widthDp = 610, heightDp = 500)
@Preview(name = "medium-tall", widthDp = 610, heightDp = 1000)
@Preview(name = "expanded-short", widthDp = 900, heightDp = 400)
@Preview(name = "expanded-medium", widthDp = 900, heightDp = 500)
@Preview(name = "expanded-tall", widthDp = 900, heightDp = 1000)
private annotation class JetpacsScreenSizes

@PreviewTest
@JetpacsScreenSizes
@Composable
private fun JetpacsComponentsAcrossWindowSizes() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(
    name = "dark-medium",
    widthDp = 610,
    heightDp = 500,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun JetpacsComponentsDark() {
    JetpacsComponentsGallery()
}

@PreviewTest
@Preview(
    name = "large-text-compact",
    widthDp = 400,
    heightDp = 500,
    fontScale = 1.5f,
)
@Composable
private fun JetpacsComponentsLargeText() {
    JetpacsComponentsGallery()
}

/** Deterministic gallery shared by every screenshot configuration. */
@Composable
private fun JetpacsComponentsGallery() {
    EbpTheme(payload = null) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 12.dp),
            ) {
                JetpacsCatalogAction(
                    headline = "Files",
                    supportingText = "app:files",
                    onClick = {},
                    leadingContent = {
                        Icon(Icons.Default.Apps, contentDescription = null)
                    },
                )
                JetpacsCatalogAction(
                    headline = "Offline notes",
                    supportingText = "Cached from Emacs",
                    enabled = false,
                    onClick = {},
                    leadingContent = {
                        Icon(Icons.Default.Apps, contentDescription = null)
                    },
                )
                Column(
                    modifier = Modifier
                        .selectableGroup()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    JetpacsChoiceRow(
                        label = "Strict",
                        supportingText = "Candidates start with what you typed",
                        selected = true,
                        onClick = {},
                    )
                    JetpacsChoiceRow(
                        label = "Contains",
                        supportingText = "Candidates match anywhere",
                        selected = false,
                        onClick = {},
                    )
                }
            }
        }
    }
}
