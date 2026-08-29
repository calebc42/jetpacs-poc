// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.companion.render

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
import com.calebc42.jetpacs.renderer.model.DiagnosticSet
import com.calebc42.jetpacs.renderer.model.FontifySet
import com.calebc42.jetpacs.renderer.model.currentDiagnostics
import com.calebc42.jetpacs.renderer.model.currentFontifyRuns

/** The contract's fixed syntax roles resolved by this Material palette. */
val SYNTAX_ROLES = listOf(
    "comment", "string", "keyword", "function", "constant", "variable",
    "type", "number", "operator", "preprocessor", "heading", "link",
    "todo", "done", "tag",
)

/** Resolve one protocol role to Material presentation, ignoring unknown roles. */
fun roleStyle(role: String, colors: SyntaxColors): SpanStyle? = when (role) {
    "comment" -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
    "string" -> SpanStyle(color = colors.string)
    "keyword" -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)
    "function" -> SpanStyle(color = colors.function)
    "constant" -> SpanStyle(color = colors.constant)
    "variable" -> SpanStyle(color = colors.variable)
    "type" -> SpanStyle(color = colors.type)
    "number" -> SpanStyle(color = colors.number)
    "operator" -> SpanStyle(color = colors.operator)
    "preprocessor" -> SpanStyle(color = colors.meta)
    "heading" -> SpanStyle(color = colors.heading.first(), fontWeight = FontWeight.Bold)
    "link" -> SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
    "todo" -> SpanStyle(color = colors.todo, fontWeight = FontWeight.Bold)
    "done" -> SpanStyle(color = colors.done, fontWeight = FontWeight.Bold)
    "tag" -> SpanStyle(color = colors.tag)
    else -> null
}

/** Material colors used for diagnostic decoration. */
data class DiagnosticColors(
    val error: Color,
    val warning: Color,
    val info: Color,
    val hint: Color,
) {
    /** Resolve a wire severity without inventing an unregistered category. */
    fun forSeverity(severity: String): Color = when (severity) {
        "error" -> error
        "warning" -> warning
        "info" -> info
        "hint" -> hint
        else -> warning
    }

    companion object {
        /** Stable fallback used by unit tests outside a Material theme. */
        fun forBackground(dark: Boolean): DiagnosticColors =
            if (dark) {
                DiagnosticColors(
                    error = Color(0xFFBF616A),
                    warning = Color(0xFFEBCB8B),
                    info = Color(0xFF81A1C1),
                    hint = Color(0xFF616E88),
                )
            } else {
                DiagnosticColors(
                    error = Color(0xFFA01F2C),
                    warning = Color(0xFF9A7A1E),
                    info = Color(0xFF3B5B8C),
                    hint = Color(0xFF7B88A1),
                )
            }
    }
}

/** Material diagnostic underline and translucent severity wash. */
fun diagnosticStyle(severity: String, colors: DiagnosticColors): SpanStyle {
    val tint = colors.forSeverity(severity)
    return SpanStyle(
        textDecoration = TextDecoration.Underline,
        background = tint.copy(alpha = 0.15f),
    )
}

/** Resolve all Material spans for [source] without changing its offsets. */
fun annotationSpans(
    source: String,
    fontify: FontifySet?,
    diagnostics: DiagnosticSet?,
    language: String,
    colors: SyntaxColors,
    diagnosticColors: DiagnosticColors,
): List<AnnotatedString.Range<SpanStyle>> {
    val length = source.length
    val output = mutableListOf<AnnotatedString.Range<SpanStyle>>()
    val runs = currentFontifyRuns(fontify, source)
    if (runs != null) {
        for (run in runs) {
            val style = roleStyle(run.role, colors) ?: continue
            val start = run.start.coerceIn(0, length)
            val end = run.end.coerceIn(start, length)
            if (end > start) output += AnnotatedString.Range(style, start, end)
        }
    } else if (language.isNotEmpty()) {
        output += highlightSpans(language, source, colors)
    }
    currentDiagnostics(diagnostics, source)?.let { current ->
        for (diagnostic in current) {
            val start = diagnostic.start.coerceIn(0, length)
            val end = diagnostic.end.coerceIn(start, length)
            if (end > start) {
                output += AnnotatedString.Range(
                    diagnosticStyle(diagnostic.severity, diagnosticColors),
                    start,
                    end,
                )
            }
        }
    }
    return output
}

/** State-based editor styling that never changes logical text or offsets. */
class AnnotationOutputTransformation(
    private val fontify: FontifySet?,
    private val diagnostics: DiagnosticSet?,
    private val language: String,
    private val colors: SyntaxColors,
    private val diagnosticColors: DiagnosticColors,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        annotationSpans(
            source,
            fontify,
            diagnostics,
            language,
            colors,
            diagnosticColors,
        ).forEach { range -> addStyle(range.item, range.start, range.end) }
    }
}

/** Legacy view of the Material span projection for non-state consumers. */
class AnnotationTransformation(
    private val fontify: FontifySet?,
    private val diagnostics: DiagnosticSet?,
    private val language: String,
    private val colors: SyntaxColors,
    private val diagnosticColors: DiagnosticColors,
) : VisualTransformation {
    private var cachedSource: String? = null
    private var cachedText: AnnotatedString? = null

    override fun filter(text: AnnotatedString): TransformedText {
        cachedText?.takeIf { cachedSource == text.text }?.let {
            return TransformedText(it, OffsetMapping.Identity)
        }
        val spans = annotationSpans(
            text.text,
            fontify,
            diagnostics,
            language,
            colors,
            diagnosticColors,
        )
        val styled = if (spans.isEmpty()) AnnotatedString(text.text)
        else AnnotatedString(text.text, spanStyles = spans)
        cachedSource = text.text
        cachedText = styled
        return TransformedText(styled, OffsetMapping.Identity)
    }
}
