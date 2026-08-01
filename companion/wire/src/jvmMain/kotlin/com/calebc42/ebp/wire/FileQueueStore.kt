// SPDX-License-Identifier: GPL-3.0-or-later
// The java.io half of QueueStore.kt, split out at RF-2c(H6.a).
package com.calebc42.ebp.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * JSON file with write-to-temp, fsync, atomic-rename replacement, so a
 * crash leaves either the old state or the new state, never a torn one.
 */
class FileQueueStore(private val file: File) : QueueStore {

    override fun load(): QueueSnapshot {
        if (!file.exists()) return QueueSnapshot(emptyList(), 1, 0)
        val text = file.readText(Charsets.UTF_8)
        if (text.isBlank()) return QueueSnapshot(emptyList(), 1, 0)
        // PERSISTED text is read by kotlinx's lenient parser, NEVER by
        // EbpJson.parse: that one is the strict WIRE parser, and SPEC 4.5
        // caps a frame at 64 nested containers. A queue record's `args` may
        // legally nest deeper on disk than any single frame may carry (the
        // record and the {"records":[…]} wrapper alone add levels), so a
        // strict re-parse would turn a perfectly legal store file into a boot
        // crash-loop. Pinned by
        // PersistenceCompatTest.strictParserRejectsWhatTheStoreMustAccept.
        val root = Json.parseToJsonElement(text).jsonObject
        val array = root.reqArr("records")
        return QueueSnapshot(
            records = array.map { it.jsonObject },
            nextSeq = root.reqLong("next_seq"),
            clockHighWater = root.reqLong("clock_high_water"),
        )
    }

    override fun replace(snapshot: QueueSnapshot) {
        val root = buildJsonObject {
            put("records", JsonArray(snapshot.records))
            put("next_seq", snapshot.nextSeq)
            put("clock_high_water", snapshot.clockHighWater)
        }
        writeJsonAtomic(file, root)
    }
}
