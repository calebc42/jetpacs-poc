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
    data class DimensionValue(val value: Double) : DesignValue
    data class NumberValue(val value: Double) : DesignValue
    data class FontFamilyValue(val value: DesignFontFamily) : DesignValue
    data class FontWeightValue(val value: Int) : DesignValue
    data class TextAlignValue(val value: DesignTextAlign) : DesignValue
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
    val canonicalContent: String,
    internal val tokens: Map<String, JsonElement>,
    internal val styleDeclarations: Map<String, JsonElement>,
    internal val motionDeclarations: Map<String, JsonElement>,
    private val styles: Map<String, CompiledStyle>,
) {
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
}

/** Pure compiler shared by admission and rendering. */
object DesignModel {
    const val MAX_CONFIGURATION_BYTES: Int = 256 * 1024
    const val MAX_SCOPE_DEPTH: Int = 8
    private const val MAX_CACHE_ENTRIES = 32
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]*")

    private val cache = object : LinkedHashMap<String, CompiledDesignScope>(
        MAX_CACHE_ENTRIES + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CompiledDesignScope>?,
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

        val tokens = merge(parent?.tokens, localTokens, 256, "$path.tokens")
        val styles = merge(parent?.styleDeclarations, localStyles, 256, "$path.styles")
        val motions = merge(parent?.motionDeclarations, localMotions, 64, "$path.motions")
        val canonical = canonicalObject(
            mapOf(
                "motions" to JsonObject(motions),
                "styles" to JsonObject(styles),
                "tokens" to JsonObject(tokens),
            ),
        )
        if (canonical.toByteArray(Charsets.UTF_8).size > MAX_CONFIGURATION_BYTES) {
            invalid(path, "design configuration exceeds 256 KiB")
        }
        cache[canonical]?.let { return it }

        val compiledMotions = compileMotions(motions, path)
        val tokenResolver = TokenResolver(tokens, path)
        tokens.keys.sorted().forEach { tokenResolver.resolve(it, "$path.tokens.$it") }
        val compiledStyles = compileStyles(
            declarations = styles,
            motions = compiledMotions,
            tokens = tokenResolver,
            path = path,
        )
        return CompiledDesignScope(
            canonicalContent = canonical,
            tokens = tokens.toMap(),
            styleDeclarations = styles.toMap(),
            motionDeclarations = motions.toMap(),
            styles = compiledStyles,
        ).also { cache[canonical] = it }
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

    @Synchronized
    internal fun clearCacheForTest() = cache.clear()

    @Synchronized
    internal fun cacheKeysForTest(): List<String> = cache.keys.toList()

    private fun merge(
        inherited: Map<String, JsonElement>?,
        local: JsonObject,
        limit: Int,
        path: String,
    ): Map<String, JsonElement> {
        val result = LinkedHashMap<String, JsonElement>()
        inherited?.let(result::putAll)
        for ((name, value) in local) {
            requireIdentifier(name, "$path.$name")
            result[name] = value
        }
        if (result.size > limit) invalid(path, "contains more than $limit identifiers")
        return result.toMap()
    }

    private fun compileMotions(
        declarations: Map<String, JsonElement>,
        scopePath: String,
    ): Map<String, DesignMotion> = declarations.keys.sorted().associateWith { name ->
        requireIdentifier(name, "$scopePath.motions.$name")
        val path = "$scopePath.motions.$name"
        val declaration = declarations[name] as? JsonObject
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
        DesignMotion(duration.toInt(), easing)
    }

    private fun compileStyles(
        declarations: Map<String, JsonElement>,
        motions: Map<String, DesignMotion>,
        tokens: TokenResolver,
        path: String,
    ): Map<String, CompiledStyle> = declarations.keys.sorted().associateWith { name ->
        requireIdentifier(name, "$path.styles.$name")
        val stylePath = "$path.styles.$name"
        val declaration = declarations[name] as? JsonObject
            ?: invalid(stylePath, "must be an object")
        val motion = motionReference(declaration["motion"], motions, "$stylePath.motion")
        val properties = propertyMap(declaration["properties"], tokens, "$stylePath.properties")
        val rules = declaration["rules"] as? JsonArray
            ?: invalid("$stylePath.rules", "must be an array")
        if (rules.size > 16) invalid("$stylePath.rules", "contains more than 16 rules")
        val compiledRules = rules.mapIndexed { index, element ->
            val rulePath = "$stylePath.rules[$index]"
            val rule = element as? JsonObject ?: invalid(rulePath, "must be an object")
            val state = when (requiredString(rule, "state", rulePath)) {
                "disabled" -> DesignState.Disabled
                "selected" -> DesignState.Selected
                "toggled" -> DesignState.Toggled
                "hovered" -> DesignState.Hovered
                "focused" -> DesignState.Focused
                "pressed" -> DesignState.Pressed
                else -> invalid("$rulePath.state", "has an unknown state")
            }
            DesignStyleLayer(
                state = state,
                properties = propertyMap(
                    rule["properties"],
                    tokens,
                    "$rulePath.properties",
                ),
                motion = motionReference(
                    rule["motion"],
                    motions,
                    "$rulePath.motion",
                ) ?: motion,
            )
        }
        CompiledStyle(properties, compiledRules.toList(), motion)
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
    ): Map<DesignProperty, DesignValue> {
        val map = element as? JsonObject ?: invalid(path, "must be an object")
        if (map.size > 64) invalid(path, "contains more than 64 properties")
        val result = LinkedHashMap<DesignProperty, DesignValue>()
        for (name in map.keys.sorted()) {
            requireIdentifier(name, "$path.$name")
            val property = DesignProperty.fromWireName(name)
                ?: invalid("$path.$name", "unknown design property '$name'")
            val raw = map.getValue(name) as? JsonObject
                ?: invalid("$path.$name", "must be a design value object")
            val value = parseValue(raw, tokens, "$path.$name")
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
        return result.toMap()
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
        ValueKind.Color -> value is DesignValue.ColorValue
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
        private val resolved = mutableMapOf<String, DesignValue>()
        private val resolving = linkedSetOf<String>()

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
        val hex = value.removePrefix("#")
        if (hex.length != 6 && hex.length != 8) {
            invalid(path, "must be #RRGGBB or #AARRGGBB")
        }
        val parsed = hex.toLongOrNull(16)
            ?: invalid(path, "must be #RRGGBB or #AARRGGBB")
        return if (hex.length == 6) 0xff000000L or parsed else parsed
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
        if (value.length > 128 || !identifier.matches(value)) {
            invalid(path, "must be a bounded identifier")
        }
    }

    private fun canonicalObject(values: Map<String, JsonElement>): String =
        canonical(JsonObject(values))

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(",", "{", "}") { key ->
            "${JsonPrimitive(key)}:${canonical(value.getValue(key))}"
        }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
}

internal fun invalid(path: String, reason: String): Nothing = throw ContentInvalid(path, reason)
