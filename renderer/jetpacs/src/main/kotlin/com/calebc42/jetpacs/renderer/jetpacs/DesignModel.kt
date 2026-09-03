// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.jetpacs

import com.calebc42.ebp.wire.ContentInvalid
import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Interaction states understood by the bounded design runtime. */
enum class DesignState {
    Disabled,
    Selected,
    Toggled,
    Hovered,
    Focused,
    Pressed,
}

/** Framework-independent values produced by design token compilation. */
sealed interface DesignValue {
    data class BooleanValue(val value: Boolean) : DesignValue
    data class ColorValue(val argb: Long) : DesignValue
    data class ThemeRoleValue(val value: DesignThemeRole) : DesignValue
    data class DimensionValue(val value: Double) : DesignValue
    data class NumberValue(val value: Double) : DesignValue
    data class FontFamilyValue(val value: DesignFontFamily) : DesignValue
    data class FontWeightValue(val value: Int) : DesignValue
    data class TextAlignValue(val value: DesignTextAlign) : DesignValue
}

/** Exact neutral color roles mirrored by EBP `theme.set`. */
enum class DesignThemeRole(val wireName: String) {
    Primary("primary"),
    OnPrimary("on_primary"),
    Secondary("secondary"),
    OnSecondary("on_secondary"),
    Error("error"),
    OnError("on_error"),
    Background("background"),
    OnBackground("on_background"),
    Surface("surface"),
    OnSurface("on_surface"),
    Outline("outline"),
    Success("success"),
    Warning("warning"),
    ;

    companion object {
        private val byWireName = entries.associateBy(DesignThemeRole::wireName)

        internal fun fromWireName(value: String): DesignThemeRole? = byWireName[value]
    }
}

/** Closed semantic-component visual slots exposed to design profiles. */
enum class DesignComponentStyleSlot(val wireName: String) {
    /** Canonical `text` presentation keyed by the node's `style` member. */
    TextBody("text.body"),
    TextTitle("text.title"),
    TextHeadline("text.headline"),
    TextCaption("text.caption"),
    TextLabel("text.label"),
    TextMono("text.mono"),
    /** Canonical `icon_button`, `badge` and `empty_state` presentation. */
    IconButtonContainer("icon-button.container"),
    BadgeContainer("badge.container"),
    BadgeLabel("badge.label"),
    EmptyStateContainer("empty-state.container"),
    EmptyStateTitle("empty-state.title"),
    EmptyStateCaption("empty-state.caption"),
    /** Canonical `card` containers keyed by variant. */
    CardFilled("card.filled"),
    CardElevated("card.elevated"),
    CardOutlined("card.outlined"),
    /** The Jetpacs list row and its text slots. */
    ListItemContainer("list-item.container"),
    ListItemOverline("list-item.overline"),
    ListItemTitle("list-item.title"),
    ListItemSubtitle("list-item.subtitle"),
    /** One revealed swipe cell and its label, shared by card and list item. */
    SwipeCell("swipe.cell"),
    SwipeLabel("swipe.label"),
    /** Canonical `button` containers keyed by variant, plus its label. */
    ButtonFilled("button.filled"),
    ButtonTonal("button.tonal"),
    ButtonElevated("button.elevated"),
    ButtonOutlined("button.outlined"),
    ButtonText("button.text"),
    ButtonLabel("button.label"),
    ChipContainer("chip.container"),
    ChipLabel("chip.label"),
    DividerLine("divider.line"),
    SectionHeaderContainer("section-header.container"),
    SectionHeaderTitle("section-header.title"),
    /** Canonical `menu`: its anchor, popup surface, rows and group heads. */
    MenuTrigger("menu.trigger"),
    MenuContainer("menu.container"),
    MenuItem("menu.item"),
    MenuItemLabel("menu.item-label"),
    MenuItemSupporting("menu.item-supporting"),
    MenuGroupLabel("menu.group-label"),
    /** Canonical `switch`: the track, the thumb that slides on it, its label. */
    SwitchTrack("switch.track"),
    SwitchThumb("switch.thumb"),
    SwitchLabel("switch.label"),
    ActionContainer("action.container"),
    ActionLabel("action.label"),
    ChoiceContainer("choice.container"),
    ChoiceIndicator("choice.indicator"),
    ChoiceLabel("choice.label"),
    PanelContainer("panel.container"),
    PanelLabel("panel.label"),
    TabsContainer("tabs.container"),
    TabsItem("tabs.item"),
    TabsIndicator("tabs.indicator"),
    TabsLabel("tabs.label"),
    SectionNavigatorContainer("section-navigator.container"),
    SectionNavigatorOption("section-navigator.option"),
    SectionNavigatorLabel("section-navigator.label"),
    SectionNavigatorButton("section-navigator.button"),
    SectionNavigatorSelector("section-navigator.selector"),
    SectionNavigatorPopup("section-navigator.popup"),
    SectionNavigatorPopupItem("section-navigator.popup-item"),
    TextFieldOutlined("text-field.outlined"),
    TextFieldFilled("text-field.filled"),
    TextFieldText("text-field.text"),
    TextFieldLabel("text-field.label"),
    TextFieldPlaceholder("text-field.placeholder"),
    TextFieldSupporting("text-field.supporting"),
    TextFieldAffix("text-field.affix"),
    EditorSurface("editor.surface"),
    EditorChromeless("editor.chromeless"),
    EditorText("editor.text"),
    EditorGutter("editor.gutter"),
    EditorToolbar("editor.toolbar"),
    EditorToolbarItem("editor.toolbar-item"),
    EditorSyncStatus("editor.sync-status"),
    EditorCompletionList("editor.completion-list"),
    EditorCompletionItem("editor.completion-item"),
    EditorCandidateDocument("editor.candidate-document"),
    EditorToolingStatus("editor.tooling-status"),
    ;

    companion object {
        private val byWireName = entries.associateBy(DesignComponentStyleSlot::wireName)

        internal fun fromWireName(value: String): DesignComponentStyleSlot? = byWireName[value]
    }
}

enum class DesignFontFamily {
    System,
    PlexSans,
    PlexSerif,
    PlexMono,
}

enum class DesignTextAlign {
    Start,
    Center,
    End,
}

enum class DesignEasing {
    Linear,
    EaseIn,
    EaseOut,
    EaseInOut,
    Spring,
}

data class DesignMotion(
    val durationMillis: Int,
    val easing: DesignEasing,
)

/** Closed set of properties exposed to Elisp authoring. */
enum class DesignProperty(val wireName: String, internal val kind: ValueKind) {
    BackgroundColor("background_color", ValueKind.Color),
    ContentColor("content_color", ValueKind.Color),
    BorderColor("border_color", ValueKind.Color),
    BorderWidth("border_width", ValueKind.Dimension),
    CornerRadius("corner_radius", ValueKind.Dimension),
    Padding("padding", ValueKind.Dimension),
    PaddingHorizontal("padding_horizontal", ValueKind.Dimension),
    PaddingVertical("padding_vertical", ValueKind.Dimension),
    PaddingStart("padding_start", ValueKind.Dimension),
    PaddingTop("padding_top", ValueKind.Dimension),
    PaddingEnd("padding_end", ValueKind.Dimension),
    PaddingBottom("padding_bottom", ValueKind.Dimension),
    Width("width", ValueKind.Dimension),
    Height("height", ValueKind.Dimension),
    MinWidth("min_width", ValueKind.Dimension),
    MinHeight("min_height", ValueKind.Dimension),
    Alpha("alpha", ValueKind.Number),
    Scale("scale", ValueKind.Number),
    FontSize("font_size", ValueKind.Dimension),
    LineHeight("line_height", ValueKind.Dimension),
    LetterSpacing("letter_spacing", ValueKind.Dimension),
    FontFamily("font_family", ValueKind.FontFamily),
    FontWeight("font_weight", ValueKind.FontWeight),
    TextAlign("text_align", ValueKind.TextAlign),
    FillWidth("fill_width", ValueKind.Boolean),
    ;

    companion object {
        private val byWireName = entries.associateBy(DesignProperty::wireName)

        internal fun fromWireName(value: String): DesignProperty? = byWireName[value]
    }
}

internal enum class ValueKind {
    Boolean,
    Color,
    Dimension,
    Number,
    FontFamily,
    FontWeight,
    TextAlign,
}

/** One authored base or state layer after all references have been resolved. */
data class DesignStyleLayer(
    val state: DesignState?,
    val properties: Map<DesignProperty, DesignValue>,
    val motion: DesignMotion?,
)

/** Immutable, ordered style program. Later matching layers win per property. */
class ComputedDesignStyle internal constructor(
    val layers: List<DesignStyleLayer>,
) {
    fun baseOnly(): ComputedDesignStyle =
        ComputedDesignStyle(layers.filter { it.state == null })

    fun resolve(activeStates: Set<DesignState> = emptySet()): ResolvedDesignStyle {
        val properties = LinkedHashMap<DesignProperty, DesignValue>()
        var motion: DesignMotion? = null
        for (layer in layers) {
            if (layer.state == null || layer.state in activeStates) {
                properties.putAll(layer.properties)
                if (layer.motion != null) motion = layer.motion
            }
        }
        return ResolvedDesignStyle(properties.toMap(), motion)
    }
}

data class ResolvedDesignStyle(
    val properties: Map<DesignProperty, DesignValue>,
    val motion: DesignMotion?,
)

internal data class CompiledStyle(
    val base: Map<DesignProperty, DesignValue>,
    val rules: List<DesignStyleLayer>,
    val motion: DesignMotion?,
)

/**
 * An immutable effective scope. Raw declarations are retained only so a
 * nested scope can replace identifiers and re-resolve inherited styles.
 */
class CompiledDesignScope internal constructor(
    private val canonical: Lazy<String>,
    val configurationBytes: Int,
    internal val tokens: Map<String, JsonElement>,
    internal val styleDeclarations: Map<String, JsonElement>,
    internal val motionDeclarations: Map<String, JsonElement>,
    internal val componentStyleDeclarations: Map<String, JsonElement>,
    internal val themeRoleDeclarations: Map<String, JsonElement>,
    private val styles: Map<String, CompiledStyle>,
    private val componentStyles: Map<DesignComponentStyleSlot, ComputedDesignStyle>,
    /**
     * EBP theme roles this scope re-declares for its subtree. Each value is a
     * literal color or a reference to an ambient role, so a scope can alias
     * one role to another as well as fix a palette.
     */
    val themeRoles: Map<DesignThemeRole, DesignValue>,
) {
    val canonicalContent: String
        get() = canonical.value

    fun computedStyle(styleNames: List<String>, path: String): ComputedDesignStyle {
        val layers = mutableListOf<DesignStyleLayer>()
        for ((index, name) in styleNames.withIndex()) {
            val style = styles[name]
                ?: invalid("$path[$index]", "unresolved style '$name'")
            layers += DesignStyleLayer(null, style.base, style.motion)
            layers += style.rules
        }
        return ComputedDesignStyle(layers.toList())
    }

    /** Return the effective authored program for [slot], when one is bound. */
    fun componentStyle(slot: DesignComponentStyleSlot): ComputedDesignStyle? =
        componentStyles[slot]
}

/** Pure compiler shared by admission and rendering. */
object DesignModel {
    const val MAX_CONFIGURATION_BYTES: Int = 256 * 1024
    const val MAX_SCOPE_DEPTH: Int = 8
    private const val MAX_CACHE_ENTRIES = 32

    private class ScopeContentKey(
        val componentStyles: Map<String, JsonElement>,
        val motions: Map<String, JsonElement>,
        val styles: Map<String, JsonElement>,
        val tokens: Map<String, JsonElement>,
        val themeRoles: Map<String, JsonElement>,
        private val contentHash: Int,
    ) {
        override fun hashCode(): Int = contentHash

        override fun equals(other: Any?): Boolean =
            other is ScopeContentKey &&
                contentHash == other.contentHash &&
                themeRoles == other.themeRoles &&
                componentStyles == other.componentStyles &&
                motions == other.motions &&
                styles == other.styles &&
                tokens == other.tokens
    }

    private val cache = object : LinkedHashMap<ScopeContentKey, CompiledDesignScope>(
        MAX_CACHE_ENTRIES + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ScopeContentKey, CompiledDesignScope>?,
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    /** Compile [node]'s effective configuration, inheriting from [parent]. */
    @Synchronized
    fun compileScope(
        node: JsonObject,
        parent: CompiledDesignScope? = null,
        path: String = "root",
    ): CompiledDesignScope {
        val localTokens = requiredObject(node, "tokens", path)
        val localStyles = requiredObject(node, "styles", path)
        val localMotions = optionalObject(node, "motions", path) ?: JsonObject(emptyMap())
        val localComponentStyles = componentStyleBindings(node, path)
        val localThemeRoles = themeRoleDeclarations(node, path)

        val tokens = merge(parent?.tokens, localTokens, 256, "$path.tokens")
        val styles = merge(parent?.styleDeclarations, localStyles, 256, "$path.styles")
        val motions = merge(parent?.motionDeclarations, localMotions, 64, "$path.motions")
        val componentStyles = merge(
            parent?.componentStyleDeclarations,
            JsonObject(localComponentStyles),
            DesignComponentStyleSlot.entries.size,
            "$path.component_styles",
        )
        val themeRoles = merge(
            parent?.themeRoleDeclarations,
            JsonObject(localThemeRoles),
            DesignThemeRole.entries.size,
            "$path.theme_roles",
        )
        val canonicalValues = mapOf(
            "component_styles" to JsonObject(componentStyles),
            "motions" to JsonObject(motions),
            "styles" to JsonObject(styles),
            "theme_roles" to JsonObject(themeRoles),
            "tokens" to JsonObject(tokens),
        )
        val canonicalMetrics = canonicalMetrics(JsonObject(canonicalValues))
        val configurationBytes = canonicalMetrics.utf8Bytes
        if (configurationBytes > MAX_CONFIGURATION_BYTES) {
            invalid(path, "design configuration exceeds 256 KiB")
        }
        val contentKey = ScopeContentKey(
            componentStyles,
            motions,
            styles,
            tokens,
            themeRoles,
            canonicalMetrics.contentHash,
        )
        cache[contentKey]?.let { return it }

        val compiledMotions = compileMotions(motions, path)
        val tokenResolver = TokenResolver(tokens, path)
        tokens.keys.forEach { tokenResolver.resolve(it, "$path.tokens.$it") }
        val compiledStyles = compileStyles(
            declarations = styles,
            motions = compiledMotions,
            tokens = tokenResolver,
            path = path,
        )
        validateLocalComponentStyleReferences(
            declarations = localComponentStyles,
            scopeStyles = compiledStyles,
            path = path,
        )
        val compiledComponentStyles = compileComponentStyles(
            declarations = componentStyles,
            scopeStyles = compiledStyles,
            path = path,
        )
        val compiledThemeRoles = compileThemeRoles(themeRoles, tokenResolver, path)
        return CompiledDesignScope(
            canonical = lazy(LazyThreadSafetyMode.PUBLICATION) {
                canonicalObject(canonicalValues)
            },
            configurationBytes = configurationBytes,
            tokens = tokens,
            styleDeclarations = styles,
            motionDeclarations = motions,
            componentStyleDeclarations = componentStyles,
            themeRoleDeclarations = themeRoles,
            styles = compiledStyles,
            componentStyles = compiledComponentStyles,
            themeRoles = compiledThemeRoles,
        ).also { cache[contentKey] = it }
    }

    /** Resolve one styled or pressable node against an effective scope. */
    fun computedStyle(
        node: JsonObject,
        scope: CompiledDesignScope,
        path: String,
        baseOnly: Boolean,
    ): ComputedDesignStyle {
        val names = styleReferences(node, path)
        val style = scope.computedStyle(names, "$path.styles")
        return if (baseOnly) style.baseOnly() else style
    }

    internal fun styleReferences(node: JsonObject, path: String): List<String> {
        val values = node["styles"] as? JsonArray
            ?: invalid("$path.styles", "must be an array")
        if (values.size !in 1..16) invalid("$path.styles", "must contain 1 to 16 styles")
        return values.mapIndexed { index, value ->
            val name = string(value, "$path.styles[$index]")
            requireIdentifier(name, "$path.styles[$index]")
            name
        }
    }

    private fun componentStyleBindings(
        node: JsonObject,
        path: String,
    ): Map<String, JsonElement> {
        val values = node["component_styles"] ?: return emptyMap()
        val bindings = values as? JsonArray
            ?: invalid("$path.component_styles", "must be an array")
        if (bindings.size > DesignComponentStyleSlot.entries.size) {
            invalid(
                "$path.component_styles",
                "contains more than ${DesignComponentStyleSlot.entries.size} bindings",
            )
        }
        val result = LinkedHashMap<String, JsonElement>(bindings.size)
        bindings.forEachIndexed { index, value ->
            val bindingPath = "$path.component_styles[$index]"
            val binding = value as? JsonObject ?: invalid(bindingPath, "must be an object")
            val slot = requiredString(binding, "slot", bindingPath)
            DesignComponentStyleSlot.fromWireName(slot)
                ?: invalid("$bindingPath.slot", "has an unknown component style slot")
            if (slot in result) {
                invalid("$bindingPath.slot", "duplicates component style slot '$slot'")
            }
            val styles = binding["styles"] as? JsonArray
                ?: invalid("$bindingPath.styles", "must be an array")
            if (styles.size !in 1..16) {
                invalid("$bindingPath.styles", "must contain 1 to 16 styles")
            }
            styles.forEachIndexed { styleIndex, style ->
                val stylePath = "$bindingPath.styles[$styleIndex]"
                requireIdentifier(string(style, stylePath), stylePath)
            }
            result[slot] = styles
        }
        return result
    }

    @Synchronized
    internal fun clearCacheForTest() = cache.clear()

    @Synchronized
    internal fun cacheKeysForTest(): List<String> = cache.values.map { it.canonicalContent }

    /** Read `theme_roles`: a closed map from EBP role name to a color-valued design value. */
    private fun themeRoleDeclarations(node: JsonObject, path: String): Map<String, JsonElement> {
        val values = node["theme_roles"] ?: return emptyMap()
        val map = values as? JsonObject ?: invalid("$path.theme_roles", "must be an object")
        if (map.size > DesignThemeRole.entries.size) {
            invalid("$path.theme_roles", "contains more than ${DesignThemeRole.entries.size} roles")
        }
        for ((name, value) in map) {
            DesignThemeRole.fromWireName(name)
                ?: invalid("$path.theme_roles.$name", "is not an EBP theme role")
            if (value !is JsonObject) {
                invalid("$path.theme_roles.$name", "must be a design value object")
            }
        }
        return map
    }

    /** Resolve every re-declared role to a literal color or an ambient role reference. */
    private fun compileThemeRoles(
        declarations: Map<String, JsonElement>,
        tokens: TokenResolver,
        path: String,
    ): Map<DesignThemeRole, DesignValue> {
        if (declarations.isEmpty()) return emptyMap()
        val result = LinkedHashMap<DesignThemeRole, DesignValue>(declarations.size)
        for ((name, declaration) in declarations) {
            val rolePath = "$path.theme_roles.$name"
            val role = DesignThemeRole.fromWireName(name)
                ?: invalid(rolePath, "is not an EBP theme role")
            val value = parseValue(declaration as JsonObject, tokens, rolePath)
            if (value !is DesignValue.ColorValue && value !is DesignValue.ThemeRoleValue) {
                invalid(rolePath, "must resolve to a color")
            }
            result[role] = value
        }
        return result.toMap()
    }

    private fun merge(
        inherited: Map<String, JsonElement>?,
        local: JsonObject,
        limit: Int,
        path: String,
    ): Map<String, JsonElement> {
        if (inherited == null) {
            if (local.size > limit) invalid(path, "contains more than $limit identifiers")
            for (name in local.keys) requireIdentifierMember(name, path)
            return local
        }
        val result = LinkedHashMap<String, JsonElement>()
        result.putAll(inherited)
        for ((name, value) in local) {
            requireIdentifierMember(name, path)
            result[name] = value
        }
        if (result.size > limit) invalid(path, "contains more than $limit identifiers")
        return result.toMap()
    }

    private fun compileMotions(
        declarations: Map<String, JsonElement>,
        scopePath: String,
    ): Map<String, DesignMotion> {
        val result = LinkedHashMap<String, DesignMotion>(declarations.size)
        for ((name, element) in declarations) {
            requireIdentifierMember(name, "$scopePath.motions")
            val path = "$scopePath.motions.$name"
            val declaration = element as? JsonObject
                ?: invalid(path, "must be an object")
            val duration = (declaration["duration_ms"] as? JsonPrimitive)?.longOrNull
                ?: invalid("$path.duration_ms", "must be an integer")
            if (duration !in 0L..10_000L) {
                invalid("$path.duration_ms", "must be between 0 and 10000")
            }
            val easing = when (requiredString(declaration, "easing", path)) {
                "linear" -> DesignEasing.Linear
                "ease-in" -> DesignEasing.EaseIn
                "ease-out" -> DesignEasing.EaseOut
                "ease-in-out" -> DesignEasing.EaseInOut
                "spring" -> DesignEasing.Spring
                else -> invalid("$path.easing", "has an unknown easing")
            }
            result[name] = DesignMotion(duration.toInt(), easing)
        }
        return result
    }

    private fun compileStyles(
        declarations: Map<String, JsonElement>,
        motions: Map<String, DesignMotion>,
        tokens: TokenResolver,
        path: String,
    ): Map<String, CompiledStyle> {
        val result = LinkedHashMap<String, CompiledStyle>(declarations.size)
        val hasRules = declarations.values.any { element ->
            val declaration = element as? JsonObject
            val rules = declaration?.get("rules") as? JsonArray
            rules?.isNotEmpty() == true
        }
        val propertyCache = if (hasRules) {
            HashMap<JsonObjectKey, Map<DesignProperty, DesignValue>>()
        } else {
            null
        }
        val valueCache = HashMap<JsonObjectKey, DesignValue>()
        for ((name, element) in declarations) {
            requireIdentifierMember(name, "$path.styles")
            val stylePath = "$path.styles.$name"
            val declaration = element as? JsonObject
                ?: invalid(stylePath, "must be an object")
            val motion = motionReference(declaration["motion"], motions, "$stylePath.motion")
            val properties = propertyMap(
                declaration["properties"],
                tokens,
                "$stylePath.properties",
                propertyCache,
                valueCache,
            )
            val rules = declaration["rules"] as? JsonArray
                ?: invalid("$stylePath.rules", "must be an array")
            if (rules.size > 16) invalid("$stylePath.rules", "contains more than 16 rules")
            val compiledRules = ArrayList<DesignStyleLayer>(rules.size)
            for ((index, ruleElement) in rules.withIndex()) {
                val rulePath = "$stylePath.rules[$index]"
                val rule = ruleElement as? JsonObject ?: invalid(rulePath, "must be an object")
                val state = when (requiredString(rule, "state", rulePath)) {
                    "disabled" -> DesignState.Disabled
                    "selected" -> DesignState.Selected
                    "toggled" -> DesignState.Toggled
                    "hovered" -> DesignState.Hovered
                    "focused" -> DesignState.Focused
                    "pressed" -> DesignState.Pressed
                    else -> invalid("$rulePath.state", "has an unknown state")
                }
                compiledRules += DesignStyleLayer(
                    state = state,
                    properties = propertyMap(
                        rule["properties"],
                        tokens,
                        "$rulePath.properties",
                        propertyCache,
                        valueCache,
                    ),
                    motion = motionReference(
                        rule["motion"],
                        motions,
                        "$rulePath.motion",
                    ) ?: motion,
                )
            }
            result[name] = CompiledStyle(properties, compiledRules, motion)
        }
        return result
    }

    private fun compileComponentStyles(
        declarations: Map<String, JsonElement>,
        scopeStyles: Map<String, CompiledStyle>,
        path: String,
    ): Map<DesignComponentStyleSlot, ComputedDesignStyle> {
        val result = LinkedHashMap<DesignComponentStyleSlot, ComputedDesignStyle>()
        for ((slotName, element) in declarations) {
            val slot = DesignComponentStyleSlot.fromWireName(slotName)
                ?: invalid("$path.component_styles.$slotName", "has an unknown component style slot")
            val references = element as? JsonArray
                ?: invalid("$path.component_styles.$slotName", "must be an array")
            val layers = mutableListOf<DesignStyleLayer>()
            references.forEachIndexed { index, reference ->
                val referencePath = "$path.component_styles.$slotName[$index]"
                val styleName = string(reference, referencePath)
                val style = scopeStyles[styleName]
                    ?: invalid(referencePath, "unresolved style '$styleName'")
                layers += DesignStyleLayer(null, style.base, style.motion)
                layers += style.rules
            }
            result[slot] = ComputedDesignStyle(layers.toList())
        }
        return result.toMap()
    }

    private fun validateLocalComponentStyleReferences(
        declarations: Map<String, JsonElement>,
        scopeStyles: Map<String, CompiledStyle>,
        path: String,
    ) {
        declarations.entries.forEachIndexed { bindingIndex, (_, element) ->
            val references = element as JsonArray
            references.forEachIndexed { styleIndex, reference ->
                val referencePath =
                    "$path.component_styles[$bindingIndex].styles[$styleIndex]"
                val styleName = string(reference, referencePath)
                if (styleName !in scopeStyles) {
                    invalid(referencePath, "unresolved style '$styleName'")
                }
            }
        }
    }

    private fun motionReference(
        element: JsonElement?,
        motions: Map<String, DesignMotion>,
        path: String,
    ): DesignMotion? {
        if (element == null) return null
        val name = string(element, path)
        requireIdentifier(name, path)
        return motions[name] ?: invalid(path, "unresolved motion '$name'")
    }

    private fun propertyMap(
        element: JsonElement?,
        tokens: TokenResolver,
        path: String,
        propertyCache: MutableMap<JsonObjectKey, Map<DesignProperty, DesignValue>>?,
        valueCache: MutableMap<JsonObjectKey, DesignValue>,
    ): Map<DesignProperty, DesignValue> {
        val map = element as? JsonObject ?: invalid(path, "must be an object")
        val mapKey = propertyCache?.let { JsonObjectKey(map) }
        if (mapKey != null) propertyCache[mapKey]?.let { return it }
        if (map.size > 64) invalid(path, "contains more than 64 properties")
        val result = LinkedHashMap<DesignProperty, DesignValue>()
        for ((name, element) in map) {
            requireIdentifierMember(name, path)
            val property = DesignProperty.fromWireName(name)
                ?: invalid("$path.$name", "unknown design property '$name'")
            val raw = element as? JsonObject
                ?: invalid("$path.$name", "must be a design value object")
            val valueKey = JsonObjectKey(raw)
            val value = valueCache[valueKey] ?: parseValue(raw, tokens, "$path.$name")
                .also { valueCache[valueKey] = it }
            if (!matches(property.kind, value)) {
                invalid("$path.$name", "value kind does not match property '$name'")
            }
            when (property) {
                DesignProperty.Alpha -> requireRange(value, 0.0, 1.0, "$path.$name")
                DesignProperty.Scale -> requireRange(value, 0.0, 4.0, "$path.$name")
                else -> Unit
            }
            result[property] = value
        }
        val compiled = result.toMap()
        if (mapKey != null) propertyCache[mapKey] = compiled
        return compiled
    }

    private class JsonObjectKey(private val value: JsonObject) {
        private val contentHash = value.hashCode()

        override fun hashCode(): Int = contentHash

        override fun equals(other: Any?): Boolean =
            other is JsonObjectKey && contentHash == other.contentHash && value == other.value
    }

    private fun requireRange(
        value: DesignValue,
        minimum: Double,
        maximum: Double,
        path: String,
    ) {
        val number = (value as DesignValue.NumberValue).value
        if (number !in minimum..maximum) {
            invalid(path, "must be between $minimum and $maximum")
        }
    }

    private fun matches(kind: ValueKind, value: DesignValue): Boolean = when (kind) {
        ValueKind.Boolean -> value is DesignValue.BooleanValue
        ValueKind.Color -> value is DesignValue.ColorValue || value is DesignValue.ThemeRoleValue
        ValueKind.Dimension -> value is DesignValue.DimensionValue
        ValueKind.Number -> value is DesignValue.NumberValue
        ValueKind.FontFamily -> value is DesignValue.FontFamilyValue
        ValueKind.FontWeight -> value is DesignValue.FontWeightValue
        ValueKind.TextAlign -> value is DesignValue.TextAlignValue
    }

    private class TokenResolver(
        private val declarations: Map<String, JsonElement>,
        private val scopePath: String,
    ) {
        private val resolved = HashMap<String, DesignValue>(declarations.size)
        private val resolving = HashSet<String>()

        fun resolve(name: String, requestPath: String): DesignValue {
            resolved[name]?.let { return it }
            val declaration = declarations[name] as? JsonObject
                ?: if (name in declarations) {
                    invalid("$scopePath.tokens.$name", "must be a design value object")
                } else {
                    invalid(requestPath, "unresolved token '$name'")
                }
            if (!resolving.add(name)) {
                invalid(requestPath, "cyclic token reference involving '$name'")
            }
            return try {
                parseValue(declaration, this, "$scopePath.tokens.$name")
                    .also { resolved[name] = it }
            } finally {
                resolving.remove(name)
            }
        }
    }

    private fun parseValue(
        declaration: JsonObject,
        tokens: TokenResolver,
        path: String,
    ): DesignValue {
        val kind = requiredString(declaration, "kind", path)
        val raw = requiredString(declaration, "value", path)
        return when (kind) {
            "token" -> {
                requireIdentifier(raw, "$path.value")
                tokens.resolve(raw, "$path.value")
            }
            "boolean" -> when (raw) {
                "true" -> DesignValue.BooleanValue(true)
                "false" -> DesignValue.BooleanValue(false)
                else -> invalid("$path.value", "must be true or false")
            }
            "color" -> DesignValue.ColorValue(parseColor(raw, "$path.value"))
            "theme-role" -> DesignValue.ThemeRoleValue(
                DesignThemeRole.fromWireName(raw)
                    ?: invalid("$path.value", "has an unknown EBP theme role"),
            )
            "dimension" -> DesignValue.DimensionValue(
                parseFinite(raw, 0.0, 10_000.0, "$path.value"),
            )
            "number" -> DesignValue.NumberValue(
                parseFinite(raw, -10_000.0, 10_000.0, "$path.value"),
            )
            "font-family" -> DesignValue.FontFamilyValue(
                when (raw) {
                    "system" -> DesignFontFamily.System
                    "plex-sans" -> DesignFontFamily.PlexSans
                    "plex-serif" -> DesignFontFamily.PlexSerif
                    "plex-mono" -> DesignFontFamily.PlexMono
                    else -> invalid("$path.value", "has an unknown font family")
                },
            )
            "font-weight" -> {
                val weight = raw.toIntOrNull()
                    ?: invalid("$path.value", "must be an integer font weight")
                if (weight !in 100..900 || weight % 100 != 0) {
                    invalid("$path.value", "must be a 100-step weight from 100 to 900")
                }
                DesignValue.FontWeightValue(weight)
            }
            "text-align" -> DesignValue.TextAlignValue(
                when (raw) {
                    "start" -> DesignTextAlign.Start
                    "center" -> DesignTextAlign.Center
                    "end" -> DesignTextAlign.End
                    else -> invalid("$path.value", "has an unknown text alignment")
                },
            )
            else -> invalid("$path.kind", "has an unknown design value kind")
        }
    }

    private fun parseColor(value: String, path: String): Long {
        if (value.length != 7 && value.length != 9 || value[0] != '#') {
            invalid(path, "must be #RRGGBB or #AARRGGBB")
        }
        var parsed = 0L
        for (index in 1 until value.length) {
            val digit = value[index].digitToIntOrNull(16)
                ?: invalid(path, "must be #RRGGBB or #AARRGGBB")
            parsed = parsed shl 4 or digit.toLong()
        }
        return if (value.length == 7) 0xff000000L or parsed else parsed
    }

    private fun parseFinite(
        value: String,
        minimum: Double,
        maximum: Double,
        path: String,
    ): Double {
        val parsed = value.toDoubleOrNull()
        if (parsed == null || !parsed.isFinite() || parsed !in minimum..maximum) {
            invalid(path, "must be a finite number between $minimum and $maximum")
        }
        return parsed
    }

    private fun requiredObject(node: JsonObject, member: String, path: String): JsonObject =
        node[member] as? JsonObject ?: invalid("$path.$member", "must be an object")

    private fun optionalObject(node: JsonObject, member: String, path: String): JsonObject? {
        val value = node[member] ?: return null
        return value as? JsonObject ?: invalid("$path.$member", "must be an object")
    }

    private fun requiredString(node: JsonObject, member: String, path: String): String =
        string(node[member], "$path.$member")

    private fun string(value: JsonElement?, path: String): String {
        val primitive = value as? JsonPrimitive
            ?: invalid(path, "must be a string")
        if (!primitive.isString) invalid(path, "must be a string")
        return primitive.contentOrNull ?: invalid(path, "must be a string")
    }

    private fun requireIdentifier(value: String, path: String) {
        if (!validIdentifier(value)) {
            invalid(path, "must be a bounded identifier")
        }
    }

    private fun requireIdentifierMember(value: String, parentPath: String) {
        if (!validIdentifier(value)) {
            invalid("$parentPath.$value", "must be a bounded identifier")
        }
    }

    private fun validIdentifier(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 128 &&
            isAsciiLetterOrDigit(value[0]) &&
            value.all(::isIdentifierCharacter)

    private fun isAsciiLetterOrDigit(character: Char): Boolean =
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9'

    private fun isIdentifierCharacter(character: Char): Boolean =
        isAsciiLetterOrDigit(character) ||
            character == '.' ||
            character == '_' ||
            character == ':' ||
            character == '/' ||
            character == '-'

    private fun canonicalObject(values: Map<String, JsonElement>): String =
        StringBuilder(MAX_CONFIGURATION_BYTES).also { canonical(JsonObject(values), it) }.toString()

    private data class CanonicalMetrics(
        val utf8Bytes: Int,
        val contentHash: Int,
    )

    private fun canonicalMetrics(value: JsonElement): CanonicalMetrics {
        val accumulator = CanonicalMetricAccumulator()
        val contentHash = accumulator.add(value)
        return CanonicalMetrics(accumulator.utf8Bytes, contentHash)
    }

    private class CanonicalMetricAccumulator {
        var utf8Bytes: Int = 0
            private set

        fun add(value: JsonElement): Int = when (value) {
            is JsonObject -> {
                utf8Bytes += 2 + (value.size - 1).coerceAtLeast(0)
                var contentHash = 0
                for ((key, element) in value) {
                    utf8Bytes += jsonStringUtf8Size(key) + 1
                    contentHash += key.hashCode() xor add(element)
                }
                contentHash
            }
            is JsonArray -> {
                utf8Bytes += 2 + (value.size - 1).coerceAtLeast(0)
                var contentHash = 1
                for (element in value) contentHash = 31 * contentHash + add(element)
                contentHash
            }
            is JsonPrimitive -> {
                utf8Bytes += if (value.isString) {
                    jsonStringUtf8Size(value.contentOrNull ?: "")
                } else {
                    value.toString().length
                }
                value.hashCode()
            }
        }
    }

    private fun jsonStringUtf8Size(value: String): Int {
        var size = 2
        var index = 0
        while (index < value.length) {
            val character = value[index]
            size += when {
                character == '"' || character == '\\' -> 2
                character == '\b' ||
                    character == '\t' ||
                    character == '\n' ||
                    character == '\u000c' ||
                    character == '\r' -> 2
                character < ' ' -> 6
                character <= '\u007f' -> 1
                character <= '\u07ff' -> 2
                character.isHighSurrogate() &&
                    index + 1 < value.length &&
                    value[index + 1].isLowSurrogate() -> {
                    index += 1
                    4
                }
                else -> 3
            }
            index += 1
        }
        return size
    }

    private fun canonical(value: JsonElement, output: StringBuilder) {
        when (value) {
            is JsonObject -> {
                output.append('{')
                value.keys.sorted().forEachIndexed { index, key ->
                    if (index > 0) output.append(',')
                    output.append(JsonPrimitive(key)).append(':')
                    canonical(value.getValue(key), output)
                }
                output.append('}')
            }
            is JsonArray -> {
                output.append('[')
                value.forEachIndexed { index, element ->
                    if (index > 0) output.append(',')
                    canonical(element, output)
                }
                output.append(']')
            }
            else -> output.append(value)
        }
    }
}

internal fun invalid(path: String, reason: String): Nothing = throw ContentInvalid(path, reason)
