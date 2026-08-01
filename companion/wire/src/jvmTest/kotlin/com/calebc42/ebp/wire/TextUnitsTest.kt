// SPDX-License-Identifier: GPL-3.0-or-later
// RF-2c H2: the TextUnits compat helpers property-checked against their
// java.lang oracles (the runbook §3.2 requirement). The corpora include
// LONE surrogates deliberately: the helpers must reproduce the oracles'
// unpaired-surrogate semantics exactly, because EditorSession's splice
// math and diff round-trip depend on them.
package com.calebc42.ebp.wire

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TextUnitsTest {

    private val fixed = listOf(
        "", "a", "hello, world", "héllo wörld",              // ASCII + Latin-1 BMP
        "日本語テキスト、混在 ascii",                            // BMP CJK
        "astral 😀🤖 pair 𐍈", // emoji + Gothic
        "\uD800", "\uDC00", "\uDC00\uD800",                  // lone high, lone low, reversed
        "a\uD800b\uDC00c😀d",                      // interleaved lone + valid
        "😀", "x\uD83D", "\uDE00x",                // pair alone, split halves
    )

    /** Seeded fuzz: ASCII, BMP, valid pairs, and lone surrogate units. */
    private fun fuzz(seed: Int, n: Int, maxLen: Int): List<String> {
        val rng = Random(seed)
        return List(n) {
            val sb = StringBuilder()
            repeat(rng.nextInt(maxLen + 1)) {
                when (rng.nextInt(5)) {
                    0 -> sb.append(('a' + rng.nextInt(26)))
                    1 -> sb.append((0x00A0 + rng.nextInt(0x2000)).toChar())
                    2 -> { // a valid astral pair
                        val cp = 0x10000 + rng.nextInt(0x1000)
                        sb.append((((cp - 0x10000) shr 10) + 0xD800).toChar())
                        sb.append((((cp - 0x10000) and 0x3FF) + 0xDC00).toChar())
                    }
                    3 -> sb.append((0xD800 + rng.nextInt(0x400)).toChar()) // lone high
                    else -> sb.append((0xDC00 + rng.nextInt(0x400)).toChar()) // lone low
                }
            }
            sb.toString()
        }
    }

    private val corpus = fixed + fuzz(seed = 42, n = 2_000, maxLen = 64)

    @Test
    fun codePointAtMatchesOracleAtEveryIndex() {
        for (s in corpus) for (i in s.indices)
            assertEquals("codePointAt($i) of ${s.length}-char string",
                s.codePointAt(i), codePointAtCompat(s, i))
    }

    @Test
    fun codePointCountMatchesOracleOnSubranges() {
        val rng = Random(7)
        for (s in corpus) {
            assertEquals(s.codePointCount(0, s.length),
                codePointCountCompat(s, 0, s.length))
            repeat(8) {
                val b = rng.nextInt(s.length + 1)
                val e = b + rng.nextInt(s.length - b + 1)
                assertEquals("range [$b,$e)", s.codePointCount(b, e),
                    codePointCountCompat(s, b, e))
            }
        }
        // The bad-range throw matches the oracle's exception type.
        try {
            codePointCountCompat("ab", 1, 5)
            fail("expected IndexOutOfBoundsException")
        } catch (_: IndexOutOfBoundsException) { }
    }

    @Test
    fun offsetByCodePointsMatchesOracleBothDirections() {
        for (s in corpus) {
            val total = s.codePointCount(0, s.length)
            for (k in 0..total)
                assertEquals("forward $k", s.offsetByCodePoints(0, k),
                    offsetByCodePointsCompat(s, 0, k))
            for (k in 0..total)
                assertEquals("backward $k", s.offsetByCodePoints(s.length, -k),
                    offsetByCodePointsCompat(s, s.length, -k))
        }
        try {
            offsetByCodePointsCompat("ab", 0, 3)
            fail("expected IndexOutOfBoundsException")
        } catch (_: IndexOutOfBoundsException) { }
    }

    @Test
    fun charCountMatchesOracleOverBoundaries() {
        val samples = listOf(0, 0x41, 0x7F, 0x80, 0x7FF, 0x800, 0xD7FF,
            0xD800, 0xDBFF, 0xDC00, 0xDFFF, 0xE000, 0xFFFF, 0x10000,
            0x1F600, 0x10FFFF) + List(500) { Random(9).nextInt(0x110000) }
        for (cp in samples)
            assertEquals("charCount($cp)", Character.charCount(cp), charCountCompat(cp))
    }

    @Test
    fun codePointsOfMatchesStreamOracle() {
        for (s in corpus)
            assertTrue("codePointsOf mismatch for ${s.length}-char string",
                s.codePoints().toArray().contentEquals(codePointsOf(s)))
    }

    @Test
    fun stringFromCodePointsMatchesCtorOracle() {
        val rng = Random(11)
        for (s in corpus) {
            val cps = s.codePoints().toArray()
            // whole array, incl. lone-surrogate values re-materializing
            assertEquals(String(cps, 0, cps.size),
                stringFromCodePoints(cps, 0, cps.size))
            // random slices
            repeat(4) {
                if (cps.isEmpty()) return@repeat
                val off = rng.nextInt(cps.size)
                val cnt = rng.nextInt(cps.size - off + 1)
                assertEquals(String(cps, off, cnt), stringFromCodePoints(cps, off, cnt))
            }
        }
    }

    @Test
    fun utf8SizeMatchesTheJvmTapeIncludingLoneSurrogates() {
        // Measured fact (it falsified the runbook's §3.2 caveat as stated):
        // on the JVM, encodeToByteArray delegates to java's REPLACE-mode
        // encoder, so a lone surrogate is '?' — ONE byte, identical to the
        // retired toByteArray(UTF_8) tape. The U+FFFD (3-byte) substitution
        // is Kotlin/Native's encoder, so the lone-surrogate delta is
        // per-PLATFORM and only materializes when a native target lands.
        // The invariant is the single tape: measure and emit are the same
        // call, so they agree on every platform whatever its substitution.
        assertEquals(1, "\uD800".utf8Size())
        for (s in corpus)
            assertEquals(s.toByteArray(Charsets.UTF_8).size, s.utf8Size())
    }
}
