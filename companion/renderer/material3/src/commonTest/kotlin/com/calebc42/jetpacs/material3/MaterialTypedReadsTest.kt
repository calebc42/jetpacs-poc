// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard for the typed-member migration: a renderer file that has
 * been converted reads its node's members through `NodeProperties` and the
 * generated member sets, never through the raw `JsonObject` helpers. The
 * allowlist of unconverted files shrinks with each migration commit; item-level
 * reads inside structured members (`span.stringOr(...)`) are not matched.
 */
class MaterialTypedReadsTest {
    private val converted = listOf(
        "Attributes.kt", "ChromeBack.kt", "Chronometer.kt", "ContentNodes.kt", "InputNodes.kt", "LayoutNodes.kt",
        "MaterialComponents.kt", "Renderer.kt", "RetainedPresentationTracker.kt",
        "VisualizationNodes.kt",
    )
    private val remaining = emptyList<String>()
    private val rawRead = Regex(
        """\bnode\.(stringOr|stringOrNull|boolOr|boolOrNull|objOrNull|arrOrNull|doubleOr|dimInt|intByValue|numOrNull)\(|\bnode\[""",
    )

    /** The discriminator is not a member; reading `t` raw is the one allowed exception. */
    private val discriminatorRead = Regex("""\bnode\.stringOr(?:Null)?\("t"\)""")

    private fun sourceDirectory(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "src/commonMain/kotlin/com/calebc42/jetpacs/material3"
        while (dir != null) {
            for (candidate in listOf(File(dir, relative), File(dir, "renderer/material3/$relative"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("renderer sources not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun convertedFilesReadOnlyThroughTypedMembers() {
        val offenders = converted.flatMap { name ->
            File(sourceDirectory(), name).readLines().mapIndexedNotNull { index, line ->
                line.takeIf { rawRead.containsMatchIn(discriminatorRead.replace(it, "")) }
                    ?.let { "$name:${index + 1}: ${it.trim()}" }
            }
        }
        assertTrue("raw node reads in converted renderer files:\n${offenders.joinToString("\n")}",
            offenders.isEmpty())
    }

    @Test
    fun everyRendererFileIsEitherConvertedOrListedAsRemaining() {
        val renderers = sourceDirectory().listFiles()!!
            .filter { it.extension == "kt" && it.readText().contains("@Composable\ninternal fun Render") ||
                it.name in setOf("Attributes.kt", "Renderer.kt", "MaterialComponents.kt",
                    "ChromeBack.kt", "RetainedPresentationTracker.kt") }
            .map { it.name }
            .toSortedSet()
        assertEquals(renderers, (converted + remaining).toSortedSet())
    }
}
