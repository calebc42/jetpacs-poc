// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.calebc42.jetpacs.companion.host.CompanionPresentation
import com.calebc42.jetpacs.companion.host.DeviceBridge
import com.calebc42.jetpacs.material3.IconMap
import com.calebc42.jetpacs.material3.RenderDialogRoot
import com.calebc42.jetpacs.material3.RenderPieMenu
import com.calebc42.jetpacs.renderer.jetpacs.jetpacsDialogChrome
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * SPEC 14.1 `confirm`: the user confirms BEFORE the event is created.
 * Its own host for the same reason the others have theirs — a parked
 * confirmation must not recompose the surface tree.  A dismissal (scrim
 * or back) is a REFUSAL: the event is never created, which is the whole
 * point of a guarded destructive verb.
 */
@Composable
fun ConfirmHost(bridge: DeviceBridge) {
    val pending by bridge.pendingConfirm.collectAsState()
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { bridge.resolveConfirm(false) },
            // §14.1 object form: the face is authored; a bare string keeps
            // exactly the dialog this host always drew.
            icon = p.icon?.let {
                {
                    Icon(IconMap.get(it), contentDescription = null)
                }
            },
            title = p.title?.let { { Text(it) } },
            text = { Text(p.prompt) },
            confirmButton = {
                TextButton(onClick = { bridge.resolveConfirm(true) }) {
                    Text(p.confirmLabel ?: "OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { bridge.resolveConfirm(false) }) {
                    Text(p.dismissLabel ?: "Cancel")
                }
            },
        )
    }
}

/** SPEC 18.3: the one pie menu the process presents, over any screen. */
@Composable
fun PieMenuHost(
    flow: StateFlow<Pair<String, JsonObject>?>,
    bridge: DeviceBridge,
) {
    val pie by flow.collectAsState()
    pie?.let { (id, spec) -> RenderPieMenu(id, spec, bridge) }
}

/** SPEC 18.1: the one outstanding dialog, in a platform dialog window. */
@Composable
fun DialogHost(
    flow: StateFlow<CompanionPresentation.DialogShow?>,
    bridge: DeviceBridge,
) {
    val dialog by flow.collectAsState()
    dialog?.let { (id, dspec, epoch) ->
        // A dialog whose root is a design scope sizes and colors this
        // surface through its chrome.dialog slot; otherwise Material's.
        val chrome = remember(dspec) { jetpacsDialogChrome(dspec) }
        Dialog(
            // SPEC 18.1: a platform dismissal is a dismiss.
            onDismissRequest = { bridge.dismissDialog(id) },
            properties = DialogProperties(usePlatformDefaultWidth = chrome?.width == null),
        ) {
            chrome?.dim?.let { LocalHostUiPlatform.current.DialogDim(it) }
            Surface(
                modifier = chrome?.width?.let { Modifier.width(it) } ?: Modifier,
                shape = chrome?.corner?.let { RoundedCornerShape(it) }
                    ?: MaterialTheme.shapes.extraLarge,
                color = chrome?.container ?: MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                // The HOST container scrolls. SPEC 18.1 forbids lazy_column
                // NODES in a dialog spec, not the window scrolling — and
                // without this, any dialog taller than the window (a long
                // enum_list picker, stacked context cards) has UNREACHABLE
                // content below the fold. JC-4 prerequisite.
                //
                // heightIn is what makes verticalScroll work AT ALL here:
                // a Dialog measures its content with UNBOUNDED height, so a
                // scrolling column believes it has infinite room, never
                // scrolls, and the window is simply clipped by the screen.
                // Capping the height gives the scroll something to overflow.
                // A cap rather than fillMaxHeight so a short dialog still
                // wraps its content instead of always filling the screen.
                val maxDialogHeight = LocalHostUiPlatform.current.windowSizeDp().height * 0.8f
                Column(
                    Modifier
                        .heightIn(max = maxDialogHeight)
                        .verticalScroll(rememberScrollState())
                        .padding(chrome?.padding ?: 24.dp),
                ) {
                    RenderDialogRoot(
                        id,
                        dspec,
                        bridge,
                        epoch,
                        LocalCompanionRendererConfiguration.current,
                    )
                }
            }
        }
    }
}
