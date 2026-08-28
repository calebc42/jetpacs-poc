// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorModelsTest {
    @Test
    fun scalarIndexConvertsAstralTextAndCanMoveBackwards() {
        val index = ScalarToUtf16Index("a😀bc")

        assertEquals(0, index.convert(0))
        assertEquals(1, index.convert(1))
        assertEquals(3, index.convert(2))
        assertEquals(5, index.convert(4))
        assertEquals(3, index.convert(2))
    }

    @Test
    fun spliceNeverSplitsASurrogatePair() {
        assertEquals(
            Utf16TextSplice(start = 1, deleted = 2, inserted = "!"),
            utf16TextSplice("a😀b", "a!b"),
        )
        assertEquals(
            Utf16TextSplice(start = 1, deleted = 2, inserted = "😃"),
            utf16TextSplice("a😀b", "a😃b"),
        )
        assertNull(utf16TextSplice("same", "same"))
    }

    @Test
    fun annotationParsersConvertScalarRangesToCompleteUtf16CodePoints() {
        val text = "a😀b"
        val fontify = parseFontify(
            buildJsonObject {
                put("session", "session-1")
                put("seq", 7)
                put("runs", buildJsonArray {
                    add(buildJsonObject {
                        put("start", 1)
                        put("end", 2)
                        put("role", "string")
                    })
                })
            },
            text,
        )
        val diagnostics = parseDiagnostics(
            buildJsonObject {
                put("session", "session-1")
                put("seq", 7)
                put("diagnostics", buildJsonArray {
                    add(buildJsonObject {
                        put("start", 1)
                        put("end", 1)
                        put("severity", "error")
                        put("message", "astral point")
                    })
                })
            },
            text,
        )

        assertEquals(FontifyRun(1, 3, "string"), fontify!!.runs.single())
        assertEquals(
            DiagnosticRange(1, 3, "error", "astral point"),
            diagnostics!!.diagnostics.single(),
        )
    }

    @Test
    fun shiftingRunsKeepsUnaffectedRangesAndMovesFollowingRanges() {
        val runs = listOf(
            FontifyRun(0, 3, "comment"),
            FontifyRun(4, 7, "string"),
        )

        assertEquals(
            listOf(
                FontifyRun(0, 4, "comment"),
                FontifyRun(5, 8, "string"),
            ),
            shiftFontifyRuns(runs, Utf16TextSplice(2, 0, "x")),
        )
    }
}
