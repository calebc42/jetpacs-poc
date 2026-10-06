// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-components.json (format 2) by
// tools/gen-renderer-extension-vocabulary.py. DO NOT EDIT: JetpacsComponentsVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.renderer.model.vocabulary.ActionMember
import com.calebc42.ebp.renderer.model.vocabulary.ArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.BooleanMember
import com.calebc42.ebp.renderer.model.vocabulary.ChildNodeMember
import com.calebc42.ebp.renderer.model.vocabulary.IntMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMemberSet
import com.calebc42.ebp.renderer.model.vocabulary.ObjectMember
import com.calebc42.ebp.renderer.model.vocabulary.StringMember

/** Typed members of the `jetpacs.action` node. */
object JetpacsActionMembers : NodeMemberSet("jetpacs.action") {
    val Label: StringMember = StringMember("label", required = true)
    val OnTap: ActionMember = ActionMember("on_tap", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    override val required: List<NodeMember<*>> = listOf(Label, OnTap)
    override val optional: List<NodeMember<*>> = listOf(Enabled)
}

/** Typed members of the `jetpacs.choice` node. */
object JetpacsChoiceMembers : NodeMemberSet("jetpacs.choice") {
    val Checked: BooleanMember = BooleanMember("checked", required = true)
    val Id: StringMember = StringMember("id", required = true)
    val Label: StringMember = StringMember("label", required = true)
    val OnChange: ActionMember = ActionMember("on_change", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    override val required: List<NodeMember<*>> = listOf(Checked, Id, Label, OnChange)
    override val optional: List<NodeMember<*>> = listOf(Enabled)
}

/** Typed members of the `jetpacs.list_item` node. */
object JetpacsListItemMembers : NodeMemberSet("jetpacs.list_item") {
    val Title: StringMember = StringMember("title", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val Leading: ChildNodeMember = ChildNodeMember("leading", required = false)
    val OnLongTap: ActionMember = ActionMember("on_long_tap", required = false)
    val OnTap: ActionMember = ActionMember("on_tap", required = false)
    val Overline: StringMember = StringMember("overline", required = false)
    val Selected: BooleanMember = BooleanMember("selected", required = false)
    val Subtitle: StringMember = StringMember("subtitle", required = false)
    val SubtitleMaxLines: IntMember = IntMember("subtitle_max_lines", required = false, tag = "integer", minimum = 1L, maximum = 10000L)
    val SwipeEnd: ObjectMember = ObjectMember("swipe_end", required = false, tag = "swipe-object")
    val SwipeStart: ObjectMember = ObjectMember("swipe_start", required = false, tag = "swipe-object")
    val TitleMaxLines: IntMember = IntMember("title_max_lines", required = false, tag = "integer", minimum = 1L, maximum = 10000L)
    val Trailing: ChildNodeMember = ChildNodeMember("trailing", required = false)
    override val required: List<NodeMember<*>> = listOf(Title)
    override val optional: List<NodeMember<*>> = listOf(Enabled, Leading, OnLongTap, OnTap, Overline, Selected, Subtitle, SubtitleMaxLines, SwipeEnd, SwipeStart, TitleMaxLines, Trailing)
}

/** Typed members of the `jetpacs.panel` node. */
object JetpacsPanelMembers : NodeMemberSet("jetpacs.panel") {
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    val Label: StringMember = StringMember("label", required = true)
    override val required: List<NodeMember<*>> = listOf(Children, Label)
    override val optional: List<NodeMember<*>> = listOf()
}

/** Typed members of the `jetpacs.scope` node. */
object JetpacsScopeMembers : NodeMemberSet("jetpacs.scope") {
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    override val required: List<NodeMember<*>> = listOf(Children)
    override val optional: List<NodeMember<*>> = listOf()
}

/** Typed members of the `jetpacs.section_navigator` node. */
object JetpacsSectionNavigatorMembers : NodeMemberSet("jetpacs.section_navigator") {
    val Id: StringMember = StringMember("id", required = true)
    val OnChange: ActionMember = ActionMember("on_change", required = true)
    val Options: ArrayMember = ArrayMember("options", required = true, tag = "array")
    val Value: StringMember = StringMember("value", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val Pinned: BooleanMember = BooleanMember("pinned", required = false)
    override val required: List<NodeMember<*>> = listOf(Id, OnChange, Options, Value)
    override val optional: List<NodeMember<*>> = listOf(Enabled, Pinned)
}

/** Typed members of the `jetpacs.tabs` node. */
object JetpacsTabsMembers : NodeMemberSet("jetpacs.tabs") {
    val Id: StringMember = StringMember("id", required = true)
    val OnChange: ActionMember = ActionMember("on_change", required = true)
    val Options: ArrayMember = ArrayMember("options", required = true, tag = "array")
    val Value: StringMember = StringMember("value", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val Pinned: BooleanMember = BooleanMember("pinned", required = false)
    val Scrollable: BooleanMember = BooleanMember("scrollable", required = false)
    val Variant: StringMember = StringMember("variant", required = false)
    override val required: List<NodeMember<*>> = listOf(Id, OnChange, Options, Value)
    override val optional: List<NodeMember<*>> = listOf(Enabled, Pinned, Scrollable, Variant)
}

/** Every generated member set, keyed by node type. */
val JETPACS_COMPONENTS_NODE_MEMBERS: Map<String, NodeMemberSet> = mapOf(
    "jetpacs.action" to JetpacsActionMembers,
    "jetpacs.choice" to JetpacsChoiceMembers,
    "jetpacs.list_item" to JetpacsListItemMembers,
    "jetpacs.panel" to JetpacsPanelMembers,
    "jetpacs.scope" to JetpacsScopeMembers,
    "jetpacs.section_navigator" to JetpacsSectionNavigatorMembers,
    "jetpacs.tabs" to JetpacsTabsMembers,
)
