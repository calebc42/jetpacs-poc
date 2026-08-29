// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.render

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.calebc42.ebp.companion.MaterialRendererHost
import com.calebc42.ebp.companion.ui.SemanticsHostActivity
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.InputDisplay
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.jetpacs.renderer.compose.ComposeCanonicalNodeOverride
import com.calebc42.jetpacs.renderer.compose.ComposeCanonicalOverrideRegistry
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRegistry
import com.calebc42.jetpacs.renderer.compose.ComposeExtensionRenderContext
import com.calebc42.jetpacs.renderer.compose.ComposeNodeExtension
import com.calebc42.jetpacs.renderer.compose.ComposeNodeRenderContext
import com.calebc42.jetpacs.renderer.compose.ComposeRendererConfiguration
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.CandidateDocument
import com.calebc42.jetpacs.renderer.model.CompletionOffer
import com.calebc42.jetpacs.renderer.model.EditorAnnotationState
import com.calebc42.jetpacs.renderer.model.EditorConnectionPhase
import com.calebc42.jetpacs.renderer.model.EditorEditOutcome
import com.calebc42.jetpacs.renderer.model.EditorMirror
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import com.calebc42.jetpacs.renderer.model.RendererActionRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScopedCoreOverrideDispatchTest {
    @get:Rule
    val compose = createAndroidComposeRule<SemanticsHostActivity>()

    private val bridge = InertMaterialHost()

    @Test
    fun nearestAdmittedScopeOverridesCoreWhileExtensionsAndOutsideStayCanonical() {
        val firstScope = "example.first"
        val secondScope = "example.second"
        val firstExtension = ScopeExtension(
            extensionId = firstScope,
            scopeNodeType = "example.first.scope",
            ordinaryNodeType = "example.first.widget",
        )
        val secondExtension = ScopeExtension(
            extensionId = secondScope,
            scopeNodeType = "example.second.scope",
        )
        val configuration = ComposeRendererConfiguration(
            appNodeTypes = NodeSupport.APP_NODE_TYPES + firstExtension.nodeTypes +
                secondExtension.nodeTypes,
            dialogNodeTypes = NodeSupport.DIALOG_NODE_TYPES,
            appExtensions = NodeSupport.APP_EXTENSIONS + firstScope + secondScope,
            dialogExtensions = NodeSupport.DIALOG_EXTENSIONS,
            extensions = ComposeExtensionRegistry(listOf(firstExtension, secondExtension)),
            canonicalOverrides = ComposeCanonicalOverrideRegistry(
                listOf(
                    TextOverride("first-text", firstScope, "First"),
                    TextOverride("second-text", secondScope, "Second"),
                ),
            ),
        )
        val nested = scopeNode(
            secondExtension.scopeNodeType,
            textNode("nested"),
        )
        val outer = scopeNode(
            firstExtension.scopeNodeType,
            textNode("before", semanticsName = "Scoped name"),
            nested,
            textNode("after"),
            buildJsonObject { put("t", firstExtension.ordinaryNodeType) },
            semanticsName = "Scope must not announce",
        )
        val outside = textNode("outside")
        val root = RenderCtx("app:test", bridge, configuration = configuration)

        compose.setContent {
            Column {
                RenderNode(outer, root.child(outer, 0))
                RenderNode(outside, root.child(outside, 1))
            }
        }

        compose.onNodeWithText("First:before").assertIsDisplayed()
        compose.onNodeWithText("Second:nested").assertIsDisplayed()
        compose.onNodeWithText("First:after").assertIsDisplayed()
        compose.onNodeWithText("Extension:example.first.widget").assertIsDisplayed()
        compose.onNodeWithText("outside").assertIsDisplayed()
        compose.onNodeWithContentDescription("Scoped name").assertIsDisplayed()
        compose.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf("Scope must not announce"),
            ),
        ).assertCountEquals(0)
    }

    @Test
    fun dialogTargetRejectsAnAppOnlyScopeEvenIfAContextCarriesItsName() {
        val designScope = "example.first"
        val configuration = ComposeRendererConfiguration(
            appNodeTypes = NodeSupport.APP_NODE_TYPES,
            dialogNodeTypes = NodeSupport.DIALOG_NODE_TYPES,
            appExtensions = NodeSupport.APP_EXTENSIONS + designScope,
            dialogExtensions = NodeSupport.DIALOG_EXTENSIONS,
            canonicalOverrides = ComposeCanonicalOverrideRegistry(
                listOf(TextOverride("first-text", designScope, "First")),
            ),
        )
        val node = textNode("dialog")
        val unscopedRoot = RenderCtx("app:test", bridge, configuration = configuration)
        assertNull(unscopedRoot.designScope)
        val dialogContext = unscopedRoot.copy(
            surface = "dialog:test",
            dialog = DialogContext(
                "test",
                mutableStateMapOf(),
                bridge,
            ),
            designScope = designScope,
        )

        compose.setContent { RenderNode(node, dialogContext.child(node, 0)) }

        compose.onNodeWithText("dialog").assertIsDisplayed()
        compose.onAllNodesWithText("First:dialog").assertCountEquals(0)
    }

    @Test
    fun conditionalEditorOverrideLeavesSynchronizedEditorOnMaterialFallback() {
        val designScope = "example.editor"
        val extension = ScopeExtension(
            extensionId = designScope,
            scopeNodeType = "example.editor.scope",
        )
        val configuration = ComposeRendererConfiguration(
            appNodeTypes = NodeSupport.APP_NODE_TYPES + extension.nodeTypes,
            dialogNodeTypes = NodeSupport.DIALOG_NODE_TYPES,
            appExtensions = NodeSupport.APP_EXTENSIONS + designScope,
            dialogExtensions = NodeSupport.DIALOG_EXTENSIONS,
            extensions = ComposeExtensionRegistry(listOf(extension)),
            canonicalOverrides = ComposeCanonicalOverrideRegistry(
                listOf(LocalEditorOverride(designScope)),
            ),
        )
        val local = buildJsonObject {
            put("t", "editor")
            put("id", "local")
            put("value", "local seed")
        }
        val synchronized = buildJsonObject {
            put("t", "editor")
            put("id", "synchronized")
            put("document", "notes")
            put("value", "Synchronized fallback")
        }
        val scope = scopeNode(extension.scopeNodeType, local, synchronized)
        val root = RenderCtx("app:test", bridge, configuration = configuration)

        compose.setContent { RenderNode(scope, root.child(scope, 0)) }

        compose.onNodeWithText("Local editor override").assertIsDisplayed()
        compose.onNodeWithText("Synchronized fallback")
            .assertIsDisplayed()
        compose.onAllNodesWithText("local seed").assertCountEquals(0)
    }

    @Test
    fun canonicalPasswordPublishesOnlyObfuscatedAccessibilityText() {
        val node = buildJsonObject {
            put("t", "text_input")
            put("id", "secret")
            put("label", "One-time secret")
            put("password", true)
            put("single_line", true)
        }
        val root = RenderCtx("app:test", bridge)

        compose.setContent { RenderNode(node, root.child(node, 0)) }

        val field = compose.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
        )
        field.performTextInput("swordfish")
        val obfuscated = AnnotatedString("\u2022".repeat(9))
        field
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.InputText,
                obfuscated,
            ))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                obfuscated,
            ))
    }

    private fun textNode(text: String, semanticsName: String? = null) = buildJsonObject {
        put("t", "text")
        put("text", text)
        semanticsName?.let { name ->
            put("semantics", buildJsonObject { put("name", name) })
        }
    }

    private fun scopeNode(
        type: String,
        vararg children: JsonObject,
        semanticsName: String? = null,
    ) = buildJsonObject {
        put("t", type)
        put("children", buildJsonArray { children.forEach(::add) })
        semanticsName?.let { name ->
            put("semantics", buildJsonObject { put("name", name) })
        }
    }
}

private class ScopeExtension(
    override val extensionId: String,
    val scopeNodeType: String,
    val ordinaryNodeType: String? = null,
) : ComposeNodeExtension {
    override val id = "$extensionId.compose"
    override val nodeTypes = setOfNotNull(scopeNodeType, ordinaryNodeType)

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeExtensionRenderContext,
        modifier: Modifier,
    ) {
        when ((node["t"] as JsonPrimitive).content) {
            scopeNodeType -> (node["children"] as JsonArray).forEachIndexed { index, child ->
                context.renderScopedChild(child as JsonObject, index)
            }
            ordinaryNodeType -> BasicText("Extension:$ordinaryNodeType", modifier)
        }
    }
}

private class TextOverride(
    override val id: String,
    override val designScope: String,
    private val prefix: String,
) : ComposeCanonicalNodeOverride {
    override val nodeTypes = setOf("text")

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        BasicText("$prefix:${(node["text"] as JsonPrimitive).content}", modifier)
    }
}

private class LocalEditorOverride(
    override val designScope: String,
) : ComposeCanonicalNodeOverride {
    override val id = "local-editor"
    override val nodeTypes = setOf("editor")

    override fun appliesTo(node: JsonObject): Boolean = "document" !in node

    @Composable
    override fun render(
        node: JsonObject,
        context: ComposeNodeRenderContext,
        modifier: Modifier,
    ) {
        BasicText("Local editor override", modifier)
    }
}

private class InertMaterialHost : MaterialRendererHost {
    override val inputDisplays = MutableStateFlow<Map<Pair<String, String>, InputDisplay>>(emptyMap())
    override val maxFieldBytes = 65_536
    override val maxEditorBytes = 65_536
    override val editorConnectionPhase = MutableStateFlow(EditorConnectionPhase.READY)
    override val editorMirrors = MutableStateFlow<Map<Pair<String, String>, EditorMirror>>(emptyMap())
    override val editorAnnotations =
        MutableStateFlow<Map<Pair<String, String>, EditorAnnotationState>>(emptyMap())
    override val completionOffers =
        MutableStateFlow<Map<Pair<String, String>, CompletionOffer>>(emptyMap())
    override val completionOfferViews =
        MutableStateFlow<Map<Pair<String, String>, CompletionOfferView>>(emptyMap())
    override val candidateDocuments =
        MutableStateFlow<Map<Pair<String, String>, CandidateDocument>>(emptyMap())
    override var completionNarrowing = CompletionNarrowing.STRICT
    override val variantSelections: StateFlow<Map<Pair<String, String>, String>> =
        MutableStateFlow(emptyMap())
    override val retainedPresentationIncarnations: StateFlow<Map<Pair<String, String>, Long>> =
        MutableStateFlow(emptyMap())

    override fun dispatch(
        request: RendererActionRequest,
        onOutcome: (RendererActionOutcome) -> Unit,
    ) = ActionHandoff.HandedOff
    override fun publishState(
        surface: String,
        id: String,
        value: JsonElement?,
        caret: Int?,
    ) = Unit
    override fun dialogDefaults(dialogId: String): JsonObject? = null
    override fun submitDialog(
        dialogId: String,
        value: JsonElement?,
        fields: JsonObject,
        secret: com.calebc42.jetpacs.renderer.model.RendererVolatileSecret?,
        onOutcome: (RendererActionOutcome) -> Unit,
    ) = ActionHandoff.HandedOff
    override fun dismissDialog(dialogId: String) = Unit
    override fun requestEditorCompletion(document: String, editorId: String) = Unit
    override fun selectEditorCompletion(
        document: String,
        editorId: String,
        label: String,
        insert: String,
    ) = Unit
    override fun requestCandidateDocument(
        document: String,
        editorId: String,
        index: Int,
        epoch: Long,
    ) = Unit
    override fun publishEditorEdit(
        document: String,
        editorId: String,
        start: ScalarPos,
        deletedScalars: Int,
        inserted: String,
        base: String,
        onOutcome: (EditorEditOutcome) -> Unit,
    ) = Unit
    override fun publishEditorCaret(
        document: String,
        editorId: String,
        cursorUtf16: Int,
        selectionStartUtf16: Int,
        selectionEndUtf16: Int,
    ) = Unit
    override fun dispatchEditorCommand(
        surface: String,
        document: String,
        editorId: String,
        command: String,
        cursorUtf16: Int,
        selectionStartUtf16: Int,
        selectionEndUtf16: Int,
    ) = Unit
    override fun pieMenuSelect(menuId: String, categoryIndex: Int, itemIndex: Int?) = Unit
    override fun pieMenuDismiss(menuId: String) = Unit
}
