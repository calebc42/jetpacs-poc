// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-components.json (format
// 1) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: JetpacsComponentsVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.NodeRow

const val JETPACS_COMPONENTS_EXTENSION = "jetpacs.components"

val JETPACS_COMPONENTS_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
    "jetpacs.action" to NodeRow(setOf("label", "on_tap"), setOf("enabled")),
    "jetpacs.choice" to NodeRow(setOf("id", "label", "checked", "on_change"), setOf("enabled")),
    "jetpacs.panel" to NodeRow(setOf("label", "children"), setOf()),
)

val JETPACS_COMPONENTS_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
    "app" to setOf("jetpacs.action", "jetpacs.choice", "jetpacs.panel"),
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
