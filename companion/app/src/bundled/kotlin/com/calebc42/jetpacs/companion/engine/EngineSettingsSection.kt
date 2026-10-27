// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.companion.host.DeviceBridge
import com.calebc42.jetpacs.companion.host.TransportStatus

/**
 * The bundled engine's sections at the top of Jetpacs Settings
 * (docs/PLAN-bundled-emacs.md, E5): the background-running policy and
 * where the notes live. Its state, SPEC 5.1's profile and address, and
 * the maintenance actions are [EngineDiagnostics], at the end. No Elisp
 * is evaluated from either (SPEC invariant 4).
 */
@Composable
internal fun EngineSettingsSection() {
    val context = LocalContext.current
    var background by remember { mutableStateOf(EngineSupervisor.backgroundEnabled(context)) }

    Text("Background", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Run in background")
            Text(
                "Keeps the notes engine available for reminders and widgets " +
                    "while the app is closed, with a notification. Off, it runs " +
                    "only while the app is open and is woken briefly for " +
                    "background events.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = background,
            onCheckedChange = { enabled ->
                background = enabled
                EngineSupervisor.setBackgroundEnabled(context, enabled)
            },
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("Notes", style = MaterialTheme.typography.titleSmall)
    VaultSettings()
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
}

/**
 * The engine's diagnostics, last in Jetpacs Settings: SPEC 5.1 asks a
 * bundled deployment to show its offered profile and address, beside what
 * the engine process and the session are doing right now, where the
 * notes are on disk, and the two maintenance actions.
 */
@Composable
internal fun EngineDiagnostics(bridge: DeviceBridge) {
    val context = LocalContext.current
    val connected by bridge.connected.collectAsState()
    val transport by bridge.transportStatus.collectAsState()
    val alive = EngineSupervisor.processAlive(context)
    val halted = EngineSupervisor.halted(context)
    val notes = EngineVault.current(EngineHome.of(context)).path

    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
    val transportText = when (val state = transport) {
        is TransportStatus.ListeningPath -> "loopback-unix at ${state.path}"
        is TransportStatus.Listening -> "loopback-tcp at 127.0.0.1:${state.port}"
        is TransportStatus.Connected -> "${bridge.transport.profile}, serving ${state.peer}"
        is TransportStatus.Failed -> "${bridge.transport.profile} refused: ${state.reason}"
        else -> "${bridge.transport.profile} at ${bridge.transport.address}, idle"
    }
    for (line in listOf(
        "Engine process: " + when {
            halted -> "stopped after repeated failures; opening the app again retries"
            alive -> "running"
            else -> "not running"
        } + ". Session: " + if (connected) "connected" else "not connected",
        "Transport: $transportText",
        "Notes folder: $notes",
    )) {
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { EngineSupervisor.restart(context) }) { Text("Restart engine") }
        TextButton(onClick = { EngineSupervisor.rebuildCache(context) }) { Text("Rebuild engine cache") }
    }
}
