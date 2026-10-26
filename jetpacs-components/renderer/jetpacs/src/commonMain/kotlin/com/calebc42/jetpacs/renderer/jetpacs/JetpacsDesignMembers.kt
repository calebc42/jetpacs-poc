// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-design.json (format 2) by
// tools/gen-renderer-extension-vocabulary.py. DO NOT EDIT: JetpacsDesignVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.renderer.model.vocabulary.ActionMember
import com.calebc42.ebp.renderer.model.vocabulary.ArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.BooleanMember
import com.calebc42.ebp.renderer.model.vocabulary.ChildNodeMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMemberSet
import com.calebc42.ebp.renderer.model.vocabulary.NumberMember
import com.calebc42.ebp.renderer.model.vocabulary.ObjectMember
import com.calebc42.ebp.renderer.model.vocabulary.StringMember

/** Typed members of the `jetpacs.design_scope` node. */
object JetpacsDesignScopeMembers : NodeMemberSet("jetpacs.design_scope") {
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    val Styles: ObjectMember = ObjectMember("styles", required = true, tag = "identifier-map")
    val Tokens: ObjectMember = ObjectMember("tokens", required = true, tag = "identifier-map")
    val ComponentStyles: ArrayMember = ArrayMember("component_styles", required = false, tag = "array")
    val Motions: ObjectMember = ObjectMember("motions", required = false, tag = "identifier-map")
    val ThemeRoles: ObjectMember = ObjectMember("theme_roles", required = false, tag = "identifier-map")
    override val required: List<NodeMember<*>> = listOf(Children, Styles, Tokens)
    override val optional: List<NodeMember<*>> = listOf(ComponentStyles, Motions, ThemeRoles)
}

/** Typed members of the `jetpacs.styled` node. */
object JetpacsStyledMembers : NodeMemberSet("jetpacs.styled") {
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    val Styles: ArrayMember = ArrayMember("styles", required = true, tag = "array")
    override val required: List<NodeMember<*>> = listOf(Children, Styles)
    override val optional: List<NodeMember<*>> = listOf()
}

/** Typed members of the `jetpacs.pressable` node. */
object JetpacsPressableMembers : NodeMemberSet("jetpacs.pressable") {
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    val OnTap: ActionMember = ActionMember("on_tap", required = true)
    val Styles: ArrayMember = ArrayMember("styles", required = true, tag = "array")
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val OnLongTap: ActionMember = ActionMember("on_long_tap", required = false)
    val Selected: BooleanMember = BooleanMember("selected", required = false)
    val SwipeEnd: ObjectMember = ObjectMember("swipe_end", required = false, tag = "swipe-object")
    val SwipeStart: ObjectMember = ObjectMember("swipe_start", required = false, tag = "swipe-object")
    val Toggled: BooleanMember = BooleanMember("toggled", required = false)
    override val required: List<NodeMember<*>> = listOf(Children, OnTap, Styles)
    override val optional: List<NodeMember<*>> = listOf(Enabled, OnLongTap, Selected, SwipeEnd, SwipeStart, Toggled)
}

/** Typed members of the `jetpacs.glyph` node. */
object JetpacsGlyphMembers : NodeMemberSet("jetpacs.glyph") {
    val Paths: ArrayMember = ArrayMember("paths", required = true, tag = "array")
    val Size: NumberMember = NumberMember("size", required = true)
    val ViewportHeight: NumberMember = NumberMember("viewport_height", required = true)
    val ViewportWidth: NumberMember = NumberMember("viewport_width", required = true)
    val Color: StringMember = StringMember("color", required = false)
    val ContentDescription: StringMember = StringMember("content_description", required = false)
    override val required: List<NodeMember<*>> = listOf(Paths, Size, ViewportHeight, ViewportWidth)
    override val optional: List<NodeMember<*>> = listOf(Color, ContentDescription)
}

/** Typed members of the `jetpacs.inline_text` node. */
object JetpacsInlineTextMembers : NodeMemberSet("jetpacs.inline_text") {
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    val Style: StringMember = StringMember("style", required = false)
    override val required: List<NodeMember<*>> = listOf(Children)
    override val optional: List<NodeMember<*>> = listOf(Style)
}

/** Typed members of the `jetpacs.inline` node. */
object JetpacsInlineMembers : NodeMemberSet("jetpacs.inline") {
    val Child: ChildNodeMember = ChildNodeMember("child", required = true)
    val Height: NumberMember = NumberMember("height", required = true)
    val ReserveExtra: NumberMember = NumberMember("reserve_extra", required = false)
    val ReserveMax: NumberMember = NumberMember("reserve_max", required = false)
    val ReserveText: StringMember = StringMember("reserve_text", required = false)
    val ReserveTextSize: NumberMember = NumberMember("reserve_text_size", required = false)
    override val required: List<NodeMember<*>> = listOf(Child, Height)
    override val optional: List<NodeMember<*>> = listOf(ReserveExtra, ReserveMax, ReserveText, ReserveTextSize)
}

/** Typed members of the `jetpacs.wheel` node. */
object JetpacsWheelMembers : NodeMemberSet("jetpacs.wheel") {
    val Id: StringMember = StringMember("id", required = true)
    val Value: StringMember = StringMember("value", required = true)
    val Values: ArrayMember = ArrayMember("values", required = true, tag = "array")
    val Color: StringMember = StringMember("color", required = false)
    val DividerColor: StringMember = StringMember("divider_color", required = false)
    val Enabled: BooleanMember = BooleanMember("enabled", required = false)
    val OnChange: ActionMember = ActionMember("on_change", required = false)
    val Wrap: BooleanMember = BooleanMember("wrap", required = false)
    override val required: List<NodeMember<*>> = listOf(Id, Value, Values)
    override val optional: List<NodeMember<*>> = listOf(Color, DividerColor, Enabled, OnChange, Wrap)
}

/** Typed members of the `jetpacs.state_switch` node. */
object JetpacsStateSwitchMembers : NodeMemberSet("jetpacs.state_switch") {
    val Children: NodeArrayMember = NodeArrayMember("children", required = true)
    val Source: StringMember = StringMember("source", required = true)
    val Values: ArrayMember = ArrayMember("values", required = true, tag = "array")
    val Otherwise: ChildNodeMember = ChildNodeMember("otherwise", required = false)
    override val required: List<NodeMember<*>> = listOf(Children, Source, Values)
    override val optional: List<NodeMember<*>> = listOf(Otherwise)
}

/** Every generated member set, keyed by node type. */
val JETPACS_DESIGN_NODE_MEMBERS: Map<String, NodeMemberSet> = mapOf(
    "jetpacs.design_scope" to JetpacsDesignScopeMembers,
    "jetpacs.styled" to JetpacsStyledMembers,
    "jetpacs.pressable" to JetpacsPressableMembers,
    "jetpacs.glyph" to JetpacsGlyphMembers,
    "jetpacs.inline_text" to JetpacsInlineTextMembers,
    "jetpacs.inline" to JetpacsInlineMembers,
    "jetpacs.wheel" to JetpacsWheelMembers,
    "jetpacs.state_switch" to JetpacsStateSwitchMembers,
)
