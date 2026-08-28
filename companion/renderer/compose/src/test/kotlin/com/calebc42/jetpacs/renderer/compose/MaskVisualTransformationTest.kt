// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.ui.text.AnnotatedString
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskVisualTransformationTest {
    @Test
    fun projectionIsScalarSafeAndLeavesOverflowVisible() {
        assertEquals("(1😀)😀-2", applyMask("1😀2", "(##)😀-#"))
        assertEquals("a-bc", applyMask("abc", "#-#"))
        assertEquals("", applyMask("", "prefix-#"))
    }

    @Test
    fun bothMappingsAreTotalAcrossAstralLiteralsAndStoredScalars() {
        val transformed = MaskVisualTransformation("😀##-#")
            .filter(AnnotatedString("1😀2"))
        assertEquals("😀1😀-2", transformed.text.text)
        assertEquals(listOf(2, 3, 4, 6, 7), (0..4).map {
            transformed.offsetMapping.originalToTransformed(it)
        })
        assertEquals(listOf(0, 0, 0, 1, 2, 3, 3, 4), (0..7).map {
            transformed.offsetMapping.transformedToOriginal(it)
        })
    }

    @Test
    fun productionProjectionMeetsFourTimesScalingGate() {
        val sizes = listOf(1_024, 4_096, 16_384, 65_536)
        // Warm every allocation shape and the JIT before sampling.
        sizes.forEach { size -> repeat(4) { sampleProjection(size) } }
        val samples = sizes.associateWith { size ->
            List(9) { sampleProjection(size) }
        }
        val timeMedians = samples.mapValues { (_, values) ->
            values.map(ProjectionSample::elapsedNanos).sorted()[4]
        }
        val allocationMedians = samples.mapValues { (_, values) ->
            values.map(ProjectionSample::allocatedBytes).sorted()[4]
        }
        println("mask projection medians (ns): $timeMedians")
        println("mask projection medians (allocated bytes): $allocationMedians")

        for ((smaller, larger) in sizes.zipWithNext()) {
            val ratio = timeMedians.getValue(larger).toDouble() /
                timeMedians.getValue(smaller).coerceAtLeast(1L)
            assertTrue(
                "$smaller->$larger mask projection grew ${"%.2f".format(ratio)}x",
                ratio <= 8.0,
            )
            val allocationRatio = allocationMedians.getValue(larger).toDouble() /
                allocationMedians.getValue(smaller).coerceAtLeast(1L)
            assertTrue(
                "$smaller->$larger allocations grew " +
                    "${"%.2f".format(allocationRatio)}x",
                allocationRatio <= 8.0,
            )
        }
        assertTrue(
            "64 KiB mask projection took ${timeMedians.getValue(65_536)} ns",
            timeMedians.getValue(65_536) < 2_000_000_000L,
        )
    }

    private fun sampleProjection(size: Int): ProjectionSample {
        val text = "x".repeat(size)
        val mask = alternatingMask(size)
        val transformation = MaskVisualTransformation(mask)
        val beforeBytes = allocationBean.currentThreadAllocatedBytes
        val started = System.nanoTime()
        val transformed = transformation.filter(AnnotatedString(text))
        val elapsed = System.nanoTime() - started
        val allocated = allocationBean.currentThreadAllocatedBytes - beforeBytes
        assertEquals(size * 2 - 1, transformed.text.length)
        assertEquals(
            transformed.text.length,
            transformed.offsetMapping.originalToTransformed(text.length),
        )
        return ProjectionSample(elapsed, allocated)
    }

    private fun applyMask(text: String, mask: String): String =
        MaskVisualTransformation(mask).filter(AnnotatedString(text)).text.text

    private fun alternatingMask(scalars: Int): String = buildString(scalars * 2) {
        repeat(scalars) { index ->
            append('#')
            if (index + 1 < scalars) append('-')
        }
    }

    private data class ProjectionSample(
        val elapsedNanos: Long,
        val allocatedBytes: Long,
    )

    private companion object {
        val allocationBean: ThreadMXBean =
            (ManagementFactory.getThreadMXBean() as ThreadMXBean).apply {
                check(isThreadAllocatedMemorySupported)
                isThreadAllocatedMemoryEnabled = true
            }
    }
}
