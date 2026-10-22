// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The desktop Companion. Configuration comes from the command line and the
 * environment (see [DesktopConfig.parse]); the composition root is built on
 * an IO thread before the window opens, the transport starts (the loopback
 * listener, or the emacs-unix dialer the profile selects), and closing the
 * window stops it. A desktop Companion never launches or signals Emacs
 * (SPEC §5.8): the user pairs from the screen this window shows first.
 */
fun main(args: Array<String>) {
    val config = try {
        DesktopConfig.parse(args, System::getenv)
    } catch (failure: IllegalArgumentException) {
        System.err.println("jetpacs: " + failure.message)
        exitProcess(2)
    }
    if (System.getProperty("jetpacs.stages") == "true") {
        // The per-stage frame timer logs at FINE under this tag (DeviceBridge).
        Logger.getLogger("JetpacsStages").level = Level.FINE
    }
    if (config.devKnownAnswerPairing) {
        Logger.getLogger("Jetpacs").warning(
            "DEV PAIRING: accepting the public SPEC known-answer credential; anyone who can " +
                "reach its transport (" + config.endpoint + ") can authenticate",
        )
    }
    DesktopFonts.install()
    val companion = runBlocking(Dispatchers.IO) { DesktopCompanion.create(config) }
    companion.start()
    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = {
                companion.stop()
                exitApplication()
            },
            title = config.windowTitle,
            state = windowState,
        ) {
            CompanionWindow(
                companion = companion,
                windowSize = { windowState.size },
                onExit = {
                    companion.stop()
                    exitApplication()
                },
            )
        }
    }
}
