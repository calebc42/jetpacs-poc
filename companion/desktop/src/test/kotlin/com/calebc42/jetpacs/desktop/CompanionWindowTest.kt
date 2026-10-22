// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.core.ebpstore.PrivateFileSecretStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window on a first run: the pairing screen with the generated values,
 * the port and the init snippet, then, once acknowledged, the catalog
 * waiting for Emacs at the listening address. Headless; writes
 * `build/spike/pairing.png` and `build/spike/window.png`.
 */
class CompanionWindowTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theFirstRunShowsThePairingAndThenWaitsForEmacs() {
        val root = Files.createTempDirectory("jetpacs-desktop-window-")
        val data = root.resolve("data")
        DesktopFonts.install()
        val companion = runBlocking {
            DesktopCompanion.create(
                DesktopConfig(data, port = 8766),
                Executor { it.run() },
                PrivateFileSecretStore(data.resolve(PrivateFileSecretStore.FILE_NAME)),
            )
        }
        try {
            val out = File(System.getProperty("jetpacs.spike.out") ?: "build/spike").apply { mkdirs() }
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    CompanionWindow(companion, { DpSize(1280.dp, 800.dp) }, onExit = {})
                }
                onNodeWithText("Pairing ID").assertIsDisplayed()
                onNodeWithText(companion.pairing.pairingId.value).assertIsDisplayed()
                onNodeWithText(companion.pairing.freshTokenDisplay!!).assertIsDisplayed()
                onAllNodesWithText("127.0.0.1:8766", substring = true).onFirst().assertIsDisplayed()
                onNodeWithText("jetpacs-port 8766", substring = true).assertIsDisplayed()
                onNodeWithText("Reduced protection").assertIsDisplayed()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "pairing.png"))

                onNodeWithText("I have copied these").performScrollTo().performClick()

                onNodeWithText("Waiting for Emacs").assertIsDisplayed()
                onNodeWithText("Jetpacs Companion is listening on 127.0.0.1:8766.").assertIsDisplayed()
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "window.png"))
            }
            assertTrue(companion.settings.onboardingComplete)
        } finally {
            runBlocking { companion.shutdown() }
            root.toFile().deleteRecursively()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun anEmacsUnixRunNamesTheProfileAndTheSocket() {
        val root = Files.createTempDirectory("ebp-")
        val data = root.resolve("data")
        val socket = root.resolve("ebp.sock")
        DesktopFonts.install()
        val companion = runBlocking {
            DesktopCompanion.create(
                DesktopConfig(data, transport = TransportProfile.EMACS_UNIX, socket = socket),
                Executor { it.run() },
                PrivateFileSecretStore(data.resolve(PrivateFileSecretStore.FILE_NAME)),
            )
        }
        try {
            runDesktopComposeUiTest(width = 1280, height = 800) {
                setContent {
                    CompanionWindow(companion, { DpSize(1280.dp, 800.dp) }, onExit = {})
                }
                onAllNodesWithText("emacs-unix profile", substring = true).onFirst().assertIsDisplayed()
                onAllNodesWithText(socket.toString(), substring = true).onFirst().assertIsDisplayed()
                onNodeWithText("jetpacs-transport 'emacs-unix", substring = true).assertIsDisplayed()
                onNodeWithText("jetpacs-socket \"$socket\"", substring = true).assertIsDisplayed()
                onNodeWithText("jetpacs-port", substring = true).assertDoesNotExist()
                onNodeWithText("I have copied these").performScrollTo().performClick()
                onNodeWithText("Jetpacs Companion dials the Emacs socket $socket (emacs-unix).").assertIsDisplayed()
            }
        } finally {
            runBlocking { companion.shutdown() }
            root.toFile().deleteRecursively()
        }
    }
}
