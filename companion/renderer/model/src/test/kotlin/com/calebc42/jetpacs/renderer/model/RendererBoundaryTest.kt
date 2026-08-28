// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-level dependency guard for the renderer-neutral layers. */
class RendererBoundaryTest {
    @Test
    fun protocolModelAndCoreComposeDoNotImportMaterialOrStyles() {
        val companion = findCompanionRoot()
        val roots = listOf(
            "ebp-kmp/src",
            "wire/src",
            "core",
            "renderer/model/src/main",
            "renderer/compose/src/main",
        )
        val forbidden = listOf(
            "androidx.compose.material",
            "androidx.compose.foundation.style",
        )
        val offenders = roots.flatMap { relative ->
            File(companion, relative).walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "kts") }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        line.takeIf { text -> forbidden.any(text::contains) }
                            ?.let { "${file.relativeTo(companion)}:${index + 1}: $it" }
                    }
                }
                .toList()
        }
        assertTrue("neutral renderer boundary violations:\n${offenders.joinToString("\n")}",
            offenders.isEmpty())
    }

    private fun findCompanionRoot(): File {
        var directory: File? = File(
            requireNotNull(System.getProperty("user.dir")),
        ).absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile &&
                File(directory, "renderer").isDirectory) {
                return directory
            }
            directory = directory.parentFile
        }
        error("companion root not found")
    }
}
