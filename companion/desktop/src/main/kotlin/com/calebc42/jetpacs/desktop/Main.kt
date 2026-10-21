// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** D0 entry point: one window hosting the throwaway [SpikeApp] screen. */
fun main() {
    DesktopFonts.install()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Jetpacs desktop spike",
            state = rememberWindowState(width = 1280.dp, height = 800.dp),
        ) {
            SpikeApp()
        }
    }
}
