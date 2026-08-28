// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.state.ToggleableState
import com.calebc42.jetpacs.renderer.model.NodeSemantics
import com.calebc42.jetpacs.renderer.model.SemanticStateOverride
import com.calebc42.jetpacs.renderer.model.SemanticToggleState
import com.calebc42.jetpacs.renderer.model.projectNodeSemantics
import kotlinx.serialization.json.JsonObject

/**
 * Project admitted EBP semantics through Compose Foundation.
 *
 * This modifier is additive: it never clears or merges descendant semantics,
 * and custom actions hand their already-admitted descriptors to [onAction]
 * through the same host path as visible controls. Returning `true` means that
 * handoff succeeded; it does not wait for a remote action to complete.
 */
fun Modifier.ebpSemantics(
    node: JsonObject,
    stateOverride: SemanticStateOverride = SemanticStateOverride(),
    onAction: (JsonObject) -> Unit,
): Modifier {
    val projected = projectNodeSemantics(node, stateOverride)
    if (!projected.hasComposeProperties()) return this

    return semantics {
        val description = projected.description
        if (projected.exposesAccessibleName || description != null) {
            contentDescription = if (description == null) {
                projected.accessibleName
            } else {
                "${projected.accessibleName}. $description"
            }
        }
        projected.stateDescription?.let { stateDescription = it }
            ?: projected.state.expanded?.let {
                stateDescription = if (it) "Expanded" else "Collapsed"
            }
        projected.error?.let { error(it) }
        projected.paneTitle?.let { paneTitle = it }
        if (projected.headingLevel != null) heading()
        projected.liveRegion?.let {
            liveRegion = when (it) {
                "assertive" -> LiveRegionMode.Assertive
                else -> LiveRegionMode.Polite
            }
        }
        projected.collection?.let {
            collectionInfo = CollectionInfo(
                rowCount = it.rowCount.toComposeCount(),
                columnCount = it.columnCount.toComposeCount(),
            )
        }
        projected.collectionItem?.let {
            collectionItemInfo = CollectionItemInfo(
                rowIndex = it.rowIndex.toComposeCount(),
                rowSpan = it.rowSpan.toComposeCount(),
                columnIndex = it.columnIndex.toComposeCount(),
                columnSpan = it.columnSpan.toComposeCount(),
            )
        }
        projected.traversalGroup?.let { isTraversalGroup = it }
        projected.traversalIndex?.let {
            traversalIndex = it.coerceIn(-Float.MAX_VALUE.toDouble(), Float.MAX_VALUE.toDouble())
                .toFloat()
        }
        projected.role.toComposeRole()?.let { role = it }
        if (projected.state.enabled == false) disabled()
        if (projected.role == "text_input" || projected.state.readOnly != null) {
            isEditable = projected.state.readOnly != true
        }
        projected.state.toggleState?.let {
            toggleableState = when (it) {
                SemanticToggleState.OFF -> ToggleableState.Off
                SemanticToggleState.ON -> ToggleableState.On
                SemanticToggleState.INDETERMINATE -> ToggleableState.Indeterminate
            }
        }
        projected.state.selected?.let { selected = it }
        projected.state.progress?.let {
            val current = it.current
            progressBarRangeInfo = if (current == null) {
                ProgressBarRangeInfo.Indeterminate
            } else {
                ProgressBarRangeInfo(
                    current = current.toFloat(),
                    range = it.minimum.toFloat()..it.maximum.toFloat(),
                    steps = it.steps,
                )
            }
        }
        if (projected.actions.isNotEmpty()) {
            customActions = projected.actions.map { action ->
                CustomAccessibilityAction(action.label) {
                    onAction(action.descriptor)
                    true
                }
            }
        }
    }
}

private fun NodeSemantics.hasComposeProperties(): Boolean =
    exposesAccessibleName || description != null || stateDescription != null ||
        error != null || paneTitle != null || headingLevel != null ||
        liveRegion != null || collection != null || collectionItem != null ||
        traversalGroup != null || traversalIndex != null || role != null ||
        state != com.calebc42.jetpacs.renderer.model.NodeSemanticState() ||
        actions.isNotEmpty()

private fun Long.toComposeCount(): Int =
    coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

private fun String?.toComposeRole(): Role? = when (this) {
    "button" -> Role.Button
    "checkbox" -> Role.Checkbox
    "switch" -> Role.Switch
    "image" -> Role.Image
    "dropdown" -> Role.DropdownList
    else -> null
}
