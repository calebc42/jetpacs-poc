// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.ContentInvalid
import com.calebc42.ebp.wire.CORE_NODE_SET
import com.calebc42.ebp.wire.EBP_NODE_VOCABULARY
import com.calebc42.ebp.wire.NODE_SCHEMA
import com.calebc42.ebp.wire.SpecValidator
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JetpacsDesignVocabularyTest {
    private val vocabulary = EBP_NODE_VOCABULARY.copy(
        schema = NODE_SCHEMA + JETPACS_DESIGN_NODE_SCHEMA,
        extensions = mapOf(
            JETPACS_DESIGN_EXTENSION to JETPACS_DESIGN_NODE_SCHEMA.keys,
        ),
        extensionSchemas = JETPACS_DESIGN_TYPED_NODE_SCHEMA,
        extensionSemanticValidators = mapOf(
            JETPACS_DESIGN_EXTENSION to JetpacsDesignSemanticValidator,
        ),
    )
    private val advertised = CORE_NODE_SET + setOf("column", "icon") +
        JETPACS_DESIGN_NODE_SCHEMA.keys

    @Test
    fun generatedVocabularyMatchesFormatTwoManifest() {
        val manifest = Json.parseToJsonElement(
            repositoryFile("renderer-extensions/jetpacs-design.json").readText(),
        ).jsonObject

        assertEquals(2, manifest.getValue("format").jsonPrimitive.content.toInt())
        assertEquals(
            JETPACS_DESIGN_EXTENSION,
            manifest.getValue("extension").jsonPrimitive.content,
        )
        assertEquals(
            manifest.getValue("node_types").jsonArray.map { it.jsonPrimitive.content },
            JETPACS_DESIGN_NODE_SCHEMA.keys.toList(),
        )
        assertEquals(JETPACS_DESIGN_NODE_SCHEMA.keys, JETPACS_DESIGN_TYPED_NODE_SCHEMA.keys)
    }

    @Test
    fun advertisedDesignDocumentPassesStructuralAndSemanticAdmission() {
        SpecValidator.validateSurfaceSpec(
            designDocument(),
            path = "surface",
            advertisedTypes = advertised,
            nodeVocabulary = vocabulary,
        )
    }

    @Test
    fun semanticFailureRetainsTheOrdinaryContentInvalidPath() {
        val document = Json.parseToJsonElement(
            """
            {
              "t":"jetpacs.design_scope",
              "tokens":{},
              "styles":{"face":{"properties":{},"rules":[]}},
              "children":[{
                "t":"jetpacs.pressable",
                "styles":["missing"],
                "on_tap":{"action":"demo.run"},
                "children":[{"t":"text","text":"Run"}]
              }]
            }
            """,
        ) as JsonObject

        val failure = assertThrows(ContentInvalid::class.java) {
            SpecValidator.validateSurfaceSpec(
                document,
                path = "surface",
                advertisedTypes = advertised,
                nodeVocabulary = vocabulary,
            )
        }
        assertEquals("surface.children[0].styles[0]", failure.path)
    }

    private fun designDocument(): JsonObject = Json.parseToJsonElement(
        """
        {
          "t":"jetpacs.design_scope",
          "tokens":{
            "accent":{"kind":"color","value":"#315c49"},
            "space":{"kind":"dimension","value":"12"}
          },
          "motions":{"quick":{"duration_ms":120,"easing":"ease-out"}},
          "styles":{
            "button":{
              "motion":"quick",
              "properties":{
                "background_color":{"kind":"token","value":"accent"},
                "padding":{"kind":"token","value":"space"}
              },
              "rules":[
                {"state":"pressed","properties":{"scale":{"kind":"number","value":"0.97"}}}
              ]
            }
          },
          "children":[{
            "t":"jetpacs.pressable",
            "styles":["button"],
            "on_tap":{"action":"demo.run"},
            "enabled":true,
            "selected":false,
            "toggled":false,
            "children":[{"t":"text","text":"Run"}]
          }]
        }
        """,
    ) as JsonObject

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
        error("$relative not found")
    }
}
