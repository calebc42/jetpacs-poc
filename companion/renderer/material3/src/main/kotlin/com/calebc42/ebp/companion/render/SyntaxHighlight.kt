// SPDX-License-Identifier: GPL-3.0-or-later
// SPEC 18.4 syntax fontification: best-effort, client-side token colouring for
// text/text_input/editor nodes carrying a `syntax` language identifier. The
// static polarity palettes (Nord-derived, keyed only on surface luminance so
// they stay legible on any scheme) are the fallback; a theme.set `syntax` map
// overlays them so the editor reads like the user's real Emacs theme. Format-6
// drift from poc-v1: `syntax` roles map to SyntaxStyle OBJECTS
// ({fg, bg, font_weight, italic, underline}), not bare colors — emacsSyntaxColors
// reads each role's `fg`. The contract FIXES the syntax_roles set (15 names,
// contract.json); SPEC 18.4 requires unknown roles to be IGNORED, which is why
// this file reads only registered names: `tag` drives org tags, `preprocessor`
// drives meta lines, and the paren rainbow is deliberately STATIC — one
// overlay color would destroy the depth cue (amendment #126, option B).
package com.calebc42.ebp.companion.render

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.calebc42.jetpacs.renderer.compose.SyntaxRole
import com.calebc42.jetpacs.renderer.compose.projectSyntaxSpans
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Token colours for code highlighting; the fallback is a polarity palette. */
data class SyntaxColors(
    val comment: Color,
    val string: Color,
    val keyword: Color,
    val function: Color,
    val constant: Color,
    // `variable', `type' and `operator' carry no client tokenizer of their own
    // — the shared best-effort tokenizer does not distinguish them. They exist because
    // Emacs DOES: ebp-sync maps font-lock-variable-name-face, -type-face and
    // -operator-face onto these three §19.5 roles, so without them a third of
    // a real fontify batch would arrive registered and land unstyled.
    val variable: Color,
    val type: Color,
    val operator: Color,
    val number: Color,
    val link: Color,
    val meta: Color,
    val tag: Color,
    val todo: Color,
    val done: Color,
    val heading: List<Color>,
    val paren: List<Color>,
) {
    companion object {
        fun forBackground(dark: Boolean): SyntaxColors =
            if (dark) SyntaxColors(
                comment = Color(0xFF616E88), string = Color(0xFFA3BE8C),
                keyword = Color(0xFF81A1C1), function = Color(0xFF88C0D0),
                constant = Color(0xFFB48EAD),
                variable = Color(0xFFD8DEE9), type = Color(0xFF8FBCBB),
                operator = Color(0xFF81A1C1),
                number = Color(0xFFB48EAD),
                link = Color(0xFF88C0D0), meta = Color(0xFF7B88A1),
                tag = Color(0xFF8FBCBB),
                todo = Color(0xFFBF616A), done = Color(0xFFA3BE8C),
                heading = listOf(
                    Color(0xFF88C0D0), Color(0xFF81A1C1), Color(0xFFB48EAD),
                    Color(0xFFA3BE8C), Color(0xFFEBCB8B), Color(0xFFD08770)),
                paren = listOf(
                    Color(0xFF81A1C1), Color(0xFFB48EAD), Color(0xFFA3BE8C),
                    Color(0xFFEBCB8B), Color(0xFFD08770), Color(0xFF88C0D0)))
            else SyntaxColors(
                comment = Color(0xFF7B88A1), string = Color(0xFF4F6F3F),
                keyword = Color(0xFF3B5B8C), function = Color(0xFF2E6E7E),
                constant = Color(0xFF8A4B82),
                variable = Color(0xFF4C566A), type = Color(0xFF1F6F5C),
                operator = Color(0xFF3B5B8C),
                number = Color(0xFF8A4B82),
                link = Color(0xFF2E6E7E), meta = Color(0xFF5E6B82),
                tag = Color(0xFF3F7A6E),
                todo = Color(0xFFA01F2C), done = Color(0xFF4F6F3F),
                heading = listOf(
                    Color(0xFF2E6E7E), Color(0xFF3B5B8C), Color(0xFF8A4B82),
                    Color(0xFF4F6F3F), Color(0xFF9A7A1E), Color(0xFFA85A36)),
                paren = listOf(
                    Color(0xFF3B5B8C), Color(0xFF8A4B82), Color(0xFF4F6F3F),
                    Color(0xFF9A7A1E), Color(0xFFA85A36), Color(0xFF2E6E7E)))
    }
}

/** LocalSyntaxColors before any EbpTheme provides real ones. */
val LocalSyntaxColors = staticCompositionLocalOf { SyntaxColors.forBackground(false) }

/**
 * §18.4: a role's SyntaxStyle `fg` (or a bare color string, leniently). Only
 * the foreground drives the tokenizer's colours; bg/weight/italic/underline are
 * the token's own per-type decisions.
 */
private fun JsonObject.syntaxFg(role: String): Color? {
    val v = this[role] ?: return null
    val hex = when (v) {
        is JsonObject -> v.stringOr("fg").takeIf { it.isNotEmpty() }
        is JsonPrimitive -> v.strOrNull()?.takeIf { it.isNotEmpty() }
        else -> null
    } ?: return null
    return parseHexColor(hex)?.let(::Color)
}

/** SyntaxColors from a pushed §18.4 `syntax` map, holes filled from [fallback].
 * REGISTERED roles only (SPEC 18.4 requires ignoring the rest): `tag` styles
 * org tags, `preprocessor` styles meta/table lines, and the paren rainbow is
 * never overlaid — one uniform color would destroy the depth cue (#126 B). */
fun emacsSyntaxColors(syntax: JsonObject?, fallback: SyntaxColors): SyntaxColors {
    val s = syntax ?: return fallback
    fun one(role: String, base: Color) = s.syntaxFg(role) ?: base
    // A single "heading" fg recolours the whole heading rainbow uniformly.
    val heading = s.syntaxFg("heading")?.let { listOf(it) } ?: fallback.heading
    return SyntaxColors(
        comment = one("comment", fallback.comment),
        string = one("string", fallback.string),
        keyword = one("keyword", fallback.keyword),
        function = one("function", fallback.function),
        constant = one("constant", fallback.constant),
        variable = one("variable", fallback.variable),
        type = one("type", fallback.type),
        operator = one("operator", fallback.operator),
        number = one("number", fallback.number),
        link = one("link", fallback.link),
        meta = one("preprocessor", fallback.meta),
        tag = one("tag", fallback.tag),
        todo = one("todo", fallback.todo),
        done = one("done", fallback.done),
        heading = heading, paren = fallback.paren)
}

/**
 * Best-effort span styles for [src] in [language] — the one tokenizer entry
 * point. Uncapped: every tokenizer is one linear pass, and `max_editor_bytes`
 * at the 65536 floor bounds every synchronized document, so highlighting the
 * whole text is what jit-lock's never-stop-fontifying discipline asks for
 * (LD-7 removed the silent 20,000-char stop). Any tokenizer hiccup falls
 * back to no styles rather than crashing the field.
 */
fun highlightSpans(
    language: String, src: String, colors: SyntaxColors,
): List<AnnotatedString.Range<SpanStyle>> = projectSyntaxSpans(language, src).map { span ->
    val color = when (span.role) {
        SyntaxRole.Plain -> Color.Unspecified
        SyntaxRole.Comment -> colors.comment
        SyntaxRole.String -> colors.string
        SyntaxRole.Keyword -> colors.keyword
        SyntaxRole.Function -> colors.function
        SyntaxRole.Constant -> colors.constant
        SyntaxRole.Number -> colors.number
        SyntaxRole.Link -> colors.link
        SyntaxRole.Preprocessor -> colors.meta
        SyntaxRole.Tag -> colors.tag
        SyntaxRole.Todo -> colors.todo
        SyntaxRole.Done -> colors.done
        SyntaxRole.Heading -> colors.heading[span.variant.mod(colors.heading.size)]
        SyntaxRole.Parenthesis -> colors.paren[span.variant.mod(colors.paren.size)]
    }
    AnnotatedString.Range(
        SpanStyle(
            color = color,
            fontWeight = FontWeight.Bold.takeIf { span.bold },
            fontStyle = FontStyle.Italic.takeIf { span.italic },
            textDecoration = TextDecoration.Underline.takeIf { span.underline },
        ),
        span.start,
        span.end,
    )
}

/** A VisualTransformation that recolours a code field in place; never changes
 * the character count, so the offset mapping is the identity — cursor,
 * selection, and IME keep working exactly as on a plain field. */
class SyntaxTransformation(
    private val language: String,
    private val colors: SyntaxColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val spans = highlightSpans(language, text.text, colors)
        val styled = if (spans.isEmpty()) AnnotatedString(text.text)
            else AnnotatedString(text.text, spanStyles = spans)
        return TransformedText(styled, OffsetMapping.Identity)
    }
}

/** State-based equivalent used by editable fields without changing text. */
class SyntaxOutputTransformation(
    private val language: String,
    private val colors: SyntaxColors,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        highlightSpans(language, asCharSequence().toString(), colors)
            .forEach { range -> addStyle(range.item, range.start, range.end) }
    }
}

/** Best-effort language guess from a file path's extension. */
fun syntaxForPath(path: String): String =
    when (path.substringAfterLast('.', "").lowercase()) {
        "el", "elc" -> "elisp"
        "org" -> "org"
        "py" -> "python"
        "rs" -> "rust"
        "sh", "bash" -> "shell"
        "c", "h" -> "c"
        "cc", "cpp", "hpp" -> "cpp"
        else -> ""
    }
