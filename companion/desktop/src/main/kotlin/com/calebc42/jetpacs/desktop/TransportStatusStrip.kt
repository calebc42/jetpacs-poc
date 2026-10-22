// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.companion.host.TransportStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * The transport's state while it is not simply serving: a dialer between
 * attempts, and the terminal refusal SPEC §5.6 requires (an owner mismatch)
 * which must be visible, so it is also toasted once through [toasts].
 */
@Composable
fun TransportStatusStrip(status: StateFlow<TransportStatus>, toasts: DesktopToasts, now: () -> Long = System::currentTimeMillis) {
    val current by status.collectAsState()
    LaunchedEffect(current) {
        (current as? TransportStatus.Failed)?.let { toasts.show("Emacs transport refused: " + it.reason) }
    }
    val text = when (val state = current) {
        is TransportStatus.Dialing -> {
            val wait = state.nextAttemptAtMs?.let { ((it - now()) / 1000).coerceAtLeast(0) }
            if (wait == null) "Dialing the Emacs socket…"
            else "Emacs socket not answering (attempt ${state.attempt}); retrying in $wait s."
        }
        is TransportStatus.Failed -> "Emacs transport refused: " + state.reason
        else -> null
    } ?: return
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (current is TransportStatus.Failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (current is TransportStatus.Failed) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}
