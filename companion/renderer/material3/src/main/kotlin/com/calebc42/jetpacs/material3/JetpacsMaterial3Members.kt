// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-material3.json (format 2) by
// tools/gen-renderer-extension-vocabulary.py. DO NOT EDIT: JetpacsMaterial3VocabularyTest pins this projection.
package com.calebc42.jetpacs.material3

import com.calebc42.ebp.renderer.model.vocabulary.ActionMember
import com.calebc42.ebp.renderer.model.vocabulary.ArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.BooleanMember
import com.calebc42.ebp.renderer.model.vocabulary.EnumMember
import com.calebc42.ebp.renderer.model.vocabulary.IntMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMemberSet
import com.calebc42.ebp.renderer.model.vocabulary.StringMember

/** Typed members of the `material3.assist_chip` node. */
object Material3AssistChipMembers : NodeMemberSet("material3.assist_chip") {
    val Label: StringMember = StringMember("label", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val Icon: StringMember = StringMember("icon", required = false)
    val OnTap: ActionMember = ActionMember("on_tap", required = false)
    val Variant: EnumMember<Material3AssistChipVariant> = EnumMember("variant", required = false, domain = com.calebc42.jetpacs.material3.Material3AssistChipVariant)
    override val required: List<NodeMember<*>> = listOf(Label)
    override val optional: List<NodeMember<*>> = listOf(Enabled, Icon, OnTap, Variant)
}

/** Typed members of the `material3.split_button` node. */
object Material3SplitButtonMembers : NodeMemberSet("material3.split_button") {
    val OnTap: ActionMember = ActionMember("on_tap", required = true)
    val Checked: BooleanMember = BooleanMember("checked", required = false)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val Icon: StringMember = StringMember("icon", required = false)
    val Items: ArrayMember = ArrayMember("items", required = false, tag = "array")
    val Label: StringMember = StringMember("label", required = false)
    val OnChange: ActionMember = ActionMember("on_change", required = false)
    val OnTrailingTap: ActionMember = ActionMember("on_trailing_tap", required = false)
    val Size: EnumMember<Material3SplitButtonSize> = EnumMember("size", required = false, domain = com.calebc42.jetpacs.material3.Material3SplitButtonSize)
    val TrailingDescription: StringMember = StringMember("trailing_description", required = false)
    val TrailingIcon: StringMember = StringMember("trailing_icon", required = false)
    val TrailingLabel: StringMember = StringMember("trailing_label", required = false)
    val Variant: EnumMember<Material3SplitButtonVariant> = EnumMember("variant", required = false, domain = com.calebc42.jetpacs.material3.Material3SplitButtonVariant)
    override val required: List<NodeMember<*>> = listOf(OnTap)
    override val optional: List<NodeMember<*>> = listOf(Checked, Enabled, Icon, Items, Label, OnChange, OnTrailingTap, Size, TrailingDescription, TrailingIcon, TrailingLabel, Variant)
}

/** Typed members of the `material3.app_bar_row` node. */
object Material3AppBarRowMembers : NodeMemberSet("material3.app_bar_row") {
    val Items: ArrayMember = ArrayMember("items", required = true, tag = "array")
    val MaxItems: IntMember = IntMember("max_items", required = false, tag = "integer", minimum = 1L, maximum = 64L)
    val OverflowIcon: StringMember = StringMember("overflow_icon", required = false)
    override val required: List<NodeMember<*>> = listOf(Items)
    override val optional: List<NodeMember<*>> = listOf(MaxItems, OverflowIcon)
}

/** Typed members of the `material3.app_bar_column` node. */
object Material3AppBarColumnMembers : NodeMemberSet("material3.app_bar_column") {
    val Items: ArrayMember = ArrayMember("items", required = true, tag = "array")
    val MaxItems: IntMember = IntMember("max_items", required = false, tag = "integer", minimum = 1L, maximum = 64L)
    val OverflowIcon: StringMember = StringMember("overflow_icon", required = false)
    override val required: List<NodeMember<*>> = listOf(Items)
    override val optional: List<NodeMember<*>> = listOf(MaxItems, OverflowIcon)
}

/** Typed members of the `material3.fab_menu` node. */
object Material3FabMenuMembers : NodeMemberSet("material3.fab_menu") {
    val Items: ArrayMember = ArrayMember("items", required = true, tag = "array")
    val CloseIcon: StringMember = StringMember("close_icon", required = false)
    val Icon: StringMember = StringMember("icon", required = false)
    override val required: List<NodeMember<*>> = listOf(Items)
    override val optional: List<NodeMember<*>> = listOf(CloseIcon, Icon)
}

/** Every generated member set, keyed by node type. */
val JETPACS_MATERIAL3_NODE_MEMBERS: Map<String, NodeMemberSet> = mapOf(
    "material3.assist_chip" to Material3AssistChipMembers,
    "material3.split_button" to Material3SplitButtonMembers,
    "material3.app_bar_row" to Material3AppBarRowMembers,
    "material3.app_bar_column" to Material3AppBarColumnMembers,
    "material3.fab_menu" to Material3FabMenuMembers,
)
