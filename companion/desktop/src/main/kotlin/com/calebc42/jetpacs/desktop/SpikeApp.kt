// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.ui.NavDisplay
import com.calebc42.jetpacs.core.navigation.JetpacsNavKey

private const val SPIKE_PAIRING = "spike"

/**
 * D0 throwaway screen. Each region proves one dependency the later desktop
 * items rely on: JetBrains Material 3 Expressive, JetBrains adaptive panes,
 * Google's Navigation 3 runtime under JetBrains' NavDisplay with the shared
 * `:core:navigation` keys, a window-size report, and the `:wire` decoder.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SpikeApp(wireProof: String = remember { decodeHelloGolden() }) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val windowSize =
                    "window ${maxWidth.value.toInt()} x ${maxHeight.value.toInt()} dp"
                Row(Modifier.fillMaxSize()) {
                    SpikeRail()
                    Column(
                        Modifier.weight(1f).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            "Jetpacs desktop spike",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(windowSize, Modifier.testTag("window-size"))
                        Text(wireProof, Modifier.testTag("wire-proof"))
                        ExpressiveRow()
                        Box(Modifier.weight(1f)) { SpikeNavigation() }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpikeRail() {
    val railState = rememberWideNavigationRailState()
    var selected by remember { mutableIntStateOf(0) }
    WideNavigationRail(state = railState, modifier = Modifier.testTag("rail")) {
        listOf(
            "Hub" to Icons.Filled.Home,
            "Search" to Icons.Filled.Search,
            "Settings" to Icons.Filled.Settings,
        ).forEachIndexed { index, (label, icon) ->
            WideNavigationRailItem(
                railExpanded = false,
                selected = selected == index,
                onClick = { selected = index },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ExpressiveRow() {
    var dense by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(true) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.testTag("expressive"),
    ) {
        ButtonGroup(
            overflowIndicator = { state ->
                ButtonGroupDefaults.OverflowIndicator(menuState = state)
            },
        ) {
            toggleableItem(checked = dense, label = "Dense", onCheckedChange = { dense = it })
            toggleableItem(checked = !dense, label = "Roomy", onCheckedChange = { dense = !it })
        }
        ToggleButton(checked = pinned, onCheckedChange = { pinned = it }) {
            Text(if (pinned) "Pinned" else "Pin")
        }
        LoadingIndicator(progress = { 0.6f }, modifier = Modifier.size(40.dp))
        HorizontalFloatingToolbar(expanded = true) {
            IconButton(onClick = {}) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            IconButton(onClick = {}) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
    }
}

/** The Companion's own back-stack construction (`JetpacsNavigation.kt`). */
@Composable
private fun SpikeNavigation() {
    val backStack: NavBackStack<JetpacsNavKey> = rememberSerializable(
        serializer = NavBackStackSerializer<JetpacsNavKey>(),
    ) {
        NavBackStack<JetpacsNavKey>(JetpacsNavKey.Catalog(SPIKE_PAIRING))
    }
    NavDisplay(
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryProvider = entryProvider {
            entry<JetpacsNavKey.Catalog> {
                SpikePanes(onOpen = {
                    backStack.add(JetpacsNavKey.Surface(SPIKE_PAIRING, "app:hub"))
                })
            }
            entry<JetpacsNavKey.Surface> { key ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Surface ${key.surfaceId}", Modifier.testTag("surface-entry"))
                    Button(onClick = { backStack.removeAt(backStack.lastIndex) }) {
                        Text("Back")
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SpikePanes(onOpen: () -> Unit) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Any>()
    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        scaffoldState = navigator.scaffoldState,
        listPane = {
            AnimatedPane {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("List pane", Modifier.testTag("list-pane"))
                    Button(onClick = onOpen, modifier = Modifier.testTag("open-surface")) {
                        Text("Open app:hub")
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane { Text("Detail pane", Modifier.testTag("detail-pane")) }
        },
    )
}
