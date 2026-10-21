// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.SurfaceStore
import com.calebc42.jetpacs.material3.testing.MaterialTestController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A `surface.data` patch, or a whole-document `surface.update` that changes
 * one node, recomposes only the changed identity path: the root (whose
 * instance always changes) and the nodes on the way to the change. The
 * witness is [LocalNodeRenderObserver], which [MaterialTestController] wires
 * to a list of the identity paths whose node body ran.
 *
 * The test witnesses two things at once. material3 compares Json parameters
 * by reference (there is no Compose stability configuration for the
 * kotlinx-serialization Json types), so an untouched node skips only when
 * the snapshot hands it the previous revision's instance; that instance
 * comes from `Materializer.share`, which `SurfaceStore.patch` and `update`
 * run against the previous record. Without the sharing every child of an
 * eager container runs: scenario 1 measured all four paths instead of two.
 * Rows of a lazy column are the exception, because `RenderLazyItem` still
 * compares its `@Immutable` [LazyRenderItem] structurally; there the
 * `assertSame` checks are the witness of the sharing.
 *
 * A templated lazy column here uses a non-batchable row root (`card`).
 * Batchable rows (`text`, `row`, …) are grouped into presentation chunks
 * keyed by their first row (`lazyColumnProjection`), so an insert ahead of a
 * chunk re-keys it and re-runs its rows by design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PatchRecompositionTest {
    @get:Rule
    val compose = createAndroidComposeRule(ComponentActivity::class.java)

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject
    private fun ops(text: String): JsonArray = Json.parseToJsonElement(text) as JsonArray
    private fun children(node: JsonObject): List<JsonObject> =
        (node.getValue("children") as JsonArray).map { it as JsonObject }

    private val surface = "app:test"
    private val ran = mutableListOf<String>()

    private fun controller() = MaterialTestController(compose, onNodeRender = { ran += it })

    private fun store(authored: JsonObject, data: JsonObject?): SurfaceStore =
        SurfaceStore(16, 1024).also {
            val result = if (data == null) it.update(surface, 1, authored, null, null, null)
            else it.update(surface, 1, authored, null, null, null, data = data, dataModelGranted = true)
            assertEquals("applied", result.status)
        }

    /** The identity paths of [node] and its whole subtree under [parentPath]. */
    private fun subtree(parentPath: String, node: JsonObject, index: Int): Set<String> {
        val path = identityPath(parentPath, node, index)
        val kids = (node["children"] as? JsonArray)?.map { it as JsonObject }.orEmpty()
        return setOf(path) + kids.flatMapIndexed { i, kid -> subtree(path, kid, i) }
    }

    @Test
    fun aPatchRunsOnlyTheRootAndTheChangedChild() {
        val store = store(
            json(
                """{"t":"column","key":"root","children":[
                    {"t":"text","key":"a","bind":{"text":"/a"}},
                    {"t":"text","key":"b","bind":{"text":"/b"}},
                    {"t":"text","key":"c","bind":{"text":"/c"}}]}""",
            ),
            json("""{"a":"A","b":"B","c":"C"}"""),
        )
        val controller = controller()
        val before = store.spec(surface)!!
        controller.start(before)
        assertEquals(subtree("", before, 0), ran.toSet())

        ran.clear()
        assertEquals("applied", store.patch(surface, 2, ops("""[{"op":"set","path":"/b","value":"B2"}]""")).status)
        val after = store.spec(surface)!!
        assertSame(children(before)[0], children(after)[0])
        assertSame(children(before)[2], children(after)[2])
        controller.update(after)
        controller.waitForIdle()

        val rootPath = identityPath("", after, 0)
        assertEquals(setOf(rootPath, identityPath(rootPath, children(after)[1], 1)), ran.toSet())
    }

    @Test
    fun aTemplatedLazyColumnRunsOnlyTheTouchedRow() {
        val store = store(
            json(
                """{"t":"lazy_column","key":"outline","bind":{"children":"/rows"},
                    "template":{"t":"card","bind":{"key":"id"},"children":[
                        {"t":"text","key":"title","bind":{"text":"title"}}]}}""",
            ),
            json(
                """{"rows":[{"id":"n1","title":"One"},{"id":"n2","title":"Two"},{"id":"n3","title":"Three"},
                    {"id":"n4","title":"Four"},{"id":"n5","title":"Five"}]}""",
            ),
        )
        val controller = controller()
        val first = store.spec(surface)!!
        controller.start(first)
        // Every row fits the viewport, so every row composed.
        assertEquals(subtree("", first, 0), ran.toSet())

        // A set inside one row: the root (the lazy column) and that row's subtree.
        ran.clear()
        assertEquals("applied",
            store.patch(surface, 2, ops("""[{"op":"set","path":"/rows/2/title","value":"Three!"}]""")).status)
        val second = store.spec(surface)!!
        for (i in listOf(0, 1, 3, 4)) assertSame(children(first)[i], children(second)[i])
        controller.update(second)
        controller.waitForIdle()
        val rootPath = identityPath("", second, 0)
        assertEquals(subtree(rootPath, children(second)[2], 2) + rootPath, ran.toSet())

        // An insert at the head: the new row runs, no pre-existing row does.
        ran.clear()
        assertEquals("applied",
            store.patch(surface, 3,
                ops("""[{"op":"insert","path":"/rows/0","value":{"id":"n0","title":"Zero"}}]""")).status)
        val third = store.spec(surface)!!
        assertNotSame(second.getValue("children"), third.getValue("children"))
        for (i in 0 until 5) assertSame(children(second)[i], children(third)[i + 1])
        controller.update(third)
        controller.waitForIdle()
        assertEquals(subtree(rootPath, children(third)[0], 0) + rootPath, ran.toSet())
    }

    @Test
    fun aDraftOnOneSurfaceRunsNoNodeOfAnother() {
        // C3: the per-surface display state. A draft published for surface B
        // runs B's stateful node and nothing of surface A — not even A's
        // root, which used to carry the whole store's display map in its
        // context and so recomposed its entire tree on any draft anywhere.
        val a = json(
            """{"t":"column","key":"ra","children":[
                {"t":"text","key":"ta","text":"A"},
                {"t":"text_input","id":"ia","label":"A"}]}""",
        )
        val b = json(
            """{"t":"column","key":"rb","children":[
                {"t":"text","key":"tb","text":"B"},
                {"t":"text_input","id":"ib","label":"B"}]}""",
        )
        val controller = controller()
        controller.start(mapOf("app:a" to a, "app:b" to b))
        assertEquals(subtree("", a, 0) + subtree("", b, 0), ran.toSet())

        ran.clear()
        controller.host.inputDisplays.value = mapOf(
            ("app:b" to "ib") to InputDisplay(1L, JsonPrimitive("typed")),
        )
        controller.waitForIdle()
        // B's field re-seeds from the new generation: the recomposition
        // happened, in the field's own scope below the observer, which is
        // why no identity path is recorded for it. Before C3 every path of
        // A and B ran here, the root's context having changed.
        compose.onNodeWithText("typed").assertIsDisplayed()
        assertEquals("nothing ran above a field's own scope: $ran", emptyList<String>(), ran)

        // A's own draft seeds A's field and still runs nothing of B — or of
        // A above the field.
        controller.host.inputDisplays.value = mapOf(
            ("app:b" to "ib") to InputDisplay(1L, JsonPrimitive("typed")),
            ("app:a" to "ia") to InputDisplay(1L, JsonPrimitive("mine")),
        )
        controller.waitForIdle()
        compose.onNodeWithText("mine").assertIsDisplayed()
        compose.onNodeWithText("typed").assertIsDisplayed()
        assertEquals(emptyList<String>(), ran)
    }

    @Test
    fun aWholeDocumentUpdateRunsOnlyTheChangedPath() {
        val authored = """{"t":"column","key":"root","children":[
            {"t":"text","key":"one","text":"one"},
            {"t":"text","key":"two","text":"two"},
            {"t":"row","key":"tail","children":[{"t":"text","key":"three","text":"three"}]}]}"""
        val store = store(json(authored), null)
        val controller = controller()
        val before = store.spec(surface)!!
        controller.start(before)

        ran.clear()
        assertEquals("applied",
            store.update(surface, 2, json(authored.replace("\"two\"}", "\"TWO\"}")), null, null, null).status)
        val after = store.spec(surface)!!
        assertSame(children(before)[0], children(after)[0])
        assertSame(children(before)[2], children(after)[2])
        controller.update(after)
        controller.waitForIdle()

        val rootPath = identityPath("", after, 0)
        assertEquals(setOf(rootPath, identityPath(rootPath, children(after)[1], 1)), ran.toSet())
    }
}
