// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/glasspane-material3.json (format
// 1) by tools/gen-renderer-extension-vocabulary.py.
// DO NOT EDIT: GlasspaneMaterial3VocabularyTest pins this projection.
package com.calebc42.ebp.companion.render

import com.calebc42.ebp.wire.NodeRow

const val GLASSPANE_MATERIAL3_EXTENSION = "glasspane.material3"

val GLASSPANE_MATERIAL3_NODE_SCHEMA: Map<String, NodeRow> = mapOf(
    "material3.assist_chip" to NodeRow(setOf("label"), setOf("on_tap", "icon", "enabled", "variant")),
    "material3.split_button" to NodeRow(setOf("on_tap"), setOf("label", "icon", "variant", "size", "trailing_icon", "trailing_label", "trailing_description", "checked", "on_change", "on_trailing_tap", "items", "enabled")),
    "material3.app_bar_row" to NodeRow(setOf("items"), setOf("overflow_icon", "max_items")),
    "material3.app_bar_column" to NodeRow(setOf("items"), setOf("overflow_icon", "max_items")),
    "material3.fab_menu" to NodeRow(setOf("items"), setOf("icon", "close_icon")),
)

val GLASSPANE_MATERIAL3_TARGET_NODE_TYPES: Map<String, Set<String>> = mapOf(
    "app" to setOf("material3.assist_chip", "material3.split_button", "material3.app_bar_row", "material3.app_bar_column", "material3.fab_menu"),
    "dialog" to setOf("material3.assist_chip", "material3.split_button"),
    "notification" to setOf(),
)

val GLASSPANE_MATERIAL3_STATEFUL_WHEN_PRESENT: Map<String, String> = mapOf(
    "material3.split_button" to "checked",
)

val GLASSPANE_MATERIAL3_AT_LEAST_ONE_NON_EMPTY: Map<String, Set<String>> = mapOf(
    "material3.split_button" to setOf("label", "icon"),
)
