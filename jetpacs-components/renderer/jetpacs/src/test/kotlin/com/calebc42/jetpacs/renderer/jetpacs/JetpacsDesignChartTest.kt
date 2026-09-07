// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class JetpacsDesignChartTest {
    private fun node(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun ordinalExtentAndFlatSeriesRemainFinite() {
        val data = designChartData(node("""{"series":[{"points":[{"x":100,"y":4},{"x":900,"y":4}]}]}"""))
        assertEquals(2, data.count)
        assertEquals(4.0, data.min, 0.0)
        assertEquals(5.0, data.max, 0.0)
    }

    @Test fun zeroBaselineHandlesNegativeBarsAndExplicitRangeWins() {
        val negative = designChartData(node("""{"kind":"bar","series":[{"points":[{"x":0,"y":-5},{"x":1,"y":-2}]}]}"""))
        assertEquals(-5.0, negative.min, 0.0)
        assertEquals(0.0, negative.max, 0.0)
        val explicit = designChartData(node("""{"kind":"area","y_range":[10,20],"series":[{"points":[{"x":0,"y":15}]}]}"""))
        assertEquals(10.0, explicit.min, 0.0)
        assertEquals(20.0, explicit.max, 0.0)
    }

    @Test fun emptyChartHasFiniteDomainAndActionsKeepTheirHostSeam() {
        val empty = node("""{"series":[]}""")
        val data = designChartData(empty)
        assertEquals(0, data.count)
        assertTrue(data.max > data.min)
        assertTrue(JetpacsDesignChartRenderer.appliesTo(empty))
        assertFalse(JetpacsDesignChartRenderer.appliesTo(node("""{"series":[],"on_point_tap":{"action":"sample.select"}}""")))
    }
}
