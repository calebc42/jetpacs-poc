// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JetpacsRendererBoundaryTest {
    private fun moduleDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            listOf(
                File(directory, "renderer/jetpacs"),
                File(directory, "companion/renderer/jetpacs"),
            ).firstOrNull { File(it, "build.gradle.kts").isFile }?.let { return it }
            directory = directory.parentFile
        }
        error("renderer/jetpacs not found")
    }

    @Test
    fun mainSourcesAndDependenciesRemainMaterialFree() {
        val module = moduleDirectory()
        val sources = File(module, "src/main").walkTopDown()
            .filter(File::isFile)
            .joinToString("\n") { it.readText() }
        val build = File(module, "build.gradle.kts").readText()

        assertFalse(sources.contains("androidx.compose.material"))
        assertFalse(sources.contains("renderer.material3"))
        assertFalse(build.contains("compose.material3"))
        assertFalse(build.contains("renderer.material3"))
        assertTrue(build.contains("androidx.compose.foundation"))
    }

    @Test
    fun maskedFieldsUseTheSharedExplicitOffsetMappingPath() {
        val source = File(
            moduleDirectory(),
            "src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/" +
                "JetpacsTextInputRenderer.kt",
        ).readText()

        assertTrue(source.contains("rememberLegacyTextInputAdapter(binding.controller)"))
        assertTrue(source.contains("MaskVisualTransformation(mask)"))
        assertTrue(source.contains("JetpacsMaskedTextField("))
        assertFalse(source.contains("MaskOutputTransformation"))
    }
}
