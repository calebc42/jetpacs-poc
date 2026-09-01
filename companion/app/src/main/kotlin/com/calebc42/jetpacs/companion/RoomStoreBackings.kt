// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.companion

import com.calebc42.ebp.wire.AtomicSurfaceBacking
import com.calebc42.ebp.wire.AtomicReminderPresentationBacking
import com.calebc42.ebp.wire.DurableDraft
import com.calebc42.ebp.wire.DurableOutboxEvent
import com.calebc42.ebp.wire.DurableOutboxPolicy
import com.calebc42.ebp.wire.DurablePlatformEffect
import com.calebc42.ebp.wire.DurableReminder
import com.calebc42.ebp.wire.DurableReminderReceipt
import com.calebc42.ebp.wire.DurableSurfaceRecord
import com.calebc42.ebp.wire.DurableTheme
import com.calebc42.ebp.wire.DurableTriggerRegistration
import com.calebc42.ebp.wire.EventId
import com.calebc42.ebp.wire.PairingFence
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.PersistedDraft
import com.calebc42.ebp.wire.PersistedRecord
import com.calebc42.ebp.wire.PersistedRegistration
import com.calebc42.ebp.wire.QueueSnapshot
import com.calebc42.ebp.wire.QueueStore
import com.calebc42.ebp.wire.ReminderBacking
import com.calebc42.ebp.wire.ReminderState
import com.calebc42.ebp.wire.SurfaceState
import com.calebc42.ebp.wire.TriggerBacking
import com.calebc42.ebp.wire.TriggerOccurrenceTransaction
import com.calebc42.ebp.wire.TriggerState
import com.calebc42.ebp.wire.replaceTheme
import com.calebc42.ebp.wire.ReplaceThemeCommand
import com.calebc42.ebp.wire.ReplaceThemeResult
import com.calebc42.jetpacs.core.ebpstore.RoomEbpDurableStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Synchronous compatibility boundary for the proven wire runtime. Production
 * durability still has one owner: every call below commits through the same
 * pairing-scoped Room store. The process command actor is the only caller.
 */
internal class RoomSurfaceBacking(
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    private val nowMs: () -> Long,
) : AtomicSurfaceBacking {
    private var knownRecords = linkedMapOf<String, PersistedRecord>()
    private var acceptedAt = mutableMapOf<String, Long>()
    private var firstSeenOrdinal = mutableMapOf<String, Long>()
    private var knownDrafts = linkedMapOf<Pair<String, String>, PersistedDraft>()

    override fun load(): SurfaceState = roomBlocking {
        val snapshot = durableStore.restore(pairingId)
        val records = snapshot.surfaces.map { row ->
            acceptedAt[row.surfaceId] = row.acceptedAtMs
            firstSeenOrdinal[row.surfaceId] = row.firstSeenOrdinal
            PersistedRecord(
                surface = row.surfaceId,
                revision = row.revision,
                present = row.present,
                spec = row.spec,
                currentView = row.currentView,
                staleSpec = row.staleSpec,
                staleAfterSeconds = row.staleAfterSeconds,
            )
        }
        val drafts = snapshot.drafts.map { row ->
            PersistedDraft(
                surface = row.surfaceId,
                id = row.nodeId,
                value = row.value.takeUnless { it is JsonNull },
            )
        }
        knownRecords = records.associateByTo(linkedMapOf()) { it.surface }
        knownDrafts = drafts.associateByTo(linkedMapOf()) { it.surface to it.id }
        SurfaceState(records, drafts)
    }

    override fun replaceRecords(records: List<PersistedRecord>) {
        replace(records, null)
    }

    override fun replaceDrafts(drafts: List<PersistedDraft>) {
        replace(null, drafts)
    }

    override fun replaceState(
        records: List<PersistedRecord>,
        drafts: List<PersistedDraft>,
    ) {
        replace(records, drafts)
    }

    private fun replace(
        records: List<PersistedRecord>?,
        drafts: List<PersistedDraft>?,
    ) = roomBlocking {
        val nextRecords = records?.associateByTo(linkedMapOf()) { it.surface }
        val nextDrafts = drafts?.associateByTo(linkedMapOf()) { it.surface to it.id }
        val nextAcceptedAt = acceptedAt.toMutableMap()
        val nextOrdinals = firstSeenOrdinal.toMutableMap()
        durableStore.write(pairingId) {
            check(pairing()?.fence == PairingFence.ACTIVE) {
                "Surface persistence requires an active pairing"
            }
            val initialRuntime = runtime()
            var updatedRuntime = initialRuntime
            if (nextRecords != null) {
                (knownRecords.keys - nextRecords.keys).forEach { deleteSurface(it) }
                nextRecords.values.forEach { record ->
                    val previous = knownRecords[record.surface]
                    val existing = surface(record.surface)
                    val ordinal = existing?.firstSeenOrdinal ?: updatedRuntime.nextSurfaceOrdinal.also {
                        updatedRuntime = updatedRuntime.copy(nextSurfaceOrdinal = it + 1)
                    }
                    val accepted = if (previous == record) {
                        acceptedAt[record.surface] ?: existing?.acceptedAtMs ?: safeNow(nowMs)
                    } else {
                        safeNow(nowMs)
                    }
                    putSurface(
                        DurableSurfaceRecord(
                            surfaceId = record.surface,
                            revision = record.revision,
                            present = record.present,
                            spec = record.spec,
                            staleSpec = record.staleSpec,
                            staleAfterSeconds = record.staleAfterSeconds,
                            currentView = record.currentView,
                            acceptedAtMs = accepted,
                            firstSeenOrdinal = ordinal,
                        ),
                    )
                    nextAcceptedAt[record.surface] = accepted
                    nextOrdinals[record.surface] = ordinal
                }
                if (updatedRuntime != initialRuntime) putRuntime(updatedRuntime)
            }
            if (nextDrafts != null) {
                (knownDrafts.keys - nextDrafts.keys).forEach { (surface, node) ->
                    deleteDraft(surface, node)
                }
                nextDrafts.values.forEach { draft ->
                    putDraft(
                        DurableDraft(
                            surfaceId = draft.surface,
                            nodeId = draft.id,
                            value = draft.value ?: JsonNull,
                        ),
                    )
                }
            }
        }
        if (nextRecords != null) {
            knownRecords = nextRecords
            acceptedAt = nextAcceptedAt.filterKeys { it in nextRecords }.toMutableMap()
            firstSeenOrdinal = nextOrdinals.filterKeys { it in nextRecords }.toMutableMap()
            knownDrafts.entries.removeAll { (key, _) -> key.first !in nextRecords }
        }
        if (nextDrafts != null) knownDrafts = nextDrafts
        Unit
    }
}

/**
 * Actor-confined transaction coordinator for one queue/wake trigger firing.
 * DurableQueue first stages its next snapshot; TriggerStore then supplies the
 * consumed runtime records. Room commits both projections together.
 */
internal class RoomTriggerOccurrenceCoordinator(
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
) : TriggerOccurrenceTransaction {
    private var active = false
    private var committed = false
    private var queueSnapshot: QueueSnapshot? = null

    override fun begin() {
        check(!active) { "A trigger occurrence transaction is already active" }
        active = true
        committed = false
        queueSnapshot = null
    }

    fun stageQueue(snapshot: QueueSnapshot): Boolean {
        if (!active) return false
        check(!committed && queueSnapshot == null) {
            "A trigger occurrence can stage exactly one queue projection"
        }
        queueSnapshot = snapshot
        return true
    }

    fun replaceTriggers(registrations: List<DurableTriggerRegistration>): Boolean {
        if (!active) return false
        check(!committed) { "Trigger occurrence was already committed" }
        val queue = checkNotNull(queueSnapshot) {
            "Trigger records cannot commit before the queue projection"
        }
        roomBlocking {
            durableStore.replaceOutboxState(
                pairingId = pairingId,
                events = queue.records.map { it.toDurableEvent() }
                    .sortedBy { it.queueSequence },
                nextQueueSequence = queue.nextSeq,
                effectiveClockHighWaterMs = queue.clockHighWater,
                triggerRegistrations = registrations,
            )
        }
        committed = true
        queueSnapshot = null
        return true
    }

    override fun complete() {
        // The durable write has already succeeded. Cleanup is deliberately
        // non-throwing so it cannot turn a committed occurrence into a rollback.
        active = false
        committed = false
        queueSnapshot = null
    }

    override fun abort() {
        active = false
        committed = false
        queueSnapshot = null
    }
}

internal class RoomQueueStore(
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    private val occurrenceCoordinator: RoomTriggerOccurrenceCoordinator? = null,
) : QueueStore {
    override fun load(): QueueSnapshot = roomBlocking {
        val snapshot = durableStore.restore(pairingId)
        QueueSnapshot(
            records = snapshot.outbox.sortedBy { it.queueSequence }.map { it.toWireRecord() },
            nextSeq = snapshot.runtime.nextQueueSequence,
            clockHighWater = snapshot.runtime.effectiveClockHighWaterMs,
        )
    }

    override fun replace(snapshot: QueueSnapshot) {
        if (occurrenceCoordinator?.stageQueue(snapshot) == true) return
        persist(snapshot)
    }

    private fun persist(snapshot: QueueSnapshot) = roomBlocking {
        val events = snapshot.records.map { it.toDurableEvent() }.sortedBy { it.queueSequence }
        durableStore.replaceOutboxState(
            pairingId = pairingId,
            events = events,
            nextQueueSequence = snapshot.nextSeq,
            effectiveClockHighWaterMs = snapshot.clockHighWater,
        )
    }
}

internal class RoomReminderBacking(
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    private val nowMs: () -> Long,
) : AtomicReminderPresentationBacking {
    private var knownReminders = linkedMapOf<Pair<String, String>, DurableReminder>()
    private var knownReceipts = linkedMapOf<String, DurableReminderReceipt>()

    override fun load(): ReminderState = roomBlocking {
        val snapshot = durableStore.restore(pairingId)
        knownReminders = snapshot.reminders.associateByTo(linkedMapOf()) {
            it.owner to it.reminderId
        }
        knownReceipts = snapshot.reminderReceipts.associateByTo(linkedMapOf()) {
            reminderReceiptKey(it.owner, it.reminderId, it.atMs)
        }
        ReminderState(
            owners = snapshot.reminders.groupBy { it.owner }
                .mapValues { (_, values) ->
                    values.sortedBy { it.authoredOrdinal }.map { it.payload }
                },
            fired = knownReceipts.keys,
        )
    }

    override fun replace(state: ReminderState) = replace(state, null)

    override fun replaceWithPresentation(
        state: ReminderState,
        presentationEffect: DurablePlatformEffect,
    ) = replace(state, presentationEffect)

    private fun replace(
        state: ReminderState,
        presentationEffect: DurablePlatformEffect?,
    ) = roomBlocking {
        val nextReminders = linkedMapOf<Pair<String, String>, DurableReminder>()
        state.owners.forEach { (owner, reminders) ->
            reminders.forEachIndexed { ordinal, payload ->
                val id = payload.requiredString("id")
                nextReminders[owner to id] = DurableReminder(
                    owner = owner,
                    reminderId = id,
                    atMs = payload.integralTimestamp("at_ms"),
                    authoredOrdinal = ordinal.toLong(),
                    payload = payload,
                )
            }
        }
        val nextReceipts = state.fired.associateWithTo(linkedMapOf()) { key ->
            knownReceipts[key] ?: nextReminders.values.firstOrNull {
                reminderReceiptKey(it.owner, it.reminderId, it.atMs) == key
            }?.let {
                DurableReminderReceipt(
                    owner = it.owner,
                    reminderId = it.reminderId,
                    atMs = it.atMs,
                    firedAtMs = safeNow(nowMs),
                )
            } ?: error("Fired receipt has no matching reminder")
        }
        durableStore.write(pairingId) {
            check(pairing()?.fence == PairingFence.ACTIVE) {
                "Reminder persistence requires an active pairing"
            }
            (knownReminders.keys - nextReminders.keys).forEach { (owner, id) ->
                deleteReminder(owner, id)
            }
            nextReminders.values.forEach { putReminder(it) }
            (knownReceipts.keys - nextReceipts.keys).forEach { key ->
                knownReceipts.getValue(key).let {
                    deleteReminderReceipt(it.owner, it.reminderId, it.atMs)
                }
            }
            nextReceipts.values.forEach { receipt ->
                if (reminderReceipts(receipt.owner).none {
                        it.reminderId == receipt.reminderId && it.atMs == receipt.atMs
                    }) putReminderReceipt(receipt)
            }
            presentationEffect?.let { putPlatformEffect(it) }
        }
        knownReminders = nextReminders
        knownReceipts = nextReceipts
    }
}

internal class RoomTriggerBacking(
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    private val occurrenceCoordinator: RoomTriggerOccurrenceCoordinator? = null,
) : TriggerBacking {
    private var known = linkedMapOf<Pair<String, String>, DurableTriggerRegistration>()

    override fun load(): TriggerState = roomBlocking {
        val snapshot = durableStore.restore(pairingId)
        known = snapshot.triggers.associateByTo(linkedMapOf()) {
            it.identity to it.triggerId
        }
        TriggerState(
            snapshot.triggers.groupBy { it.identity }.mapValues { (_, entries) ->
                entries.sortedBy { it.authoredOrdinal }.map { it.toPersistedRegistration() }
            },
        )
    }

    override fun replace(state: TriggerState) {
        val next = linkedMapOf<Pair<String, String>, DurableTriggerRegistration>()
        state.identities.forEach { (identity, registrations) ->
            registrations.forEachIndexed { ordinal, registration ->
                val id = registration.entry.requiredString("id")
                next[identity to id] = DurableTriggerRegistration(
                    identity = identity,
                    triggerId = id,
                    authoredOrdinal = ordinal.toLong(),
                    entry = registration.entry,
                    canonicalIdentity = registration.entry.toString(),
                    throttleFloorMs = registration.throttleFloorMs,
                    oneShotCompleted = registration.oneShotCompleted,
                    scheduleAnchorMs = registration.scheduleAnchorMs,
                    lastFireFloorMs = registration.lastFireFloorMs,
                    bootGeneration = registration.bootGeneration,
                )
            }
        }
        if (occurrenceCoordinator?.replaceTriggers(next.values.toList()) != true) {
            roomBlocking {
                durableStore.write(pairingId) {
                    check(pairing()?.fence == PairingFence.ACTIVE) {
                        "Trigger persistence requires an active pairing"
                    }
                    (known.keys - next.keys).forEach { (identity, id) ->
                        deleteTrigger(identity, id)
                    }
                    next.values.forEach { putTrigger(it) }
                }
            }
        }
        known = next
    }
}

internal class RoomThemeStore(
    private val durableStore: RoomEbpDurableStore,
    private val pairingId: PairingId,
    private val nowMs: () -> Long,
) {
    fun load(): JsonObject? = roomBlocking { durableStore.restore(pairingId).theme?.payload }

    fun replace(payload: JsonObject) = roomBlocking {
        check(
            durableStore.replaceTheme(
                ReplaceThemeCommand(
                    pairingId,
                    DurableTheme(payload, safeNow(nowMs)),
                ),
            ) is ReplaceThemeResult.Replaced,
        ) { "Theme persistence requires an active pairing" }
        Unit
    }
}

private fun DurableOutboxEvent.toWireRecord(): JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("event", payload)
        put("policy", policy.name.lowercase())
        put("expires_at_ms", expiresAtMs)
        dedupeKey?.let { put("dedupe", it) }
        if (pendingLocal) put("pending_local", true)
        triggerIdentity?.let { put("trigger_identity", it) }
        put("queue_seq", queueSequence)
    }

private fun JsonObject.toDurableEvent(): DurableOutboxEvent {
    val event = requiredObject("event")
    val policy = when (requiredString("policy")) {
        "queue" -> DurableOutboxPolicy.QUEUE
        "wake" -> DurableOutboxPolicy.WAKE
        else -> error("Only durable queue/wake events may enter Room")
    }
    return DurableOutboxEvent(
        queueSequence = requiredLong("queue_seq"),
        eventId = EventId(event.requiredString("event_id")),
        payload = event,
        policy = policy,
        occurredAtMs = event.requiredLong("occurred_at_ms"),
        queuedAtMs = event.requiredLong("queued_at_ms"),
        expiresAtMs = requiredLong("expires_at_ms"),
        dedupeKey = (this["dedupe"] as? JsonPrimitive)?.content,
        accountedBytes = toString().encodeToByteArray().size.toLong(),
        pendingLocal = booleanOr("pending_local"),
        triggerIdentity = (this["trigger_identity"] as? JsonPrimitive)?.content,
    )
}

private fun DurableTriggerRegistration.toPersistedRegistration() =
    PersistedRegistration(
        entry = entry,
        throttleFloorMs = throttleFloorMs,
        oneShotCompleted = oneShotCompleted,
        scheduleAnchorMs = scheduleAnchorMs,
        lastFireFloorMs = lastFireFloorMs,
        bootGeneration = bootGeneration,
    )

private fun JsonObject.integralTimestamp(key: String): Long {
    val primitive = this[key] as? JsonPrimitive ?: throw NoSuchElementException(key)
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
        ?: throw NoSuchElementException(key)
}

private fun JsonObject.requiredObject(key: String): JsonObject =
    this[key] as? JsonObject ?: throw NoSuchElementException(key)

private fun JsonObject.requiredString(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw NoSuchElementException(key)

private fun JsonObject.requiredLong(key: String): Long =
    (this[key] as? JsonPrimitive)?.longOrNull ?: throw NoSuchElementException(key)

private fun JsonObject.booleanOr(key: String, default: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }
        ?.content?.toBooleanStrictOrNull() ?: default

private fun reminderReceiptKey(owner: String, reminderId: String, atMs: Long) =
    "$owner $reminderId $atMs"

private fun safeNow(nowMs: () -> Long): Long = nowMs().coerceAtLeast(0)

private fun <T> roomBlocking(block: suspend () -> T): T = runBlocking { block() }
