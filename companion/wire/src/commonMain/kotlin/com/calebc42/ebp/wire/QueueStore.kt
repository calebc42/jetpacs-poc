// SPDX-License-Identifier: GPL-3.0-or-later
// Durable backing for the SPEC 15 queue. Every mutation is one atomic
// whole-snapshot replace: the SPEC 15.1/15.2 "one durable transaction"
// requirements fall out of that shape by construction. The file store is
// the kill-matrix witness — a process death is "re-open the same file".
package com.calebc42.ebp.wire

import kotlinx.serialization.json.JsonObject

/** One durable state of the queue: records plus the two counters that
 * must survive restart (SPEC 15.1 queue_seq; SPEC 15.2 clock mark). */
data class QueueSnapshot(
    val records: List<JsonObject>,
    val nextSeq: Long,
    val clockHighWater: Long,
)

interface QueueStore {
    fun load(): QueueSnapshot
    /** Atomically and durably replace the whole state; throws on failure. */
    fun replace(snapshot: QueueSnapshot)
}

class MemoryQueueStore : QueueStore {
    private var snapshot = QueueSnapshot(emptyList(), 1, 0)
    override fun load(): QueueSnapshot = snapshot
    override fun replace(snapshot: QueueSnapshot) { this.snapshot = snapshot }
}
