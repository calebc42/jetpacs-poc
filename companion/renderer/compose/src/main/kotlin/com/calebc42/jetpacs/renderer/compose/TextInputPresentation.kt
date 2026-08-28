// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.compose

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calebc42.ebp.wire.ENUMS
import com.calebc42.ebp.wire.TEXT_INPUT_CONTRACT
import com.calebc42.ebp.wire.textInputScalarToUtf16
import com.calebc42.jetpacs.renderer.model.ActionHandoff
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Accepted, toolkit-neutral presentation members of one EBP `text_input`. */
data class TextInputPresentation(
    val id: String,
    val label: String?,
    val hint: String?,
    val supportingText: String?,
    val prefix: String?,
    val suffix: String?,
    val leadingIcon: String?,
    val trailingIcon: String?,
    val password: Boolean,
    val singleLine: Boolean,
    val monospace: Boolean,
    val syntax: String?,
    val autofocus: Boolean,
    val enabled: Boolean,
    val isError: Boolean,
    val variant: String,
    val mask: String?,
    val contentPadding: Dp?,
    val hideKeyboardOnSubmit: Boolean,
    val keyboardOptions: KeyboardOptions,
    val lineLimits: TextFieldLineLimits,
)

/**
 * Shared behavioral binding consumed by alternate `text_input` presentations.
 *
 * Renderers may decorate [presentation], [controller], and [focusRequester];
 * they must not reparse JSON or bypass the controller for editing actions.
 */
@Stable
class TextInputBinding internal constructor(
    val presentation: TextInputPresentation,
    val controller: TextInputController,
    val focusRequester: FocusRequester,
) {
    /** Enabled state including the contract-required pending-secret lock. */
    val enabled: Boolean
        get() = presentation.enabled &&
            !(presentation.password && controller.passwordSubmissionPending)

    /** Submit the current logical value through the ordinary action host. */
    fun submit(): ActionHandoff = controller.submit()
}

/** Initial state selected from authored content and an accepted retained value. */
internal data class TextInputSeed(
    val text: String,
    val selection: TextRange,
)

/**
 * Bind one admitted canonical node to shared state, reconciliation, and action
 * behavior. The containing render identity owns this composable invocation.
 */
@Composable
fun rememberTextInputBinding(
    node: JsonObject,
    context: ComposeNodeRenderContext,
): TextInputBinding {
    val id = node.requiredString("id")
    val password = node.boolean("password")
    val singleLine = node.boolean("single_line")
    val stored = (context.storeValue(id) as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
    val seed = textInputSeed(node, stored)
    val onSubmit = node.obj("on_submit")
    val controller = rememberTextInputController(
        presentationEpoch = context.epochOf(id),
        initialText = seed.text,
        initialSelection = seed.selection,
        config = TextInputControllerConfig(
            id = id,
            password = password,
            singleLine = singleLine,
            filter = node.string("filter"),
            maxLengthScalars = (node["max_length"] as? JsonPrimitive)?.longOrNull,
            clearOnSubmit = node.boolean("clear_on_submit"),
            onChange = node.obj("on_change"),
            onSubmit = onSubmit,
            publishPasswordLocally = context.inDialog,
        ),
        maxFieldBytes = context.maxFieldBytes,
        publishState = { context.state(id, JsonPrimitive(it)) },
        actionDispatcher = EditingActionDispatcher {
                descriptor, value, fields, sourceId, onOutcome ->
            context.dispatchAction(
                descriptor = descriptor,
                value = value,
                fields = fields,
                sourceId = sourceId,
                onOutcome = onOutcome,
            )
        },
    )
    val presentation = textInputPresentationOf(node)
    val focusRequester = rememberPresentationFocusRequester(
        identity = context.path,
        autofocus = presentation.autofocus,
    )
    return remember(presentation, controller, focusRequester) {
        TextInputBinding(presentation, controller, focusRequester)
    }
}

/** Pure contract projection shared by unit tests and both renderers. */
internal fun textInputPresentationOf(node: JsonObject): TextInputPresentation {
    val password = node.boolean("password")
    val singleLine = node.boolean("single_line")
    val onSubmit = node.obj("on_submit")
    return TextInputPresentation(
        id = node.requiredString("id"),
        label = node.nonEmptyString("label"),
        hint = node.nonEmptyString("hint"),
        supportingText = node.nonEmptyString("supporting_text"),
        prefix = node.nonEmptyString("prefix"),
        suffix = node.nonEmptyString("suffix"),
        leadingIcon = node.nonEmptyString("leading_icon"),
        trailingIcon = node.nonEmptyString("trailing_icon"),
        password = password,
        singleLine = singleLine,
        monospace = node.boolean("monospace"),
        syntax = node.nonEmptyString("syntax"),
        autofocus = node.boolean("autofocus"),
        enabled = node.boolean("enabled", true),
        isError = node.boolean("is_error"),
        variant = node.textInputVariant(),
        mask = node.nonEmptyString("mask"),
        contentPadding = (node["content_padding"] as? JsonPrimitive)
            ?.doubleOrNull?.toFloat()
            ?.takeIf { it.isFinite() && it >= 0f }
            ?.dp,
        hideKeyboardOnSubmit = node.boolean("hide_keyboard_on_submit"),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType(node.string("keyboard"), password),
            imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default,
        ),
        lineLimits = node.lineLimits(singleLine),
    )
}

/** Pure state seed selection implementing retained-draft and scalar rules. */
internal fun textInputSeed(node: JsonObject, stored: String?): TextInputSeed {
    val password = node.boolean("password")
    val authored = node.string("value") ?: ""
    val initialText = if (password) "" else stored ?: authored
    val retainedDraftWins = !password && stored != null && stored != authored
    val authoredSelection = node.array("selection")
        ?.mapNotNull { (it as? JsonPrimitive)?.longOrNull?.toInt() }
    val selection = if (retainedDraftWins || authoredSelection == null) {
        TextRange(initialText.length)
    } else {
        val start = authoredSelection.getOrElse(0) { 0 }
        val end = authoredSelection.getOrElse(1) { start }
        TextRange(
            textInputScalarToUtf16(initialText, start),
            textInputScalarToUtf16(initialText, end),
        )
    }
    return TextInputSeed(initialText, selection)
}

/** Request focus at most once for one accepted presentation identity. */
@Composable
fun rememberPresentationFocusRequester(
    identity: String,
    autofocus: Boolean,
): FocusRequester {
    val requester = remember(identity) { FocusRequester() }
    var requested by rememberSaveable(identity) { mutableStateOf(false) }
    LaunchedEffect(requester, autofocus) {
        if (autofocus && !requested) {
            requested = true
            requester.requestFocus()
        }
    }
    return requester
}

/** Standard IME callback preserving the renderer-local hide-on-handoff rule. */
fun TextInputBinding.keyboardAction(
    hideKeyboard: () -> Unit,
): KeyboardActionHandler = KeyboardActionHandler {
    if (submit() == ActionHandoff.HandedOff && presentation.hideKeyboardOnSubmit) {
        hideKeyboard()
    }
}

private fun JsonObject.lineLimits(singleLine: Boolean): TextFieldLineLimits {
    if (singleLine) return TextFieldLineLimits.SingleLine
    val minimum = integer("min_lines") ?: 1
    val maximum = integer("max_lines") ?: minimum
    return TextFieldLineLimits.MultiLine(
        minHeightInLines = minimum,
        maxHeightInLines = maximum,
    )
}

private fun JsonObject.textInputVariant(): String {
    val authored = string("variant") ?: return TEXT_INPUT_CONTRACT.variantDefault
    return authored.takeIf { it in ENUMS.getValue("text_input.variant") }
        ?: TEXT_INPUT_CONTRACT.variantUnknown
}

private fun keyboardType(name: String?, password: Boolean): KeyboardType {
    if (password) return KeyboardType.Password
    return when (name) {
        "number" -> KeyboardType.Number
        "decimal" -> KeyboardType.Decimal
        "email" -> KeyboardType.Email
        "phone" -> KeyboardType.Phone
        "uri" -> KeyboardType.Uri
        else -> KeyboardType.Text
    }
}

private fun JsonObject.requiredString(name: String): String =
    (get(name) as JsonPrimitive).content

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.nonEmptyString(name: String): String? =
    string(name)?.takeIf { it.isNotEmpty() }

private fun JsonObject.boolean(name: String, default: Boolean = false): Boolean =
    (get(name) as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonObject.integer(name: String): Int? =
    (get(name) as? JsonPrimitive)?.longOrNull
        ?.coerceIn(1L, Int.MAX_VALUE.toLong())
        ?.toInt()

private fun JsonObject.obj(name: String): JsonObject? = get(name) as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = get(name) as? JsonArray
