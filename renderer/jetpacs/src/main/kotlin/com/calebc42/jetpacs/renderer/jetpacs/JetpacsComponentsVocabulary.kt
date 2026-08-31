// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-components.json (format
// 1) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: JetpacsComponentsVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.NodeRow
import com.calebc42.ebp.wire.SelectionOptionsRule
import com.calebc42.ebp.wire.TrueRequiresParentRule

const val JETPACS_COMPONENTS_EXTENSION = "jetpacs.components"

val JETPACS_COMPONENTS_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
    "jetpacs.action" to NodeRow(setOf("label", "on_tap"), setOf("enabled")),
    "jetpacs.choice" to NodeRow(setOf("id", "label", "checked", "on_change"), setOf("enabled")),
    "jetpacs.panel" to NodeRow(setOf("label", "children"), setOf()),
    "jetpacs.scope" to NodeRow(setOf("children"), setOf()),
    "jetpacs.section_navigator" to NodeRow(setOf("id", "options", "value", "on_change"), setOf("enabled", "pinned")),
    "jetpacs.tabs" to NodeRow(setOf("id", "options", "value", "on_change"), setOf("enabled", "scrollable", "pinned", "variant")),
)

val JETPACS_COMPONENTS_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
    "app" to setOf("jetpacs.action", "jetpacs.choice", "jetpacs.panel", "jetpacs.scope", "jetpacs.section_navigator", "jetpacs.tabs"),
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
