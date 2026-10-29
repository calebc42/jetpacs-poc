// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.calebc42.jetpacs.companion.host.SurfaceViews
import com.calebc42.jetpacs.companion.host.ui.PerViewState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The views of a multi-view surface render at one place in the composition,
 * so each needs its own saveable state: a pushed screen opens at its top,
 * Back finds the parent where the user left it, and a popped screen forgets
 * its position.
 */
class PerViewStateTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun eachViewKeepsItsOwnScroll() = runDesktopComposeUiTest(width = 400, height = 300) {
        var views by mutableStateOf<SurfaceViews?>(SurfaceViews("root", setOf("root")))
        var scroll: ScrollState? = null
        setContent {
            PerViewState(views) {
                val state = rememberScrollState()
                scroll = state
                Column(Modifier.height(300.dp).verticalScroll(state)) {
                    repeat(60) { Text("Row $it", Modifier.height(40.dp)) }
                }
            }
        }
        waitForIdle()
        runBlocking { scroll!!.scrollTo(900) }
        waitForIdle()

        views = SurfaceViews("child", setOf("root", "child"))
        waitForIdle()
        assertEquals("a pushed screen opens at its top", 0, scroll!!.value)
        runBlocking { scroll!!.scrollTo(400) }

        views = SurfaceViews("root", setOf("root"))
        waitForIdle()
        assertEquals("Back returns to the parent's position", 900, scroll!!.value)

        views = SurfaceViews("child", setOf("root", "child"))
        waitForIdle()
        assertEquals("a popped screen forgets its position", 0, scroll!!.value)

        views = null
        waitForIdle()
        runBlocking { scroll!!.scrollTo(200) }
        waitForIdle()
        assertEquals("a single-view surface scrolls as before", 200, scroll!!.value)
    }
}
