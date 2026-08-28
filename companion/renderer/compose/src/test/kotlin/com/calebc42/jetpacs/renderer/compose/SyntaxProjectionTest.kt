// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxProjectionTest {
    @Test
    fun elispProjectionKeepsRolesAndMultilineStringRange() {
        val source = "(defun demo () \"one\ntwo\") ; comment"
        val spans = projectSyntaxSpans("elisp", source)

        assertTrue(spans.any {
            it.role == SyntaxRole.Keyword && source.substring(it.start, it.end) == "defun"
        })
        assertTrue(spans.any {
            it.role == SyntaxRole.String &&
                source.substring(it.start, it.end) == "\"one\ntwo\""
        })
        assertTrue(spans.any {
            it.role == SyntaxRole.Comment && source.substring(it.start, it.end) == "; comment"
        })
    }

    @Test
    fun orgProjectionKeepsHeadingTodoTagAndInlineRoles() {
        val source = "* TODO Heading :work:\n- [[https://example.test][link]] and ~code~"
        val spans = projectSyntaxSpans("org", source)

        assertTrue(spans.any { it.role == SyntaxRole.Heading })
        assertTrue(spans.any { it.role == SyntaxRole.Todo })
        assertTrue(spans.any { it.role == SyntaxRole.Tag })
        assertTrue(spans.any { it.role == SyntaxRole.Constant })
        assertTrue(spans.any { it.role == SyntaxRole.Link && it.underline })
        assertTrue(spans.any { it.role == SyntaxRole.String })
    }

    @Test
    fun everySupportedFamilyProjectsAndUnknownLanguageDegrades() {
        val samples = mapOf(
            "python" to "del value # comment",
            "rust" to "fn main() { let value = 1; }",
            "shell" to "if true; then echo ok; fi",
            "h" to "int function(void);",
            "cpp" to "class Demo {};",
        )

        samples.forEach { (language, source) ->
            assertTrue("$language should project a role", projectSyntaxSpans(language, source).isNotEmpty())
        }
        assertEquals(emptyList<SyntaxSpan>(), projectSyntaxSpans("unknown", "plain"))
    }

    @Test
    fun projectionDoesNotStopAtTwentyThousandCharacters() {
        val source = "x".repeat(20_100) + "\n;; tail"
        assertTrue(projectSyntaxSpans("elisp", source).any { it.start > 20_000 })
    }
}
