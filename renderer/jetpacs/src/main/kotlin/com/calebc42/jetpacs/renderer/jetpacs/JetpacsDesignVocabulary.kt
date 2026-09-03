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
    "jetpacs.design_scope" to NodeRow(setOf("tokens", "styles", "children"), setOf("motions", "component_styles", "theme_roles")),
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
    "jetpacs.design_scope" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(0, 10000), "styles" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("properties" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("kind" to ExtensionValueSchema.EnumValue(setOf("boolean", "color", "dimension", "font-family", "font-weight", "number", "text-align", "theme-role", "token")), "value" to ExtensionValueSchema.StringValue(1, 256)), mapOf()), 0, 64), "rules" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("properties" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("kind" to ExtensionValueSchema.EnumValue(setOf("boolean", "color", "dimension", "font-family", "font-weight", "number", "text-align", "theme-role", "token")), "value" to ExtensionValueSchema.StringValue(1, 256)), mapOf()), 0, 64), "state" to ExtensionValueSchema.EnumValue(setOf("disabled", "focused", "hovered", "pressed", "selected", "toggled"))), mapOf("motion" to ExtensionValueSchema.StringValue(1, 128))), 0, 16)), mapOf("motion" to ExtensionValueSchema.StringValue(1, 128))), 0, 256), "tokens" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("kind" to ExtensionValueSchema.EnumValue(setOf("boolean", "color", "dimension", "font-family", "font-weight", "number", "text-align", "theme-role", "token")), "value" to ExtensionValueSchema.StringValue(1, 256)), mapOf()), 0, 256)), mapOf("component_styles" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("slot" to ExtensionValueSchema.EnumValue(setOf("action.container", "action.label", "badge.container", "badge.label", "button.elevated", "button.filled", "button.label", "button.outlined", "button.text", "button.tonal", "card.elevated", "card.filled", "card.outlined", "chip.container", "chip.label", "choice.container", "choice.indicator", "choice.label", "divider.line", "editor.candidate-document", "editor.chromeless", "editor.completion-item", "editor.completion-list", "editor.gutter", "editor.surface", "editor.sync-status", "editor.text", "editor.toolbar", "editor.toolbar-item", "editor.tooling-status", "empty-state.caption", "empty-state.container", "empty-state.title", "icon-button.container", "list-item.container", "list-item.overline", "list-item.subtitle", "list-item.title", "menu.container", "menu.group-label", "menu.item", "menu.item-label", "menu.item-supporting", "menu.trigger", "panel.container", "panel.label", "section-header.container", "section-header.title", "section-navigator.button", "section-navigator.container", "section-navigator.label", "section-navigator.option", "section-navigator.popup", "section-navigator.popup-item", "section-navigator.selector", "swipe.cell", "swipe.label", "tabs.container", "tabs.indicator", "tabs.item", "tabs.label", "text-field.affix", "text-field.filled", "text-field.label", "text-field.outlined", "text-field.placeholder", "text-field.supporting", "text-field.text", "text.body", "text.caption", "text.headline", "text.label", "text.mono", "text.title")), "styles" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.StringValue(1, 128), 1, 16)), mapOf()), 0, 74), "motions" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("duration_ms" to ExtensionValueSchema.IntegerValue(0L, 10000L), "easing" to ExtensionValueSchema.EnumValue(setOf("ease-in", "ease-in-out", "ease-out", "linear", "spring"))), mapOf()), 0, 64), "theme_roles" to ExtensionValueSchema.IdentifierMapValue(ExtensionValueSchema.ObjectValue(mapOf("kind" to ExtensionValueSchema.EnumValue(setOf("boolean", "color", "dimension", "font-family", "font-weight", "number", "text-align", "theme-role", "token")), "value" to ExtensionValueSchema.StringValue(1, 256)), mapOf()), 0, 13))),
    "jetpacs.styled" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(0, 10000), "styles" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.StringValue(1, 128), 1, 16)), mapOf()),
    "jetpacs.pressable" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(1, 16), "on_tap" to ExtensionValueSchema.ActionDescriptorValue, "styles" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.StringValue(1, 128), 1, 16)), mapOf("enabled" to ExtensionValueSchema.BooleanValue, "selected" to ExtensionValueSchema.BooleanValue, "toggled" to ExtensionValueSchema.BooleanValue)),
)

const val JETPACS_DESIGN_SCHEMA_SHA256 = "23af154d4eee4d9317a0ab8f96390ac4e1cd04dab30919589cc9d8d44f621827"
