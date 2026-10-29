// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.calebc42.jetpacs.companion.host.fonts.PickedFontFile
import com.calebc42.jetpacs.companion.host.fonts.UserFontStore
import com.calebc42.jetpacs.companion.host.fonts.UserFonts
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import com.calebc42.jetpacs.companion.host.CompanionRenderer
import com.calebc42.jetpacs.companion.host.HOST_BUILTINS
import com.calebc42.jetpacs.companion.host.PlatformRendererTargets
import com.calebc42.jetpacs.companion.host.ui.HostUiPlatform

/**
 * The desktop half of the shared window composition: the window's own size,
 * no system back gesture, and no Emacs launch (SPEC §5.8 forbids a desktop
 * Companion from launching or signalling Emacs, so the offline chrome has
 * no such button here).
 */
class DesktopHostUi(
    private val windowSize: () -> DpSize,
    override val listeningEndpoint: String,
    override val listeningDescription: String,
    override val userFonts: UserFonts? = null,
) : HostUiPlatform {
    @Composable
    override fun BackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

    @Composable
    override fun windowSizeDp(): DpSize = windowSize()

    override val openEmacs: (() -> Unit)? = null

    /**
     * The AWT file dialog, modal on the event thread the window already runs
     * on, filtered to the two extensions the store accepts; the file is read,
     * bounded to one byte over the store's limit, off that thread.
     */
    @Composable
    override fun rememberFontFilePicker(onPicked: (PickedFontFile?) -> Unit): (() -> Unit)? {
        val current = rememberUpdatedState(onPicked)
        val scope = rememberCoroutineScope()
        return {
            val dialog = FileDialog(null as Frame?, "Add a font", FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.endsWith(".ttf", true) || name.endsWith(".otf", true) }
            dialog.isVisible = true
            val file = dialog.files.firstOrNull()
            if (file == null) {
                current.value(null)
            } else {
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { file.inputStream().use { it.readNBytes(UserFontStore.MAX_FILE_BYTES + 1) } }
                            .getOrNull()
                    }
                    current.value(bytes?.let { PickedFontFile(file.name, it) })
                }
            }
        }
    }
}

/**
 * The desktop's renderer installation: the shared app and dialog profiles,
 * no notification, widget or tile target, and every host builtin except
 * `share.send`, which has no desktop share sheet behind it.
 */
object DesktopCompanionRenderer : CompanionRenderer(
    PlatformRendererTargets(
        notification = null,
        widget = null,
        tile = null,
        hostBuiltins = HOST_BUILTINS - "share.send",
    ),
)
