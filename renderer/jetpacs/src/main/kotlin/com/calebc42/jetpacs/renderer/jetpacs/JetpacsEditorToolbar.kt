// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.rememberUpdatedStyleState
import androidx.compose.foundation.style.styleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.calebc42.ebp.wire.ToolbarEdit
import com.calebc42.ebp.wire.ToolbarEdits
import com.calebc42.ebp.renderer.compose.EditorController
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar

/**
 * Jetpacs presentation of an accepted inline EBP editor toolbar.
 *
 * Each item remains a separate accessible button. Local text transforms use
 * the shared pure EBP implementation and feed one edit back through
 * [EditorController], preserving publication and byte-limit behavior.
 */
@Composable
internal fun JetpacsEditorToolbar(
    items: JsonArray,
    controller: EditorController,
    dispatch: (JsonObject) -> Unit,
    onCommand: (String) -> Unit,
    enabled: Boolean,
    localDate: () -> String = ::localEditorDateStamp,
    localTime: () -> String = ::localEditorTimeStamp,
) {
    var pendingInput by remember { mutableStateOf<JsonObject?>(null) }
    val styleState = rememberUpdatedStyleState(null) { it.isEnabled = enabled }

    fun applySnippet(snippet: String, placement: String, input: String?) {
        val value = controller.snapshot()
        val result = ToolbarEdits.applySnippet(
            ToolbarEdit(
                value.text,
                value.selectionStartUtf16,
                value.selectionEndUtf16,
            ),
            snippet,
            placement,
            input,
            localDate(),
            localTime(),
        )
        controller.applyPresentationEdit(result.text, result.selStart, result.selEnd)
    }

    val runOperation: (JsonObject) -> Unit = run@{ operation ->
        if (!enabled) return@run
        val onTap = operation.editorToolbarObject("on_tap")
        val line = operation.editorToolbarString("line")
        when {
            onTap != null -> dispatch(onTap)
            line.isNotEmpty() -> {
                val value = controller.snapshot()
                ToolbarEdits.lineOp(
                    line,
                    ToolbarEdit(
                        value.text,
                        value.selectionStartUtf16,
                        value.selectionEndUtf16,
                    ),
                )?.let {
                    controller.applyPresentationEdit(it.text, it.selStart, it.selEnd)
                }
            }
            "snippet" in operation -> {
                val snippet = operation.editorToolbarString("snippet")
                if (ToolbarEdits.needsInput(snippet)) {
                    pendingInput = operation
                } else {
                    applySnippet(
                        snippet,
                        operation.editorToolbarString("placement"),
                        null,
                    )
                }
            }
            "command" in operation -> operation.editorToolbarString("command")
                .takeIf { it.isNotEmpty() }
                ?.let(onCommand)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .styleable(styleState, JetpacsTheme.styles.editorToolbar),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            (item as? JsonObject)?.let {
                JetpacsEditorToolbarItem(it, enabled, runOperation)
            }
        }
    }

    pendingInput?.let { operation ->
        val snippet = operation.editorToolbarString("snippet")
        JetpacsSnippetInputDialog(
            prompt = ToolbarEdits.inputPrompt(snippet),
            onDismiss = { pendingInput = null },
            onConfirm = { entry ->
                pendingInput = null
                applySnippet(
                    snippet,
                    operation.editorToolbarString("placement"),
                    entry,
                )
            },
        )
    }
}

@Composable
private fun JetpacsEditorToolbarItem(
    item: JsonObject,
    enabled: Boolean,
    runOperation: (JsonObject) -> Unit,
) {
    val menu = item["menu"] as? JsonArray
    if (menu != null) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            JetpacsEditorToolbarButton(
                item = item,
                enabled = enabled,
                onClick = { expanded = true },
            )
            if (expanded) {
                Popup(
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    Column(
                        modifier = Modifier.styleable(
                            remember { MutableStyleState(null) },
                            JetpacsTheme.styles.panel,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        menu.forEach { child ->
                            (child as? JsonObject)?.let { operation ->
                                JetpacsEditorToolbarButton(
                                    item = operation,
                                    enabled = enabled,
                                    onClick = {
                                        expanded = false
                                        runOperation(operation)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }
    val longPress = item.editorToolbarObject("long_press")
    JetpacsEditorToolbarButton(
        item = item,
        enabled = enabled,
        onClick = { runOperation(item) },
        onLongClick = longPress?.let { operation ->
            { runOperation(operation) }
        },
    )
}

@Composable
private fun JetpacsEditorToolbarButton(
    item: JsonObject,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val label = item.editorToolbarString("label")
    val icon = item.editorToolbarString("icon")
    val accessibleLabel = label.ifEmpty { icon.replace('-', ' ') }
    val source = remember { MutableInteractionSource() }
    val styleState = rememberUpdatedStyleState(source) { it.isEnabled = enabled }
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = accessibleLabel }
            .combinedClickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onLongClickLabel = onLongClick?.let { "Alternate $accessibleLabel" },
                onLongClick = onLongClick?.let { action ->
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        action()
                    }
                },
                onClick = onClick,
            )
            .styleable(styleState, JetpacsTheme.styles.editorToolbarItem),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon.isNotEmpty()) {
            JetpacsFieldGlyph(icon, Modifier.size(18.dp))
            if (label.isNotEmpty()) Spacer(Modifier.width(4.dp))
        }
        if (label.isNotEmpty()) {
            BasicText(label, style = JetpacsTheme.typography.fieldLabel)
        }
    }
}

/** The one receiver-local free-text prompt used by `${input:Prompt}`. */
@Composable
private fun JetpacsSnippetInputDialog(
    prompt: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val state = rememberTextFieldState()
    Dialog(onDismissRequest = onDismiss) {
        JetpacsPanel("SNIPPET INPUT") {
            BasicText(
                prompt,
                modifier = Modifier.semantics { heading() },
                style = JetpacsTheme.typography.choice,
            )
            JetpacsTextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Input" },
                label = "Input",
                lineLimits = TextFieldLineLimits.SingleLine,
            )
            JetpacsAction("Insert", onClick = { onConfirm(state.text.toString()) })
            JetpacsAction("Cancel", onClick = onDismiss)
        }
    }
}

/** SPEC 17.7 `${date}`: local `YYYY-MM-DD Day`. */
private fun localEditorDateStamp(): String {
    val calendar = Calendar.getInstance()
    val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    return "%04d-%02d-%02d %s".format(
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH) + 1,
        calendar.get(Calendar.DAY_OF_MONTH),
        days[calendar.get(Calendar.DAY_OF_WEEK) - 1],
    )
}

/** SPEC 17.7 `${time}`: local `HH:MM`. */
private fun localEditorTimeStamp(): String {
    val calendar = Calendar.getInstance()
    return "%02d:%02d".format(
        calendar.get(Calendar.HOUR_OF_DAY),
        calendar.get(Calendar.MINUTE),
    )
}

private fun JsonObject.editorToolbarString(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

private fun JsonObject.editorToolbarObject(name: String): JsonObject? = get(name) as? JsonObject

/** Stable toolbar chrome used only by deterministic screenshot tests. */
@Composable
internal fun JetpacsEditorToolbarFixture() {
    val styleState = remember {
        MutableStyleState(null).apply { isEnabled = true }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .styleable(styleState, JetpacsTheme.styles.editorToolbar),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf(
            buildJsonObject {
                put("label", "Heading")
                put("icon", "format-title")
            },
            buildJsonObject {
                put("label", "Link")
                put("icon", "link")
            },
            buildJsonObject {
                put("label", "More")
                put("icon", "more")
            },
        ).forEach { item ->
            JetpacsEditorToolbarButton(item, enabled = true, onClick = {})
        }
    }
}
