// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion.host.fonts

import java.io.ByteArrayOutputStream

/**
 * A minimal sfnt: a table directory with the tables the reader consults and
 * nothing a rasterizer could draw. Enough to pin what [OpenTypeMetadata]
 * reads, and what [UserFontStore] does with it, without a real font file in
 * the test tree.
 */
internal object SyntheticFont {
    fun build(
        family: String,
        weight: Int = 400,
        italic: Boolean = false,
        variable: Boolean = false,
        typographicFamily: String? = null,
        macFamilyOnly: Boolean = false,
        version: Int = 0x00010000,
    ): ByteArray {
        val tables = LinkedHashMap<String, ByteArray>()
        tables["OS/2"] = ByteArray(96).also {
            it.u16(4, weight)
            it.u16(62, if (italic) 1 else 0)
        }
        tables["head"] = ByteArray(54)
        tables["name"] = nameTable(family, typographicFamily, macFamilyOnly)
        if (variable) tables["fvar"] = ByteArray(16)
        tables["glyf"] = ByteArray(4)
        val out = ByteArrayOutputStream()
        val header = ByteArray(12)
        header.u32(0, version)
        header.u16(4, tables.size)
        out.write(header)
        var offset = 12 + tables.size * 16
        val records = ByteArrayOutputStream()
        val bodies = ByteArrayOutputStream()
        for ((tag, body) in tables) {
            val record = ByteArray(16)
            tag.toByteArray(Charsets.ISO_8859_1).copyInto(record, 0)
            record.u32(8, offset)
            record.u32(12, body.size)
            records.write(record)
            bodies.write(body)
            val padded = (body.size + 3) / 4 * 4
            repeat(padded - body.size) { bodies.write(0) }
            offset += padded
        }
        out.write(records.toByteArray())
        out.write(bodies.toByteArray())
        return out.toByteArray()
    }

    private fun nameTable(family: String, typographic: String?, macOnly: Boolean): ByteArray {
        data class Record(val platform: Int, val encoding: Int, val language: Int, val nameId: Int, val text: ByteArray)
        val records = mutableListOf<Record>()
        if (macOnly) {
            records += Record(1, 0, 0, 1, family.toByteArray(Charsets.ISO_8859_1))
        } else {
            records += Record(3, 1, 0x409, 1, family.toByteArray(Charsets.UTF_16BE))
            records += Record(1, 0, 0, 1, "Mac $family".toByteArray(Charsets.ISO_8859_1))
            if (typographic != null) records += Record(3, 1, 0x409, 16, typographic.toByteArray(Charsets.UTF_16BE))
        }
        val strings = ByteArrayOutputStream()
        val header = ByteArray(6 + records.size * 12)
        header.u16(2, records.size)
        header.u16(4, header.size)
        records.forEachIndexed { index, record ->
            val at = 6 + index * 12
            header.u16(at, record.platform)
            header.u16(at + 2, record.encoding)
            header.u16(at + 4, record.language)
            header.u16(at + 6, record.nameId)
            header.u16(at + 8, record.text.size)
            header.u16(at + 10, strings.size())
            strings.write(record.text)
        }
        return header + strings.toByteArray()
    }

    private fun ByteArray.u16(at: Int, value: Int) {
        this[at] = (value shr 8).toByte()
        this[at + 1] = value.toByte()
    }

    private fun ByteArray.u32(at: Int, value: Int) {
        this[at] = (value shr 24).toByte()
        this[at + 1] = (value shr 16).toByte()
        this[at + 2] = (value shr 8).toByte()
        this[at + 3] = value.toByte()
    }
}
