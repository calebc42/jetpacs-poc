// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.ContentInvalid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignModelTest {
    @After
    fun clearCache() {
        DesignModel.clearCacheForTest()
    }

    @Test
    fun canonicalizationIgnoresObjectOrderAndCacheReusesCompilation() {
        val first = scope(
            """
            {
              "tokens": {
                "space": {"kind":"dimension","value":"8"},
                "ink": {"kind":"color","value":"#112233"}
              },
              "styles": {
                "body": {
                  "properties": {
                    "padding": {"kind":"token","value":"space"},
                    "content_color": {"kind":"token","value":"ink"}
                  },
                  "rules": []
                }
              },
              "children": []
            }
            """,
        )
        val reordered = scope(
            """
            {
              "children": [],
              "styles": {
                "body": {
                  "rules": [],
                  "properties": {
                    "content_color": {"value":"ink","kind":"token"},
                    "padding": {"value":"space","kind":"token"}
                  }
                }
              },
              "tokens": {
                "ink": {"value":"#112233","kind":"color"},
                "space": {"value":"8","kind":"dimension"}
              }
            }
            """,
        )

        val compiledFirst = DesignModel.compileScope(first)
        val compiledAgain = DesignModel.compileScope(reordered)

        assertEquals(compiledFirst.canonicalContent, compiledAgain.canonicalContent)
        assertSame(compiledFirst, compiledAgain)
    }

    @Test
    fun nestedScopeReplacesIdentifiersAndReresolvesInheritedStyles() {
        val parent = DesignModel.compileScope(
            scope(
                """
                {
                  "tokens": {"accent":{"kind":"color","value":"#aa0000"}},
                  "styles": {
                    "filled": {
                      "properties": {"background_color":{"kind":"token","value":"accent"}},
                      "rules": []
                    }
                  },
                  "children": []
                }
                """,
            ),
            path = "root",
        )
        val child = DesignModel.compileScope(
            scope(
                """
                {
                  "tokens": {"accent":{"kind":"color","value":"#0000bb"}},
                  "styles": {},
                  "children": []
                }
                """,
            ),
            parent,
            "root.children[0]",
        )

        assertEquals(
            DesignValue.ColorValue(0xff0000bbL),
            child.computedStyle(listOf("filled"), "node.styles")
                .resolve().properties[DesignProperty.BackgroundColor],
        )
    }

    @Test
    fun styleAndRulePrecedenceIsAuthoredAndLastValueWins() {
        val compiled = DesignModel.compileScope(
            scope(
                """
                {
                  "tokens": {},
                  "motions": {
                    "quick":{"duration_ms":90,"easing":"ease-out"},
                    "slow":{"duration_ms":220,"easing":"spring"}
                  },
                  "styles": {
                    "first": {
                      "motion":"slow",
                      "properties": {
                        "alpha":{"kind":"number","value":"0.9"},
                        "background_color":{"kind":"color","value":"#111111"}
                      },
                      "rules": [
                        {"state":"selected","properties":{"alpha":{"kind":"number","value":"0.6"}}},
                        {"state":"selected","motion":"quick","properties":{"alpha":{"kind":"number","value":"0.5"}}},
                        {"state":"pressed","properties":{"scale":{"kind":"number","value":"0.95"}}}
                      ]
                    },
                    "second": {
                      "properties":{"alpha":{"kind":"number","value":"0.8"}},
                      "rules":[
                        {"state":"selected","properties":{"alpha":{"kind":"number","value":"0.4"}}}
                      ]
                    }
                  },
                  "children": []
                }
                """,
            ),
        )

        val style = compiled.computedStyle(listOf("first", "second"), "node.styles")
        val selected = style.resolve(setOf(DesignState.Selected))
        val selectedAndPressed = style.resolve(setOf(DesignState.Selected, DesignState.Pressed))

        assertEquals(DesignValue.NumberValue(0.4), selected.properties[DesignProperty.Alpha])
        assertEquals(DesignMotion(90, DesignEasing.EaseOut), selected.motion)
        assertEquals(
            DesignValue.NumberValue(0.95),
            selectedAndPressed.properties[DesignProperty.Scale],
        )
        assertEquals(
            DesignValue.ColorValue(0xff111111L),
            selected.properties[DesignProperty.BackgroundColor],
        )
        assertEquals(
            DesignValue.NumberValue(0.8),
            style.baseOnly().resolve(setOf(DesignState.Selected)).properties[DesignProperty.Alpha],
        )
    }

    @Test
    fun allSixStatesResolveIndependently() {
        val rules = DesignState.entries.mapIndexed { index, state ->
            val wire = state.name.lowercase()
            """{"state":"$wire","properties":{"padding":{"kind":"dimension","value":"${index + 1}"}}}"""
        }.joinToString(",")
        val compiled = DesignModel.compileScope(
            scope(
                """
                {"tokens":{},"styles":{"states":{"properties":{},"rules":[$rules]}},"children":[]}
                """,
            ),
        )
        val style = compiled.computedStyle(listOf("states"), "node.styles")

        DesignState.entries.forEachIndexed { index, state ->
            assertEquals(
                DesignValue.DimensionValue((index + 1).toDouble()),
                style.resolve(setOf(state)).properties[DesignProperty.Padding],
            )
        }
    }

    @Test
    fun unresolvedAndCyclicReferencesPreservePaths() {
        val missingToken = assertThrows(ContentInvalid::class.java) {
            DesignModel.compileScope(
                scope(
                    """
                    {"tokens":{},"styles":{"bad":{"properties":{"padding":{"kind":"token","value":"missing"}},"rules":[]}},"children":[]}
                    """,
                ),
                path = "surface.children[2]",
            )
        }
        assertEquals(
            "surface.children[2].styles.bad.properties.padding.value",
            missingToken.path,
        )

        val cycle = assertThrows(ContentInvalid::class.java) {
            DesignModel.compileScope(
                scope(
                    """
                    {"tokens":{"a":{"kind":"token","value":"b"},"b":{"kind":"token","value":"a"}},"styles":{},"children":[]}
                    """,
                ),
            )
        }
        assertEquals("root.tokens.b.value", cycle.path)

        val missingStyle = assertThrows(ContentInvalid::class.java) {
            val compiled = DesignModel.compileScope(
                scope("""{"tokens":{},"styles":{},"children":[]}"""),
            )
            compiled.computedStyle(listOf("missing"), "root.children[0].styles")
        }
        assertEquals("root.children[0].styles[0]", missingStyle.path)
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedEntryAfterThirtyTwoScopes() {
        val first = DesignModel.compileScope(uniqueScope(0))
        val firstKey = first.canonicalContent
        for (index in 1..32) DesignModel.compileScope(uniqueScope(index))

        assertEquals(32, DesignModel.cacheKeysForTest().size)
        assertFalse(firstKey in DesignModel.cacheKeysForTest())
        assertNotSame(first, DesignModel.compileScope(uniqueScope(0)))
    }

    @Test
    fun semanticValidatorRequiresScopeAndPassivePressableContent() {
        val outside = assertThrows(ContentInvalid::class.java) {
            JetpacsDesignSemanticValidator.validate(
                objectNode(
                    """
                    {"t":"jetpacs.styled","styles":["x"],"children":[]}
                    """,
                ),
                "surface",
            )
        }
        assertEquals("surface", outside.path)

        val interactiveChild = assertThrows(ContentInvalid::class.java) {
            JetpacsDesignSemanticValidator.validate(
                documentWithPressable(
                    """{"t":"button","label":"Nested","on_tap":{"action":"nested.run"}}""",
                ),
                "surface",
            )
        }
        assertEquals("surface.children[0].children[0].children[0]", interactiveChild.path)

        val actionOwningBox = assertThrows(ContentInvalid::class.java) {
            JetpacsDesignSemanticValidator.validate(
                documentWithPressable(
                    """{"t":"box","children":[{"t":"text","text":"Face"}],"on_tap":{"action":"nested.run"}}""",
                ),
                "surface",
            )
        }
        assertEquals(
            "surface.children[0].children[0].children[0].on_tap",
            actionOwningBox.path,
        )

        JetpacsDesignSemanticValidator.validate(
            documentWithPressable(
                """{"t":"jetpacs.styled","styles":["face"],"children":[{"t":"row","children":[{"t":"text","text":"Safe"},{"t":"icon","name":"check"}]}]}""",
            ),
            "surface",
        )
    }

    @Test
    fun semanticValidatorRejectsNinthNestedScope() {
        var child = """{"t":"text","text":"leaf"}"""
        repeat(DesignModel.MAX_SCOPE_DEPTH + 1) {
            child = """{"t":"jetpacs.design_scope","tokens":{},"styles":{},"children":[$child]}"""
        }
        val failure = assertThrows(ContentInvalid::class.java) {
            JetpacsDesignSemanticValidator.validate(objectNode(child), "surface")
        }
        assertTrue(failure.reason.contains("nesting"))
    }

    private fun uniqueScope(index: Int): JsonObject = scope(
        """
        {
          "tokens":{"color":{"kind":"color","value":"#${index.toString(16).padStart(6, '0')}"}},
          "styles":{},
          "children":[]
        }
        """,
    )

    private fun documentWithPressable(child: String): JsonObject = objectNode(
        """
        {
          "t":"column",
          "children":[{
            "t":"jetpacs.design_scope",
            "tokens":{},
            "styles":{"face":{"properties":{},"rules":[]}},
            "children":[{
              "t":"jetpacs.pressable",
              "styles":["face"],
              "on_tap":{"action":"face.run"},
              "children":[$child]
            }]
          }]
        }
        """,
    )

    private fun scope(json: String): JsonObject = objectNode(json)

    private fun objectNode(json: String): JsonObject =
        Json.parseToJsonElement(json) as JsonObject
}
