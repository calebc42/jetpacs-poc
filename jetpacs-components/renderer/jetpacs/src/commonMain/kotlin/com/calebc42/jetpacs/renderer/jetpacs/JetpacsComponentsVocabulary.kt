// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-components.json (format
// 2) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: JetpacsComponentsVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.NodeRow
import com.calebc42.ebp.wire.ExtensionNodeSchema
import com.calebc42.ebp.wire.ExtensionValueSchema
import com.calebc42.ebp.wire.SelectionOptionsRule
import com.calebc42.ebp.wire.TrueRequiresParentRule

const val JETPACS_COMPONENTS_EXTENSION = "jetpacs.components"

val JETPACS_COMPONENTS_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
    "jetpacs.action" to NodeRow(setOf("label", "on_tap"), setOf("enabled")),
    "jetpacs.choice" to NodeRow(setOf("id", "label", "checked", "on_change"), setOf("enabled")),
    "jetpacs.list_item" to NodeRow(setOf("title"), setOf("overline", "subtitle", "title_max_lines", "subtitle_max_lines", "leading", "trailing", "on_tap", "on_long_tap", "swipe_start", "swipe_end", "selected", "enabled")),
    "jetpacs.panel" to NodeRow(setOf("label", "children"), setOf()),
    "jetpacs.scope" to NodeRow(setOf("children"), setOf()),
    "jetpacs.section_navigator" to NodeRow(setOf("id", "options", "value", "on_change"), setOf("enabled", "pinned")),
    "jetpacs.tabs" to NodeRow(setOf("id", "options", "value", "on_change"), setOf("enabled", "scrollable", "pinned", "variant")),
)

val JETPACS_COMPONENTS_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
    "app" to setOf("jetpacs.action", "jetpacs.choice", "jetpacs.list_item", "jetpacs.panel", "jetpacs.scope", "jetpacs.section_navigator", "jetpacs.tabs"),
    "dialog" to setOf(),
    "notification" to setOf(),
)

val JETPACS_COMPONENTS_STATEFUL_WHEN_PRESENT: Map<String, String> = mapOf(
    "jetpacs.choice" to "checked",
)

val JETPACS_COMPONENTS_AT_LEAST_ONE_NON_EMPTY: Map<String, Set<String>> = mapOf(
    "jetpacs.action" to setOf("label"),
    "jetpacs.choice" to setOf("label"),
    "jetpacs.panel" to setOf("label"),
)

val JETPACS_COMPONENTS_SELECTION_OPTIONS: Map<String, SelectionOptionsRule> = mapOf(
    "jetpacs.section_navigator" to SelectionOptionsRule("options", "value", "label", "value", requiredOptionMembers = mapOf("level" to "integer-1-6"), optionalOptionMembers = mapOf()),
    "jetpacs.tabs" to SelectionOptionsRule("options", "value", "label", "value"),
)

val JETPACS_COMPONENTS_TRUE_REQUIRES_PARENT: Map<String, TrueRequiresParentRule> = mapOf(
    "jetpacs.section_navigator" to TrueRequiresParentRule("pinned", setOf("lazy_column")),
    "jetpacs.tabs" to TrueRequiresParentRule("pinned", setOf("lazy_column")),
)

val JETPACS_COMPONENTS_LAZY_COLUMN_STICKY_MEMBERS: Map<String, String> = mapOf(
    "jetpacs.section_navigator" to "pinned",
    "jetpacs.tabs" to "pinned",
)

val JETPACS_COMPONENTS_TYPED_NODE_SCHEMA: Map<String, ExtensionNodeSchema> = mapOf(
    "jetpacs.action" to ExtensionNodeSchema(mapOf("label" to ExtensionValueSchema.StringValue(0, 4096), "on_tap" to ExtensionValueSchema.ActionDescriptorValue), mapOf("enabled" to ExtensionValueSchema.BooleanValue)),
    "jetpacs.choice" to ExtensionNodeSchema(mapOf("checked" to ExtensionValueSchema.BooleanValue, "id" to ExtensionValueSchema.StringValue(1, 128), "label" to ExtensionValueSchema.StringValue(0, 4096), "on_change" to ExtensionValueSchema.ActionDescriptorValue), mapOf("enabled" to ExtensionValueSchema.BooleanValue)),
    "jetpacs.list_item" to ExtensionNodeSchema(mapOf("title" to ExtensionValueSchema.StringValue(0, 4096)), mapOf("enabled" to ExtensionValueSchema.BooleanValue, "leading" to ExtensionValueSchema.NodeValue, "on_long_tap" to ExtensionValueSchema.ActionDescriptorValue, "on_tap" to ExtensionValueSchema.ActionDescriptorValue, "overline" to ExtensionValueSchema.StringValue(0, 4096), "selected" to ExtensionValueSchema.BooleanValue, "subtitle" to ExtensionValueSchema.StringValue(0, 4096), "subtitle_max_lines" to ExtensionValueSchema.IntegerValue(1L, 10000L), "swipe_end" to ExtensionValueSchema.SwipeSideValue, "swipe_start" to ExtensionValueSchema.SwipeSideValue, "title_max_lines" to ExtensionValueSchema.IntegerValue(1L, 10000L), "trailing" to ExtensionValueSchema.NodeValue)),
    "jetpacs.panel" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(0, 10000), "label" to ExtensionValueSchema.StringValue(0, 4096)), mapOf()),
    "jetpacs.scope" to ExtensionNodeSchema(mapOf("children" to ExtensionValueSchema.NodeArrayValue(0, 10000)), mapOf()),
    "jetpacs.section_navigator" to ExtensionNodeSchema(mapOf("id" to ExtensionValueSchema.StringValue(1, 128), "on_change" to ExtensionValueSchema.ActionDescriptorValue, "options" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("label" to ExtensionValueSchema.StringValue(0, 4096), "level" to ExtensionValueSchema.IntegerValue(1L, 6L), "value" to ExtensionValueSchema.StringValue(1, 4096)), mapOf()), 1, 10000), "value" to ExtensionValueSchema.StringValue(1, 4096)), mapOf("enabled" to ExtensionValueSchema.BooleanValue, "pinned" to ExtensionValueSchema.BooleanValue)),
    "jetpacs.tabs" to ExtensionNodeSchema(mapOf("id" to ExtensionValueSchema.StringValue(1, 128), "on_change" to ExtensionValueSchema.ActionDescriptorValue, "options" to ExtensionValueSchema.ArrayValue(ExtensionValueSchema.ObjectValue(mapOf("label" to ExtensionValueSchema.StringValue(0, 4096), "value" to ExtensionValueSchema.StringValue(1, 4096)), mapOf()), 1, 10000), "value" to ExtensionValueSchema.StringValue(1, 4096)), mapOf("enabled" to ExtensionValueSchema.BooleanValue, "pinned" to ExtensionValueSchema.BooleanValue, "scrollable" to ExtensionValueSchema.BooleanValue, "variant" to ExtensionValueSchema.StringValue(1, 64))),
)

const val JETPACS_COMPONENTS_SCHEMA_SHA256 = "efc0fd59a656b4f10279ac752a07edaef4de9549986e100d20f74706602d85d0"
