// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2c H2: UTF-16 code-unit / Unicode-scalar arithmetic, common-stdlib
// only. Each *Compat matches its java.lang oracle exactly, INCLUDING
// lone-surrogate semantics: codePointAtCompat returns the surrogate's own
// value (which falls in the < 0x10000 arm of any width table),
// charCountCompat of it is 1, codePointsOf emits it as one value, and
// stringFromCodePoints re-materializes it verbatim. TextUnitsTest
// property-checks all six against the java.lang originals over
// ASCII/BMP/astral/lone-surrogate corpora.
package com.calebc42.ebp.wire

/**
 * The single UTF-8 measuring tape (RF-2c "measure == emit"): every byte
 * gate measures with this because encodeFrame emits encodeToByteArray()
 * bytes. What a lone surrogate counts is the PLATFORM ENCODER's
 * substitution — '?' (1 byte) on the JVM, whose encodeToByteArray
 * delegates to java's REPLACE-mode encoder, U+FFFD (3 bytes) under
 * Kotlin/Native's own encoder. The tape and the emitter agree by
 * construction on every platform because they are the same call; that
 * agreement, not any particular constant, is the invariant
 * (TextUnitsTest pins the JVM figure).
 */
internal fun String.utf8Size(): Int = encodeToByteArray().size

/** java.lang Character.charCount: 2 for astral, 1 otherwise (including
 * lone-surrogate values). */
internal fun charCountCompat(codePoint: Int): Int =
    if (codePoint >= 0x10000) 2 else 1

/** java.lang String.codePointAt: the scalar at [index], or the surrogate's
 * own value when unpaired. */
internal fun codePointAtCompat(s: CharSequence, index: Int): Int {
    val hi = s[index]
    if (hi.isHighSurrogate() && index + 1 < s.length) {
        val lo = s[index + 1]
        if (lo.isLowSurrogate())
            return ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000
    }
    return hi.code
}

/** java.lang String.codePointCount over [beginIndex, endIndex); throws
 * IndexOutOfBoundsException on a bad range, as the oracle does. A pair
 * split by endIndex counts its high half as one unit, like the oracle. */
internal fun codePointCountCompat(s: CharSequence, beginIndex: Int, endIndex: Int): Int {
    if (beginIndex < 0 || endIndex > s.length || beginIndex > endIndex)
        throw IndexOutOfBoundsException("[$beginIndex, $endIndex) in length ${s.length}")
    var count = 0
    var i = beginIndex
    while (i < endIndex) {
        i += if (s[i].isHighSurrogate() && i + 1 < endIndex && s[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return count
}

/** java.lang String.offsetByCodePoints; throws IndexOutOfBoundsException
 * when the offset runs past either end (both live callers pre-clamp). */
internal fun offsetByCodePointsCompat(s: CharSequence, index: Int, codePointOffset: Int): Int {
    if (index < 0 || index > s.length) throw IndexOutOfBoundsException("$index")
    var i = index
    var n = codePointOffset
    while (n > 0) {
        if (i >= s.length) throw IndexOutOfBoundsException("offset ran past end")
        i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
        n--
    }
    while (n < 0) {
        if (i <= 0) throw IndexOutOfBoundsException("offset ran past start")
        i--
        if (s[i].isLowSurrogate() && i > 0 && s[i - 1].isHighSurrogate()) i--
        n++
    }
    return i
}

/** java.lang String.codePoints().toArray(): scalars in order, a lone
 * surrogate as its own value. */
internal fun codePointsOf(s: String): IntArray {
    val out = IntArray(codePointCountCompat(s, 0, s.length))
    var i = 0
    var k = 0
    while (i < s.length) {
        val cp = codePointAtCompat(s, i)
        out[k++] = cp
        i += charCountCompat(cp)
    }
    return out
}

/** The java.lang String(int[], offset, count) constructor: astral values
 * become pairs, surrogate-range values re-materialize verbatim (the
 * oracle accepts them — EditorSession.diff's round-trip depends on it). */
internal fun stringFromCodePoints(codePoints: IntArray, offset: Int, count: Int): String {
    val sb = StringBuilder(count)
    for (k in offset until offset + count) {
        val cp = codePoints[k]
        when {
            cp < 0 || cp > 0x10FFFF -> throw IllegalArgumentException("invalid code point $cp")
            cp < 0x10000 -> sb.append(cp.toChar())
            else -> {
                sb.append((((cp - 0x10000) shr 10) + 0xD800).toChar())
                sb.append((((cp - 0x10000) and 0x3FF) + 0xDC00).toChar())
            }
        }
    }
    return sb.toString()
}
