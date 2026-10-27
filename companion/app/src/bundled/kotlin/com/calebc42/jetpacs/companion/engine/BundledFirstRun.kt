// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The bundled flavor's whole first run (docs/PLAN-bundled-emacs.md, E3):
 * nothing to install, paste or pair. It carries the two sentences the plan
 * owes the user, the background-running policy (decision 1) and SPEC 9.1's
 * one-time notice that the engine's credential copy lives in application-
 * private storage rather than the device keystore.
 */
@Composable
internal fun BundledFirstRun(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Jetpacs", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your notes live in this app on this device. Nothing leaves it unless you export or share it.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "The notes engine keeps running in the background so reminders and widgets stay current. You can turn that off in Settings.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "The engine's pairing key is kept in this app's private storage, not in the device keystore.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone) { Text("Start") }
    }
}
