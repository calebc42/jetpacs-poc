// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host

import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * These readers are deliberately local to the Companion host and its
 * platform adapters. The wire module validates every inbound shape before the
 * host runs, while this layer still avoids kotlinx.serialization's scalar
 * string coercions when it translates a validated value into a platform call.
 */
fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.stringOr(key: String, default: String = ""): String =
    stringOrNull(key) ?: default

fun JsonObject.reqString(key: String): String =
    stringOrNull(key) ?: throw NoSuchElementException(key)

fun JsonObject.boolOr(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)
        ?.takeIf { !it.isString && it !is JsonNull }
        ?.content
        ?.toBooleanStrictOrNull()
        ?: default

fun integralLongOrNull(element: JsonElement?): Long? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    primitive.content.toLongOrNull()?.let { return it }
    val value = primitive.content.toDoubleOrNull() ?: return null
    return if (value.isFinite() && value == floor(value)) value.toLong() else null
}
