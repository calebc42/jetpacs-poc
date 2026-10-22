// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.companion.host.KnownAnswerPairing
import com.calebc42.jetpacs.companion.host.pairingInitSnippet
import com.calebc42.jetpacs.core.ebpstore.CredentialProtection

/**
 * SPEC §9.1: the pairing values shown out of band, once. The token appears
 * only on the run that generated it; the reduced-protection notice appears
 * whenever the token rests on the file floor instead of a keychain; the
 * development credential is never shown without its warning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopPairingScreen(
    pairing: DesktopPairing,
    endpoint: String,
    port: Int,
    keychainReports: List<String>,
    onDone: () -> Unit,
    onCancel: (() -> Unit)?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pair Emacs") },
                navigationIcon = {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(24.dp)
                .widthIn(max = 720.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (pairing.isDevKnownAnswer) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ) {
                    Text(
                        "Development pairing: this Companion accepts the public SPEC " +
                            "known-answer credential. Anyone who can reach this machine's " +
                            "loopback interface can authenticate. Start without " +
                            "JETPACS_DEV_PAIRING for a real pairing.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text("Pair this Companion with Emacs", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Jetpacs Companion listens on $endpoint. Put these values in your Emacs " +
                    "init, then start Jetpacs in Emacs.",
                style = MaterialTheme.typography.bodyMedium,
            )
            LabeledCode("Pairing ID", pairing.pairingId.value)
            val token = pairing.freshTokenDisplay
                ?: if (pairing.isDevKnownAnswer) KnownAnswerPairing.TOKEN else null
            if (token != null) {
                LabeledCode("Pairing token", token)
                val snippet = pairingInitSnippet(pairing.pairingId.value, token, port)
                LabeledCode("Emacs init", snippet)
                OutlinedButton(onClick = { copyToSystemClipboard(snippet) }) {
                    Text("Copy the init snippet")
                }
            } else {
                Text(
                    "The pairing token was shown once, when this pairing was created, and " +
                        "is not stored anywhere it can be read back. To pair a different Emacs, " +
                        "start Jetpacs with a new data directory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val protection = pairing.protection) {
                is CredentialProtection.PrivateFile -> Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Reduced protection", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "No operating-system keychain answered" +
                                (if (keychainReports.isEmpty()) "" else " (" + keychainReports.joinToString("; ") + ")") +
                                ". The pairing token is stored in ${protection.path}, readable only " +
                                "by your user account. This is the floor SPEC §9.1 allows, and it " +
                                "asks that you know this when you pair.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is CredentialProtection.Keystore -> Text(
                    "The pairing token is kept by ${protection.backend}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("I have copied these")
            }
        }
    }
}

@Composable
private fun LabeledCode(label: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
