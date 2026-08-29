// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxProjectionTest {
    @Test
    fun elispProjectionKeepsRolesAndMultilineStringRange() {
        val source = "(defun demo () \"one\ntwo\") ; comment"
        val spans = projectSyntaxSpans("elisp", source)

        assertTrue(spans.any {
            it.role == SyntaxRole.Keyword && source.substring(it.start, it.end) == "defun"
        })
        assertTrue(spans.any {
            it.role == SyntaxRole.String &&
                source.substring(it.start, it.end) == "\"one\ntwo\""
        })
        assertTrue(spans.any {
            it.role == SyntaxRole.Comment && source.substring(it.start, it.end) == "; comment"
        })
    }

    @Test
    fun orgProjectionKeepsHeadingTodoTagAndInlineRoles() {
        val source = "* TODO Heading :work:\n- [[https://example.test][link]] and ~code~"
        val spans = projectSyntaxSpans("org", source)

        assertTrue(spans.any { it.role == SyntaxRole.Heading })
        assertTrue(spans.any { it.role == SyntaxRole.Todo })
        assertTrue(spans.any { it.role == SyntaxRole.Tag })
        assertTrue(spans.any { it.role == SyntaxRole.Constant })
        assertTrue(spans.any { it.role == SyntaxRole.Link && it.underline })
        assertTrue(spans.any { it.role == SyntaxRole.String })
    }

    @Test
    fun everySupportedFamilyProjectsAndUnknownLanguageDegrades() {
        val samples = mapOf(
            "python" to "del value # comment",
            "rust" to "fn main() { let value = 1; }",
            "shell" to "if true; then echo ok; fi",
            "h" to "int function(void);",
            "cpp" to "class Demo {};",
        )

        samples.forEach { (language, source) ->
            assertTrue("$language should project a role", projectSyntaxSpans(language, source).isNotEmpty())
        }
        assertEquals(emptyList<SyntaxSpan>(), projectSyntaxSpans("unknown", "plain"))
    }

    @Test
    fun projectionDoesNotStopAtTwentyThousandCharacters() {
        val source = "x".repeat(20_100) + "\n;; tail"
        assertTrue(projectSyntaxSpans("elisp", source).any { it.start > 20_000 })
    }

    @Test
    fun syntaxFallbackMeetsEditorScalingGate() {
        val sizes = listOf(1_024, 4_096, 16_384, 65_536)
        val sources = sizes.associateWith(::elispSource)
        sources.values.forEach { source -> repeat(4) { sampleProjection(source) } }
        val samples = sources.mapValues { (_, source) ->
            List(9) { sampleProjection(source) }
        }
        val times = samples.mapValues { (_, values) ->
            values.map(ProjectionSample::elapsedNanos).sorted()[4]
        }
        val allocations = samples.mapValues { (_, values) ->
            values.map(ProjectionSample::allocatedBytes).sorted()[4]
        }
        println("syntax projection medians (ns): $times")
        println("syntax projection medians (allocated bytes): $allocations")
        sizes.zipWithNext().forEach { (smaller, larger) ->
            val timeRatio = times.getValue(larger).toDouble() /
                times.getValue(smaller).coerceAtLeast(1)
            assertTrue("$smaller->$larger syntax time grew ${"%.2f".format(timeRatio)}x",
                timeRatio <= 8.0)
            val allocationRatio = allocations.getValue(larger).toDouble() /
                allocations.getValue(smaller).coerceAtLeast(1)
            assertTrue(
                "$smaller->$larger syntax allocations grew " +
                    "${"%.2f".format(allocationRatio)}x",
                allocationRatio <= 8.0,
            )
        }
        assertTrue(
            "64 KiB syntax projection took ${times.getValue(65_536)} ns",
            times.getValue(65_536) < 2_000_000_000L,
        )
    }

    private fun sampleProjection(source: String): ProjectionSample {
        val before = allocationBean.currentThreadAllocatedBytes
        val started = System.nanoTime()
        val spans = projectSyntaxSpans("elisp", source)
        val elapsed = System.nanoTime() - started
        val allocated = allocationBean.currentThreadAllocatedBytes - before
        assertTrue(spans.isNotEmpty())
        return ProjectionSample(elapsed, allocated)
    }

    private fun elispSource(size: Int): String {
        val unit = "(message \"x\") ; note\n"
        return buildString(size) {
            while (length + unit.length <= size) append(unit)
            append(";".repeat(size - length))
        }
    }

    private data class ProjectionSample(val elapsedNanos: Long, val allocatedBytes: Long)

    private companion object {
        val allocationBean: ThreadMXBean =
            (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply {
                check(isThreadAllocatedMemorySupported)
                isThreadAllocatedMemoryEnabled = true
            }
    }
}
