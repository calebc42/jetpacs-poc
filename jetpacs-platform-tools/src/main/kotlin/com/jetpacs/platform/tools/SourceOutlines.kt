package com.jetpacs.platform.tools

/** Token-efficient outlines which never evaluate or compile inspected source. */
internal object SourceOutlines {
    /** Select the language-aware, non-evaluating outline for [path]. */
    fun summarize(path: String, source: String, includePrivate: Boolean): String =
        when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts" -> kotlin(path, source, includePrivate)
            "el" -> elisp(path, source, includePrivate)
            else -> throw UserInputException("source summary supports .kt, .kts, and .el files")
        }

    private fun kotlin(path: String, source: String, includePrivate: Boolean): String {
        val masked = maskKotlin(source)
        val declaration = Regex(
            pattern = """(?m)^[\t ]*(?:(?:public|internal|protected|private|expect|actual|final|open|abstract|sealed|data|value|inline|tailrec|operator|infix|external|suspend|const|lateinit|override|companion)\s+)*(?:enum\s+class|annotation\s+class|fun\s+interface|class|interface|object|typealias|fun|val|var)\b""",
        )
        val lineStarts = lineStarts(source)
        val outlines = mutableListOf<String>()

        declaration.findAll(masked).forEach { match ->
            val prefix = masked.substring(0, match.range.first)
            if (!declarationScope(prefix) || delimiterDepth(prefix) != 0) return@forEach
            val end = kotlinSignatureEnd(masked, match.range.first)
            val signature = source.substring(match.range.first, end)
                .replace(Regex("\\s+"), " ")
                .trim()
                .removeSuffix("{")
                .trim()
            if (!includePrivate && Regex("(?:^|\\s)private(?:\\s|$)").containsMatchIn(signature)) {
                return@forEach
            }
            val terminator = masked.getOrNull(end - 1)
            val suffix = when (terminator) {
                '{' -> " { … }"
                '=' -> " = …"
                else -> ""
            }
            outlines += "L${lineNumber(lineStarts, match.range.first)}  $signature$suffix"
        }

        val packageName = Regex("(?m)^\\s*package\\s+([^\\s;]+)").find(masked)?.groupValues?.get(1)
        return buildString {
            appendLine("Kotlin API outline: $path")
            if (packageName != null) appendLine("package $packageName")
            appendLine()
            if (outlines.isEmpty()) appendLine("No declarations found.") else outlines.forEach(::appendLine)
        }.trimEnd().capped()
    }

    /** True at file scope or inside type bodies, false inside executable bodies. */
    private fun declarationScope(prefix: String): Boolean {
        val stack = mutableListOf<Boolean>() // true = type body
        var segmentStart = 0
        prefix.forEachIndexed { index, char ->
            when (char) {
                '{' -> {
                    val segment = prefix.substring(segmentStart, index).takeLast(800)
                    val lastType = Regex(
                        "(?:enum\\s+class|annotation\\s+class|fun\\s+interface|class|interface|object)\\b",
                    ).findAll(segment).lastOrNull()?.range?.first ?: -1
                    val lastFun = Regex("\\bfun\\b").findAll(segment).lastOrNull()?.range?.first ?: -1
                    stack += lastType >= 0 && lastType > lastFun
                    segmentStart = index + 1
                }

                '}' -> {
                    if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                    segmentStart = index + 1
                }

                ';' -> segmentStart = index + 1
            }
        }
        return stack.all { it }
    }

    private fun kotlinSignatureEnd(masked: String, start: Int): Int {
        var parens = 0
        var brackets = 0
        var angles = 0
        var index = start
        while (index < masked.length && index - start < 8_000) {
            when (masked[index]) {
                '(' -> parens += 1
                ')' -> parens = (parens - 1).coerceAtLeast(0)
                '[' -> brackets += 1
                ']' -> brackets = (brackets - 1).coerceAtLeast(0)
                '<' -> angles += 1
                '>' -> angles = (angles - 1).coerceAtLeast(0)
                '{', '=' -> if (parens == 0 && brackets == 0 && angles == 0) return index + 1
                ';' -> if (parens == 0 && brackets == 0) return index
                '\n' -> if (parens == 0 && brackets == 0 && angles == 0) {
                    val soFar = masked.substring(start, index).trimEnd()
                    val continuation = soFar.endsWith(",") || soFar.endsWith(":") || soFar.endsWith("where")
                    if (!continuation) return index
                }
            }
            index += 1
        }
        return index.coerceAtMost(masked.length)
    }

    private fun delimiterDepth(prefix: String): Int {
        var depth = 0
        prefix.forEach { char ->
            when (char) {
                '(', '[' -> depth += 1
                ')', ']' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return depth
    }

    private fun elisp(path: String, source: String, includePrivate: Boolean): String {
        val masked = maskElisp(source)
        val form = Regex(
            """(?m)^[\t ]*\((cl-defun|defun|defmacro|defsubst|defvar|defconst|defcustom|cl-defstruct|define-error)\s+([^\s()]+)""",
        )
        val lineStarts = lineStarts(source)
        val outlines = mutableListOf<String>()
        form.findAll(masked).forEach { match ->
            if (parenDepth(masked, match.range.first) != 0) return@forEach
            val kind = match.groupValues[1]
            val name = match.groupValues[2]
            if (!includePrivate && name.contains("--")) return@forEach
            val args = if (kind in setOf("defun", "cl-defun", "defmacro", "defsubst")) {
                elispArgumentList(source, masked, match.range.last + 1)
            } else {
                null
            }
            outlines += buildString {
                append("L${lineNumber(lineStarts, match.range.first)}  ($kind $name")
                if (args != null) append(" $args")
                append(")")
            }
        }
        return buildString {
            appendLine("Elisp API outline: $path")
            appendLine()
            if (outlines.isEmpty()) appendLine("No top-level definitions found.") else outlines.forEach(::appendLine)
        }.trimEnd().capped()
    }

    private fun elispArgumentList(source: String, masked: String, afterName: Int): String? {
        var start = afterName
        while (start < masked.length && masked[start].isWhitespace()) start += 1
        if (masked.getOrNull(start) != '(') return null
        var depth = 0
        var index = start
        while (index < masked.length && index - start < 4_000) {
            when (masked[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(start, index + 1).replace(Regex("\\s+"), " ")
                    }
                }
            }
            index += 1
        }
        return null
    }

    private fun parenDepth(masked: String, end: Int): Int {
        var depth = 0
        for (index in 0 until end) {
            when (masked[index]) {
                '(' -> depth += 1
                ')' -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        return depth
    }

    private fun maskKotlin(source: String): String {
        val result = source.toCharArray()
        var index = 0
        var blockDepth = 0
        var state = "code"
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when (state) {
                "code" -> when {
                    char == '/' && next == '/' -> {
                        result[index] = ' '
                        result[index + 1] = ' '
                        index += 2
                        state = "line"
                    }
                    char == '/' && next == '*' -> {
                        result[index] = ' '
                        result[index + 1] = ' '
                        index += 2
                        blockDepth = 1
                        state = "block"
                    }
                    char == '"' && source.startsWith("\"\"\"", index) -> {
                        repeat(3) { result[index + it] = ' ' }
                        index += 3
                        state = "triple"
                    }
                    char == '"' -> {
                        result[index] = ' '
                        index += 1
                        state = "string"
                    }
                    char == '\'' -> {
                        result[index] = ' '
                        index += 1
                        state = "char"
                    }
                    else -> index += 1
                }
                "line" -> {
                    if (char == '\n') state = "code" else result[index] = ' '
                    index += 1
                }
                "block" -> when {
                    char == '/' && next == '*' -> {
                        result[index] = ' '
                        result[index + 1] = ' '
                        blockDepth += 1
                        index += 2
                    }
                    char == '*' && next == '/' -> {
                        result[index] = ' '
                        result[index + 1] = ' '
                        blockDepth -= 1
                        index += 2
                        if (blockDepth == 0) state = "code"
                    }
                    else -> {
                        if (char != '\n') result[index] = ' '
                        index += 1
                    }
                }
                "string", "char" -> {
                    if (char == '\\') {
                        result[index] = ' '
                        if (index + 1 < result.size) result[index + 1] = ' '
                        index += 2
                    } else {
                        if (char != '\n') result[index] = ' '
                        val closes = (state == "string" && char == '"') || (state == "char" && char == '\'')
                        index += 1
                        if (closes) state = "code"
                    }
                }
                "triple" -> {
                    if (source.startsWith("\"\"\"", index)) {
                        repeat(3) { result[index + it] = ' ' }
                        index += 3
                        state = "code"
                    } else {
                        if (char != '\n') result[index] = ' '
                        index += 1
                    }
                }
            }
        }
        return String(result)
    }

    private fun maskElisp(source: String): String {
        val result = source.toCharArray()
        var index = 0
        var inString = false
        var inComment = false
        while (index < source.length) {
            val char = source[index]
            when {
                inComment -> {
                    if (char == '\n') inComment = false else result[index] = ' '
                    index += 1
                }
                inString && char == '\\' -> {
                    result[index] = ' '
                    if (index + 1 < result.size) result[index + 1] = ' '
                    index += 2
                }
                inString -> {
                    if (char != '\n') result[index] = ' '
                    if (char == '"') inString = false
                    index += 1
                }
                char == ';' -> {
                    result[index] = ' '
                    inComment = true
                    index += 1
                }
                char == '"' -> {
                    result[index] = ' '
                    inString = true
                    index += 1
                }
                else -> index += 1
            }
        }
        return String(result)
    }

    private fun lineStarts(source: String): IntArray {
        val starts = mutableListOf(0)
        source.forEachIndexed { index, char -> if (char == '\n') starts += index + 1 }
        return starts.toIntArray()
    }

    private fun lineNumber(starts: IntArray, position: Int): Int {
        val found = starts.binarySearch(position)
        return if (found >= 0) found + 1 else -found - 1
    }
}
