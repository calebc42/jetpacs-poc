// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonthGridDayStyleTest {
    @Test
    fun dayGeometryIsBoundedAcrossWindowWidths() {
        assertEquals(48.dp, MonthGridDayCellHeight)
        assertEquals(40.dp, MonthGridDayVisualSize)
    }

    @Test
    fun exactDateSelectsOnlyItsAuthoredPalette() {
        val styles = Json.parseToJsonElement("""{
            "2026-09-04":{"background":"primary","foreground":"on_primary"},
            "2026-09-08":{"background":"error","foreground":"on_error"}
        }""") as JsonObject

        assertEquals(
            MonthGridDayStyle("primary", "on_primary"),
            monthGridDayStyle(styles, "2026-09-04"),
        )
        assertEquals(
            MonthGridDayStyle("error", "on_error"),
            monthGridDayStyle(styles, "2026-09-08"),
        )
        assertNull(monthGridDayStyle(styles, "2026-09-05"))
    }

    @Test
    fun explicitDotColorWinsThenStyledForegroundThenLegacyFill() {
        val explicit = Color.Red
        val styledForeground = Color.White
        val primary = Color.Blue
        val onPrimary = Color.Yellow

        assertEquals(explicit, monthGridDotColor(
            explicit, styledForeground, true, primary, onPrimary))
        assertEquals(styledForeground, monthGridDotColor(
            null, styledForeground, true, primary, onPrimary))
        assertEquals(onPrimary, monthGridDotColor(
            null, null, true, primary, onPrimary))
        assertEquals(primary, monthGridDotColor(
            null, null, false, primary, onPrimary))
    }
}
