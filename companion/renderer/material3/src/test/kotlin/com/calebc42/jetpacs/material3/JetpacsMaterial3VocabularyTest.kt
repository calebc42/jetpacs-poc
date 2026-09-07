// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.wire.SpecValidator
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pins generated endpoint metadata and examples to Jetpacs's manifest. */
class JetpacsMaterial3VocabularyTest {
    private fun repositoryFile(relative: String): File {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (directory != null) {
            listOf(
                File(directory, relative),
                File(directory, "../$relative"),
                File(directory, "../../$relative"),
            ).firstOrNull(File::isFile)?.let { return it.canonicalFile }
            directory = directory.parentFile
        }
        error("$relative not found from ${System.getProperty("user.dir")}")
    }

    @Test
    fun generatedVocabularyMatchesRendererManifest() {
        val manifest = Json.parseToJsonElement(
            repositoryFile("renderer-extensions/jetpacs-material3.json").readText(),
        ).jsonObject
        assertEquals(
            manifest.getValue("extension").jsonPrimitive.content,
            JETPACS_MATERIAL3_EXTENSION,
        )
        assertEquals(
            manifest.getValue("node_types").jsonArray.map { it.jsonPrimitive.content },
            JETPACS_MATERIAL3_NODE_SCHEMA.keys.toList(),
        )
        val schemas = manifest.getValue("node_schema").jsonObject
        for ((nodeType, generated) in JETPACS_MATERIAL3_NODE_SCHEMA) {
            val authored = schemas.getValue(nodeType).jsonObject
            fun strings(name: String): Set<String> =
                authored.getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()
            assertEquals("$nodeType required", strings("required"), generated.required)
            assertEquals("$nodeType optional", strings("optional"), generated.optional)
        }
        val targets = manifest.getValue("targets").jsonObject.mapValues { (_, value) ->
            value.jsonArray.map { it.jsonPrimitive.content }.toSet()
        }
        assertEquals(targets, JETPACS_MATERIAL3_TARGET_NODE_TYPES)
    }

    @Test
    fun rendererGoldenNodesPassInstalledAdmissionVocabulary() {
        repositoryFile("renderer-extensions/jetpacs-material3.golden")
            .readLines()
            .filter(String::isNotBlank)
            .forEachIndexed { index, line ->
                val node = Json.parseToJsonElement(line.substringAfter(' ')) as JsonObject
                SpecValidator.validateSurfaceSpec(
                    node,
                    path = "jetpacs-golden[$index]",
                    advertisedTypes = NodeSupport.APP_NODE_TYPES,
                    nodeVocabulary = NodeSupport.NODE_VOCABULARY,
                )
            }
    }
}
