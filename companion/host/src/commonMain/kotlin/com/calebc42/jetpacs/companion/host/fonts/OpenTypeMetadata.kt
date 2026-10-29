// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.fonts

/**
 * What one TrueType or OpenType file says about itself, read from its own
 * tables so a user who picks a file never has to type a weight or a name:
 * the family name from `name`, the weight from `OS/2`, italic from `OS/2`
 * (else `head`), and whether `fvar` makes it a variable font.
 */
data class FontFaceMetadata(
    val familyName: String,
    /** A 100-step weight from 100 to 900, as the design model's `font-weight`. */
    val weight: Int,
    val italic: Boolean,
    /** A variable font; it loads at its default instance in this slice. */
    val variable: Boolean,
) {
    /**
     * The bounded identifier a design names this family by: the family name
     * lowercased with every run outside ASCII letters and digits folded to
     * one hyphen, which is also a valid Elisp `font-family` value.
     */
    val slug: String get() = slugOf(familyName)

    companion object {
        /** [name] as a design identifier; `font` when nothing of it survives. */
        fun slugOf(name: String): String {
            val folded = StringBuilder()
            var pendingHyphen = false
            for (character in name.lowercase()) {
                if (character in 'a'..'z' || character in '0'..'9') {
                    if (pendingHyphen && folded.isNotEmpty()) folded.append('-')
                    pendingHyphen = false
                    folded.append(character)
                } else {
                    pendingHyphen = true
                }
            }
            return folded.toString().take(MAX_SLUG).ifEmpty { "font" }
        }

        private const val MAX_SLUG = 64
    }
}

/**
 * A bounded reader of the sfnt table directory. It reads only the three
 * tables it needs, checks every offset against the file, and refuses
 * collections and web fonts, which neither platform's font loader opens.
 * Every refusal is an [IllegalArgumentException] whose message is shown to
 * the user as the reason the file was not added.
 */
object OpenTypeMetadata {
    private const val TRUETYPE = 0x00010000
    private const val OPENTYPE_CFF = 0x4F54544F // 'OTTO'
    private const val APPLE_TRUE = 0x74727565 // 'true'
    private const val COLLECTION = 0x74746366 // 'ttcf'
    private const val WOFF = 0x774F4646 // 'wOFF'
    private const val WOFF2 = 0x774F4632 // 'wOF2'
    private const val MAX_TABLES = 64

    fun parse(bytes: ByteArray): FontFaceMetadata {
        require(bytes.size >= 12) { "the file is too short to be a font" }
        when (val version = bytes.u32(0)) {
            TRUETYPE, OPENTYPE_CFF, APPLE_TRUE -> Unit
            COLLECTION -> throw IllegalArgumentException("font collections (.ttc) are not supported; add one face at a time")
            WOFF, WOFF2 -> throw IllegalArgumentException("web fonts (WOFF) are not supported; add the .ttf or .otf")
            else -> throw IllegalArgumentException("not a TrueType or OpenType font (header ${version.toUInt().toString(16)})")
        }
        val tableCount = bytes.u16(4)
        require(tableCount in 1..MAX_TABLES) { "the font declares $tableCount tables" }
        require(bytes.size >= 12 + tableCount * 16) { "the table directory runs past the end of the file" }
        val tables = HashMap<String, IntRange>(tableCount)
        for (index in 0 until tableCount) {
            val record = 12 + index * 16
            val tag = String(bytes, record, 4, Charsets.ISO_8859_1)
            val offset = bytes.u32(record + 8)
            val length = bytes.u32(record + 12)
            require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size) {
                "table $tag lies outside the file"
            }
            tables[tag] = offset until offset + length
        }
        val os2 = tables["OS/2"]
        val weight = os2?.takeIf { it.count() >= 6 }?.let { bytes.u16(it.first + 4) }.toWeight()
        val italic = when {
            os2 != null && os2.count() >= 64 -> bytes.u16(os2.first + 62) and 1 != 0
            else -> tables["head"]?.takeIf { it.count() >= 46 }?.let { bytes.u16(it.first + 44) and 2 != 0 } ?: false
        }
        val name = tables["name"]?.let { familyName(bytes, it) }?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("the font carries no family name")
        return FontFaceMetadata(
            familyName = name.trim().take(MAX_NAME),
            weight = weight,
            italic = italic,
            variable = "fvar" in tables,
        )
    }

    /** Typographic family (name ID 16) before the legacy family (ID 1); Windows Unicode before Macintosh. */
    private fun familyName(bytes: ByteArray, table: IntRange): String? {
        if (table.count() < 6) return null
        val count = bytes.u16(table.first + 2)
        val stringsAt = table.first + bytes.u16(table.first + 4)
        var best: Pair<Int, String>? = null
        for (index in 0 until minOf(count, MAX_NAME_RECORDS)) {
            val record = table.first + 6 + index * 12
            if (record + 12 > table.last + 1) break
            val platform = bytes.u16(record)
            val nameId = bytes.u16(record + 6)
            if (nameId != 16 && nameId != 1) continue
            val length = bytes.u16(record + 8)
            val offset = stringsAt + bytes.u16(record + 10)
            if (length == 0 || offset + length > table.last + 1 || offset + length > bytes.size) continue
            val text = when (platform) {
                0, 3 -> String(bytes, offset, length, Charsets.UTF_16BE)
                1 -> String(bytes, offset, length, Charsets.ISO_8859_1)
                else -> continue
            }
            // Lower ranks first: typographic beats legacy, Unicode beats Macintosh.
            val rank = (if (nameId == 16) 0 else 2) + (if (platform == 1) 1 else 0)
            if (best == null || rank < best.first) best = rank to text
        }
        return best?.second
    }

    /** `usWeightClass` as a design weight: legacy 1..9 scaled, otherwise rounded to the nearest hundred. */
    private fun Int?.toWeight(): Int {
        val raw = this ?: return 400
        val scaled = if (raw in 1..9) raw * 100 else raw
        if (scaled <= 0) return 400
        return (((scaled + 50) / 100) * 100).coerceIn(100, 900)
    }

    private fun ByteArray.u16(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

    private fun ByteArray.u32(at: Int): Int =
        ((this[at].toInt() and 0xFF) shl 24) or ((this[at + 1].toInt() and 0xFF) shl 16) or
            ((this[at + 2].toInt() and 0xFF) shl 8) or (this[at + 3].toInt() and 0xFF)

    private const val MAX_NAME = 128
    private const val MAX_NAME_RECORDS = 512
}
