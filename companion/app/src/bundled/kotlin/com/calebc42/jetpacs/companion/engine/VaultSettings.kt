// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Notes" rows of the Engine section (docs/PLAN-bundled-emacs.md,
 * decision 2): where the vault is, "Export notes" through the system's
 * document picker, and the move between the app-private vault and the
 * shared `/sdcard/Jetpacs` folder, which first needs Android's All files
 * access granted on its system page. A move copies and never deletes.
 */
@Composable
internal fun VaultSettings() {
    val context = LocalContext.current
    val home = remember { EngineHome.of(context) }
    var mode by remember { mutableStateOf(EngineVault.mode(home)) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        EngineExport.zipVault(EngineVault.current(home), output)
                    } ?: error("cannot open the chosen location")
                }
            }
            Toast.makeText(
                context,
                result.fold({ "Exported $it notes files" }, { "Export failed: ${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
    fun move(target: EngineVault.Mode) {
        busy = true
        scope.launch {
            val copied = withContext(Dispatchers.IO) {
                runCatching { EngineSupervisor.switchVault(context, target) }
            }
            busy = false
            mode = EngineVault.mode(home)
            Toast.makeText(
                context,
                copied.fold(
                    { "Notes now in ${EngineVault.current(home).path}; $it files copied" },
                    { "Could not move the notes: ${it.message}" },
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Text(
        "Notes: " + when (mode) {
            EngineVault.Mode.PRIVATE -> "private to this app (${EngineVault.privateDir(home).path})"
            EngineVault.Mode.SHARED -> "shared folder ${EngineVault.sharedDir().path}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            enabled = !busy,
            onClick = { exportLauncher.launch("jetpacs-notes.zip") },
        ) { Text("Export notes") }
        when (mode) {
            EngineVault.Mode.PRIVATE ->
                if (EngineVault.sharedAccessGranted()) {
                    TextButton(enabled = !busy, onClick = { move(EngineVault.Mode.SHARED) }) {
                        Text("Store notes in a shared folder")
                    }
                } else {
                    TextButton(
                        enabled = !busy,
                        onClick = { context.startActivity(EngineVault.sharedAccessIntent(context)) },
                    ) { Text("Allow a shared folder") }
                }
            EngineVault.Mode.SHARED ->
                TextButton(enabled = !busy, onClick = { move(EngineVault.Mode.PRIVATE) }) {
                    Text("Keep notes private")
                }
        }
    }
}
