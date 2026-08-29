// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import com.calebc42.ebp.wire.CompletionNarrowing
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun completionNarrowingPreservesWireIndicesAndDocumentEpoch() {
        val candidates = listOf(
            CompletionCandidate("alpha", null, "alpha"),
            CompletionCandidate("beta", null, "alphabet"),
            CompletionCandidate("gamma", null, "gamma"),
        )
        val visible = narrowedCompletionCandidates(
            candidates,
            active = true,
            extendedPrefix = "alph",
            extension = "h",
            narrowing = CompletionNarrowing.STRICT,
        )

        assertEquals(listOf(0, 1), visible.map { it.index })
        val document = CandidateDocument(index = 1, text = "Docs", epoch = 8)
        assertTrue(candidateDocumentVisible(document, 8, visible.map { it.index }))
        assertTrue(!candidateDocumentVisible(document, 9, visible.map { it.index }))
        assertTrue(!candidateDocumentVisible(document.copy(text = ""), 8, listOf(1)))
    }

    @Test
    fun annotationProjectionShiftsOnlyBoundedFontifyAndStrictlyGatesDiagnostics() {
        val fontify = FontifySet(
            session = "session",
            sequence = 3,
            text = "alpha beta",
            runs = listOf(FontifyRun(6, 10, "variable")),
        )
        assertEquals(
            listOf(FontifyRun(7, 11, "variable")),
            currentFontifyRuns(fontify, "xalpha beta"),
        )
        assertNull(currentFontifyRuns(fontify, "x".repeat(FONTIFY_SHIFT_MAX_EDIT + 1)))

        val diagnostics = DiagnosticSet(
            session = "session",
            sequence = 3,
            text = "alpha beta",
            diagnostics = listOf(DiagnosticRange(0, 5, "error", "bad alpha")),
        )
        assertEquals(diagnostics.diagnostics, currentDiagnostics(diagnostics, "alpha beta"))
        assertNull(currentDiagnostics(diagnostics, "alpha beta!"))

        val mirror = EditorMirror("alpha beta", 0, 0, 0, sequence = 3, epoch = 1)
        val eldoc = EldocLine("session", sequence = 3, text = "Alpha docs")
        assertEquals(eldoc, currentEldoc(eldoc, mirror, "alpha beta"))
        assertNull(currentEldoc(eldoc, mirror.copy(sequence = 4), "alpha beta"))
        assertNull(currentEldoc(eldoc, mirror, "alpha beta!"))
    }

    @Test
    fun authoritativeFontifyProjectionMeetsEditorScalingGate() {
        val sizes = listOf(1_024, 4_096, 16_384, 65_536)
        val fixtures = sizes.associateWith(::fontifyFixture)
        fixtures.values.forEach { fixture -> repeat(4) { sampleFontify(fixture) } }
        val samples = fixtures.mapValues { (_, fixture) ->
            List(9) { sampleFontify(fixture) }
        }
        val times = samples.mapValues { (_, values) ->
            values.map(ProjectionSample::elapsedNanos).sorted()[4]
        }
        val allocations = samples.mapValues { (_, values) ->
            values.map(ProjectionSample::allocatedBytes).sorted()[4]
        }
        println("fontify projection medians (ns): $times")
        println("fontify projection medians (allocated bytes): $allocations")
        sizes.zipWithNext().forEach { (smaller, larger) ->
            val timeRatio = times.getValue(larger).toDouble() /
                times.getValue(smaller).coerceAtLeast(1)
            assertTrue("$smaller->$larger fontify time grew ${"%.2f".format(timeRatio)}x",
                timeRatio <= 8.0)
            val allocationRatio = allocations.getValue(larger).toDouble() /
                allocations.getValue(smaller).coerceAtLeast(1)
            assertTrue(
                "$smaller->$larger fontify allocations grew " +
                    "${"%.2f".format(allocationRatio)}x",
                allocationRatio <= 8.0,
            )
        }
        assertTrue(
            "64 KiB fontify projection took ${times.getValue(65_536)} ns",
            times.getValue(65_536) < 2_000_000_000L,
        )
    }

    private fun fontifyFixture(size: Int): FontifyFixture {
        val base = "x".repeat(size)
        val runs = (0 until size step 16).map { start ->
            FontifyRun(start, minOf(start + 8, size), "variable")
        }
        return FontifyFixture(
            FontifySet("session", 1, base, runs),
            base.dropLast(1) + "y",
            runs.size,
        )
    }

    private fun sampleFontify(fixture: FontifyFixture): ProjectionSample {
        val before = allocationBean.currentThreadAllocatedBytes
        val started = System.nanoTime()
        val projected = currentFontifyRuns(fixture.fontify, fixture.source)
        val elapsed = System.nanoTime() - started
        val allocated = allocationBean.currentThreadAllocatedBytes - before
        assertEquals(fixture.expectedRuns, projected?.size)
        return ProjectionSample(elapsed, allocated)
    }

    private data class FontifyFixture(
        val fontify: FontifySet,
        val source: String,
        val expectedRuns: Int,
    )

    private data class ProjectionSample(val elapsedNanos: Long, val allocatedBytes: Long)

    private companion object {
        val allocationBean: ThreadMXBean =
            (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply {
                check(isThreadAllocatedMemorySupported)
                isThreadAllocatedMemoryEnabled = true
            }
    }
}
