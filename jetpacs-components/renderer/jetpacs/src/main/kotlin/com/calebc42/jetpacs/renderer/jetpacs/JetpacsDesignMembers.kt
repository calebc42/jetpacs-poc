// SPDX-License-Identifier: GPL-3.0-or-later
// GENERATED from renderer-extensions/jetpacs-design.json (format 2) by
// tools/gen-renderer-extension-vocabulary.py. DO NOT EDIT: JetpacsDesignVocabularyTest pins this projection.
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.renderer.model.vocabulary.ActionMember
import com.calebc42.ebp.renderer.model.vocabulary.ArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.BooleanMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeArrayMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMember
import com.calebc42.ebp.renderer.model.vocabulary.NodeMemberSet
import com.calebc42.ebp.renderer.model.vocabulary.ObjectMember

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
    val Selected: BooleanMember = BooleanMember("selected", required = false)
    val Toggled: BooleanMember = BooleanMember("toggled", required = false)
    override val required: List<NodeMember<*>> = listOf(Children, OnTap, Styles)
    override val optional: List<NodeMember<*>> = listOf(Enabled, Selected, Toggled)
}

/** Every generated member set, keyed by node type. */
val JETPACS_DESIGN_NODE_MEMBERS: Map<String, NodeMemberSet> = mapOf(
    "jetpacs.design_scope" to JetpacsDesignScopeMembers,
    "jetpacs.styled" to JetpacsStyledMembers,
    "jetpacs.pressable" to JetpacsPressableMembers,
)
