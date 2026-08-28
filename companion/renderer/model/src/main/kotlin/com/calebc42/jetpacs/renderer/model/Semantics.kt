// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import com.calebc42.ebp.companion.render.arrOrNull
import com.calebc42.ebp.companion.render.boolOrNull
import com.calebc42.ebp.companion.render.numOrNull
import com.calebc42.ebp.companion.render.objOrNull
import com.calebc42.ebp.companion.render.stringOrNull
import com.calebc42.ebp.wire.DEFAULT_NODE_SEMANTICS
import com.calebc42.ebp.wire.TEXT_INPUT_CONTRACT
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Toolkit-neutral authored collection bounds from SPEC 16.5.1. */
data class SemanticCollection(
    val rowCount: Long,
    val columnCount: Long,
)

/** Toolkit-neutral position within the nearest authored collection. */
data class SemanticCollectionItem(
    val rowIndex: Long,
    val rowSpan: Long,
    val columnIndex: Long,
    val columnSpan: Long,
)

/** One admitted custom action; [descriptor] enters the ordinary action path. */
data class SemanticAction(
    val label: String,
    val descriptor: JsonObject,
)

/** Derived checked state, including the checkbox indeterminate state. */
enum class SemanticToggleState {
    OFF,
    ON,
    INDETERMINATE,
}

/** A determinate range when [current] is non-null, otherwise indeterminate. */
data class SemanticProgress(
    val current: Double?,
    val minimum: Double,
    val maximum: Double,
    val steps: Int = 0,
)

/** State the receiver derives from existing Node members, never Semantics. */
data class NodeSemanticState(
    val enabled: Boolean? = null,
    val readOnly: Boolean? = null,
    val checked: Boolean? = null,
    val toggleState: SemanticToggleState? = null,
    val selected: Boolean? = null,
    val selection: JsonElement? = null,
    val expanded: Boolean? = null,
    val progress: SemanticProgress? = null,
    val maxTextLength: Int? = null,
)

/** Live renderer state that legitimately supersedes an authored default. */
data class SemanticStateOverride(
    val expanded: Boolean? = null,
)

/**
 * Complete toolkit-neutral projection of the optional Semantics envelope.
 *
 * [role] is contract-derived and cannot be authored by the envelope. The
 * renderer applies these properties additively and must not clear descendants.
 */
data class NodeSemantics(
    val accessibleName: String,
    val exposesAccessibleName: Boolean,
    val description: String? = null,
    val stateDescription: String? = null,
    val error: String? = null,
    val paneTitle: String? = null,
    val headingLevel: Int? = null,
    val liveRegion: String? = null,
    val collection: SemanticCollection? = null,
    val collectionItem: SemanticCollectionItem? = null,
    val traversalGroup: Boolean? = null,
    val traversalIndex: Double? = null,
    val role: String? = null,
    val state: NodeSemanticState = NodeSemanticState(),
    val actions: List<SemanticAction> = emptyList(),
)

/** Resolve SPEC 16.4's first-nonblank accessible-name precedence. */
fun resolveAccessibleName(node: JsonObject): String {
    val semantics = node.objOrNull("semantics")
    return semantics?.stringOrNull("name")?.takeIf(String::isNotBlank)
        ?: node.stringOrNull("content_description")?.takeIf(String::isNotBlank)
        ?: node.stringOrNull("label")?.takeIf(String::isNotBlank)
        ?: node.iconNameOrNull()?.takeIf(String::isNotBlank)
        ?: node.stringOrNull("t")?.takeIf(String::isNotBlank)
        ?: "node"
}

/**
 * Project admitted raw EBP [node] data without introducing a parallel UI AST.
 * [override] carries only live presentation state, such as a locally expanded
 * collapsible; roles and every other default remain contract-generated.
 */
fun projectNodeSemantics(
    node: JsonObject,
    override: SemanticStateOverride = SemanticStateOverride(),
): NodeSemantics {
    val type = node.stringOrNull("t").orEmpty()
    val authored = node.objOrNull("semantics")
    val defaults = DEFAULT_NODE_SEMANTICS[type]
    val role = defaults?.role?.takeIf {
        defaults.roleConditionMember == null ||
            defaults.roleConditionMember in node
    }

    val toggleState = defaults?.toggleStateMember?.let { member ->
        when (node.stringOrNull(member)) {
            "on" -> SemanticToggleState.ON
            "indeterminate" -> SemanticToggleState.INDETERMINATE
            "off" -> SemanticToggleState.OFF
            else -> null
        }
    } ?: defaults?.checkedMember?.let { member ->
        val checked = node.boolOrNull(member) ?: defaults.checkedDefault
        checked?.let {
            if (it) SemanticToggleState.ON else SemanticToggleState.OFF
        }
    }

    val checked = when (toggleState) {
        SemanticToggleState.ON -> true
        SemanticToggleState.OFF -> false
        SemanticToggleState.INDETERMINATE, null -> null
    }
    val selected = defaults?.selectedMember?.let { member ->
        node.boolOrNull(member) ?: defaults.selectedDefault
    }
    val selection = defaults?.selectionMember?.let { member ->
        node[member] ?: defaults.selectionDefaultIndex?.let(::JsonPrimitive)
    }
    val expanded = override.expanded ?: defaults?.expandedMember?.let { member ->
        val authoredValue = node.boolOrNull(member) ?: false
        if (defaults.expandedInverted) !authoredValue else authoredValue
    }

    val progress = defaults?.progressValueMember?.let { valueMember ->
        val discrete = node.arrOrNull("values")
            ?.mapNotNull(JsonElement::numOrNull)
            ?.takeIf { it.size >= 2 }
        val minimum = discrete?.firstOrNull()
            ?: defaults.progressMinMember?.let(node::numberOrNull)
            ?: defaults.progressMin
            ?: 0.0
        val maximum = discrete?.lastOrNull()
            ?: defaults.progressMaxMember?.let(node::numberOrNull)
            ?: defaults.progressMax
            ?: 1.0
        val current = node[valueMember]?.numOrNull()
            ?: minimum.takeIf { defaults.progressValueDefaultsToMin }
        SemanticProgress(
            current = current,
            minimum = minimum,
            maximum = maximum,
            steps = (discrete?.size?.minus(2) ?: 0).coerceAtLeast(0),
        )
    }

    val actionsByLabel = linkedMapOf<String, SemanticAction>()
    authored?.arrOrNull("actions")?.forEach { element ->
        val action = element as? JsonObject ?: return@forEach
        val label = action.stringOrNull("label") ?: return@forEach
        val descriptor = action.objOrNull("on_action") ?: return@forEach
        actionsByLabel.putIfAbsent(label, SemanticAction(label, descriptor))
    }
    defaults?.customActionsFrom?.forEach { member ->
        val side = node.objOrNull(member) ?: return@forEach
        val label = side.stringOrNull("label") ?: return@forEach
        val descriptor = side.objOrNull("on_trigger") ?: return@forEach
        actionsByLabel.putIfAbsent(label, SemanticAction(label, descriptor))
    }

    val authoredName = authored?.stringOrNull("name")?.takeIf(String::isNotBlank)
    val explicitLegacyName = node.stringOrNull("content_description")
        ?.takeIf(String::isNotBlank)
    val explicitLabel = node.stringOrNull("label")?.takeIf(String::isNotBlank)
    val explicitIcon = node.iconNameOrNull()?.takeIf(String::isNotBlank)
    val exposesName = authoredName != null || explicitLegacyName != null ||
        explicitLabel != null || explicitIcon != null || role != null ||
        actionsByLabel.isNotEmpty()
    val explicitError = authored?.nonBlankString("error")
    val projectedError = explicitError ?: if (
        type == "text_input" && node.boolOrNull("is_error") == true
    ) {
        TEXT_INPUT_CONTRACT.errorDescriptionPrecedence.firstNotNullOfOrNull { source ->
            when (source) {
                "semantics.error" -> explicitError
                "supporting_text" -> node.nonBlankString("supporting_text")
                else -> error("unsupported generated error-description source $source")
            }
        } ?: "Invalid input"
    } else {
        null
    }

    return NodeSemantics(
        accessibleName = resolveAccessibleName(node),
        exposesAccessibleName = exposesName,
        description = authored?.nonBlankString("description"),
        stateDescription = authored?.nonBlankString("state_description"),
        error = projectedError,
        paneTitle = authored?.nonBlankString("pane_title"),
        headingLevel = authored?.integerOrNull("heading_level")
            ?: defaults?.headingLevel,
        liveRegion = authored?.nonBlankString("live_region"),
        collection = authored?.objOrNull("collection")?.let { collection ->
            SemanticCollection(
                rowCount = collection.longOrZero("row_count"),
                columnCount = collection.longOrZero("column_count"),
            )
        },
        collectionItem = authored?.objOrNull("collection_item")?.let { item ->
            SemanticCollectionItem(
                rowIndex = item.longOrZero("row_index"),
                rowSpan = item.longOrZero("row_span"),
                columnIndex = item.longOrZero("column_index"),
                columnSpan = item.longOrZero("column_span"),
            )
        },
        traversalGroup = authored?.boolOrNull("traversal_group"),
        traversalIndex = authored?.get("traversal_index")?.numOrNull(),
        role = role,
        state = NodeSemanticState(
            enabled = defaults?.enabledMember?.let { member ->
                node.boolOrNull(member) ?: true
            },
            readOnly = defaults?.readOnlyMember?.let { member ->
                val value = node.boolOrNull(member) ?: false
                if (defaults.readOnlyInverted) !value else value
            },
            checked = checked,
            toggleState = toggleState,
            selected = selected,
            selection = selection,
            expanded = expanded,
            progress = progress,
            maxTextLength = if (type == "text_input") {
                node["max_length"]?.numOrNull()?.toLong()
                    ?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
            } else {
                null
            },
        ),
        actions = actionsByLabel.values.toList(),
    )
}

private fun JsonObject.nonBlankString(member: String): String? =
    stringOrNull(member)?.takeIf(String::isNotBlank)

private fun JsonObject.integerOrNull(member: String): Int? =
    this[member]?.numOrNull()?.toInt()

private fun JsonObject.longOrZero(member: String): Long =
    this[member]?.numOrNull()?.toLong() ?: 0L

private fun JsonObject.numberOrNull(member: String): Double? =
    this[member]?.numOrNull()

private fun JsonObject.iconNameOrNull(): String? =
    stringOrNull("icon") ?: if (stringOrNull("t") == "icon") {
        stringOrNull("name")
    } else {
        null
    }
