package com.jetpacs.platform.tools

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class SourceOutlinesTest {
    @Test
    fun `Kotlin outline keeps type members and excludes executable locals`() {
        val source = """
            package example

            class Sample(private val hidden: String) {
                val publicValue: String = "a { brace in a string }"

                data object Idle

                data class Value(
                    val nestedConstructorProperty: String,
                )

                fun visible(value: Int): String {
                    fun localOnly() = "no"
                    return value.toString()
                }

                private fun privateMember() = Unit
            }
        """.trimIndent()

        val result = SourceOutlines.summarize("Sample.kt", source, includePrivate = false)

        assertContains(result, "class Sample")
        assertContains(result, "val publicValue")
        assertContains(result, "fun visible")
        assertContains(result, "data object Idle")
        assertContains(result, "data class Value")
        assertFalse(result.contains("localOnly"))
        assertFalse(result.contains("privateMember"))
        assertFalse(result.lines().any { it.trimStart().startsWith("val nestedConstructorProperty") })
    }

    @Test
    fun `Elisp outline reads top-level definitions without evaluating forms`() {
        val source = """
            ;;; sample.el -*- lexical-binding: t; -*-
            (defcustom sample-option t "Option")
            (defun sample-public (value)
              "Return VALUE."
              (let ((text "(defun fake ())"))
                value))
            (defun sample--private () nil)
        """.trimIndent()

        val result = SourceOutlines.summarize("sample.el", source, includePrivate = false)

        assertContains(result, "(defcustom sample-option)")
        assertContains(result, "(defun sample-public (value))")
        assertFalse(result.contains("sample--private"))
        assertFalse(result.contains("fake"))
    }
}
