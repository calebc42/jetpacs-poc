// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.compose

/** Fixed presentation roles emitted by the shared best-effort tokenizer. */
enum class SyntaxRole {
    Plain,
    Comment,
    String,
    Keyword,
    Function,
    Constant,
    Number,
    Link,
    Preprocessor,
    Tag,
    Todo,
    Done,
    Heading,
    Parenthesis,
}

/** Toolkit-neutral styled range over the unchanged UTF-16 source text. */
data class SyntaxSpan(
    val role: SyntaxRole,
    val start: Int,
    val end: Int,
    val variant: Int = 0,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
)

/**
 * Tokenize one supported language without changing text or imposing a cap.
 * Unknown languages and tokenizer failures deliberately degrade to no spans.
 */
fun projectSyntaxSpans(language: String, source: String): List<SyntaxSpan> =
    runCatching {
        when (language.lowercase()) {
            "elisp", "emacs-lisp", "lisp" -> elispSpans(source)
            "org" -> orgSpans(source)
            "python", "py" -> codeSpans(source, pythonKeywords, "#", true)
            "rust", "rs" -> codeSpans(source, rustKeywords, "//", false)
            "shell", "sh", "bash" -> codeSpans(source, shellKeywords, "#", true)
            "c", "h", "cpp", "cc", "hpp" ->
                codeSpans(source, cKeywords, "//", false)
            else -> emptyList()
        }
    }.getOrElse { emptyList() }

private fun elispSpans(source: String): List<SyntaxSpan> = buildList {
    var index = 0
    var depth = 0
    while (index < source.length) {
        val character = source[index]
        when {
            character == ';' -> {
                val end = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                add(SyntaxSpan(SyntaxRole.Comment, index, end, italic = true))
                index = end
            }
            character == '"' -> {
                val end = quotedEnd(source, index, character, stopAtLineFeed = false)
                add(SyntaxSpan(SyntaxRole.String, index, end))
                index = end
            }
            character == '?' -> {
                var end = index + 1
                if (end < source.length && source[end] == '\\') end++
                if (end < source.length) end++
                add(SyntaxSpan(SyntaxRole.String, index, end))
                index = end
            }
            character == '(' || character == '[' -> {
                add(SyntaxSpan(SyntaxRole.Parenthesis, index, index + 1, variant = depth))
                depth++
                index++
                if (character == '(') {
                    while (index < source.length && source[index] == ' ') index++
                    var end = index
                    while (end < source.length && isElispSymbol(source[end])) end++
                    if (end > index) {
                        val keyword = source.substring(index, end) in elispKeywords
                        add(
                            SyntaxSpan(
                                if (keyword) SyntaxRole.Keyword else SyntaxRole.Function,
                                index,
                                end,
                                bold = keyword,
                            ),
                        )
                        index = end
                    }
                }
            }
            character == ')' || character == ']' -> {
                depth = maxOf(0, depth - 1)
                add(SyntaxSpan(SyntaxRole.Parenthesis, index, index + 1, variant = depth))
                index++
            }
            character == ':' -> {
                var end = index + 1
                while (end < source.length && isElispSymbol(source[end])) end++
                add(SyntaxSpan(SyntaxRole.Constant, index, end))
                index = end
            }
            character == '\'' || character == '`' -> {
                add(SyntaxSpan(SyntaxRole.Constant, index, index + 1))
                index++
            }
            character.isDigit() && (index == 0 || !isElispSymbol(source[index - 1])) -> {
                var end = index
                while (end < source.length &&
                    (source[end].isDigit() || source[end] == '.')
                ) end++
                add(SyntaxSpan(SyntaxRole.Number, index, end))
                index = end
            }
            else -> index++
        }
    }
}

private fun codeSpans(
    source: String,
    keywords: Set<String>,
    lineComment: String,
    singleQuoteStrings: Boolean,
): List<SyntaxSpan> = buildList {
    var index = 0
    while (index < source.length) {
        val character = source[index]
        when {
            source.startsWith(lineComment, index) -> {
                val end = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                add(SyntaxSpan(SyntaxRole.Comment, index, end, italic = true))
                index = end
            }
            character == '"' || (singleQuoteStrings && character == '\'') -> {
                val end = quotedEnd(source, index, character, stopAtLineFeed = true)
                add(SyntaxSpan(SyntaxRole.String, index, end))
                index = end
            }
            character.isDigit() && (index == 0 || !isIdentifier(source[index - 1])) -> {
                var end = index
                while (end < source.length &&
                    (isIdentifier(source[end]) || source[end] == '.')
                ) end++
                add(SyntaxSpan(SyntaxRole.Number, index, end))
                index = end
            }
            character.isLetter() || character == '_' -> {
                var end = index
                while (end < source.length && isIdentifier(source[end])) end++
                val word = source.substring(index, end)
                when {
                    word in keywords -> add(
                        SyntaxSpan(SyntaxRole.Keyword, index, end, bold = true),
                    )
                    end < source.length && source[end] == '(' -> add(
                        SyntaxSpan(SyntaxRole.Function, index, end),
                    )
                }
                index = end
            }
            else -> index++
        }
    }
}

private fun orgSpans(source: String): List<SyntaxSpan> = buildList {
    var lineStart = 0
    while (lineStart < source.length) {
        val lineEnd = source.indexOf('\n', lineStart).takeIf { it >= 0 } ?: source.length
        val line = source.substring(lineStart, lineEnd)
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("#+") -> add(
                SyntaxSpan(SyntaxRole.Keyword, lineStart, lineEnd),
            )
            line.startsWith("*") -> {
                val heading = orgHeading.find(line)
                if (heading != null) {
                    val level = heading.groupValues[1].length
                    add(
                        SyntaxSpan(
                            SyntaxRole.Heading,
                            lineStart,
                            lineEnd,
                            variant = level - 1,
                            bold = true,
                        ),
                    )
                    val titleStart = heading.groups[2]!!.range.first
                    val title = heading.groupValues[2]
                    addRegexRole(
                        title,
                        lineStart + titleStart,
                        orgTodo,
                        SyntaxRole.Todo,
                        bold = true,
                    )
                    addRegexRole(
                        title,
                        lineStart + titleStart,
                        orgDone,
                        SyntaxRole.Done,
                        bold = true,
                    )
                    orgTags.find(line)?.groups?.get(1)?.range?.let { range ->
                        add(SyntaxSpan(SyntaxRole.Tag, lineStart + range.first, lineStart + range.last + 1))
                    }
                }
            }
            trimmed.startsWith("# ") -> add(
                SyntaxSpan(SyntaxRole.Comment, lineStart, lineEnd, italic = true),
            )
            trimmed.startsWith("|") -> add(
                SyntaxSpan(SyntaxRole.Preprocessor, lineStart, lineEnd),
            )
            else -> {
                orgList.find(line)?.groups?.get(2)?.range?.let { range ->
                    add(
                        SyntaxSpan(
                            SyntaxRole.Constant,
                            lineStart + range.first,
                            lineStart + range.last + 1,
                            bold = true,
                        ),
                    )
                }
                addMatches(line, lineStart, orgLink, SyntaxRole.Link, underline = true)
                addMatches(line, lineStart, orgCode, SyntaxRole.String)
                addMatches(line, lineStart, orgVerbatim, SyntaxRole.String)
                addMatches(line, lineStart, orgBold, SyntaxRole.Plain, bold = true)
                addMatches(line, lineStart, orgItalic, SyntaxRole.Plain, italic = true)
            }
        }
        lineStart = lineEnd + 1
    }
}

private fun MutableList<SyntaxSpan>.addRegexRole(
    source: String,
    base: Int,
    regex: Regex,
    role: SyntaxRole,
    bold: Boolean = false,
) {
    regex.find(source)
        ?.range?.let { range ->
            add(SyntaxSpan(role, base + range.first, base + range.last + 1, bold = bold))
        }
}

private fun MutableList<SyntaxSpan>.addMatches(
    line: String,
    base: Int,
    regex: Regex,
    role: SyntaxRole,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
) {
    regex.findAll(line).forEach { match ->
        add(
            SyntaxSpan(
                role,
                base + match.range.first,
                base + match.range.last + 1,
                bold = bold,
                italic = italic,
                underline = underline,
            ),
        )
    }
}

private fun quotedEnd(
    source: String,
    start: Int,
    quote: Char,
    stopAtLineFeed: Boolean,
): Int {
    var end = start + 1
    while (end < source.length) {
        when (source[end]) {
            '\\' -> end += 2
            quote -> return end + 1
            '\n' -> if (stopAtLineFeed) return end else end++
            else -> end++
        }
    }
    return source.length
}

private fun isElispSymbol(character: Char): Boolean =
    !character.isWhitespace() && character !in "()[]{}\"';`,#"

private fun isIdentifier(character: Char): Boolean =
    character.isLetterOrDigit() || character == '_'

private val elispKeywords = setOf(
    "defun", "defmacro", "defvar", "defconst", "defcustom", "defgroup", "defface",
    "cl-defun", "cl-defmacro", "cl-defstruct", "cl-defmethod", "define-minor-mode",
    "let", "let*", "letrec", "lambda", "if", "when", "unless", "cond", "case",
    "pcase", "pcase-let", "while", "dolist", "dotimes", "cl-loop", "cl-dolist",
    "setq", "setq-default", "setf", "push", "pop", "progn", "prog1", "prog2",
    "and", "or", "not", "function", "quote", "interactive", "save-excursion",
    "save-restriction", "save-match-data", "with-current-buffer", "with-temp-buffer",
    "condition-case", "unwind-protect", "catch", "throw", "ignore-errors",
    "require", "provide", "declare-function", "add-hook", "remove-hook",
    "mapcar", "mapc", "mapconcat", "cl-remove-if", "cl-remove-if-not",
)

private val pythonKeywords = setOf(
    "def", "class", "return", "if", "elif", "else", "for", "while", "import",
    "from", "as", "with", "try", "except", "finally", "raise", "pass", "break",
    "continue", "lambda", "yield", "global", "nonlocal", "assert", "in", "is",
    "not", "and", "or", "None", "True", "False", "async", "await", "match", "case",
    "del",
)

private val rustKeywords = setOf(
    "fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "use", "mod",
    "crate", "match", "if", "else", "for", "while", "loop", "return", "break",
    "continue", "const", "static", "ref", "move", "async", "await", "dyn", "where",
    "type", "unsafe", "as", "in", "self", "Self", "super", "true", "false",
)

private val shellKeywords = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done",
    "case", "esac", "in", "function", "local", "return", "exit", "export", "readonly",
    "shift", "break", "continue", "echo", "read", "declare", "set", "unset", "source",
    "trap", "true", "false",
)

private val cKeywords = setOf(
    "if", "else", "for", "while", "do", "switch", "case", "default", "break",
    "continue", "return", "goto", "typedef", "struct", "union", "enum", "static",
    "extern", "const", "volatile", "inline", "sizeof", "void", "char", "short", "int",
    "long", "float", "double", "signed", "unsigned", "bool", "true", "false", "NULL",
    "include", "define", "class", "namespace", "template", "public", "private",
    "protected", "new", "delete", "nullptr", "auto", "using",
)

private val orgHeading = Regex("""^(\*+)\s+(.*)$""")
private val orgTodo = Regex("""^(TODO|NEXT|STARTED|WAIT|WAITING|HOLD|DOING)\b""")
private val orgDone = Regex("""^(DONE|CANCELLED|CANCELED|KILL)\b""")
private val orgTags = Regex("""(:[\w@#%:]+:)\s*$""")
private val orgList = Regex("""^(\s*)([-+]|\d+[.)])\s""")
private val orgLink = Regex("""\[\[[^\]]*](\[[^\]]*])?]""")
private val orgBold = Regex("""(?<![\w*])\*(\S(?:[^*\n]*\S)?)\*(?![\w*])""")
private val orgItalic = Regex("""(?<![\w/])/(\S(?:[^/\n]*\S)?)/(?![\w/])""")
private val orgCode = Regex("""(?<![\w~])~([^~\n]+)~(?![\w~])""")
private val orgVerbatim = Regex("""(?<![\w=])=([^=\n]+)=(?![\w=])""")
