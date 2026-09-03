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
        assertEquals(
            compiledFirst.canonicalContent.toByteArray(Charsets.UTF_8).size,
            compiledFirst.configurationBytes,
        )
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
    fun themeRolesAndComponentBindingsAreClosedAndNestedBindingsReplace() {
        val parent = DesignModel.compileScope(
            scope(
                """
                {
                  "tokens":{"ink":{"kind":"theme-role","value":"on_surface"}},
                  "styles":{
                    "label":{"properties":{"content_color":{"kind":"token","value":"ink"}},"rules":[]},
                    "compact":{"properties":{"padding":{"kind":"dimension","value":"6"}},"rules":[]}
                  },
                  "component_styles":[
                    {"slot":"action.container","styles":["compact"]},
                    {"slot":"action.label","styles":["label"]}
                  ],
                  "children":[]
                }
                """,
            ),
        )
        assertEquals(
            DesignValue.ThemeRoleValue(DesignThemeRole.OnSurface),
            parent.componentStyle(DesignComponentStyleSlot.ActionLabel)
                ?.resolve()
                ?.properties
                ?.get(DesignProperty.ContentColor),
        )

        val child = DesignModel.compileScope(
            scope(
                """
                {
                  "tokens":{},
                  "styles":{"wide":{"properties":{"padding":{"kind":"dimension","value":"14"}},"rules":[]}},
                  "component_styles":[
                    {"slot":"action.container","styles":["wide"]}
                  ],
                  "children":[]
                }
                """,
            ),
            parent,
            "root.children[0]",
        )
        assertEquals(
            DesignValue.DimensionValue(14.0),
            child.componentStyle(DesignComponentStyleSlot.ActionContainer)
                ?.resolve()
                ?.properties
                ?.get(DesignProperty.Padding),
        )
        assertTrue(child.componentStyle(DesignComponentStyleSlot.ActionLabel) != null)

        val duplicate = assertThrows(ContentInvalid::class.java) {
            DesignModel.compileScope(
                scope(
                    """
                    {"tokens":{},"styles":{"x":{"properties":{},"rules":[]}},
                     "component_styles":[
                       {"slot":"panel.container","styles":["x"]},
                       {"slot":"panel.container","styles":["x"]}
                     ],"children":[]}
                    """,
                ),
            )
        }
        assertEquals("root.component_styles[1].slot", duplicate.path)

        val unresolved = assertThrows(ContentInvalid::class.java) {
            DesignModel.compileScope(
                scope(
                    """
                    {"tokens":{},"styles":{},
                     "component_styles":[
                       {"slot":"action.label","styles":["missing"]}
                     ],"children":[]}
                    """,
                ),
            )
        }
        assertEquals("root.component_styles[0].styles[0]", unresolved.path)
    }

    @Test
    fun canonicalTextSlotsBindPerStyleNameAndUnknownStyleIsBody() {
        val scope = DesignModel.compileScope(
            scope(
                """
                {
                  "tokens":{},
                  "styles":{
                    "serif":{"properties":{
                      "font_family":{"kind":"font-family","value":"plex-serif"},
                      "font_size":{"kind":"dimension","value":"16"},
                      "line_height":{"kind":"dimension","value":"26"}
                    },"rules":[]},
                    "small":{"properties":{
                      "font_size":{"kind":"dimension","value":"12.5"}
                    },"rules":[]}
                  },
                  "component_styles":[
                    {"slot":"text.body","styles":["serif"]},
                    {"slot":"text.caption","styles":["small"]}
                  ],
                  "children":[]
                }
                """,
            ),
        )
        val body = scope.componentStyle(slotForStyle("body"))?.resolve()?.properties
        assertEquals(
            DesignValue.FontFamilyValue(DesignFontFamily.PlexSerif),
            body?.get(DesignProperty.FontFamily),
        )
        assertEquals(DesignValue.DimensionValue(26.0), body?.get(DesignProperty.LineHeight))
        assertEquals(
            DesignValue.DimensionValue(12.5),
            scope.componentStyle(slotForStyle("caption"))
                ?.resolve()?.properties?.get(DesignProperty.FontSize),
        )
        assertEquals(DesignComponentStyleSlot.TextBody, slotForStyle(""))
        assertEquals(DesignComponentStyleSlot.TextBody, slotForStyle("unknown"))
        assertEquals(DesignComponentStyleSlot.TextMono, slotForStyle("mono"))
        assertTrue(scope.componentStyle(DesignComponentStyleSlot.TextTitle) == null)
        assertEquals(74, DesignComponentStyleSlot.entries.size)
    }

    @Test
    fun themeRolesResolveToColorsAndInheritThroughNestedScopes() {
        val parent = DesignModel.compileScope(
            scope(
                """
                {
                  "tokens":{"paper":{"kind":"color","value":"#F3EDE1"}},
                  "styles":{},
                  "theme_roles":{
                    "background":{"kind":"token","value":"paper"},
                    "primary":{"kind":"color","value":"#8A5A2B"},
                    "on_primary":{"kind":"theme-role","value":"on_surface"}
                  },
                  "children":[]
                }
                """,
            ),
        )
        assertEquals(
            DesignValue.ColorValue(0xFFF3EDE1L),
            parent.themeRoles[DesignThemeRole.Background],
        )
        assertEquals(
            DesignValue.ThemeRoleValue(DesignThemeRole.OnSurface),
            parent.themeRoles[DesignThemeRole.OnPrimary],
        )
        assertEquals(3, parent.themeRoles.size)

        val child = DesignModel.compileScope(
            scope("""{"tokens":{},"styles":{},"theme_roles":{"primary":{"kind":"color","value":"#000000"}},"children":[]}"""),
            parent,
            "root.children[0]",
        )
        assertEquals(DesignValue.ColorValue(0xFF000000L), child.themeRoles[DesignThemeRole.Primary])
        assertEquals(DesignValue.ColorValue(0xFFF3EDE1L), child.themeRoles[DesignThemeRole.Background])
        assertTrue(DesignModel.compileScope(scope("""{"tokens":{},"styles":{},"children":[]}""")).themeRoles.isEmpty())

        val unknown = assertThrows(ContentInvalid::class.java) {
            DesignModel.compileScope(
                scope("""{"tokens":{},"styles":{},"theme_roles":{"tint":{"kind":"color","value":"#000000"}},"children":[]}"""),
            )
        }
        assertEquals("root.theme_roles.tint", unknown.path)
        val notColor = assertThrows(ContentInvalid::class.java) {
            DesignModel.compileScope(
                scope("""{"tokens":{},"styles":{},"theme_roles":{"primary":{"kind":"dimension","value":"4"}},"children":[]}"""),
            )
        }
        assertEquals("root.theme_roles.primary", notColor.path)
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

    @Test
    fun cachedPressableMustRevalidateBeforeItCanOwnInteraction() {
        val scope = DesignModel.compileScope(
            scope(
                """
                {"tokens":{},"styles":{"face":{"properties":{},"rules":[]}},"children":[]}
                """,
            ),
        )
        val invalidBoolean = objectNode(
            """
            {"t":"jetpacs.pressable","styles":["face"],
             "on_tap":{"action":"demo.run"},"enabled":"true",
             "children":[{"t":"text","text":"Run"}]}
            """,
        )
        val booleanFailure = assertThrows(ContentInvalid::class.java) {
            JetpacsDesignSemanticValidator.validatePressableForRender(
                invalidBoolean,
                scope,
                "surface.children[0]",
            )
        }
        assertEquals("surface.children[0].enabled", booleanFailure.path)

        val interactiveChild = objectNode(
            """
            {"t":"jetpacs.pressable","styles":["face"],
             "on_tap":{"action":"demo.run"},
             "children":[{"t":"button","label":"Nested","on_tap":{"action":"nested"}}]}
            """,
        )
        val passiveFailure = assertThrows(ContentInvalid::class.java) {
            JetpacsDesignSemanticValidator.validatePressableForRender(
                interactiveChild,
                scope,
                "surface.children[0]",
            )
        }
        assertEquals("surface.children[0].children[0]", passiveFailure.path)
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
