// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.compose.dropUnlessResumed
import com.calebc42.ebp.renderer.model.RendererActionContext
import com.calebc42.ebp.renderer.model.RendererActionRequest
import com.calebc42.jetpacs.companion.host.DeviceBridge
import com.calebc42.jetpacs.material3.RenderNode
import com.calebc42.jetpacs.material3.chromeBackDescriptor
import com.calebc42.jetpacs.material3.ui.JetpacsCatalogAction
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** The receiver's catalog of present app surfaces, or the waiting screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurfaceCatalogScreen(
    surfaceIds: List<String>,
    catalogLoaded: Boolean,
    connectedFlow: StateFlow<Boolean>,
    onSurface: (String) -> Unit,
    onSettings: () -> Unit,
    onRepair: () -> Unit,
) {
    val connected by connectedFlow.collectAsState()
    val openSettings = dropUnlessResumed { onSettings() }
    val repair = dropUnlessResumed { onRepair() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Jetpacs") },
                actions = {
                    IconButton(onClick = openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        if (surfaceIds.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (catalogLoaded) {
                    WaitingForEmacs(
                        description = LocalHostUiPlatform.current.listeningDescription,
                        onRepair = repair,
                    )
                } else {
                    Text(
                        "Loading cached apps…",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (connected) "Connected apps" else "Cached apps — Emacs is offline",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(
                    count = surfaceIds.size,
                    key = { surfaceIds[it] },
                ) { index ->
                    val surfaceId = surfaceIds[index]
                    val openSurface = dropUnlessResumed { onSurface(surfaceId) }
                    JetpacsCatalogAction(
                        headline = surfaceTitle(surfaceId),
                        supportingText = surfaceId,
                        onClick = openSurface,
                        leadingContent = {
                            Icon(Icons.Default.Apps, contentDescription = null)
                        },
                    )
                }
            }
        }
    }
}

private fun surfaceTitle(surfaceId: String): String =
    surfaceId.substringAfter(':')
        .split('.', '-', '_', '/')
        .filter(String::isNotEmpty)
        .joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) }
        .ifEmpty { surfaceId }

/** One Surface destination: the document and, when offline, the recovery chrome. */
@Composable
fun SurfaceDestination(
    surfaceId: String,
    surfaceFlow: StateFlow<JsonObject?>,
    connectedFlow: StateFlow<Boolean>,
    bridge: DeviceBridge,
    onSettings: () -> Unit,
    onRepair: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        SurfaceDocumentHost(surfaceId, surfaceFlow, connectedFlow, bridge, onRepair)
        ConnectionStatusHost(surfaceFlow, connectedFlow, onSettings)
    }
}

@Composable
private fun SurfaceDocumentHost(
    surfaceId: String,
    surfaceFlow: StateFlow<JsonObject?>,
    connectedFlow: StateFlow<Boolean>,
    bridge: DeviceBridge,
    onRepair: () -> Unit,
) {
    val shown by surfaceFlow.collectAsState()
    val connected by connectedFlow.collectAsState()
    val surfaceIncarnations by
        bridge.surfacePresentationIncarnations.collectAsState()
    when (val spec = shown) {
        null -> {
            val repair = dropUnlessResumed { onRepair() }
            WaitingForEmacs(
                description = LocalHostUiPlatform.current.listeningDescription,
                onRepair = repair,
            )
        }
        else -> key(surfaceIncarnations[surfaceId] ?: Long.MIN_VALUE) {
            // An authored in-surface back arrow outranks the platform's back.
            // The descriptor runs through the exact same view.switch path as a
            // tap; when absent, NavDisplay pops to the receiver catalog.
            val back = chromeBackDescriptor(
                spec,
                surfaceId = surfaceId,
                connected = connected,
            )
            val entryLifecycle by
                LocalLifecycleOwner.current.lifecycle.currentStateAsState()
            LocalHostUiPlatform.current.BackHandler(
                enabled = back != null && entryLifecycle == Lifecycle.State.RESUMED,
            ) {
                back?.let {
                    bridge.dispatch(
                        RendererActionRequest(
                            context = RendererActionContext.Surface(surfaceId),
                            descriptor = it,
                        ),
                    )
                }
            }
            RenderNode(
                spec,
                surfaceId,
                bridge,
                configuration = LocalCompanionRendererConfiguration.current,
            )
        }
    }
}

/** Show receiver-owned offline chrome without recomposing the surface tree. */
@Composable
private fun ConnectionStatusHost(
    surfaceFlow: StateFlow<JsonObject?>,
    connectedFlow: StateFlow<Boolean>,
    onSettings: () -> Unit,
) {
    val shown by surfaceFlow.collectAsState()
    val connected by connectedFlow.collectAsState()
    if (shown == null || connected) return

    val openSettings = dropUnlessResumed { onSettings() }
    val openEmacs = LocalHostUiPlatform.current.openEmacs?.let { open -> dropUnlessResumed { open() } }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        OfflineConnectionBanner(
            onSettings = openSettings,
            onOpenEmacs = openEmacs,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
        )
    }
}

/**
 * Receiver-owned recovery controls shown over a cached surface.
 *
 * These actions must not depend on the cached document: that document can be
 * the reason Emacs cannot reconnect. [onOpenEmacs] is null on a host that
 * must not launch Emacs (SPEC §5.8), and the button is omitted.
 */
@Composable
fun OfflineConnectionBanner(
    onSettings: () -> Unit,
    onOpenEmacs: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Cable, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text("Emacs is offline", style = MaterialTheme.typography.labelLarge)
                Text(
                    "Showing the last screen Emacs sent.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f),
                )
            }
            TextButton(onClick = onSettings) {
                Text("Settings", color = MaterialTheme.colorScheme.inversePrimary)
            }
            if (onOpenEmacs != null) {
                TextButton(onClick = onOpenEmacs) {
                    Text("Open Emacs", color = MaterialTheme.colorScheme.inversePrimary)
                }
            }
        }
    }
}

/** The empty state: the Companion's transport is up and Emacs has not pushed a surface. */
@Composable
fun WaitingForEmacs(description: String, onRepair: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Waiting for Emacs", style = MaterialTheme.typography.headlineMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        OutlinedButton(onClick = onRepair, modifier = Modifier.fillMaxWidth()) {
            Text("Set up or repair Jetpacs")
        }
    }
}
