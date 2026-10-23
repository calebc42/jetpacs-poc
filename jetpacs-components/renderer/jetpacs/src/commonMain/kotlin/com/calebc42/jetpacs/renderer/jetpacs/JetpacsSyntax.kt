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
import com.calebc42.ebp.renderer.compose.SyntaxRole
import com.calebc42.ebp.renderer.compose.projectSyntaxSpans
import com.calebc42.ebp.renderer.model.DiagnosticSet
import com.calebc42.ebp.renderer.model.FontifySet
import com.calebc42.ebp.renderer.model.currentDiagnostics
import com.calebc42.ebp.renderer.model.currentFontifyRuns

/** Jetpacs-private severity colors for synchronized editor diagnostics. */
@InternalJetpacsRendererApi
data class JetpacsDiagnosticColors(
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

/**
 * Resolve one fixed contract syntax role through the Jetpacs palette. A
 * `heading` run's [level] (SPEC 19.5) picks its colour from the depth cycle.
 */
internal fun jetpacsRoleStyle(
    role: String,
    colors: JetpacsSyntaxColors,
    level: Int? = null,
): SpanStyle? =
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
        "heading" -> SpanStyle(
            color = colors.heading[((level ?: 1) - 1).mod(colors.heading.size)],
            fontWeight = FontWeight.Bold,
        )
        "link" -> SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
        "todo" -> SpanStyle(color = colors.todo, fontWeight = FontWeight.Bold)
        "done" -> SpanStyle(color = colors.done, fontWeight = FontWeight.Bold)
        "tag" -> SpanStyle(color = colors.tag)
        else -> null
    }

/**
 * A design scope's `editor.syntax.*` bindings, resolved to span styles.
 *
 * Each bound slot overlays the palette style of its role: the properties it
 * authors win and the rest keep the role's default. A `heading` run with a
 * level stacks `editor.syntax.heading-N` over `editor.syntax.heading`, so a
 * level slot need only author what differs by depth. Unbound and empty slots
 * leave the palette untouched, so a scope without syntax bindings, such as
 * the baseline's, renders exactly as before.
 */
@InternalJetpacsRendererApi
data class JetpacsSyntaxOverrides(
    val byRole: Map<String, SpanStyle>,
    val byHeadingLevel: Map<Int, SpanStyle>,
) {
    /** The run's palette style with any bound override merged on top. */
    fun style(role: String, level: Int?, colors: JetpacsSyntaxColors): SpanStyle? {
        val base = jetpacsRoleStyle(role, colors, level) ?: return null
        val levelOverride = level?.takeIf { role == "heading" }?.let(byHeadingLevel::get)
        return base.merge(byRole[role]).merge(levelOverride)
    }

    companion object {
        val None = JetpacsSyntaxOverrides(emptyMap(), emptyMap())

        private val roleSlots = mapOf(
            "comment" to DesignComponentStyleSlot.EditorSyntaxComment,
            "string" to DesignComponentStyleSlot.EditorSyntaxString,
            "keyword" to DesignComponentStyleSlot.EditorSyntaxKeyword,
            "function" to DesignComponentStyleSlot.EditorSyntaxFunction,
            "constant" to DesignComponentStyleSlot.EditorSyntaxConstant,
            "variable" to DesignComponentStyleSlot.EditorSyntaxVariable,
            "type" to DesignComponentStyleSlot.EditorSyntaxType,
            "number" to DesignComponentStyleSlot.EditorSyntaxNumber,
            "operator" to DesignComponentStyleSlot.EditorSyntaxOperator,
            "preprocessor" to DesignComponentStyleSlot.EditorSyntaxPreprocessor,
            "heading" to DesignComponentStyleSlot.EditorSyntaxHeading,
            "link" to DesignComponentStyleSlot.EditorSyntaxLink,
            "todo" to DesignComponentStyleSlot.EditorSyntaxTodo,
            "done" to DesignComponentStyleSlot.EditorSyntaxDone,
            "tag" to DesignComponentStyleSlot.EditorSyntaxTag,
        )

        private val headingSlots = mapOf(
            1 to DesignComponentStyleSlot.EditorSyntaxHeading1,
            2 to DesignComponentStyleSlot.EditorSyntaxHeading2,
            3 to DesignComponentStyleSlot.EditorSyntaxHeading3,
            4 to DesignComponentStyleSlot.EditorSyntaxHeading4,
            5 to DesignComponentStyleSlot.EditorSyntaxHeading5,
            6 to DesignComponentStyleSlot.EditorSyntaxHeading6,
            7 to DesignComponentStyleSlot.EditorSyntaxHeading7,
            8 to DesignComponentStyleSlot.EditorSyntaxHeading8,
        )

        /** Resolve [scope]'s syntax bindings; [None] when nothing is bound. */
        internal fun from(
            scope: CompiledDesignScope?,
            roles: JetpacsThemeRoles,
        ): JetpacsSyntaxOverrides {
            if (scope == null) return None
            fun resolve(slot: DesignComponentStyleSlot): SpanStyle? =
                scope.resolvedSyntaxSpanStyle(slot, roles)
            val byRole = roleSlots.mapNotNull { (role, slot) ->
                resolve(slot)?.let { role to it }
            }.toMap()
            val byLevel = headingSlots.mapNotNull { (level, slot) ->
                resolve(slot)?.let { level to it }
            }.toMap()
            return if (byRole.isEmpty() && byLevel.isEmpty()) None
            else JetpacsSyntaxOverrides(byRole, byLevel)
        }
    }
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
@InternalJetpacsRendererApi
class JetpacsAnnotationOutputTransformation(
    private val fontify: FontifySet?,
    private val diagnostics: DiagnosticSet?,
    private val language: String,
    private val colors: JetpacsSyntaxColors,
    private val diagnosticColors: JetpacsDiagnosticColors,
    private val overrides: JetpacsSyntaxOverrides = JetpacsSyntaxOverrides.None,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        val source = asCharSequence().toString()
        val authoritative = currentFontifyRuns(fontify, source)
        if (authoritative != null) {
            authoritative.forEach { run ->
                overrides.style(run.role, run.level, colors)?.let { style ->
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

/**
 * One stable output transformation over annotations that keep changing.
 *
 * Every `fontify.show`, `diagnostics.show` and `eldoc.show` advances the
 * editor's annotation epoch. Handing the text field a new transformation
 * for each one made it restart its input session, so the soft keyboard
 * hid and reappeared a moment after every keystroke. This instance stays
 * put for the life of the field, and [current] reads the latest
 * projection from snapshot state, which the field observes and re-applies.
 */
@InternalJetpacsRendererApi
class JetpacsLiveAnnotationOutputTransformation(
    private val current: () -> OutputTransformation?,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        current()?.applyOutput(this)
    }
}

/** Run [this] transformation on [buffer]; outside any transformation class,
 * so the member-extension call cannot resolve to the caller's own. */
private fun OutputTransformation.applyOutput(buffer: TextFieldBuffer) {
    with(buffer) { transformOutput() }
}

/** Jetpacs palette mapping for the shared syntax-role projection. */
@InternalJetpacsRendererApi
class JetpacsSyntaxOutputTransformation(
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
        addStyle(jetpacsLocalSyntaxSpanStyle(span, colors), span.start, span.end)
    }
}

/** Jetpacs palette mapping for one locally projected syntax [span]. */
internal fun jetpacsLocalSyntaxSpanStyle(
    span: com.calebc42.ebp.renderer.compose.SyntaxSpan,
    colors: JetpacsSyntaxColors,
): SpanStyle {
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
    return SpanStyle(
        color = color,
        fontWeight = FontWeight.Bold.takeIf { span.bold },
        fontStyle = FontStyle.Italic.takeIf { span.italic },
        textDecoration = TextDecoration.Underline.takeIf { span.underline },
    )
}
