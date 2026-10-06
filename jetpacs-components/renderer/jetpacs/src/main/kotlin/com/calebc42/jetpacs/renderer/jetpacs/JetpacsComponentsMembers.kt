// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-components.json (format 1) by
// tools/gen-renderer-extension-vocabulary.py. DO NOT EDIT: JetpacsComponentsVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.renderer.model.vocabulary.ActionMember
import com.calebc42.ebp.renderer.model.vocabulary.AnyMember
import com.calebc42.ebp.renderer.model.vocabulary.ArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.BooleanMember
import com.calebc42.ebp.renderer.model.vocabulary.ChildNodeMember
import com.calebc42.ebp.renderer.model.vocabulary.IdentifierMember
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
    val Id: IdentifierMember = IdentifierMember("id", required = true)
    val Label: StringMember = StringMember("label", required = true)
    val Checked: BooleanMember = BooleanMember("checked", required = true)
    val OnChange: ActionMember = ActionMember("on_change", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    override val required: List<NodeMember<*>> = listOf(Id, Label, Checked, OnChange)
    override val optional: List<NodeMember<*>> = listOf(Enabled)
}

/** Typed members of the `jetpacs.list_item` node. */
object JetpacsListItemMembers : NodeMemberSet("jetpacs.list_item") {
    val Title: StringMember = StringMember("title", required = true)
    val Overline: AnyMember = AnyMember("overline", required = false, tag = "untyped")
    val Subtitle: AnyMember = AnyMember("subtitle", required = false, tag = "untyped")
    val TitleMaxLines: AnyMember = AnyMember("title_max_lines", required = false, tag = "untyped")
    val SubtitleMaxLines: AnyMember = AnyMember("subtitle_max_lines", required = false, tag = "untyped")
    val Leading: AnyMember = AnyMember("leading", required = false, tag = "untyped")
    val Trailing: ChildNodeMember = ChildNodeMember("trailing", required = false)
    val OnTap: ActionMember = ActionMember("on_tap", required = false)
    val OnLongTap: ActionMember = ActionMember("on_long_tap", required = false)
    val SwipeStart: ObjectMember = ObjectMember("swipe_start", required = false, tag = "swipe-object")
    val SwipeEnd: ObjectMember = ObjectMember("swipe_end", required = false, tag = "swipe-object")
    val Selected: AnyMember = AnyMember("selected", required = false, tag = "untyped")
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    override val required: List<NodeMember<*>> = listOf(Title)
    override val optional: List<NodeMember<*>> = listOf(Overline, Subtitle, TitleMaxLines, SubtitleMaxLines, Leading, Trailing, OnTap, OnLongTap, SwipeStart, SwipeEnd, Selected, Enabled)
}

/** Typed members of the `jetpacs.panel` node. */
object JetpacsPanelMembers : NodeMemberSet("jetpacs.panel") {
    val Label: StringMember = StringMember("label", required = true)
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    override val required: List<NodeMember<*>> = listOf(Label, Children)
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
    val Id: IdentifierMember = IdentifierMember("id", required = true)
    val Options: ArrayMember = ArrayMember("options", required = true, tag = "enum-option-array")
    val Value: AnyMember = AnyMember("value", required = true, tag = "untyped")
    val OnChange: ActionMember = ActionMember("on_change", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val Pinned: AnyMember = AnyMember("pinned", required = false, tag = "untyped")
    override val required: List<NodeMember<*>> = listOf(Id, Options, Value, OnChange)
    override val optional: List<NodeMember<*>> = listOf(Enabled, Pinned)
}

/** Typed members of the `jetpacs.tabs` node. */
object JetpacsTabsMembers : NodeMemberSet("jetpacs.tabs") {
    val Id: IdentifierMember = IdentifierMember("id", required = true)
    val Options: ArrayMember = ArrayMember("options", required = true, tag = "enum-option-array")
    val Value: AnyMember = AnyMember("value", required = true, tag = "untyped")
    val OnChange: ActionMember = ActionMember("on_change", required = true)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val Scrollable: BooleanMember = BooleanMember("scrollable", required = false)
    val Pinned: AnyMember = AnyMember("pinned", required = false, tag = "untyped")
    val Variant: StringMember = StringMember("variant", required = false)
    override val required: List<NodeMember<*>> = listOf(Id, Options, Value, OnChange)
    override val optional: List<NodeMember<*>> = listOf(Enabled, Scrollable, Pinned, Variant)
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
