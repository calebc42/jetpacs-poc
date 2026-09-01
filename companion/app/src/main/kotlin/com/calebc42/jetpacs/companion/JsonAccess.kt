// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * These readers are deliberately local to the Android adapter. The wire
 * module validates every inbound shape before the adapter runs, while this
 * layer still avoids kotlinx.serialization's scalar string coercions when it
 * translates a validated value into an Android API call.
 */
internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.stringOr(key: String, default: String = ""): String =
    stringOrNull(key) ?: default

internal fun JsonObject.reqString(key: String): String =
    stringOrNull(key) ?: throw NoSuchElementException(key)

internal fun JsonObject.boolOr(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString && it !is JsonNull }
        ?.content
        ?.toBooleanStrictOrNull()
        ?: default

internal fun integralLongOrNull(element: JsonElement?): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    primitive.content.toLongOrNull()?.let { return it }
    val value = primitive.content.toDoubleOrNull() ?: return null
    return if (value.isFinite() && value == floor(value)) value.toLong() else null
}
