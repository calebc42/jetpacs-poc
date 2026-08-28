// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.calebc42.ebp.wire.CompletionNarrowing
import com.calebc42.ebp.wire.CompletionOfferView
import com.calebc42.ebp.wire.SafeAdmissionEvidence
import com.calebc42.ebp.wire.ScalarPos
import com.calebc42.ebp.wire.UnsafeAdmissionReason
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import com.calebc42.jetpacs.renderer.model.CandidateDocument
import com.calebc42.jetpacs.renderer.model.CompletionOffer
import com.calebc42.jetpacs.renderer.model.EditorAnnotationState
import com.calebc42.jetpacs.renderer.model.EditorMirror
import com.calebc42.jetpacs.renderer.model.RendererActionOutcome
import com.calebc42.jetpacs.renderer.model.RendererEditorHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditingControllersTest {
    private fun descriptor(name: String = "demo.submit") = buildJsonObject {
        put("action", name)
    }

    @Test
    fun textEditPublishesStateBeforeItsAction() {
        val events = mutableListOf<String>()
        val controller = TextInputController(
            TextFieldState(),
            TextInputControllerConfig(
                id = "title",
                password = false,
                singleLine = false,
                filter = null,
                maxLengthScalars = null,
                clearOnSubmit = false,
                onChange = descriptor("demo.change"),
                onSubmit = null,
                publishPasswordLocally = false,
            ),
            maxFieldBytes = 65_536,
            publishState = { events += "state:$it" },
            actionDispatcher = EditingActionDispatcher { action, value, _, _, _ ->
                events += "action:${action["action"]}:$value"
                ActionHandoff.HandedOff
            },
        )

        controller.recordUserEdit("typed")

        assertEquals(
            listOf("state:typed", "action:\"demo.change\":\"typed\""),
            events,
        )
    }

    @Test
    fun legacyMaskedEditUsesGeneratedNormalizationAndRemapsImeRanges() {
        val proposed = TextFieldValue(
            text = "a1\n😀23",
            selection = TextRange(1, 7),
            composition = TextRange(0, 5),
        )

        val normalized = normalizeLegacyTextFieldValue(
            proposed,
            singleLine = true,
            filter = "digits",
            maximum = 2,
        )

        assertEquals("12", normalized.text)
        assertEquals(TextRange(0, 2), normalized.selection)
        assertEquals(TextRange(0, 1), normalized.composition)
    }

    @Test
    fun legacyMaskedEditUpdatesCanonicalStateBeforeChangeAndRefusesByteOverflow() {
        val state = TextFieldState()
        val events = mutableListOf<String>()
        val controller = TextInputController(
            state,
            TextInputControllerConfig(
                id = "phone",
                password = false,
                singleLine = true,
                filter = "digits",
                maxLengthScalars = 3,
                clearOnSubmit = false,
                onChange = descriptor("demo.change"),
                onSubmit = null,
                publishPasswordLocally = false,
            ),
            maxFieldBytes = 5,
            publishState = { events += "state:$it:${state.text}" },
            actionDispatcher = EditingActionDispatcher { _, value, _, _, _ ->
                events += "action:$value:${state.text}"
                ActionHandoff.HandedOff
            },
        )

        val admitted = controller.applyLegacyEdit(
            TextFieldValue("a12\n3", TextRange(5)),
        )
        assertEquals("123", admitted?.text)
        assertEquals("123", state.text.toString())
        assertEquals(
            listOf("state:123:123", "action:\"123\":123"),
            events,
        )

        val limitedState = TextFieldState("123")
        val limited = TextInputController(
            limitedState,
            textConfig(clearOnSubmit = false).copy(onSubmit = null),
            maxFieldBytes = 5,
            publishState = { events += "unexpected:$it" },
            actionDispatcher = ignoredDispatcher,
        )
        val refused = limited.applyLegacyEdit(TextFieldValue("1234", TextRange(4)))
        assertEquals(null, refused)
        assertEquals("123", limitedState.text.toString())
        assertEquals(2, events.size)
    }

    @Test
    fun clearOnSubmitWaitsForSafeAdmissionAndDoesNotEraseANewerEdit() {
        val state = TextFieldState("first")
        val published = mutableListOf<String>()
        var outcome: ((RendererActionOutcome) -> Unit)? = null
        val controller = TextInputController(
            state,
            textConfig(clearOnSubmit = true),
            65_536,
            published::add,
            EditingActionDispatcher { _, _, _, _, callback ->
                outcome = callback
                ActionHandoff.HandedOff
            },
        )

        assertEquals(ActionHandoff.HandedOff, controller.submit())
        assertEquals("first", state.text.toString())
        outcome!!(RendererActionOutcome.NotAdmitted(UnsafeAdmissionReason.RemoteRejected))
        assertEquals("first", state.text.toString())

        state.setTextAndPlaceCursorAtEnd("second")
        assertEquals(ActionHandoff.HandedOff, controller.submit())
        state.setTextAndPlaceCursorAtEnd("newer")
        controller.recordUserEdit("newer")
        requireNotNull(outcome)(
            RendererActionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteAccepted),
        )
        assertEquals("newer", state.text.toString())

        assertEquals(ActionHandoff.HandedOff, controller.submit())
        requireNotNull(outcome)(
            RendererActionOutcome.SafelyAdmitted(SafeAdmissionEvidence.RemoteDuplicate),
        )
        assertEquals("", state.text.toString())
        assertEquals("", published.last())
    }

    @Test
    fun passwordIsNeverPublishedBlocksDoubleSubmitAndErasesOnEveryConclusion() {
        val state = TextFieldState("secret")
        val published = mutableListOf<String>()
        var dispatches = 0
        var outcome: ((RendererActionOutcome) -> Unit)? = null
        val controller = TextInputController(
            state,
            TextInputControllerConfig(
                id = "password",
                password = true,
                singleLine = true,
                filter = null,
                maxLengthScalars = null,
                clearOnSubmit = false,
                onChange = null,
                onSubmit = descriptor(),
                publishPasswordLocally = false,
            ),
            65_536,
            published::add,
            EditingActionDispatcher { _, value, secret, _, callback ->
                dispatches++
                assertEquals(null, value)
                assertEquals(
                    "secret",
                    secret!!.fieldsOrNull()!!["password"]?.toString()?.trim('"'),
                )
                outcome = callback
                ActionHandoff.HandedOff
            },
        )

        assertEquals(ActionHandoff.HandedOff, controller.submit())
        assertEquals(ActionHandoff.Ignored, controller.submit())
        assertEquals(1, dispatches)
        assertTrue(published.isEmpty())
        outcome!!(RendererActionOutcome.NotAdmitted(UnsafeAdmissionReason.TransportClosed))
        assertEquals("", state.text.toString())
        assertTrue(!controller.passwordSubmissionPending)
    }

    @Test
    fun textSubmitUsesJcsEncodedFieldSize() {
        var dispatches = 0
        val controller = TextInputController(
            TextFieldState("a\""),
            textConfig(clearOnSubmit = false),
            4,
            {},
            EditingActionDispatcher { _, _, _, _, _ ->
                dispatches++
                ActionHandoff.HandedOff
            },
        )

        assertEquals(ActionHandoff.Ignored, controller.submit())
        assertEquals(0, dispatches)
    }

    @Test
    fun dialogPasswordErasureAlsoClearsItsVolatileCaptureLayer() {
        val state = TextFieldState("secret")
        val localValues = mutableListOf<String>()
        var outcome: ((RendererActionOutcome) -> Unit)? = null
        val controller = TextInputController(
            state,
            TextInputControllerConfig(
                id = "password",
                password = true,
                singleLine = true,
                filter = null,
                maxLengthScalars = null,
                clearOnSubmit = false,
                onChange = null,
                onSubmit = descriptor(),
                publishPasswordLocally = true,
            ),
            65_536,
            localValues::add,
            EditingActionDispatcher { _, _, _, _, callback ->
                outcome = callback
                ActionHandoff.HandedOff
            },
        )

        controller.submit()
        outcome!!(RendererActionOutcome.NotAdmitted(UnsafeAdmissionReason.ContentInvalid))

        assertEquals("", state.text.toString())
        assertEquals(listOf(""), localValues)
    }

    @Test
    fun editorPresentationEditUsesScalarWireOffsetsAndRemoteAdoptionDoesNotEcho() {
        val host = FakeEditorHost()
        val state = TextFieldState("a😀b")
        val controller = EditorController(
            state,
            EditorControllerConfig(
                surface = "app:main",
                id = "body",
                document = "buffer:one",
                singleLine = false,
                publishState = false,
                maxFieldBytes = 65_536,
                maxEditorBytes = 65_536,
            ),
            publishLocalState = {},
            editorHost = host,
            actionDispatcher = ignoredDispatcher,
        )

        controller.applyPresentationEdit("a!b", 2, 2)
        assertEquals(listOf(Edit(ScalarPos(1), 1, "!", "a😀b")), host.edits)

        controller.adoptMirror(
            EditorMirror("remote", 6, 6, 6, sequence = 4, epoch = 9),
        )
        assertEquals("remote", state.text.toString())
        assertEquals(1, host.edits.size)
    }

    @Test
    fun localEditorPublishesTheWholeLogicalValue() {
        val host = FakeEditorHost()
        val published = mutableListOf<String>()
        val controller = EditorController(
            TextFieldState("one"),
            EditorControllerConfig(
                "app:main", "body", "", false,
                publishState = true,
                maxFieldBytes = 65_536,
                maxEditorBytes = 65_536,
            ),
            published::add,
            host,
            ignoredDispatcher,
        )

        controller.applyPresentationEdit("two", 3, 3)

        assertEquals(listOf("two"), published)
        assertTrue(host.edits.isEmpty())
    }

    @Test
    fun localEditorWithoutPublishStateKeepsLiveTextWithoutPublishingDraft() {
        val host = FakeEditorHost()
        val published = mutableListOf<String>()
        val state = TextFieldState("one")
        val controller = EditorController(
            state,
            EditorControllerConfig(
                "app:main", "body", "", false,
                publishState = false,
                maxFieldBytes = 65_536,
                maxEditorBytes = 65_536,
            ),
            published::add,
            host,
            ignoredDispatcher,
        )

        controller.applyPresentationEdit("two", 3, 3)

        assertEquals("two", state.text.toString())
        assertTrue(published.isEmpty())
        assertTrue(host.edits.isEmpty())
    }

    @Test
    fun editorUsesItsContextLimitAndJcsEncodedSize() {
        val host = FakeEditorHost()
        val localState = TextFieldState("a")
        val local = EditorController(
            localState,
            EditorControllerConfig(
                "app:main", "local", "", false,
                publishState = false,
                maxFieldBytes = 4,
                maxEditorBytes = 100,
            ),
            {},
            host,
            ignoredDispatcher,
        )
        local.applyPresentationEdit("a\"", 2, 2)
        assertEquals("a", localState.text.toString())

        val syncedState = TextFieldState("a")
        val synced = EditorController(
            syncedState,
            EditorControllerConfig(
                "app:main", "sync", "buffer:one", false,
                publishState = false,
                maxFieldBytes = 4,
                maxEditorBytes = 5,
            ),
            {},
            host,
            ignoredDispatcher,
        )
        synced.applyPresentationEdit("a\"", 2, 2)
        assertEquals("a\"", syncedState.text.toString())
        assertEquals("\"", host.edits.last().inserted)
    }

    private fun textConfig(clearOnSubmit: Boolean) = TextInputControllerConfig(
        id = "title",
        password = false,
        singleLine = true,
        filter = null,
        maxLengthScalars = null,
        clearOnSubmit = clearOnSubmit,
        onChange = null,
        onSubmit = descriptor(),
        publishPasswordLocally = false,
    )

    private val ignoredDispatcher = EditingActionDispatcher { _, _, _, _, _ ->
        ActionHandoff.HandedOff
    }
}

private data class Edit(
    val start: ScalarPos,
    val deleted: Int,
    val inserted: String,
    val base: String,
)

private class FakeEditorHost : RendererEditorHost {
    override val maxEditorBytes = 65_536
    override val editorMirrors = MutableStateFlow<Map<Pair<String, String>, EditorMirror>>(
        emptyMap(),
    )
    override val editorAnnotations =
        MutableStateFlow<Map<Pair<String, String>, EditorAnnotationState>>(emptyMap())
    override val completionOffers =
        MutableStateFlow<Map<Pair<String, String>, CompletionOffer>>(emptyMap())
    override val completionOfferViews =
        MutableStateFlow<Map<Pair<String, String>, CompletionOfferView>>(emptyMap())
    override val candidateDocuments =
        MutableStateFlow<Map<Pair<String, String>, CandidateDocument>>(emptyMap())
    override var completionNarrowing = CompletionNarrowing.STRICT
    val edits = mutableListOf<Edit>()

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
    ) {
        edits += Edit(start, deletedScalars, inserted, base)
    }
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
}
