// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-design.json (format
// 2) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: JetpacsDesignVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.NodeRow
import com.calebc42.ebp.wire.ExtensionNodeSchema
import com.calebc42.ebp.wire.ExtensionValueSchema

const val JETPACS_DESIGN_EXTENSION = "jetpacs.design"

val JETPACS_DESIGN_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
    "jetpacs.design_scope" to NodeRow(setOf("tokens", "styles", "children"), setOf("motions")),
    "jetpacs.styled" to NodeRow(setOf("styles", "children"), setOf()),
    "jetpacs.pressable" to NodeRow(setOf("styles", "on_tap", "children"), setOf("enabled", "selected", "toggled")),
)

val JETPACS_DESIGN_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
    "app" to setOf("jetpacs.design_scope", "jetpacs.styled", "jetpacs.pressable"),
    "dialog" to setOf(),
    "notification" to setOf(),
)

val JETPACS_DESIGN_STATEFUL_WHEN_PRESENT: Map<String, String> = mapOf(

)

val JETPACS_DESIGN_AT_LEAST_ONE_NON_EMPTY: Map<String, Set<String>> = mapOf(

)

val JETPACS_DESIGN_TYPED_NODE_SCHEMA: Map<String, ExtensionNodeSchema> = mapOf(
    "jetpacs.design_scope" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(0, 10000), "styles" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("properties" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("kind" to ExtensionValueSchema.EnumValue(setOf("boolean", "color", "dimension", "font-family", "font-weight", "number", "text-align", "token")), "value" to ExtensionValueSchema.StringValue(1, 256)), mapOf()), 0, 64), "rules" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("properties" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("kind" to ExtensionValueSchema.EnumValue(setOf("boolean", "color", "dimension", "font-family", "font-weight", "number", "text-align", "token")), "value" to ExtensionValueSchema.StringValue(1, 256)), mapOf()), 0, 64), "state" to ExtensionValueSchema.EnumValue(setOf("disabled", "focused", "hovered", "pressed", "selected", "toggled"))), mapOf("motion" to ExtensionValueSchema.StringValue(1, 128))), 0, 16)), mapOf("motion" to ExtensionValueSchema.StringValue(1, 128))), 0, 256), "tokens" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("kind" to ExtensionValueSchema.EnumValue(setOf("boolean", "color", "dimension", "font-family", "font-weight", "number", "text-align", "token")), "value" to ExtensionValueSchema.StringValue(1, 256)), mapOf()), 0, 256)), mapOf("motions" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("duration_ms" to ExtensionValueSchema.IntegerValue(0L, 10000L), "easing" to ExtensionValueSchema.EnumValue(setOf("ease-in", "ease-in-out", "ease-out", "linear", "spring"))), mapOf()), 0, 64))),
    "jetpacs.styled" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(0, 10000), "styles" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.StringValue(1, 128), 1, 16)), mapOf()),
    "jetpacs.pressable" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(1, 16), "on_tap" to ExtensionValueSchema.ActionDescriptorValue, "styles" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.StringValue(1, 128), 1, 16)), mapOf("enabled" to ExtensionValueSchema.BooleanValue, "selected" to ExtensionValueSchema.BooleanValue, "toggled" to ExtensionValueSchema.BooleanValue)),
)

const val JETPACS_DESIGN_SCHEMA_SHA256 = "dcfc1bc52138a4e9bf40e5eca4580cc23a92d8bcc3e809645683080c38d8be52"
