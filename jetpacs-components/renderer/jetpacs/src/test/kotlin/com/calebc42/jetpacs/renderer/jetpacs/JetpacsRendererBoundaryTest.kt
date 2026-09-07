// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JetpacsRendererBoundaryTest {
    private fun moduleDirectory(): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            File(directory, "renderer/jetpacs")
                .takeIf { File(it, "build.gradle.kts").isFile }
                ?.let { return it }
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
    fun theDesignToStyleTranslationStaysInOneAdapter() {
        // The invariant is NOT that few files touch androidx.compose.foundation
        // .style — thirteen presentation files legitimately do. It is that the
        // pure design model never becomes Compose, and that exactly one file
        // turns a compiled design value into a Style. An earlier form of this
        // test filtered by filename, so any new file escaped it by being
        // named something else; these assertions read every main source.
        val sources = File(moduleDirectory(), "src/main/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        fun named(name: String) = sources.single { it.name == name }.readText()

        val model = named("DesignModel.kt")
        assertFalse(model.contains("androidx.compose"))
        assertFalse(model.contains("android."))

        val translators = sources.filter {
            it.readText().contains("fun ComputedDesignStyle.toFoundationStyle")
        }
        assertEquals(
            listOf("JetpacsDesignStyleAdapter.kt"),
            translators.map(File::getName),
        )

        // The other half of the boundary: a presentation file consumes a
        // RESOLVED Style, text style or color, and never converts a raw
        // DesignValue itself. Querying whether a slot is bound is fine — that
        // is a presentation decision, not a translation — so the assertion is
        // about the value type, not about touching the scope.
        val converters = sources
            .filter { it.readText().contains("DesignValue.") }
            .map(File::getName)
            .sorted()
        assertEquals(
            listOf(
                "DesignModel.kt",
                "JetpacsDesignRenderer.kt",
                "JetpacsDesignStyleAdapter.kt",
            ),
            converters,
        )
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
