// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.renderer.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Display-side text and selection authority for one synchronized editor. */
data class EditorMirror(
    val text: String,
    val cursorUtf16: Int,
    val selectionStartUtf16: Int,
    val selectionEndUtf16: Int,
    val sequence: Long,
    val epoch: Long,
)

/** One fontification run expressed in UTF-16 offsets into its batch text. */
data class FontifyRun(val start: Int, val end: Int, val role: String)

/** One diagnostic range expressed in UTF-16 offsets into its batch text. */
data class DiagnosticRange(
    val start: Int,
    val end: Int,
    val severity: String,
    val message: String,
)

/** A complete fontification batch pinned to the text its offsets index. */
data class FontifySet(
    val session: String,
    val sequence: Long,
    val text: String,
    val runs: List<FontifyRun>,
)

/** A complete diagnostics batch pinned to the text its offsets index. */
data class DiagnosticSet(
    val session: String,
    val sequence: Long,
    val text: String,
    val diagnostics: List<DiagnosticRange>,
)

/** One `eldoc.show` line pinned to the editor state it describes. */
data class EldocLine(val session: String, val sequence: Long, val text: String)

/** Latest independent annotation batches for one synchronized editor. */
data class EditorAnnotationState(
    val fontify: FontifySet? = null,
    val diagnostics: DiagnosticSet? = null,
    val eldoc: EldocLine? = null,
    val epoch: Long = 0,
)

/** One completion row after wire defaults have been applied. */
data class CompletionCandidate(
    val label: String,
    val annotation: String?,
    val insert: String,
    val kind: String? = null,
)

/** One completion result paired with the editor state that requested it. */
data class CompletionOffer(
    val prefix: String,
    val candidates: List<CompletionCandidate>,
    val session: String,
    val sequence: Long,
    val cursor: Int,
    val epoch: Long,
)

/** Lazily fetched documentation for one row in a specific offer epoch. */
data class CandidateDocument(
    val index: Int,
    val text: String,
    val epoch: Long,
)

/** One UTF-16 text splice: replace [start, start + deleted) with [inserted]. */
data class Utf16TextSplice(
    val start: Int,
    val deleted: Int,
    val inserted: String,
)

/** Sequential Unicode-scalar to UTF-16 conversion for one immutable string. */
class ScalarToUtf16Index(private val text: String) {
    private var scalar = 0
    private var utf16 = 0

    /** Convert [targetScalar], restarting safely if callers move backwards. */
    fun convert(targetScalar: Int): Int {
        if (targetScalar < scalar) {
            scalar = 0
            utf16 = 0
        }
        utf16 = text.offsetByCodePoints(utf16, targetScalar - scalar)
        scalar = targetScalar
        return utf16
    }
}

/** Minimal surrogate-safe single-splice diff in UTF-16 units. */
fun utf16TextSplice(old: CharSequence, new: CharSequence): Utf16TextSplice? {
    if (old.contentEquals(new)) return null
    var prefix = 0
    val maxPrefix = minOf(old.length, new.length)
    while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
    if (prefix > 0 && old[prefix - 1].isHighSurrogate()) prefix--
    var oldSuffix = old.length
    var newSuffix = new.length
    while (oldSuffix > prefix && newSuffix > prefix &&
        old[oldSuffix - 1] == new[newSuffix - 1]
    ) {
        oldSuffix--
        newSuffix--
    }
    if (oldSuffix < old.length && old[oldSuffix].isLowSurrogate()) {
        oldSuffix++
        newSuffix++
    }
    return Utf16TextSplice(
        prefix,
        oldSuffix - prefix,
        new.subSequence(prefix, newSuffix).toString(),
    )
}

/** Maximum nearby edit for which stale fontification may be shifted. */
const val FONTIFY_SHIFT_MAX_EDIT = 256

/** Shift fontification across one small local UTF-16 edit. */
fun shiftFontifyRuns(
    runs: List<FontifyRun>,
    splice: Utf16TextSplice,
): List<FontifyRun> {
    val delta = splice.inserted.length - splice.deleted
    val deletedEnd = splice.start + splice.deleted
    return buildList {
        for (run in runs) {
            val start = when {
                run.start < splice.start -> run.start
                run.start >= deletedEnd -> run.start + delta
                else -> splice.start + splice.inserted.length
            }
            val end = when {
                run.end <= splice.start -> run.end
                run.end >= deletedEnd -> run.end + delta
                else -> splice.start
            }
            if (end > start) {
                add(
                    if (start == run.start && end == run.end) run
                    else run.copy(start = start, end = end),
                )
            }
        }
    }
}

/** Parse a validated `fontify.show` batch against [text]. */
fun parseFontify(params: JsonObject, text: String): FontifySet? {
    val array = params.arrayOrNull("runs") ?: return null
    val scalarCount = text.codePointCount(0, text.length)
    val index = ScalarToUtf16Index(text)
    val runs = buildList {
        for (element in array) {
            val run = element as? JsonObject ?: continue
            val startScalar = (run.intOrNull("start") ?: continue)
                .coerceIn(0, scalarCount)
            val endScalar = (run.intOrNull("end") ?: continue)
                .coerceIn(startScalar, scalarCount)
            val start = index.convert(startScalar)
            val end = index.convert(endScalar)
            if (end > start) add(FontifyRun(start, end, run.stringOr("role")))
        }
    }
    return FontifySet(
        params.stringOr("session"),
        params.longOrZero("seq"),
        text,
        runs,
    )
}

/** Parse a validated `diagnostics.show` batch against [text]. */
fun parseDiagnostics(params: JsonObject, text: String): DiagnosticSet? {
    val array = params.arrayOrNull("diagnostics") ?: return null
    val scalarCount = text.codePointCount(0, text.length)
    val index = ScalarToUtf16Index(text)
    val diagnostics = buildList {
        for (element in array) {
            val diagnostic = element as? JsonObject ?: continue
            val startScalar = (diagnostic.intOrNull("start") ?: continue)
                .coerceIn(0, scalarCount)
            val endScalar = (diagnostic.intOrNull("end") ?: continue)
                .coerceIn(startScalar, scalarCount)
            var start = index.convert(startScalar)
            var end = index.convert(endScalar)
            if (end == start) {
                if (end < text.length) {
                    end = text.offsetByCodePoints(end, 1)
                } else if (start > 0) {
                    start = text.offsetByCodePoints(start, -1)
                }
            }
            if (end > start) {
                add(
                    DiagnosticRange(
                        start,
                        end,
                        diagnostic.stringOr("severity", "warning"),
                        diagnostic.stringOr("message"),
                    ),
                )
            }
        }
    }
    return DiagnosticSet(
        params.stringOr("session"),
        params.longOrZero("seq"),
        text,
        diagnostics,
    )
}

/** Parse one validated `eldoc.show` payload. */
fun parseEldoc(params: JsonObject): EldocLine? {
    val text = params.stringOrNull("text") ?: return null
    return EldocLine(params.stringOr("session"), params.longOrZero("seq"), text)
}

/** Highest-priority diagnostic containing [caret], under a strict text gate. */
fun diagnosticAt(
    diagnostics: DiagnosticSet?,
    text: String,
    caret: Int,
): DiagnosticRange? {
    if (diagnostics == null || diagnostics.text != text) return null
    return diagnostics.diagnostics
        .filter { caret >= it.start && caret <= it.end }
        .minByOrNull { severityRank(it.severity) }
}

private fun severityRank(severity: String): Int = when (severity) {
    "error" -> 0
    "warning" -> 1
    "info" -> 2
    else -> 3
}

private fun JsonObject.arrayOrNull(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.stringOrNull(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.stringOr(name: String, default: String = ""): String =
    stringOrNull(name) ?: default

private fun JsonObject.intOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)
        ?.takeIf { !it.isString && it !is JsonNull }
        ?.content
        ?.toDoubleOrNull()
        ?.toInt()

private fun JsonObject.longOrZero(name: String): Long =
    (this[name] as? JsonPrimitive)
        ?.takeIf { !it.isString && it !is JsonNull }
        ?.content
        ?.toDoubleOrNull()
        ?.toLong()
        ?: 0L
