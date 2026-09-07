// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import java.io.File
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantHostRenderTest {

    private fun sourceFile(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val source = listOf(
                File(dir, "src/main/kotlin/com/calebc42/jetpacs/material3/$name"),
                File(dir, "renderer/material3/src/main/kotlin/com/calebc42/jetpacs/material3/$name"),
            ).firstOrNull(File::isFile)
            if (source != null) return source
            dir = dir.parentFile
        }
        error("$name not found")
    }

    private val host = buildJsonObject {
        put("t", "variant_host")
        put("id", "visibility")
        put("value", "overview")
        putJsonArray("variants") {
            add(buildJsonObject {
                put("value", "overview")
                put("content", buildJsonObject {
                    put("t", "column")
                    put("key", "overview-root")
                })
            })
            add(buildJsonObject {
                put("value", "contents")
                put("content", buildJsonObject {
                    put("t", "column")
                    put("key", "contents-root")
                })
            })
        }
    }

    @Test
    fun localSelectionWinsAndInvalidSelectionFallsBackToAuthored() {
        val variants = retainedVariants(host)
        assertEquals(listOf("overview", "contents"), variants.map { it.value })
        assertEquals(1, selectedRetainedVariantIndex(
            variants, local = "contents", authored = "overview"))
        assertEquals(0, selectedRetainedVariantIndex(
            variants, local = "missing", authored = "overview"))
    }

    @Test
    fun eachRetainedBranchHasAStableDistinctPresentationPath() {
        val variants = retainedVariants(host)
        val hostPath = retainedVariantHostPath("", host)
        val first = retainedVariantPath(hostPath, host, variants[0])
        val again = retainedVariantPath(hostPath, host, variants[0])
        val second = retainedVariantPath(hostPath, host, variants[1])
        assertEquals(first, again)
        assertNotEquals(first, second)
        assertEquals(
            "$hostPath/v:${encodedIdentityAtom("overview")}/" +
                typedIdentitySegment("k", "overview-root", "column"),
            first,
        )

        val keyedHost = buildJsonObject {
            host.forEach { (name, value) -> put(name, value) }
            put("key", "body-visibility")
        }
        assertEquals("/${typedIdentitySegment(
            "k", "body-visibility", "variant_host")}",
            retainedVariantHostPath("", keyedHost))
        assertEquals("/already-resolved",
            retainedVariantHostPath("/already-resolved", keyedHost))

        val sameKeyDifferentType = RetainedVariant("overview", buildJsonObject {
            put("t", "text")
            put("key", "overview-root")
        })
        val sameIdDifferentType = RetainedVariant("overview", buildJsonObject {
            put("t", "text")
            put("id", "overview-id")
        })
        assertNotEquals(first,
            retainedVariantPath(hostPath, host, sameKeyDifferentType))
        assertNotEquals(
            retainedVariantPath(hostPath, host, sameIdDifferentType),
            retainedVariantPath(hostPath, host,
                RetainedVariant("overview", buildJsonObject {
                    put("t", "column")
                    put("id", "overview-id")
                })),
        )
    }

    @Test
    fun delimiterBearingVariantAndNodeIdentitiesCannotAlias() {
        // The former raw concatenation produced the same spelling for these:
        //   variant=a, root-key=b/k:c
        //   variant=a/k:b, root-key=c
        val left = RetainedVariant("a", buildJsonObject {
            put("t", "text")
            put("key", "b/k:c")
        })
        val right = RetainedVariant("a/k:b", buildJsonObject {
            put("t", "text")
            put("key", "c")
        })
        val hostPath = retainedVariantHostPath("", host)
        assertNotEquals(
            retainedVariantPath(hostPath, host, left),
            retainedVariantPath(hostPath, host, right),
        )

        val parentWithDelimiter = identityPath("", buildJsonObject {
            put("t", "column")
            put("key", "parent/with:delimiter")
        }, 0)
        assertNotEquals(
            identityPath(parentWithDelimiter, left.content, 0),
            identityPath(parentWithDelimiter, right.content, 0),
        )
    }

    @Test
    fun activeSlotsKeepOneMeasurablePerVariantAndOnlyOneActiveContentHost() {
        assertEquals(listOf(false, true, false),
            retainedVariantActiveSlots(count = 3, selected = 1))
        assertEquals(listOf(false, false),
            retainedVariantActiveSlots(count = 2, selected = 9))
        assertEquals(setOf("/host/variant:all/0:text"),
            removedRetainedVariantPaths(
                previous = listOf(
                    "/host/variant:overview/0:text",
                    "/host/variant:contents/0:text",
                    "/host/variant:all/0:text",
                ),
                current = listOf(
                    "/host/variant:contents/0:text",
                    "/host/variant:overview/0:text",
                )))
        assertTrue(removedRetainedVariantPaths(
            previous = listOf("/host/a", "/host/b"),
            current = listOf("/host/b", "/host/a")).isEmpty())
    }

    @Test
    fun inactiveNestedRemovalRetiresOnlyThatNodesSaveableRegistry() {
        fun tree(includeRemoved: Boolean, removedType: String = "collapsible"):
            Pair<kotlinx.serialization.json.JsonObject,
                kotlinx.serialization.json.JsonObject?> {
            val removed = if (includeRemoved) buildJsonObject {
                put("t", removedType)
                put("key", "removed")
                put("header", buildJsonObject { put("t", "text"); put("text", "X") })
                putJsonArray("children") { }
            } else null
            val root = buildJsonObject {
                put("t", "column")
                put("key", "root")
                putJsonArray("children") {
                    add(buildJsonObject {
                        put("t", "collapsible")
                        put("key", "survivor")
                        put("header", buildJsonObject {
                            put("t", "text"); put("text", "S")
                        })
                        putJsonArray("children") { }
                    })
                    removed?.let(::add)
                }
            }
            return root to removed
        }

        val (firstRoot, firstRemoved) = tree(includeRemoved = true)
        val first = retainedIdentityInventory("/branch", firstRoot)
        val removedPath = first.providerPath(firstRemoved!!)!!
        val survivorPath = first.providerPaths.single {
            "7375727669766f72" in it &&
                it.endsWith(":t:${encodedIdentityAtom("collapsible")}")
        }

        val (absentRoot, _) = tree(includeRemoved = false)
        val absent = retainedIdentityInventory("/branch", absentRoot)
        val retired = removedRetainedVariantPaths(
            first.providerPaths, absent.providerPaths)
        assertTrue(removedPath in retired)
        assertTrue(survivorPath !in retired)
        assertTrue(survivorPath in absent.providerPaths)

        // Re-adding the same §16.1 spelling happens only after the old path
        // was explicitly removed; a type change is a distinct path as well.
        val (readdedRoot, readdedNode) = tree(includeRemoved = true)
        assertEquals(removedPath,
            retainedIdentityInventory("/branch", readdedRoot)
                .providerPath(readdedNode!!))
        val (changedRoot, changedNode) = tree(
            includeRemoved = true, removedType = "text")
        assertNull(retainedIdentityInventory("/branch", changedRoot)
            .providerPath(changedNode!!))
    }

    @Test
    fun retainedInventoryDoesNotTreatOpaqueActionDataAsNodes() {
        val root = buildJsonObject {
            put("t", "button")
            put("label", "Run")
            put("on_tap", buildJsonObject {
                put("action", "demo.run")
                put("args", buildJsonObject {
                    put("t", "collapsible")
                    put("key", "application-data")
                })
            })
        }
        assertEquals(0,
            retainedIdentityInventory("/branch", root).providerPaths.size)
        assertTrue(retainedSaveableLifecyclePaths(root).isEmpty())
    }

    @Test
    fun retainedInventoryIsBoundedToActualSaveablePresentationOwners() {
        val root = buildJsonObject {
            put("t", "column")
            put("key", "root")
            putJsonArray("children") {
                add(buildJsonObject { put("t", "text"); put("text", "plain") })
                add(buildJsonObject {
                    put("t", "row")
                    put("scroll", true)
                    putJsonArray("children") { }
                })
                add(buildJsonObject {
                    put("t", "collapsible")
                    put("key", "fold")
                    putJsonArray("children") { }
                })
            }
        }
        val inventory = retainedIdentityInventory("/branch", root)
        assertEquals(2, inventory.providerPaths.size)
        assertNull(inventory.providerPath(root))
    }

    @Test
    fun acceptedRemoveThenReaddChangesOwnerIncarnationWithoutIntermediateRead() {
        fun spec(includeFold: Boolean, hostKey: String = "host") = buildJsonObject {
            put("t", "column")
            putJsonArray("children") {
                add(buildJsonObject {
                    put("t", "variant_host")
                    put("id", "layout")
                    put("key", hostKey)
                    put("value", "a")
                    putJsonArray("variants") {
                        add(buildJsonObject {
                            put("value", "a")
                            put("content", buildJsonObject {
                                put("t", "column")
                                putJsonArray("children") {
                                    if (includeFold) add(buildJsonObject {
                                        put("t", "collapsible")
                                        put("key", "fold")
                                        putJsonArray("children") { }
                                    })
                                }
                            })
                        })
                        add(buildJsonObject {
                            put("value", "b")
                            put("content", buildJsonObject {
                                put("t", "text")
                                put("text", "B")
                            })
                        })
                    }
                })
            }
        }

        val tracker = RetainedPresentationIncarnationTracker()
        val first = tracker.accept("app:test", 1, spec(includeFold = true))
        val lifecyclePath = first.saveableOwners.keys.single().second
        val firstRoot = first.surfaceRoots.getValue("app:test")
        val firstOwner = first.saveableOwners.getValue("app:test" to lifecyclePath)

        // Deliberately do not observe/use this projection, matching a
        // conflated UI collector that skips the accepted removal.
        tracker.accept("app:test", 2, spec(includeFold = false))
        val latest = tracker.accept("app:test", 3, spec(includeFold = true))
        val latestOwner = latest.saveableOwners.getValue("app:test" to lifecyclePath)
        assertNotEquals(firstOwner, latestOwner)
        assertTrue(firstRoot === latest.surfaceRoots.getValue("app:test"))

        // A whole-surface lifetime is different even when the JSON and a
        // future revision spelling are identical.
        tracker.accept("app:test", null, null)
        val readded = tracker.accept("app:test", 1, spec(includeFold = true))
        assertNotSame(firstRoot, readded.surfaceRoots.getValue("app:test"))
        assertNotEquals(latestOwner,
            readded.saveableOwners.getValue("app:test" to lifecyclePath))
    }

    @Test
    fun rapidHostKeyRoundTripCannotReuseTheEarlierOwnerIncarnation() {
        fun spec(hostKey: String) = buildJsonObject {
            put("t", "variant_host")
            put("id", "layout")
            put("key", hostKey)
            put("value", "a")
            putJsonArray("variants") {
                add(buildJsonObject {
                    put("value", "a")
                    put("content", buildJsonObject {
                        put("t", "collapsible")
                        put("key", "fold")
                        putJsonArray("children") { }
                    })
                })
                add(buildJsonObject {
                    put("value", "b")
                    put("content", buildJsonObject {
                        put("t", "text")
                        put("text", "B")
                    })
                })
            }
        }

        val tracker = RetainedPresentationIncarnationTracker()
        val first = tracker.accept("app:test", 1, spec("foo"))
        val lookup = first.saveableOwners.keys.single().second
        val firstToken = first.saveableOwners.getValue("app:test" to lookup)
        tracker.accept("app:test", 2, spec("bar"))
        val latest = tracker.accept("app:test", 3, spec("foo"))
        assertNotEquals(firstToken,
            latest.saveableOwners.getValue("app:test" to lookup))

        fun nestedSpec(parentKey: String) = buildJsonObject {
            put("t", "column")
            put("key", parentKey)
            putJsonArray("children") { add(spec("host")) }
        }
        val ancestorTracker = RetainedPresentationIncarnationTracker()
        val ancestorFirst = ancestorTracker.accept(
            "app:nested", 1, nestedSpec("left"))
        val ancestorLookup = ancestorFirst.saveableOwners.keys.single().second
        val ancestorToken = ancestorFirst.saveableOwners
            .getValue("app:nested" to ancestorLookup)
        ancestorTracker.accept("app:nested", 2, nestedSpec("right"))
        val ancestorLatest = ancestorTracker.accept(
            "app:nested", 3, nestedSpec("left"))
        assertNotEquals(ancestorToken, ancestorLatest.saveableOwners
            .getValue("app:nested" to ancestorLookup))
    }

    @Test
    fun ownerTokenNamespaceRebasesBehindFreshSurfaceRoots() {
        fun spec(hostKey: String) = buildJsonObject {
            put("t", "variant_host")
            put("id", "layout")
            put("key", hostKey)
            put("value", "a")
            putJsonArray("variants") {
                add(buildJsonObject {
                    put("value", "a")
                    put("content", buildJsonObject {
                        put("t", "collapsible")
                        putJsonArray("children") { }
                    })
                })
                add(buildJsonObject {
                    put("value", "b")
                    put("content", buildJsonObject {
                        put("t", "text"); put("text", "B")
                    })
                })
            }
        }

        val tracker = RetainedPresentationIncarnationTracker(Long.MAX_VALUE - 1)
        val before = tracker.accept("app:test", 1, spec("before"))
        val lookup = before.saveableOwners.keys.single().second
        assertEquals(Long.MAX_VALUE,
            before.saveableOwners.getValue("app:test" to lookup))
        val oldRoot = before.surfaceRoots.getValue("app:test")

        val after = tracker.accept("app:test", 2, spec("after"))
        assertNotSame(oldRoot, after.surfaceRoots.getValue("app:test"))
        assertTrue(after.saveableOwners.getValue("app:test" to lookup) > 0L)
        assertTrue(after.saveableOwners.getValue("app:test" to lookup) < Long.MAX_VALUE)
    }

    @Test
    fun rendererPinsInactiveSubtreesBehindReusableContentHost() {
        val text = sourceFile("LayoutNodes.kt").readText()
        assertTrue(text.contains(
            "ReusableContentHost(active = activeSlots[index])"))
        assertTrue(text.contains("rememberSaveableStateHolder()"))
        assertTrue(text.contains("LocalRetainedSaveableScope provides"))
        val renderer = sourceFile("Renderer.kt").readText()
        assertTrue(renderer.contains(
            "retained.holder.SaveableStateProvider(providerPath)"))
        assertTrue(text.contains("forEach(stateHolder::removeState)"))
        assertTrue(text.contains("rememberSaveable(ctx.path)"))
        val visualization = sourceFile("VisualizationNodes.kt").readText()
        assertTrue(visualization.contains("MonthGridPresentationSaver"))
        assertTrue(visualization.contains("reconcileMonthGridPresentation("))
    }

    @Test
    fun maskedFieldsUseTheSharedExplicitOffsetMappingPath() {
        val renderer = sourceFile("Renderer.kt").readText()

        assertTrue(renderer.contains("rememberLegacyTextInputAdapter(controller)"))
        assertTrue(renderer.contains("MaskVisualTransformation(maskSpec)"))
        assertTrue(renderer.contains("LegacyMaskedMaterialTextField("))
        assertFalse(renderer.contains("MaskOutputTransformation"))
    }
}
