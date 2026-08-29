// SPDX-License-Identifier: GPL-3.0-or-later
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.calebc42.jetpacs.renderer.jetpacs

import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.calebc42.jetpacs.renderer.compose.SyntaxRole
import com.calebc42.jetpacs.renderer.compose.projectSyntaxSpans
import com.calebc42.jetpacs.renderer.model.DiagnosticSet
import com.calebc42.jetpacs.renderer.model.FontifySet
import com.calebc42.jetpacs.renderer.model.currentDiagnostics
import com.calebc42.jetpacs.renderer.model.currentFontifyRuns

/** Jetpacs-private severity colors for synchronized editor diagnostics. */
internal data class JetpacsDiagnosticColors(
    val error: Color,
    val warning: Color,
    val info: Color,
    val hint: Color,
) {
    /** Resolve the contract's closed severity vocabulary. */
    fun forSeverity(severity: String): Color = when (severity) {
        "error" -> error
        "warning" -> warning
        "info" -> info
        "hint" -> hint
        else -> warning
    }
}

/** Resolve one fixed contract syntax role through the Jetpacs palette. */
internal fun jetpacsRoleStyle(role: String, colors: JetpacsSyntaxColors): SpanStyle? =
    when (role) {
        "comment" -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
        "string" -> SpanStyle(color = colors.string)
        "keyword" -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.Bold)
        "function" -> SpanStyle(color = colors.function)
        "constant" -> SpanStyle(color = colors.constant)
        "variable" -> SpanStyle(color = colors.function)
        "type" -> SpanStyle(color = colors.heading.first())
        "number" -> SpanStyle(color = colors.number)
        "operator" -> SpanStyle(color = colors.preprocessor)
        "preprocessor" -> SpanStyle(color = colors.preprocessor)
        "heading" -> SpanStyle(color = colors.heading.first(), fontWeight = FontWeight.Bold)
        "link" -> SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
        "todo" -> SpanStyle(color = colors.todo, fontWeight = FontWeight.Bold)
        "done" -> SpanStyle(color = colors.done, fontWeight = FontWeight.Bold)
        "tag" -> SpanStyle(color = colors.tag)
        else -> null
    }

/** Jetpacs diagnostic underline and bounded severity wash. */
internal fun jetpacsDiagnosticStyle(
    severity: String,
    colors: JetpacsDiagnosticColors,
): SpanStyle = SpanStyle(
    background = colors.forSeverity(severity).copy(alpha = 0.15f),
    textDecoration = TextDecoration.Underline,
)

/**
 * State-based authoritative annotation projection with local syntax fallback.
 *
 * Styling never inserts text or changes offsets, so caret, selection, and
 * synchronization continue to index the controller's untransformed value.
 */
internal class JetpacsAnnotationOutputTransformation(
    private val fontify: FontifySet?,
    private val diagnostics: DiagnosticSet?,
    private val language: String,
    private val colors: JetpacsSyntaxColors,
    private val diagnosticColors: JetpacsDiagnosticColors,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        val authoritative = currentFontifyRuns(fontify, source)
        if (authoritative != null) {
            authoritative.forEach { run ->
                jetpacsRoleStyle(run.role, colors)?.let { style ->
                    val start = run.start.coerceIn(0, source.length)
                    val end = run.end.coerceIn(start, source.length)
                    if (end > start) addStyle(style, start, end)
                }
            }
        } else if (language.isNotEmpty()) {
            addLocalSyntaxStyles(source, language, colors)
        }
        currentDiagnostics(diagnostics, source)?.forEach { diagnostic ->
            val start = diagnostic.start.coerceIn(0, source.length)
            val end = diagnostic.end.coerceIn(start, source.length)
            if (end > start) {
                addStyle(
                    jetpacsDiagnosticStyle(diagnostic.severity, diagnosticColors),
                    start,
                    end,
                )
            }
        }
    }
}

/** Jetpacs palette mapping for the shared syntax-role projection. */
internal class JetpacsSyntaxOutputTransformation(
    private val language: String,
    private val colors: JetpacsSyntaxColors,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        addLocalSyntaxStyles(asCharSequence().toString(), language, colors)
    }
}

private fun TextFieldBuffer.addLocalSyntaxStyles(
    source: String,
    language: String,
    colors: JetpacsSyntaxColors,
) {
    projectSyntaxSpans(language, source).forEach { span ->
        val color = when (span.role) {
            SyntaxRole.Plain -> Color.Unspecified
            SyntaxRole.Comment -> colors.comment
            SyntaxRole.String -> colors.string
            SyntaxRole.Keyword -> colors.keyword
            SyntaxRole.Function -> colors.function
            SyntaxRole.Constant -> colors.constant
            SyntaxRole.Number -> colors.number
            SyntaxRole.Link -> colors.link
            SyntaxRole.Preprocessor -> colors.preprocessor
            SyntaxRole.Tag -> colors.tag
            SyntaxRole.Todo -> colors.todo
            SyntaxRole.Done -> colors.done
            SyntaxRole.Heading -> colors.heading[span.variant.mod(colors.heading.size)]
            SyntaxRole.Parenthesis ->
                colors.parenthesis[span.variant.mod(colors.parenthesis.size)]
        }
        addStyle(
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
}
