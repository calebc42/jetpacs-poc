// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.ebp.wire

/** Count the Unicode scalar positions used by EBP `text_input` members.
 *
 * Kotlin and Compose expose UTF-16 offsets; EBP selection and maximum length
 * do not. This helper is the single conversion boundary shared by admission,
 * draft reconciliation, and renderers. */
fun textInputScalarLength(text: String): Int =
    text.codePointCount(0, text.length)

/** Return whether [text] satisfies an authored `text_input.filter`.
 *
 * The generated contract maps known filters to deterministic character-set
 * names. An unknown filter is the §16.3 receiver fallback and therefore
 * imposes no restriction. */
fun textInputFilterMatches(text: String, filter: String?): Boolean =
    when (TEXT_INPUT_CONTRACT.filterCharacterSets[filter]) {
        "ascii-digit" -> text.all { it in '0'..'9' }
        "ascii-alphanumeric" -> text.all {
            it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z'
        }
        else -> true
    }

/** Count generated mask-slot spellings without assuming a UTF-16 width. */
fun textInputMaskSlotCount(mask: String): Int {
    val slot = TEXT_INPUT_CONTRACT.mask.slot
    var count = 0
    var from = 0
    while (from <= mask.length - slot.length) {
        val found = mask.indexOf(slot, from)
        if (found < 0) break
        count++
        from = found + slot.length
    }
    return count
}

/** Apply one known `text_input.filter`, preserving unknown-filter fallback. */
fun filterTextInput(text: String, filter: String?): String =
    when (TEXT_INPUT_CONTRACT.filterCharacterSets[filter]) {
        "ascii-digit" -> text.filter { it in '0'..'9' }
        "ascii-alphanumeric" -> text.filter {
            it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z'
        }
        else -> text
    }

/** Truncate [text] to at most [maximum] Unicode scalar values. */
fun truncateTextInputScalars(text: String, maximum: Long): String {
    if (maximum < 0 || textInputScalarLength(text) <= maximum) return text
    return text.substring(0, text.offsetByCodePoints(0, maximum.toInt()))
}

/** Apply the contract-defined local transform order to one proposed value. */
fun normalizeTextInput(
    text: String,
    singleLine: Boolean,
    filter: String?,
    maximum: Long?,
): String {
    var normalized = text
    for (step in TEXT_INPUT_CONTRACT.transformOrder) {
        normalized = when (step) {
            "single_line" -> if (singleLine) normalized.replace("\n", "") else normalized
            "filter" -> filterTextInput(normalized, filter)
            "max_length" -> maximum?.let {
                truncateTextInputScalars(normalized, it)
            } ?: normalized
            else -> error("unsupported generated text-input transform $step")
        }
    }
    return normalized
}

/** Convert one admitted EBP scalar offset to the corresponding UTF-16 offset. */
fun textInputScalarToUtf16(text: String, scalarOffset: Int): Int =
    text.offsetByCodePoints(
        0,
        scalarOffset.coerceIn(0, textInputScalarLength(text)),
    )
