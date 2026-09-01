// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.jetpacs.core.ebpstore

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.calebc42.ebp.wire.AdmitOutboxCommand
import com.calebc42.ebp.wire.DurableDraft
import com.calebc42.ebp.wire.DurableOutboxEvent
import com.calebc42.ebp.wire.DurableOutboxIndexEntry
import com.calebc42.ebp.wire.DurableOutboxPolicy
import com.calebc42.ebp.wire.DurablePairing
import com.calebc42.ebp.wire.DurablePlatformEffect
import com.calebc42.ebp.wire.DurablePlatformEffectState
import com.calebc42.ebp.wire.DurableReminder
import com.calebc42.ebp.wire.DurableReminderReceipt
import com.calebc42.ebp.wire.DurableSnapshot
import com.calebc42.ebp.wire.DurableSurfaceRecord
import com.calebc42.ebp.wire.DurableTheme
import com.calebc42.ebp.wire.DurableTriggerRegistration
import com.calebc42.ebp.wire.EbpDurableStore
import com.calebc42.ebp.wire.EbpWriteTransaction
import com.calebc42.ebp.wire.EVENT_ID_RETENTION_FLOOR_MS
import com.calebc42.ebp.wire.EventId
import com.calebc42.ebp.wire.IssuedEventId
import com.calebc42.ebp.wire.PairingFence
import com.calebc42.ebp.wire.PairingId
import com.calebc42.ebp.wire.PairingRuntime
import com.calebc42.ebp.wire.PreparedOutboxAdmission
import com.calebc42.ebp.wire.SurfaceCardinality
import com.calebc42.ebp.wire.requireEbpTimestamp
import com.calebc42.jetpacs.core.database.IssuedEventIdEntity
import com.calebc42.jetpacs.core.database.JetpacsDatabase
import com.calebc42.jetpacs.core.database.PairingPartitionEntity
import com.calebc42.jetpacs.core.database.PairingRevocationEntity
import com.calebc42.jetpacs.core.database.PairingRuntimeEntity
import com.calebc42.jetpacs.core.database.PairingThemeEntity
import com.calebc42.jetpacs.core.database.PlatformEffectEntity
import com.calebc42.jetpacs.core.database.QueueEventEntity
import com.calebc42.jetpacs.core.database.QueueEventVersionRow
import com.calebc42.jetpacs.core.database.SurfaceDraftEntity
import com.calebc42.jetpacs.core.database.SurfaceRecordEntity
import com.calebc42.jetpacs.core.database.ReminderEntity
import com.calebc42.jetpacs.core.database.ReminderReceiptEntity
import com.calebc42.jetpacs.core.database.TriggerRegistrationEntity
import com.calebc42.jetpacs.core.database.TriggerRuntimeEntity
import com.calebc42.jetpacs.core.database.TriggerWithRuntime
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Room3-backed Jetpacs implementation of the portable EBP durable-store laws.
 * Outbox envelope crypto and parsing happen only before or after a Room
 * transaction; the write phase performs generation/version and alias
 * compare-and-set.
 */
class RoomEbpDurableStore(
    private val database: JetpacsDatabase,
    private val aliasResolver: ProvisionedPairingAliasResolver,
    private val envelopeCodec: OutboxPayloadEnvelopeCodec,
    private val generationSource: StorageGenerationSource,
    private val json: Json = Json,
) : EbpDurableStore {
    override suspend fun restore(pairingId: PairingId): DurableSnapshot {
        val raw = database.withReadTransaction {
            RawRestore(
                pairingState = database.readPairingStateRows(pairingId),
                surfaces = database.surfaceDao().getRecords(pairingId.value),
                drafts = database.surfaceDao().getDrafts(pairingId.value),
                outbox = database.queueEventDao().getEvents(pairingId.value).map {
                    it.copy(payloadEnvelope = it.payloadEnvelope.copyOf())
                },
                issuedEventIds = database.issuedEventIdDao().getIssuedEventIds(pairingId.value),
                reminders = database.reminderDao().getReminders(pairingId.value),
                reminderReceipts = database.reminderDao().getReceipts(pairingId.value, null),
                triggers = database.triggerDao().getTriggers(pairingId.value, null),
                theme = database.themeAndEffectDao().getTheme(pairingId.value),
                platformEffects = database.themeAndEffectDao().getEffects(
                    pairingId.value,
                    DurablePlatformEffectState.entries.map { it.name },
                    Int.MAX_VALUE,
                ),
            )
        }

        val pairing = raw.pairingState.validatedPairing(pairingId)
        raw.requireConsistentOwnedState()
        if (pairing == null) return DurableSnapshot.empty()

        val payloadAlias = raw.pairingState.partition?.payloadKeyAlias
        val decodedOutbox = if (raw.outbox.isEmpty()) {
            emptyList()
        } else {
            checkNotNull(payloadAlias) { "Outbox rows require a live pairing partition" }
            raw.outbox.map { row ->
                val metadata = row.toEnvelopeMetadata(pairingId)
                val payload = envelopeCodec.decode(
                    envelope = row.payloadEnvelope.copyOf(),
                    metadata = metadata,
                    payloadKeyAlias = payloadAlias,
                )
                row.toDurableOutboxEvent(payload)
            }
        }

        return DurableSnapshot(
            pairing = pairing,
            runtime = raw.pairingState.runtime?.toPairingRuntime()
                ?: PairingRuntime(),
            surfaces = raw.surfaces.map { it.toDurableSurface(json) },
            drafts = raw.drafts.map { it.toDurableDraft(json) },
            outbox = decodedOutbox,
            issuedEventIds = raw.issuedEventIds.map { it.toIssuedEventId() },
            reminders = raw.reminders.map { it.toDurableReminder(json) },
            reminderReceipts = raw.reminderReceipts.map { it.toDurableReminderReceipt() },
            triggers = raw.triggers.map { it.toDurableTrigger(json) },
            theme = raw.theme?.toDurableTheme(json),
            platformEffects = raw.platformEffects.map { it.toDurablePlatformEffect(json) },
        )
    }

    override suspend fun <T> write(
        pairingId: PairingId,
        block: suspend EbpWriteTransaction.() -> T,
    ): T {
        // Pure host lookup, deliberately completed before acquiring Room's writer.
        val provisionedAliases = aliasResolver.resolve(pairingId)
        return database.withWriteTransaction {
            val scope = RoomWriteScope(
                database = database,
                pairingId = pairingId,
                json = json,
                provisionedAliases = provisionedAliases,
                preparation = null,
            )
            try {
                scope.block()
            } finally {
                scope.close()
            }
        }
    }

    override suspend fun <T> writePreparedOutbox(
        command: AdmitOutboxCommand,
        block: suspend EbpWriteTransaction.(PreparedOutboxAdmission) -> T,
    ): T {
        while (true) {
            val preparation = prepareOutbox(command)
            try {
                return database.withWriteTransaction {
                    val scope = RoomWriteScope(
                        database = database,
                        pairingId = command.pairingId,
                        json = json,
                        provisionedAliases = null,
                        preparation = preparation,
                    )
                    try {
                        scope.assertPreparationCurrent()
                        scope.block(preparation)
                    } finally {
                        scope.close()
                    }
                }
            } catch (_: StaleOutboxPreparation) {
                currentCoroutineContext().ensureActive()
                // Re-read, decode/seal outside Room, then rerun the effect-free reducer.
            }
        }
    }

    /**
     * Persist the process projection used by the current synchronous wire
     * runtime as one encrypted Room transaction. Encryption and generation
     * allocation happen before Room's writer is acquired; the transaction
     * revalidates the active partition and then replaces only the outbox rows,
     * its counters, and newly issued EventId guards.
     *
     * This narrow bridge can disappear when the wire runtime consumes the
     * suspending SPI directly. It deliberately does not expose DAOs or
     * plaintext envelopes to the Android composition root.
     */
    suspend fun replaceOutboxState(
        pairingId: PairingId,
        events: List<DurableOutboxEvent>,
        nextQueueSequence: Long,
        effectiveClockHighWaterMs: Long,
        triggerRegistrations: List<DurableTriggerRegistration>? = null,
    ) {
        require(nextQueueSequence >= 1)
        requireEbpTimestamp(effectiveClockHighWaterMs, "effectiveClockHighWaterMs")
        require(events == events.sortedBy { it.queueSequence }) {
            "Outbox events must be supplied in queue order"
        }
        require(events.map { it.queueSequence }.toSet().size == events.size) {
            "Outbox queue sequences must be unique"
        }
        require(events.map { it.eventId }.toSet().size == events.size) {
            "Outbox EventIds must be unique"
        }
        require(events.none { it.queueSequence >= nextQueueSequence }) {
            "nextQueueSequence must be above every retained event"
        }
        triggerRegistrations?.let { triggers ->
            require(triggers.map { it.identity to it.triggerId }.toSet().size == triggers.size) {
                "Trigger identities must be unique within a replacement"
            }
            require(
                triggers.map { it.identity to it.authoredOrdinal }.toSet().size == triggers.size,
            ) { "Trigger authored ordinals must be unique within an identity" }
            require(triggers.all { it.authoredOrdinal >= 0 }) {
                "Trigger authored ordinals must be non-negative"
            }
        }

        val aliases = checkNotNull(aliasResolver.resolve(pairingId)) {
            "Pairing aliases must be provisioned before outbox persistence"
        }
        val sealed = events.map { event ->
            val generation = generationSource.next()
            require(STORAGE_GENERATION.matches(generation)) {
                "StorageGenerationSource returned an invalid generation"
            }
            val metadata = event.toEnvelopeMetadata(pairingId, generation)
            val envelope = envelopeCodec.encode(
                payload = event.payload,
                metadata = metadata,
                payloadKeyAlias = aliases.payloadKeyAlias,
            ).copyOf()
            require(envelope.isNotEmpty()) {
                "OutboxPayloadEnvelopeCodec returned an empty envelope"
            }
            SealedOutboxState(event, metadata, envelope)
        }

        database.withWriteTransaction {
            val pairingState = database.readPairingStateRows(pairingId)
            val pairing = pairingState.validatedPairing(pairingId)
            check(pairing?.fence == PairingFence.ACTIVE) {
                "Outbox persistence requires an active pairing"
            }
            val partition = checkNotNull(pairingState.partition)
            check(partition.credentialKeyAlias == aliases.credentialKeyAlias)
            check(partition.payloadKeyAlias == aliases.payloadKeyAlias)

            // A queued trigger occurrence and the runtime record which consumes
            // its throttle/one-shot eligibility are one crash-consistent fact.
            // Callers which are not admitting a trigger leave this projection
            // untouched by passing null.
            triggerRegistrations?.let { triggers ->
                database.triggerDao().deleteTriggers(pairingId.value, null)
                triggers.forEach { trigger ->
                    database.triggerDao().upsertRegistration(
                        trigger.toRegistrationEntity(pairingId, json),
                    )
                    database.triggerDao().upsertRuntime(trigger.toRuntimeEntity(pairingId))
                }
            }

            val previouslyQueuedIds = database.queueEventDao()
                .getEventIndex(pairingId.value)
                .mapTo(mutableSetOf()) { it.eventId }
            database.queueEventDao().deleteAllEvents(pairingId.value)
            sealed.forEach { prepared ->
                database.queueEventDao().insertEventRow(
                    prepared.event.toEntity(
                        pairingId,
                        prepared.metadata,
                        prepared.envelope.copyOf(),
                    ),
                )
                val prior = database.issuedEventIdDao().getIssuedEventId(
                    pairingId.value,
                    prepared.event.eventId.value,
                )
                if (prior == null) {
                    database.issuedEventIdDao().insertIssuedEventId(
                        IssuedEventId(
                            eventId = prepared.event.eventId,
                            issuedAtMs = prepared.event.occurredAtMs,
                            reusableAfterMs = prepared.event.occurredAtMs +
                                EVENT_ID_RETENTION_FLOOR_MS,
                        ).toEntity(pairingId),
                    )
                } else if (prepared.event.eventId.value !in previouslyQueuedIds) {
                    check(effectiveClockHighWaterMs >= prior.reusableAfterEpochMs) {
                        "EventId was reused before its retention floor"
                    }
                    database.issuedEventIdDao().deleteIssuedEventId(
                        pairingId.value,
                        prepared.event.eventId.value,
                    )
                    database.issuedEventIdDao().insertIssuedEventId(
                        IssuedEventId(
                            eventId = prepared.event.eventId,
                            issuedAtMs = prepared.event.occurredAtMs,
                            reusableAfterMs = prepared.event.occurredAtMs +
                                EVENT_ID_RETENTION_FLOOR_MS,
                        ).toEntity(pairingId),
                    )
                }
            }
            val runtime = checkNotNull(pairingState.runtime).toPairingRuntime()
            check(
                database.pairingDao().updateRuntime(
                    runtime.copy(
                        nextQueueSequence = nextQueueSequence,
                        effectiveClockHighWaterMs = maxOf(
                            runtime.effectiveClockHighWaterMs,
                            effectiveClockHighWaterMs,
                        ),
                    ).toEntity(pairingId),
                ) == 1,
            ) { "Pairing runtime changed during outbox persistence" }
        }
    }

    private suspend fun prepareOutbox(command: AdmitOutboxCommand): RoomOutboxPreparation {
        val raw = database.withReadTransaction {
            PreparedRead(
                pairingState = database.readPairingStateRows(command.pairingId),
                event = database.queueEventDao().getEventById(
                    command.pairingId.value,
                    command.eventId.value,
                )?.let { it.copy(payloadEnvelope = it.payloadEnvelope.copyOf()) },
            )
        }
        val pairing = raw.pairingState.validatedPairing(command.pairingId)
        if (pairing?.fence != PairingFence.ACTIVE) {
            return RoomOutboxPreparation.inactive(
                command.pairingId,
                command.eventId,
                raw.pairingState.toObservation(),
            )
        }
        val partition = checkNotNull(raw.pairingState.partition)
        val runtime = checkNotNull(raw.pairingState.runtime)
        val aliases = partition.toAliases()
        val existing = raw.event
        if (existing != null) {
            val metadata = existing.toEnvelopeMetadata(command.pairingId)
            val decoded = envelopeCodec.decode(
                envelope = existing.payloadEnvelope.copyOf(),
                metadata = metadata,
                payloadKeyAlias = aliases.payloadKeyAlias,
            )
            return RoomOutboxPreparation(
                pairingId = command.pairingId,
                eventId = command.eventId,
                observedPairing = raw.pairingState.toObservation(),
                observedVersion = existing.toVersion(),
                decodedExisting = existing.toDurableOutboxEvent(decoded),
                expectedNewEvent = null,
                metadata = null,
                sealedEnvelope = null,
            )
        }

        val generation = generationSource.next()
        require(STORAGE_GENERATION.matches(generation)) {
            "StorageGenerationSource returned an invalid generation"
        }
        val expected = command.toExpectedEvent(runtime.nextQueueSequence)
        val metadata = expected.toEnvelopeMetadata(command.pairingId, generation)
        val sealed = envelopeCodec.encode(
            payload = command.payload,
            metadata = metadata,
            payloadKeyAlias = aliases.payloadKeyAlias,
        ).copyOf()
        require(sealed.isNotEmpty()) { "OutboxPayloadEnvelopeCodec returned an empty envelope" }
        return RoomOutboxPreparation(
            pairingId = command.pairingId,
            eventId = command.eventId,
            observedPairing = raw.pairingState.toObservation(),
            observedVersion = null,
            decodedExisting = null,
            expectedNewEvent = expected,
            metadata = metadata,
            sealedEnvelope = sealed,
        )
    }
}

private data class SealedOutboxState(
    val event: DurableOutboxEvent,
    val metadata: OutboxEnvelopeMetadata,
    val envelope: ByteArray,
)

private data class PairingStateRows(
    val partition: PairingPartitionEntity?,
    val revocation: PairingRevocationEntity?,
    val runtime: PairingRuntimeEntity?,
) {
    fun validatedPairing(expectedId: PairingId): DurablePairing? {
        check(partition?.pairingId == null || partition.pairingId == expectedId.value)
        check(revocation?.pairingId == null || revocation.pairingId == expectedId.value)
        check(runtime?.pairingId == null || runtime.pairingId == expectedId.value)
        if (partition == null) {
            check(runtime == null) { "Runtime cannot exist without a pairing partition" }
            if (revocation == null) return null
            check(revocation.finalizedAtEpochMs != null) {
                "An open revocation journal requires a REVOKING partition"
            }
            check(revocation.fencedAtEpochMs >= revocation.pairingCreatedAtEpochMs) {
                "Revocation fence cannot precede pairing creation"
            }
            return revocation.toSyntheticRevokingPairing()
        }

        checkNotNull(runtime) { "A live pairing partition requires runtime state" }
        check(partition.credentialKeyAlias != partition.payloadKeyAlias) {
            "Credential and payload key aliases must remain distinct"
        }
        if (revocation != null) {
            check(revocation.pairingCreatedAtEpochMs == partition.createdAtEpochMs) {
                "Pairing partition and revocation journal creation times differ"
            }
            check(revocation.fencedAtEpochMs >= partition.createdAtEpochMs) {
                "Revocation fence cannot precede pairing creation"
            }
        }
        when (partition.state) {
            PairingPartitionEntity.ACTIVE -> check(revocation == null) {
                "An ACTIVE pairing cannot have a revocation journal"
            }
            PairingPartitionEntity.REVOKING -> check(
                revocation != null && revocation.finalizedAtEpochMs == null,
            ) { "A REVOKING partition requires an open revocation journal" }
            else -> error("Unknown pairing state ${partition.state}")
        }
        return partition.toDurablePairing()
    }

    fun toObservation() = PairingStateObservation(
        partition = partition?.toObservation(),
        revocation = revocation,
        hasRuntime = runtime != null,
    )
}

private suspend fun JetpacsDatabase.readPairingStateRows(pairingId: PairingId) =
    PairingStateRows(
        partition = pairingDao().getPartition(pairingId.value),
        revocation = revocationDao().getRevocation(pairingId.value),
        runtime = pairingDao().getRuntime(pairingId.value),
    )

private data class RawRestore(
    val pairingState: PairingStateRows,
    val surfaces: List<SurfaceRecordEntity>,
    val drafts: List<SurfaceDraftEntity>,
    val outbox: List<QueueEventEntity>,
    val issuedEventIds: List<IssuedEventIdEntity>,
    val reminders: List<ReminderEntity>,
    val reminderReceipts: List<ReminderReceiptEntity>,
    val triggers: List<TriggerWithRuntime>,
    val theme: PairingThemeEntity?,
    val platformEffects: List<PlatformEffectEntity>,
) {
    fun requireConsistentOwnedState() {
        if (pairingState.partition?.state == PairingPartitionEntity.REVOKING) {
            check(
                surfaces.isEmpty() &&
                    drafts.isEmpty() &&
                    outbox.isEmpty() &&
                    issuedEventIds.isEmpty() &&
                    reminders.isEmpty() &&
                    reminderReceipts.isEmpty() &&
                    triggers.isEmpty() &&
                    theme == null &&
                    platformEffects.isEmpty() &&
                    pairingState.runtime?.toPairingRuntime() == PairingRuntime(),
            ) { "A REVOKING partition must contain only reset runtime state" }
            return
        }
        if (pairingState.partition != null) return
        check(
            pairingState.runtime == null &&
                surfaces.isEmpty() &&
                drafts.isEmpty() &&
                outbox.isEmpty() &&
                issuedEventIds.isEmpty() &&
                reminders.isEmpty() &&
                reminderReceipts.isEmpty() &&
                triggers.isEmpty() &&
                theme == null &&
                platformEffects.isEmpty(),
        ) { "Pairing-owned rows cannot survive without a pairing partition" }
    }
}

private data class PreparedRead(
    val pairingState: PairingStateRows,
    val event: QueueEventEntity?,
)

private data class PairingPartitionObservation(
    val pairingId: String,
    val credentialKeyAlias: String,
    val payloadKeyAlias: String,
    val state: String,
    val createdAtMs: Long,
)

private data class PairingStateObservation(
    val partition: PairingPartitionObservation?,
    val revocation: PairingRevocationEntity?,
    val hasRuntime: Boolean,
)

private data class RoomOutboxPreparation(
    override val pairingId: PairingId,
    override val eventId: EventId,
    val observedPairing: PairingStateObservation,
    val observedVersion: QueueEventVersionRow?,
    val decodedExisting: DurableOutboxEvent?,
    val expectedNewEvent: DurableOutboxEvent?,
    val metadata: OutboxEnvelopeMetadata?,
    val sealedEnvelope: ByteArray?,
) : PreparedOutboxAdmission {
    companion object {
        fun inactive(
            pairingId: PairingId,
            eventId: EventId,
            observation: PairingStateObservation,
        ) = RoomOutboxPreparation(
            pairingId = pairingId,
            eventId = eventId,
            observedPairing = observation,
            observedVersion = null,
            decodedExisting = null,
            expectedNewEvent = null,
            metadata = null,
            sealedEnvelope = null,
        )
    }
}

private class StaleOutboxPreparation : IllegalStateException()

private class RoomWriteScope(
    private val database: JetpacsDatabase,
    private val pairingId: PairingId,
    private val json: Json,
    private val provisionedAliases: ProvisionedPairingAliases?,
    private val preparation: RoomOutboxPreparation?,
) : EbpWriteTransaction {
    private var open = true
    private var preparationResolved = false
    private var preparationResolvedWasAbsent = false

    fun close() {
        open = false
    }

    suspend fun assertPreparationCurrent(): QueueEventVersionRow? {
        checkOpen()
        val expected = preparation ?: return null
        val currentPairing = database.readPairingStateRows(pairingId)
        currentPairing.validatedPairing(pairingId)
        if (currentPairing.toObservation() != expected.observedPairing) stale()
        if (expected.expectedNewEvent != null &&
            currentPairing.runtime?.nextQueueSequence != expected.expectedNewEvent.queueSequence
        ) stale()
        if (expected.decodedExisting == null && expected.expectedNewEvent == null) return null
        val currentVersion = database.queueEventDao().getEventVersionById(
            pairingId.value,
            expected.eventId.value,
        )
        if (!currentVersion.sameImmutableVersion(expected.observedVersion)) stale()
        return currentVersion
    }

    override suspend fun pairing(): DurablePairing? {
        checkOpen()
        return database.readPairingStateRows(pairingId).validatedPairing(pairingId)
    }

    override suspend fun putPairing(pairing: DurablePairing) {
        checkOpen()
        require(pairing.id == pairingId) { "Cross-pairing write" }
        val state = database.readPairingStateRows(pairingId)
        val current = state.validatedPairing(pairingId)
        if (current != null) {
            check(current == pairing) {
                "Generic pairing writes cannot change identity, creation time, or fence"
            }
            return
        }
        check(pairing.fence == PairingFence.ACTIVE) {
            "A new pairing must begin ACTIVE"
        }
        val aliases = checkNotNull(provisionedAliases) {
            "Pairing aliases must be provisioned by the host before activation"
        }
        database.pairingDao().insertPairing(
            partition = PairingPartitionEntity(
                pairingId = pairingId.value,
                credentialKeyAlias = aliases.credentialKeyAlias,
                payloadKeyAlias = aliases.payloadKeyAlias,
                state = PairingPartitionEntity.ACTIVE,
                createdAtEpochMs = pairing.createdAtMs,
                lastAuthenticatedAtEpochMs = null,
            ),
            runtime = PairingRuntime().toEntity(pairingId),
        )
    }

    override suspend fun runtime(): PairingRuntime {
        checkOpen()
        val state = database.readPairingStateRows(pairingId)
        val current = state.validatedPairing(pairingId) ?: return PairingRuntime()
        return if (current.fence == PairingFence.ACTIVE || state.partition != null) {
            checkNotNull(state.runtime).toPairingRuntime()
        } else {
            PairingRuntime()
        }
    }

    override suspend fun putRuntime(runtime: PairingRuntime) {
        checkOpen()
        requireActivePairing()
        val entity = runtime.toEntity(pairingId)
        check(database.pairingDao().updateRuntime(entity) == 1) {
            "Pairing runtime changed concurrently"
        }
    }

    override suspend fun fenceAndErasePairingState(fencedAtMs: Long): DurablePairing? {
        checkOpen()
        requireEbpTimestamp(fencedAtMs, "fencedAtMs")
        val current = pairing() ?: return null
        require(fencedAtMs >= current.createdAtMs) {
            "Revocation fence cannot precede pairing creation"
        }
        check(database.revocationDao().fenceJournalAndEraseState(pairingId.value, fencedAtMs)) {
            "Pairing disappeared during revocation"
        }
        return current.copy(fence = PairingFence.REVOKING)
    }

    override suspend fun surface(surfaceId: String): DurableSurfaceRecord? {
        checkOpen()
        return database.surfaceDao().getRecord(pairingId.value, surfaceId)
            ?.toDurableSurface(json)
    }

    override suspend fun surfaceCardinality(): SurfaceCardinality {
        checkOpen()
        val row = database.surfaceDao().getCardinality(pairingId.value)
        return SurfaceCardinality(row.total, row.present)
    }

    override suspend fun putSurface(record: DurableSurfaceRecord) {
        checkOpen()
        requireActivePairing()
        val entity = record.toEntity(pairingId, json)
        val existing = database.surfaceDao().getRecord(pairingId.value, record.surfaceId)
        if (existing == null) {
            database.surfaceDao().insertRecordRow(entity)
        } else {
            require(existing.firstSeenOrdinal == record.firstSeenOrdinal) {
                "A surface first-seen ordinal is immutable"
            }
            check(database.surfaceDao().updateRecordRow(entity) == 1) {
                "Surface changed concurrently"
            }
        }
    }

    override suspend fun deleteSurface(surfaceId: String) {
        checkOpen()
        database.surfaceDao().deleteRecord(pairingId.value, surfaceId)
    }

    override suspend fun deleteAllSurfaces() {
        checkOpen()
        database.surfaceDao().deleteAllRecords(pairingId.value)
    }

    override suspend fun drafts(surfaceId: String): List<DurableDraft> {
        checkOpen()
        return database.surfaceDao().getDrafts(pairingId.value, surfaceId)
            .map { it.toDurableDraft(json) }
    }

    override suspend fun putDraft(draft: DurableDraft) {
        checkOpen()
        requireActivePairing()
        database.surfaceDao().upsertDraft(draft.toEntity(pairingId, json))
    }

    override suspend fun deleteDraft(surfaceId: String, nodeId: String) {
        checkOpen()
        database.surfaceDao().deleteDraft(pairingId.value, surfaceId, nodeId)
    }

    override suspend fun deleteDrafts(surfaceId: String) {
        checkOpen()
        database.surfaceDao().deleteDrafts(pairingId.value, surfaceId)
    }

    override suspend fun outboxIndex(): List<DurableOutboxIndexEntry> {
        checkOpen()
        return database.queueEventDao().getEventIndex(pairingId.value).map {
            DurableOutboxIndexEntry(
                queueSequence = it.queueSequence,
                eventId = EventId(it.eventId),
                expiresAtMs = it.expiresAtEpochMs,
                dedupeKey = it.dedupeKey,
                accountedBytes = it.accountedByteCount,
            )
        }
    }

    override suspend fun resolvePreparedOutbox(
        preparation: PreparedOutboxAdmission,
    ): DurableOutboxEvent? {
        checkOpen()
        val expected = requirePreparation(preparation)
        check(!preparationResolved) { "Prepared outbox capability was already resolved" }
        val currentVersion = assertPreparationCurrent()
        preparationResolved = true
        preparationResolvedWasAbsent = currentVersion == null
        return expected.decodedExisting?.copy(
            pendingLocal = checkNotNull(currentVersion).pendingLocal,
        )
    }

    override suspend fun putPreparedOutbox(
        preparation: PreparedOutboxAdmission,
        event: DurableOutboxEvent,
    ) {
        checkOpen()
        val expected = requirePreparation(preparation)
        requireActivePairing()
        assertPreparationCurrent()
        check(preparationResolved && preparationResolvedWasAbsent) {
            "Prepared outbox must resolve stable absence before insertion"
        }
        val expectedEvent = checkNotNull(expected.expectedNewEvent)
        if (runtime().nextQueueSequence != expectedEvent.queueSequence) stale()
        check(expected.decodedExisting == null && event == expectedEvent) {
            "Prepared outbox capability does not match the admitted occurrence"
        }
        val metadata = checkNotNull(expected.metadata)
        val envelope = checkNotNull(expected.sealedEnvelope).copyOf()
        database.queueEventDao().insertEventRow(event.toEntity(pairingId, metadata, envelope))
    }

    override suspend fun clearOutboxPendingLocal(queueSequence: Long): Boolean {
        checkOpen()
        requireActivePairing()
        return database.queueEventDao().clearPendingLocal(pairingId.value, queueSequence) == 1
    }

    override suspend fun deleteOutbox(queueSequence: Long) {
        checkOpen()
        database.queueEventDao().deleteEvent(pairingId.value, queueSequence)
    }

    override suspend fun deleteAllOutbox() {
        checkOpen()
        database.queueEventDao().deleteAllEvents(pairingId.value)
    }

    override suspend fun issuedEventId(eventId: EventId): IssuedEventId? {
        checkOpen()
        return database.issuedEventIdDao().getIssuedEventId(pairingId.value, eventId.value)
            ?.toIssuedEventId()
    }

    override suspend fun putIssuedEventId(record: IssuedEventId) {
        checkOpen()
        requireActivePairing()
        database.issuedEventIdDao().insertIssuedEventId(record.toEntity(pairingId))
    }

    override suspend fun deleteIssuedEventId(eventId: EventId) {
        checkOpen()
        database.issuedEventIdDao().deleteIssuedEventId(pairingId.value, eventId.value)
    }

    override suspend fun issuedEventIdsReusableAtOrBefore(
        effectiveNowMs: Long,
        limit: Int,
    ): List<IssuedEventId> {
        checkOpen()
        return database.issuedEventIdDao().getReusableAtOrBefore(
            pairingId.value,
            effectiveNowMs,
            limit,
        ).map { it.toIssuedEventId() }
    }

    override suspend fun deleteAllIssuedEventIds() {
        checkOpen()
        database.issuedEventIdDao().deleteAllIssuedEventIds(pairingId.value)
    }

    override suspend fun reminders(owner: String?): List<DurableReminder> {
        checkOpen()
        val rows = if (owner == null) database.reminderDao().getReminders(pairingId.value)
        else database.reminderDao().getReminders(pairingId.value, owner)
        return rows.map { it.toDurableReminder(json) }
    }

    override suspend fun putReminder(reminder: DurableReminder) {
        checkOpen(); requireActivePairing()
        database.reminderDao().upsertReminder(reminder.toEntity(pairingId, json))
    }

    override suspend fun deleteReminder(owner: String, reminderId: String) {
        checkOpen(); requireActivePairing()
        database.reminderDao().deleteReminder(pairingId.value, owner, reminderId)
    }

    override suspend fun deleteReminders(owner: String?) {
        checkOpen(); requireActivePairing()
        database.reminderDao().deleteReminders(pairingId.value, owner)
    }

    override suspend fun reminderReceipts(owner: String?): List<DurableReminderReceipt> {
        checkOpen()
        return database.reminderDao().getReceipts(pairingId.value, owner)
            .map { it.toDurableReminderReceipt() }
    }

    override suspend fun putReminderReceipt(receipt: DurableReminderReceipt) {
        checkOpen(); requireActivePairing()
        val current = database.reminderDao().getReminders(pairingId.value, receipt.owner)
            .firstOrNull { it.reminderId == receipt.reminderId }
        require(current?.atEpochMs == receipt.atMs) {
            "Reminder receipt requires its current reminder tuple"
        }
        database.reminderDao().insertReceipt(receipt.toEntity(pairingId))
    }

    override suspend fun deleteReminderReceipt(owner: String, reminderId: String, atMs: Long) {
        checkOpen(); requireActivePairing()
        database.reminderDao().deleteReceipt(pairingId.value, owner, reminderId, atMs)
    }

    override suspend fun deleteReminderReceipts(owner: String?) {
        checkOpen(); requireActivePairing()
        database.reminderDao().deleteReceipts(pairingId.value, owner)
    }

    override suspend fun triggers(identity: String?): List<DurableTriggerRegistration> {
        checkOpen()
        return database.triggerDao().getTriggers(pairingId.value, identity)
            .map { it.toDurableTrigger(json) }
    }

    override suspend fun putTrigger(trigger: DurableTriggerRegistration) {
        checkOpen(); requireActivePairing()
        database.triggerDao().upsertRegistration(trigger.toRegistrationEntity(pairingId, json))
        database.triggerDao().upsertRuntime(trigger.toRuntimeEntity(pairingId))
    }

    override suspend fun deleteTrigger(identity: String, triggerId: String) {
        checkOpen(); requireActivePairing()
        database.triggerDao().deleteTrigger(pairingId.value, identity, triggerId)
    }

    override suspend fun deleteTriggers(identity: String?) {
        checkOpen(); requireActivePairing()
        database.triggerDao().deleteTriggers(pairingId.value, identity)
    }

    override suspend fun theme(): DurableTheme? {
        checkOpen()
        return database.themeAndEffectDao().getTheme(pairingId.value)?.toDurableTheme(json)
    }

    override suspend fun putTheme(theme: DurableTheme) {
        checkOpen(); requireActivePairing()
        database.themeAndEffectDao().upsertTheme(theme.toEntity(pairingId, json))
    }

    override suspend fun deleteTheme() {
        checkOpen(); requireActivePairing()
        database.themeAndEffectDao().deleteTheme(pairingId.value)
    }

    override suspend fun platformEffects(
        states: Set<DurablePlatformEffectState>,
        limit: Int,
    ): List<DurablePlatformEffect> {
        checkOpen(); require(limit > 0)
        if (states.isEmpty()) return emptyList()
        return database.themeAndEffectDao().getEffects(
            pairingId.value,
            states.map { it.name },
            limit,
        ).map { it.toDurablePlatformEffect(json) }
    }

    override suspend fun platformEffect(effectId: String): DurablePlatformEffect? {
        checkOpen()
        return database.themeAndEffectDao().getEffect(pairingId.value, effectId)
            ?.toDurablePlatformEffect(json)
    }

    override suspend fun putPlatformEffect(effect: DurablePlatformEffect) {
        checkOpen(); requireActivePairing()
        val duplicate = database.themeAndEffectDao().getEffectByDedupeKey(
            pairingId.value,
            effect.dedupeKey,
        )
        require(duplicate == null || duplicate.effectId == effect.effectId) {
            "platform effect dedupe key already exists"
        }
        database.themeAndEffectDao().upsertEffect(effect.toEntity(pairingId, json))
    }

    override suspend fun deletePlatformEffect(effectId: String) {
        checkOpen(); requireActivePairing()
        database.themeAndEffectDao().deleteEffect(pairingId.value, effectId)
    }

    override suspend fun deleteAllPlatformEffects() {
        checkOpen(); requireActivePairing()
        database.themeAndEffectDao().deleteEffects(pairingId.value)
    }

    private fun requirePreparation(value: PreparedOutboxAdmission): RoomOutboxPreparation {
        check(value === preparation) { "Prepared capability belongs to another write" }
        return checkNotNull(preparation)
    }

    private suspend fun requireActivePairing(): DurablePairing {
        val current = database.readPairingStateRows(pairingId).validatedPairing(pairingId)
        check(current?.fence == PairingFence.ACTIVE) {
            "Pairing-owned state can only be written for an ACTIVE pairing"
        }
        return current
    }

    private fun checkOpen() {
        check(open) { "Durable transaction scope is closed" }
    }

    private fun stale(): Nothing = throw StaleOutboxPreparation()
}

private fun PairingPartitionEntity.toAliases() = ProvisionedPairingAliases(
    credentialKeyAlias = credentialKeyAlias,
    payloadKeyAlias = payloadKeyAlias,
)

private fun PairingPartitionEntity.toObservation() = PairingPartitionObservation(
    pairingId = pairingId,
    credentialKeyAlias = credentialKeyAlias,
    payloadKeyAlias = payloadKeyAlias,
    state = state,
    createdAtMs = createdAtEpochMs,
)

private fun PairingPartitionEntity.toDurablePairing() = DurablePairing(
    id = PairingId(pairingId),
    fence = when (state) {
        PairingPartitionEntity.ACTIVE -> PairingFence.ACTIVE
        PairingPartitionEntity.REVOKING -> PairingFence.REVOKING
        else -> error("Unknown pairing state $state")
    },
    createdAtMs = createdAtEpochMs,
)

private fun PairingRevocationEntity.toSyntheticRevokingPairing() = DurablePairing(
    id = PairingId(pairingId),
    fence = PairingFence.REVOKING,
    createdAtMs = pairingCreatedAtEpochMs,
)

private fun PairingRuntimeEntity.toPairingRuntime() = PairingRuntime(
    nextQueueSequence = nextQueueSequence,
    nextSurfaceOrdinal = nextSurfaceOrdinal,
    effectiveClockHighWaterMs = clockHighWaterEpochMs,
    clockForwardClaimWallMs = forwardStepClaimEpochMs,
    clockForwardClaimMonotonicStartedMs = forwardStepClaimedAtElapsedMs,
    clockForwardClaimBootId = clockForwardClaimBootId,
    readyDisconnectedAtMs = readyDisconnectedAtEpochMs,
)

private fun PairingRuntime.toEntity(pairingId: PairingId) = PairingRuntimeEntity(
    pairingId = pairingId.value,
    nextQueueSequence = nextQueueSequence,
    nextSurfaceOrdinal = nextSurfaceOrdinal,
    clockHighWaterEpochMs = effectiveClockHighWaterMs,
    forwardStepClaimEpochMs = clockForwardClaimWallMs,
    clockForwardClaimBootId = clockForwardClaimBootId,
    forwardStepClaimedAtElapsedMs = clockForwardClaimMonotonicStartedMs,
    readyDisconnectedAtEpochMs = readyDisconnectedAtMs,
)

private fun SurfaceRecordEntity.toDurableSurface(json: Json) = DurableSurfaceRecord(
    surfaceId = surfaceId,
    revision = revision,
    present = present,
    spec = specJson?.let { json.decodeObject(it) },
    staleSpec = staleSpecJson?.let { json.decodeObject(it) },
    staleAfterSeconds = staleAfterSeconds,
    currentView = currentView,
    acceptedAtMs = acceptedAtEpochMs,
    firstSeenOrdinal = firstSeenOrdinal,
)

private fun DurableSurfaceRecord.toEntity(pairingId: PairingId, json: Json) =
    SurfaceRecordEntity(
        pairingId = pairingId.value,
        surfaceId = surfaceId,
        revision = revision,
        present = present,
        specJson = spec?.let { json.encodeObject(it) },
        staleSpecJson = staleSpec?.let { json.encodeObject(it) },
        staleAfterSeconds = staleAfterSeconds,
        currentView = currentView,
        acceptedAtEpochMs = acceptedAtMs,
        firstSeenOrdinal = firstSeenOrdinal,
    )

private fun SurfaceDraftEntity.toDurableDraft(json: Json) = DurableDraft(
    surfaceId = surfaceId,
    nodeId = nodeId,
    value = json.parseToJsonElement(valueJson),
)

private fun DurableDraft.toEntity(pairingId: PairingId, json: Json) = SurfaceDraftEntity(
    pairingId = pairingId.value,
    surfaceId = surfaceId,
    nodeId = nodeId,
    valueJson = json.encodeElement(value),
)

private fun QueueEventVersionRow?.sameImmutableVersion(
    other: QueueEventVersionRow?,
): Boolean = when {
    this == null || other == null -> this == null && other == null
    else -> queueSequence == other.queueSequence &&
        eventId == other.eventId &&
        storageGeneration == other.storageGeneration
}

private fun QueueEventEntity.toVersion() = QueueEventVersionRow(
    queueSequence = queueSequence,
    eventId = eventId,
    storageGeneration = storageGeneration,
    pendingLocal = pendingLocal,
)

private fun QueueEventEntity.toEnvelopeMetadata(pairingId: PairingId) = OutboxEnvelopeMetadata(
    pairingId = pairingId,
    eventId = EventId(eventId),
    queueSequence = queueSequence,
    storageGeneration = storageGeneration,
    policy = policy.toDurablePolicy(),
    occurredAtMs = occurredAtEpochMs,
    queuedAtMs = queuedAtEpochMs,
    expiresAtMs = expiresAtEpochMs,
    dedupeKey = dedupeKey,
    accountedBytes = accountedByteCount,
    triggerIdentity = triggerIdentity,
)

private fun QueueEventEntity.toDurableOutboxEvent(payload: JsonObject) = DurableOutboxEvent(
    queueSequence = queueSequence,
    eventId = EventId(eventId),
    payload = payload,
    policy = policy.toDurablePolicy(),
    occurredAtMs = occurredAtEpochMs,
    queuedAtMs = queuedAtEpochMs,
    expiresAtMs = expiresAtEpochMs,
    dedupeKey = dedupeKey,
    accountedBytes = accountedByteCount,
    pendingLocal = pendingLocal,
    triggerIdentity = triggerIdentity,
)

private fun DurableOutboxEvent.toEnvelopeMetadata(
    pairingId: PairingId,
    generation: String,
) = OutboxEnvelopeMetadata(
    pairingId = pairingId,
    eventId = eventId,
    queueSequence = queueSequence,
    storageGeneration = generation,
    policy = policy,
    occurredAtMs = occurredAtMs,
    queuedAtMs = queuedAtMs,
    expiresAtMs = expiresAtMs,
    dedupeKey = dedupeKey,
    accountedBytes = accountedBytes,
    triggerIdentity = triggerIdentity,
)

private fun DurableOutboxEvent.toEntity(
    pairingId: PairingId,
    metadata: OutboxEnvelopeMetadata,
    envelope: ByteArray,
) = QueueEventEntity(
    pairingId = pairingId.value,
    queueSequence = queueSequence,
    eventId = eventId.value,
    storageGeneration = metadata.storageGeneration,
    payloadEnvelope = envelope,
    accountedByteCount = accountedBytes,
    policy = policy.toEntityPolicy(),
    occurredAtEpochMs = occurredAtMs,
    queuedAtEpochMs = queuedAtMs,
    expiresAtEpochMs = expiresAtMs,
    dedupeKey = dedupeKey,
    pendingLocal = pendingLocal,
    triggerIdentity = triggerIdentity,
)

private fun AdmitOutboxCommand.toExpectedEvent(queueSequence: Long) = DurableOutboxEvent(
    queueSequence = queueSequence,
    eventId = eventId,
    payload = payload,
    policy = policy,
    occurredAtMs = occurredAtMs,
    queuedAtMs = queuedAtMs,
    expiresAtMs = expiresAtMs,
    dedupeKey = dedupeKey,
    accountedBytes = accountedBytes,
    pendingLocal = pendingLocal,
    triggerIdentity = triggerIdentity,
)

private fun IssuedEventIdEntity.toIssuedEventId() = IssuedEventId(
    eventId = EventId(eventId),
    issuedAtMs = issuedAtEpochMs,
    reusableAfterMs = reusableAfterEpochMs,
)

private fun IssuedEventId.toEntity(pairingId: PairingId) = IssuedEventIdEntity(
    pairingId = pairingId.value,
    eventId = eventId.value,
    issuedAtEpochMs = issuedAtMs,
    reusableAfterEpochMs = reusableAfterMs,
)

private fun ReminderEntity.toDurableReminder(json: Json) = DurableReminder(
    owner = owner,
    reminderId = reminderId,
    atMs = atEpochMs,
    authoredOrdinal = authoredOrdinal,
    payload = json.decodeObject(payloadJson),
)

private fun DurableReminder.toEntity(pairingId: PairingId, json: Json) = ReminderEntity(
    pairingId = pairingId.value,
    owner = owner,
    reminderId = reminderId,
    atEpochMs = atMs,
    authoredOrdinal = authoredOrdinal,
    payloadJson = json.encodeObject(payload),
)

private fun ReminderReceiptEntity.toDurableReminderReceipt() = DurableReminderReceipt(
    owner = owner,
    reminderId = reminderId,
    atMs = atEpochMs,
    firedAtMs = firedAtEpochMs,
)

private fun DurableReminderReceipt.toEntity(pairingId: PairingId) = ReminderReceiptEntity(
    pairingId = pairingId.value,
    owner = owner,
    reminderId = reminderId,
    atEpochMs = atMs,
    firedAtEpochMs = firedAtMs,
)

private fun TriggerWithRuntime.toDurableTrigger(json: Json) = DurableTriggerRegistration(
    identity = identity,
    triggerId = triggerId,
    authoredOrdinal = authoredOrdinal,
    entry = json.decodeObject(entryJson),
    canonicalIdentity = canonicalIdentity,
    throttleFloorMs = throttleFloorEpochMs,
    oneShotCompleted = oneShotCompleted,
    scheduleAnchorMs = scheduleAnchorEpochMs,
    lastFireFloorMs = lastFireFloorEpochMs,
    bootGeneration = bootGeneration,
)

private fun DurableTriggerRegistration.toRegistrationEntity(pairingId: PairingId, json: Json) =
    TriggerRegistrationEntity(
        pairingId = pairingId.value,
        identity = identity,
        triggerId = triggerId,
        authoredOrdinal = authoredOrdinal,
        entryJson = json.encodeObject(entry),
        canonicalIdentity = canonicalIdentity,
    )

private fun DurableTriggerRegistration.toRuntimeEntity(pairingId: PairingId) =
    TriggerRuntimeEntity(
        pairingId = pairingId.value,
        identity = identity,
        triggerId = triggerId,
        throttleFloorEpochMs = throttleFloorMs,
        oneShotCompleted = oneShotCompleted,
        scheduleAnchorEpochMs = scheduleAnchorMs,
        lastFireFloorEpochMs = lastFireFloorMs,
        bootGeneration = bootGeneration,
    )

private fun PairingThemeEntity.toDurableTheme(json: Json) = DurableTheme(
    payload = json.decodeObject(payloadJson),
    acceptedAtMs = acceptedAtEpochMs,
)

private fun DurableTheme.toEntity(pairingId: PairingId, json: Json) = PairingThemeEntity(
    pairingId = pairingId.value,
    payloadJson = json.encodeObject(payload),
    acceptedAtEpochMs = acceptedAtMs,
)

private fun PlatformEffectEntity.toDurablePlatformEffect(json: Json) = DurablePlatformEffect(
    effectId = effectId,
    kind = kind,
    payload = json.decodeObject(payloadJson),
    dedupeKey = dedupeKey,
    state = DurablePlatformEffectState.valueOf(state),
    createdAtMs = createdAtEpochMs,
    attemptCount = attemptCount,
    claimedAtMs = claimedAtEpochMs,
    completedAtMs = completedAtEpochMs,
)

private fun DurablePlatformEffect.toEntity(pairingId: PairingId, json: Json) =
    PlatformEffectEntity(
        effectId = effectId,
        pairingId = pairingId.value,
        kind = kind,
        payloadJson = json.encodeObject(payload),
        dedupeKey = dedupeKey,
        state = state.name,
        createdAtEpochMs = createdAtMs,
        attemptCount = attemptCount,
        claimedAtEpochMs = claimedAtMs,
        completedAtEpochMs = completedAtMs,
    )

private fun String.toDurablePolicy() = when (this) {
    QueueEventEntity.POLICY_QUEUE -> DurableOutboxPolicy.QUEUE
    QueueEventEntity.POLICY_WAKE -> DurableOutboxPolicy.WAKE
    else -> error("Unknown outbox policy $this")
}

private fun DurableOutboxPolicy.toEntityPolicy() = when (this) {
    DurableOutboxPolicy.QUEUE -> QueueEventEntity.POLICY_QUEUE
    DurableOutboxPolicy.WAKE -> QueueEventEntity.POLICY_WAKE
}

private fun Json.decodeObject(value: String): JsonObject =
    parseToJsonElement(value) as? JsonObject
        ?: error("Stored surface payload is not a JSON object")

private fun Json.encodeObject(value: JsonObject): String =
    encodeToString(JsonObject.serializer(), value)

private fun Json.encodeElement(value: JsonElement): String =
    encodeToString(JsonElement.serializer(), value)
