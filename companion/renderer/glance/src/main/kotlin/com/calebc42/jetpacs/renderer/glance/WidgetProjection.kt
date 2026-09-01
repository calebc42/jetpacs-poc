// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.glance

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Select the first authored size variant whose minimums fit this instance. */
fun selectWidgetBody(spec: JsonObject, widthDp: Float, heightDp: Float): JsonObject? {
    val selected = (spec["size_variants"] as? JsonArray)
        ?.firstOrNull { raw ->
            val variant = raw as? JsonObject ?: return@firstOrNull false
            widthDp >= variant.number("min_width") &&
                heightDp >= variant.number("min_height")
        }
        ?.let { it as? JsonObject }
        ?.get("body") as? JsonObject
    val base = selected ?: spec["body"] as? JsonObject ?: return null
    return if (nodeHasPresentableContent(base)) {
        base
    } else {
        spec["empty"] as? JsonObject ?: base
    }
}

/** Conservative empty-state projection; actions and icons are presentable. */
fun nodeHasPresentableContent(node: JsonObject): Boolean = when (node.string("t")) {
    "text" -> node.string("text").isNotBlank()
    "icon", "icon_button", "button", "badge", "section_header" -> true
    "empty_state" -> listOf("title", "caption", "action_label", "icon")
        .any { node.string(it).isNotBlank() }
    "spacer", "divider" -> false
    else -> (node["children"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.any(::nodeHasPresentableContent)
        ?: (node["header"] as? JsonObject)?.let(::nodeHasPresentableContent)
        ?: false
}

internal fun JsonObject.string(member: String): String =
    (this[member] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

internal fun JsonObject.number(member: String): Float =
    (this[member] as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f

internal fun JsonObject.boolean(member: String, default: Boolean = false): Boolean =
    (this[member] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default

internal fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject
