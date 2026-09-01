// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.glance

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroveWidgetFixtureTest {
    private val noActions = GlanceActionResolver { _, _ -> null }

    @Test
    fun captureFixtureProjectsItsCompactAffordance() = runGlanceAppWidgetUnitTest {
        val spec = fixture("00")
        setAppWidgetSize(DpSize(180.dp, 110.dp))
        provideComposable {
            GlanceTheme {
                RenderGlanceWidget(spec, 180f, 110f, noActions)
            }
        }

        onNode(hasText("\u2731  Capture")).assertExists()
        onNode(hasText("Capture unavailable")).assertDoesNotExist()
    }

    @Test
    fun agendaFixtureProjectsItsExpandedBody() = runGlanceAppWidgetUnitTest {
        val spec = fixture("01")
        setAppWidgetSize(DpSize(300.dp, 180.dp))
        provideComposable {
            GlanceTheme {
                RenderGlanceWidget(spec, 300f, 180f, noActions)
            }
        }

        onNode(hasText("2 today \u00b7 5 in 7 days")).assertExists()
        onNode(hasText("Agenda \u00b7 2 today")).assertDoesNotExist()
    }

    private fun fixture(index: String): JsonObject {
        val line = File(
            checkNotNull(System.getProperty("ebp.dir")),
            "goldens/widget-surfaces.golden",
        ).useLines { lines -> lines.first { it.startsWith("$index ") } }
        return Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject
    }
}
