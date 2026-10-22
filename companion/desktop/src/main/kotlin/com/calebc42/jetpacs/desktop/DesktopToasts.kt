// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SPEC 18.2 toasts and the bridge's queue diagnostics on a desktop, which has
 * no system toast: the latest message, shown briefly over the window. The
 * epoch keeps two identical texts in a row from collapsing into one.
 */
class DesktopToasts {
    data class Toast(val text: String, val epoch: Long)

    private val epoch = AtomicLong()
    private val _current = MutableStateFlow<Toast?>(null)
    val current: StateFlow<Toast?> get() = _current

    fun show(text: String) {
        _current.value = Toast(text, epoch.incrementAndGet())
    }

    fun dismiss(toastEpoch: Long) {
        if (_current.value?.epoch == toastEpoch) _current.value = null
    }
}

/** Shows the current toast for a few seconds at the bottom of the window. */
@Composable
fun ToastHost(toasts: DesktopToasts) {
    val toast by toasts.current.collectAsState()
    toast?.let { shown ->
        LaunchedEffect(shown.epoch) {
            delay(TOAST_MILLIS)
            toasts.dismiss(shown.epoch)
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Snackbar(modifier = Modifier.widthIn(max = 560.dp)) { Text(shown.text) }
        }
    }
}

private const val TOAST_MILLIS = 3_500L
