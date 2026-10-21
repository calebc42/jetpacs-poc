// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.material3.testing

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.calebc42.ebp.renderer.compose.ComposeIconResolver
import com.calebc42.ebp.renderer.compose.ComposeRendererConfiguration
import com.calebc42.ebp.renderer.compose.LocalComposeIconResolver
import com.calebc42.jetpacs.material3.EbpTheme
import com.calebc42.jetpacs.material3.IconMap
import com.calebc42.jetpacs.material3.ImageLoader
import com.calebc42.jetpacs.material3.LocalMaterialImageSource
import com.calebc42.jetpacs.material3.LocalNodeRenderObserver
import com.calebc42.jetpacs.material3.NodeSupport
import com.calebc42.jetpacs.material3.RenderDialogRoot
import com.calebc42.jetpacs.material3.RenderNode
import kotlinx.serialization.json.JsonObject

/**
 * Mounts one accepted surface (or dialog) document in the production Material
 * renderer against a [RecordingMaterialHost], with the composition
 * environment `MainActivity` provides: the EBP theme, the icon resolver, and
 * an image source that never opens a socket. [start] composes once; [update]
 * replaces the document in the same composition, which is how a test
 * exercises retained presentation state and draft reconciliation.
 */
class MaterialTestController(
    private val rule: ComposeContentTestRule,
    /** The host every action, state publication and dialog call lands in. */
    val host: RecordingMaterialHost = RecordingMaterialHost(),
    private val configuration: ComposeRendererConfiguration = NodeSupport.COMPOSE_CONFIGURATION,
    private val surface: String = "app:test",
    private val theme: JsonObject? = null,
    private val imageSource: suspend (String, ImageLoader.Limits) -> Bitmap? = { _, _ -> null },
    /** Called with a node's identity path each time its body runs
     * ([LocalNodeRenderObserver]); a test counts recompositions with it. */
    private val onNodeRender: ((String) -> Unit)? = null,
) {
    private sealed interface Mounted {
        data class Surface(val spec: JsonObject) : Mounted
        /** Several surfaces in one composition, each its own [RenderNode] root. */
        data class Surfaces(val specs: Map<String, JsonObject>) : Mounted
        data class Dialog(val dialogId: String, val spec: JsonObject, val epoch: Long) : Mounted
    }

    private val mounted = mutableStateOf<Mounted?>(null)
    private var started = false

    val dispatched: List<RecordingMaterialHost.Dispatch> get() = host.dispatched
    val published: List<RecordingMaterialHost.Published> get() = host.published
    val events: List<String> get() = host.events

    /** Compose [spec] as the surface document and wait for idle. Call once per test. */
    fun start(spec: JsonObject) = mount(Mounted.Surface(spec))

    /** Compose every entry of [specs] (surface id to document) as its own root in one
     * composition and wait for idle, for a test that watches one surface while another
     * changes. Call once per test; [update] does not apply. */
    fun start(specs: Map<String, JsonObject>) = mount(Mounted.Surfaces(specs))

    /** Compose [spec] as dialog [dialogId]'s document and wait for idle. Call once per test. */
    fun startDialog(dialogId: String, spec: JsonObject, epoch: Long = 0L) =
        mount(Mounted.Dialog(dialogId, spec, epoch))

    /** Replace the surface document in the same composition and wait for idle. */
    fun update(spec: JsonObject) {
        check(started) { "call start() first" }
        mounted.value = Mounted.Surface(spec)
        rule.waitForIdle()
    }

    /** Wait for composition, layout and effects to settle. */
    fun waitForIdle() = rule.waitForIdle()

    /** Forget recorded host calls; flows and defaults stay. */
    fun clearRecords() = host.clear()

    private fun mount(target: Mounted) {
        check(!started) { "start() was already called; use update()" }
        started = true
        mounted.value = target
        rule.setContent { Content() }
        rule.waitForIdle()
    }

    @Composable
    private fun Content() {
        EbpTheme(theme) {
            CompositionLocalProvider(
                LocalComposeIconResolver provides ComposeIconResolver(IconMap::get),
                LocalMaterialImageSource provides imageSource,
                LocalNodeRenderObserver provides onNodeRender,
            ) {
                when (val target = mounted.value) {
                    is Mounted.Surface ->
                        RenderNode(target.spec, surface, host, configuration = configuration)
                    is Mounted.Surfaces ->
                        Column {
                            for ((id, spec) in target.specs) {
                                RenderNode(spec, id, host, configuration = configuration)
                            }
                        }
                    is Mounted.Dialog ->
                        RenderDialogRoot(target.dialogId, target.spec, host, target.epoch, configuration)
                    null -> Unit
                }
            }
        }
    }
}
