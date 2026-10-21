// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-material3.json (format
// 2) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: JetpacsMaterial3VocabularyTest pins this projection.
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.wire.NodeRow
import com.calebc42.ebp.wire.ExtensionNodeSchema
import com.calebc42.ebp.wire.ExtensionValueSchema

const val JETPACS_MATERIAL3_EXTENSION = "jetpacs.material3"

val JETPACS_MATERIAL3_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
    "material3.assist_chip" to NodeRow(setOf("label"), setOf("on_tap", "icon", "enabled", "variant")),
    "material3.split_button" to NodeRow(setOf("on_tap"), setOf("label", "icon", "variant", "size", "trailing_icon", "trailing_label", "trailing_description", "checked", "on_change", "on_trailing_tap", "items", "enabled")),
    "material3.app_bar_row" to NodeRow(setOf("items"), setOf("overflow_icon", "max_items")),
    "material3.app_bar_column" to NodeRow(setOf("items"), setOf("overflow_icon", "max_items")),
    "material3.fab_menu" to NodeRow(setOf("items"), setOf("icon", "close_icon")),
)

val JETPACS_MATERIAL3_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
    "app" to setOf("material3.assist_chip", "material3.split_button", "material3.app_bar_row", "material3.app_bar_column", "material3.fab_menu"),
    "dialog" to setOf("material3.assist_chip", "material3.split_button"),
    "notification" to setOf(),
)

val JETPACS_MATERIAL3_STATEFUL_WHEN_PRESENT: Map<String, String> = mapOf(
    "material3.split_button" to "checked",
)

val JETPACS_MATERIAL3_AT_LEAST_ONE_NON_EMPTY: Map<String, Set<String>> = mapOf(
    "material3.split_button" to setOf("label", "icon"),
)

val JETPACS_MATERIAL3_TYPED_NODE_SCHEMA: Map<String, ExtensionNodeSchema> = mapOf(
    "material3.assist_chip" to ExtensionNodeSchema(mapOf("label" to ExtensionValueSchema.StringValue(0, 4096)), mapOf("enabled" to ExtensionValueSchema.BooleanValue, "icon" to ExtensionValueSchema.StringValue(1, 128), "on_tap" to ExtensionValueSchema.ActionDescriptorValue, "variant" to ExtensionValueSchema.EnumValue(setOf("elevated", "elevated_suggestion", "flat", "suggestion")))),
    "material3.split_button" to ExtensionNodeSchema(mapOf("on_tap" to ExtensionValueSchema.ActionDescriptorValue), mapOf("checked" to ExtensionValueSchema.BooleanValue, "enabled" to ExtensionValueSchema.BooleanValue, "icon" to ExtensionValueSchema.StringValue(1, 128), "items" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("label" to ExtensionValueSchema.StringValue(0, 4096), "on_tap" to ExtensionValueSchema.ActionDescriptorValue), mapOf("enabled" to ExtensionValueSchema.BooleanValue, "icon" to ExtensionValueSchema.StringValue(1, 128))), 0, 64), "label" to ExtensionValueSchema.StringValue(0, 4096), "on_change" to ExtensionValueSchema.ActionDescriptorValue, "on_trailing_tap" to ExtensionValueSchema.ActionDescriptorValue, "size" to ExtensionValueSchema.EnumValue(setOf("large", "medium", "small", "xlarge", "xsmall")), "trailing_description" to ExtensionValueSchema.StringValue(0, 4096), "trailing_icon" to ExtensionValueSchema.StringValue(1, 128), "trailing_label" to ExtensionValueSchema.StringValue(0, 4096), "variant" to ExtensionValueSchema.EnumValue(setOf("elevated", "filled", "outlined", "tonal")))),
    "material3.app_bar_row" to ExtensionNodeSchema(mapOf("items" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("icon" to ExtensionValueSchema.StringValue(1, 128), "label" to ExtensionValueSchema.StringValue(0, 4096), "on_tap" to ExtensionValueSchema.ActionDescriptorValue), mapOf("enabled" to ExtensionValueSchema.BooleanValue)), 1, 64)), mapOf("max_items" to ExtensionValueSchema.IntegerValue(1L, 64L), "overflow_icon" to ExtensionValueSchema.StringValue(1, 128))),
    "material3.app_bar_column" to ExtensionNodeSchema(mapOf("items" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("icon" to ExtensionValueSchema.StringValue(1, 128), "label" to ExtensionValueSchema.StringValue(0, 4096), "on_tap" to ExtensionValueSchema.ActionDescriptorValue), mapOf("enabled" to ExtensionValueSchema.BooleanValue)), 1, 64)), mapOf("max_items" to ExtensionValueSchema.IntegerValue(1L, 64L), "overflow_icon" to ExtensionValueSchema.StringValue(1, 128))),
    "material3.fab_menu" to ExtensionNodeSchema(mapOf("items" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("icon" to ExtensionValueSchema.StringValue(1, 128), "label" to ExtensionValueSchema.StringValue(0, 4096), "on_tap" to ExtensionValueSchema.ActionDescriptorValue), mapOf()), 1, 64)), mapOf("close_icon" to ExtensionValueSchema.StringValue(1, 128), "icon" to ExtensionValueSchema.StringValue(1, 128))),
)

const val JETPACS_MATERIAL3_SCHEMA_SHA256 = "99105d126640bae463fd4359753903f109ca9cfc68685f5ef7764e9068e27a87"
