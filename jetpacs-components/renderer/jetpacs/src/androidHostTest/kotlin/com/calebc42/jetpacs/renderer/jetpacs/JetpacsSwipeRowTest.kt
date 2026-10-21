// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wire reading a Foundation row shares with the Material one. */
class JetpacsSwipeRowTest {
    private fun swipe(json: String) = Json.parseToJsonElement(json) as JsonObject

    @Test
    fun theLegacyShapeIsOneActionThatCommitsOnRelease() {
        val side = jetpacsSwipeSide(
            swipe("""{"label":"Dismiss","icon":"close","color":"error",
                      "on_trigger":{"action":"demo.dismiss"}}"""),
        )!!
        assertEquals(1, side.actions.size)
        assertEquals("Dismiss", side.actions[0].label)
        assertEquals("close", side.actions[0].icon)
        assertEquals("error", side.actions[0].color)
        assertTrue(side.behavior.legacyCommitOnRelease)
        assertEquals(false, side.behavior.commitFirstOnDeepSwipe)
    }

    @Test
    fun theRichShapeRevealsAndOptsIntoTheDeepCommit() {
        val revealOnly = jetpacsSwipeSide(
            swipe("""{"actions":[
                       {"label":"A","on_trigger":{"action":"a"}},
                       {"label":"B","on_trigger":{"action":"b"}}]}"""),
        )!!
        assertEquals(2, revealOnly.actions.size)
        assertEquals(false, revealOnly.behavior.legacyCommitOnRelease)
        assertEquals(false, revealOnly.behavior.commitFirstOnDeepSwipe)

        val committing = jetpacsSwipeSide(
            swipe("""{"commit":true,"actions":[
                       {"label":"Done","on_trigger":{"action":"done"}},
                       {"label":"Plan","on_trigger":{"action":"plan"}}]}"""),
        )!!
        assertTrue(committing.behavior.commitFirstOnDeepSwipe)
    }

    @Test
    fun anActionThatCannotRunIsNotDrawnAndAnEmptySideIsNotSwipeable() {
        val side = jetpacsSwipeSide(
            swipe("""{"actions":[
                       {"label":"Dead"},
                       {"label":"Live","on_trigger":{"action":"live"}}]}"""),
        )!!
        assertEquals(listOf("Live"), side.actions.map { it.label })
        assertEquals(1, side.behavior.actionCount)

        assertNull(jetpacsSwipeSide(swipe("""{"actions":[{"label":"Dead"}]}""")))
        assertNull(jetpacsSwipeSide(swipe("""{"actions":[]}""")))
        assertNull(jetpacsSwipeSide(null))
    }

    @Test
    fun theSideIsBoundedAtTheContractsFourActions() {
        val side = jetpacsSwipeSide(
            swipe(
                """{"actions":[
                     {"label":"1","on_trigger":{"action":"a"}},
                     {"label":"2","on_trigger":{"action":"b"}},
                     {"label":"3","on_trigger":{"action":"c"}},
                     {"label":"4","on_trigger":{"action":"d"}},
                     {"label":"5","on_trigger":{"action":"e"}}]}""",
            ),
        )!!
        assertEquals(4, side.behavior.actionCount)
    }

    @Test
    fun theFoundationAndMaterialStripsReadTheSameSide() {
        // Both chromes adapt the wire through their own reader; if they ever
        // disagree the same authored row would travel differently depending on
        // which renderer drew it. The shared cell width is one definition now,
        // so only the behavior mapping needs pinning here.
        val table = listOf(
            """{"label":"L","on_trigger":{"action":"a"}}""" to Triple(1, false, true),
            """{"actions":[{"label":"A","on_trigger":{"action":"a"}}]}""" to
                Triple(1, false, false),
            """{"commit":true,"actions":[
                 {"label":"A","on_trigger":{"action":"a"}},
                 {"label":"B","on_trigger":{"action":"b"}}]}""" to Triple(2, true, false),
        )
        table.forEach { (json, expected) ->
            val side = jetpacsSwipeSide(swipe(json))!!.behavior
            assertEquals(expected.first, side.actionCount)
            assertEquals(expected.second, side.commitFirstOnDeepSwipe)
            assertEquals(expected.third, side.legacyCommitOnRelease)
        }
    }
}
