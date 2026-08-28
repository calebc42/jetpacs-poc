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

/** Jetpacs palette mapping for the shared syntax-role projection. */
internal class JetpacsSyntaxOutputTransformation(
    private val language: String,
    private val colors: JetpacsSyntaxColors,
) : OutputTransformation {
    override fun TextFieldBuffer.transformOutput() {
        projectSyntaxSpans(language, asCharSequence().toString()).forEach { span ->
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
}
