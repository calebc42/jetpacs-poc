// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.ACTION_HOOK_KEYS
import com.calebc42.ebp.wire.ExtensionSemanticValidator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Semantic admission for the closed, renderer-owned design language. */
object JetpacsDesignSemanticValidator : ExtensionSemanticValidator {
    private val opaqueMembers = setOf("args", "meta", "value")
    private val passiveTypes = setOf(
        "text",
        "rich_text",
        "icon",
        "image",
        "date_stamp",
        "progress",
        "badge",
        "row",
        "column",
        "flow_row",
        "box",
        "surface",
        "spacer",
        "divider",
        "jetpacs.design_scope",
        "jetpacs.styled",
    )

    override fun validate(document: JsonObject, path: String) {
        walk(document, path, scope = null, scopeDepth = 0)
    }

    /** Recheck cached content before a pressable can acquire interaction. */
    internal fun validatePressableForRender(
        node: JsonObject,
        scope: CompiledDesignScope,
        path: String,
    ): ComputedDesignStyle {
        val style = DesignModel.computedStyle(node, scope, path, baseOnly = false)
        val descriptor = node["on_tap"] as? JsonObject
            ?: invalid("$path.on_tap", "must be an action descriptor")
        val action = descriptor.stringMember("action")
        val builtin = descriptor.stringMember("builtin")
        if ((action == null) == (builtin == null)) {
            invalid("$path.on_tap", "must name exactly one action or builtin")
        }
        for (member in listOf("enabled", "selected", "toggled")) {
            val value = node[member] ?: continue
            val primitive = value as? JsonPrimitive
                ?: invalid("$path.$member", "must be a boolean")
            if (primitive.isString || primitive.content.toBooleanStrictOrNull() == null) {
                invalid("$path.$member", "must be a boolean")
            }
        }
        val children = node["children"] as? JsonArray
            ?: invalid("$path.children", "must be an array")
        if (children.size !in 1..16) {
            invalid("$path.children", "must contain 1 to 16 passive nodes")
        }
        children.forEachIndexed { index, child ->
            if (child !is JsonObject) {
                invalid("$path.children[$index]", "must be a node")
            }
            validatePassive(child, "$path.children[$index]")
        }
        return style
    }

    private fun walk(
        element: JsonElement,
        path: String,
        scope: CompiledDesignScope?,
        scopeDepth: Int,
    ) {
        when (element) {
            is JsonArray -> element.forEachIndexed { index, child ->
                walk(child, "$path[$index]", scope, scopeDepth)
            }
            is JsonObject -> {
                when (element.nodeType()) {
                    "jetpacs.design_scope" -> {
                        if (scopeDepth >= DesignModel.MAX_SCOPE_DEPTH) {
                            invalid(path, "design scope nesting exceeds ${DesignModel.MAX_SCOPE_DEPTH}")
                        }
                        val effective = DesignModel.compileScope(element, scope, path)
                        walkChildren(element, path, effective, scopeDepth + 1)
                    }
                    "jetpacs.styled" -> {
                        val effective = scope ?: invalid(path, "styled node requires a design scope")
                        DesignModel.computedStyle(element, effective, path, baseOnly = true)
                        walkChildren(element, path, effective, scopeDepth)
                    }
                    "jetpacs.pressable" -> {
                        val effective = scope ?: invalid(path, "pressable node requires a design scope")
                        DesignModel.computedStyle(element, effective, path, baseOnly = false)
                        val children = element["children"] as? JsonArray
                            ?: invalid("$path.children", "must be an array")
                        children.forEachIndexed { index, child ->
                            validatePassive(child, "$path.children[$index]")
                            walk(child, "$path.children[$index]", effective, scopeDepth)
                        }
                    }
                    else -> element.forEach { (member, child) ->
                        if (member !in opaqueMembers) {
                            walk(child, "$path.$member", scope, scopeDepth)
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun walkChildren(
        node: JsonObject,
        path: String,
        scope: CompiledDesignScope,
        scopeDepth: Int,
    ) {
        val children = node["children"] as? JsonArray
            ?: invalid("$path.children", "must be an array")
        children.forEachIndexed { index, child ->
            walk(child, "$path.children[$index]", scope, scopeDepth)
        }
    }

    private fun validatePassive(element: JsonElement, path: String) {
        when (element) {
            is JsonArray -> element.forEachIndexed { index, child ->
                validatePassive(child, "$path[$index]")
            }
            is JsonObject -> {
                element.nodeType()?.let { type ->
                    if (type !in passiveTypes) {
                        invalid(path, "pressable content type '$type' is not passive")
                    }
                    val actionMember = element.keys.firstOrNull {
                        it in ACTION_HOOK_KEYS || it == "swipe_start" || it == "swipe_end"
                    }
                    if (actionMember != null) {
                        invalid("$path.$actionMember", "pressable content must not own an action")
                    }
                }
                element.forEach { (member, child) ->
                    if (member !in opaqueMembers) validatePassive(child, "$path.$member")
                }
            }
            else -> Unit
        }
    }

    private fun JsonObject.nodeType(): String? {
        val primitive = this["t"] as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.contentOrNull else null
    }

    private fun JsonObject.stringMember(name: String): String? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        return primitive.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotEmpty)
    }
}
