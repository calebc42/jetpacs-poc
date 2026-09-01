// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.glance

import android.os.Parcel
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
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

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Test
    fun renderedAcceptanceFixturesStayInsideTheAdvertisedParcelBudget() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val composer = GlanceRemoteViews()
        listOf(
            fixture("00") to DpSize(180.dp, 110.dp),
            fixture("01") to DpSize(300.dp, 180.dp),
        ).forEach { (spec, size) ->
            val remoteViews = composer.compose(context, size) {
                GlanceTheme {
                    RenderGlanceWidget(
                        spec,
                        size.width.value,
                        size.height.value,
                        noActions,
                    )
                }
            }.remoteViews
            val parcel = Parcel.obtain()
            val bytes = try {
                remoteViews.writeToParcel(parcel, 0)
                parcel.dataSize()
            } finally {
                parcel.recycle()
            }
            assertTrue("fixture parcel was $bytes bytes", bytes in 1..716_800)
        }
    }

    private fun fixture(index: String): JsonObject {
        val line = File(
            checkNotNull(System.getProperty("ebp.dir")),
            "goldens/widget-surfaces.golden",
        ).useLines { lines -> lines.first { it.startsWith("$index ") } }
        return Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject
    }
}
