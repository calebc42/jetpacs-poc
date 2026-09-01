# POC 3 Room 3 rebuild plan

Status: accepted Android persistence plan; Room-only composition/API 34 cutover
implemented 2026-08-31. The native-suspending wire migration remains a later
refinement described below.

## Implemented production cutover (2026-08-31)

- Android 14/API 34 is the minimum across the app, KMP store/wire artifact,
  Foundation renderer, Glasspane Material renderer, and Jetpacs renderer.
- `JetpacsApplication` constructs one new `jetpacs.db`, one
  `RoomEbpDurableStore`, one bounded `EbpCommandActor`, and one bridge. The
  production graph has no `File*Backing` or JSON-store caller.
- The POC credential is imported transiently into an operation-only
  AndroidKeyStore HMAC handle; Room retains only purpose-separated aliases.
- Surfaces/drafts, queue/EventId guards, reminders/receipts, triggers/runtime,
  theme, platform effects, revocation state, and the user-enabled bridge policy
  are in schema v1. Queue payloads are AES-GCM sealed with a distinct Keystore
  key.
- A queue/wake trigger occurrence commits its queue projection and consumed
  trigger runtime together. A reminder fire commits its receipt and idempotent
  notification work together; cold-start reconciliation reclaims incomplete
  notification work.
- A user-enabled `specialUse` foreground service owns the loopback listener as
  a best-effort availability hint. Its notification explicitly does not claim
  that the process or Emacs connection is alive.
- Exact alarms use `SCHEDULE_EXACT_ALARM` when granted and
  `setAndAllowWhileIdle` otherwise. API 34 has a dedicated managed-device CI
  smoke gate for Room, Keystore, and the private FGS declaration.
- Old JSON stores and the disposable prototype database are neither read nor
  deleted. Emacs re-pushes authoritative state into the clean database.

The current `RoomStoreBackings` file is a deliberately narrow compatibility
adapter for `:wire`'s synchronous whole-snapshot APIs. It runs only behind the
single actor and all projections are initialized on the composition root's I/O
dispatcher, but it still blocks while awaiting Room and therefore is not the
final native-suspending shape described under the coroutine model.

## Grove incorporation decision

Grove remains an Elisp-applet/UI reference, not a source of Jetpacs backend
architecture. Its JSON repositories, search model, application navigation, and
domain-specific Kotlin stay out of the platform. The reusable Android host
pattern adopted in this cutover is capability-checked exact-alarm scheduling
with an inexact Doze-capable fallback. The foreground bridge service is likewise
a Jetpacs platform capability with deliberately honest best-effort semantics.

Reusable visual vocabulary continues to belong in the EBP core profiles or the
`jetpacs.components` Foundation extension so Grove can consume it from Elisp.
Glance is the next separate renderer/profile slice over the same Room
projections; it is not coupled to this storage cutover.

This plan supersedes the fork-forward cache adapter described by the original
POC 3 scaffold. POC 3 is a clean rebuild of Jetpacs around Room 3. It preserves
the useful POC 2 EBP behavior and characterization tests, but it does not retain
POC 2's file-backed application architecture.

## Decision

Room 3 will be Jetpacs' sole durable implementation of accepted presentation
state and the Companion-owned durable delivery queue. It is not a second
projection written after POC 2 has already accepted and persisted a mutation.

Emacs remains the authority for authored documents. The EBP spec remains the
cross-platform authority. `:ebp-kmp` contains the storage-neutral durable-store
SPI, reducers, and memory reference implementation, while `:wire` retains
transport, framing, and protocol code. Jetpacs' Room adapter implements that SPI
and exposes read-only `Flow` projections to the Android UI.

Durable event delivery therefore has two independent ledgers:

- Jetpacs commits outgoing `event.action` records to its Room 3 outbox.
- Emacs commits received EventIds together with durable work to its local
  `ebp-sqlite.el` inbox before returning `accepted`.

Room never substitutes for the receiver-owned Emacs commitment required by
SPEC 14.4.

EBP remains Jetpacs-agnostic. The EBP specification may require observable
durability and atomicity, but it must not name Room, Android, Navigation 3,
Compose, Jetpacs, or any Jetpacs schema. Room 3 and Navigation 3 are Jetpacs
implementation choices permitted by the specification.

The rebuild proves observable behavior in implementation and backend contracts
before proposing EBP changes. Once those gates are green, each mismatch is
audited against the current spec: the implementation changes when the spec
already covers the behavior; otherwise the spec may be expanded only with a
language- and platform-agnostic requirement. Kotlin, Room, Android, Nav,
Compose, and Jetpacs remain implementation evidence, never EBP requirements.

The existing branch checkpoint at `1bc66e8` remains the recoverable pre-rebuild
reference in Git history. Rebuilding must not use runtime dual-write as a
rollback mechanism.

## Why the current scaffold is not the target

The present `SurfaceRecordEntity` and `RoomSurfaceCacheRepository` form a
secondary surface cache. `RoomSurfaceCacheRepository.accept` independently
compares revisions after the existing `SurfaceStore` has already made the EBP
decision. That creates two acceptance authorities and still leaves queue,
draft, reminder, trigger, and theme durability in JSON files.

POC 2 also contains correctness gaps that must be characterized, not copied:

- `SurfaceStore` mutates memory, swallows record and draft persistence errors,
  and can still return `applied`.
- `stale_spec` is validated and discarded; `stale_after_s` is not retained.
- surfaces, queue records, reminders, and theme are not completely partitioned
  by pairing identity.
- a trigger firing writes the queue and trigger runtime separately, then uses
  compensation and restart recovery to approximate one transaction.
- pairing revocation does not durably fence authentication and erase every
  identity-scoped category.
- the current queue clock does not yet implement all of the current EBP
  forward-step corroboration rules.

The rebuild preserves POC 2's protocol semantics where they agree with the
current local EBP specification and uses regression tests to expose and repair
the gaps above.

## Target module graph

```text
:wire -----------------------> :ebp-kmp
  transport, framing,          durable-store SPI, reducers,
  protocol handling/state      rollback-capable memory reference
  forbidden: Jetpacs, Room, Android, Nav, Compose, java.io file stores

:core:ebp-store -------------> :ebp-kmp
  Jetpacs Room adapter
        |
        +---------------------> :core:database
                                  Room 3 entities, DAOs, migrations,
                                  builders, exported schemas
                                           |
                               +-----------+-----------+
                               |                       |
                               v                       v
                     :core:data read-only Flow   Android platform effects
                               |
                               v
feature ViewModels -> Nav 3 -> dumb Compose EBP renderer
```

The complete initial scaffold is:

- `:ebp-kmp`
- `:wire`
- `:core:model`
- `:core:database`
- `:core:ebp-store`
- `:core:data`
- `:core:navigation`
- `:core:testing`
- `:feature:pairing`
- `:feature:surface`
- `:feature:settings`
- `:app`

`:ebp-kmp` defines the storage-neutral durable records, reducers, transaction
contracts, and memory reference implementation. `:wire` retains transport,
framing, and protocol code and depends on `:ebp-kmp`. Neither module sees a
Room entity. `:core:database` contains storage mechanics and does not depend on
`:ebp-kmp` or `:wire`. `:core:ebp-store` depends on `:ebp-kmp` and
`:core:database` and performs the mapping. `:core:data`
is read-only from the protocol's perspective: it must not compare revisions,
accept mutations, or write EBP state.

The layout follows the local `architecture-templates` multimodule project. The
repository/`Flow`/ViewModel path follows the local `architecture-samples` TODO
application. Room KMP configuration follows the local Fruitties sample, and
the single saveable stack and entry decorators follow local `nav3-recipes`.
Local repositories, not GitHub, are the implementation references.

## Companion storage-independent persistence API

The implemented `:ebp-kmp` SPI does not preserve the whole-file `load`/`replace`
shape. It is suspending and transaction-oriented. Its core shape is
an EBP-domain transaction scope:

```kotlin
interface EbpDurableStore {
    suspend fun restore(pairingId: PairingId): DurableSnapshot

    suspend fun <T> write(
        pairingId: PairingId,
        block: suspend EbpWriteTransaction.() -> T,
    ): T
}
```

`EbpWriteTransaction` exposes domain records and bounded operations, never SQL,
DAOs, cursors, or Room annotations. Generic reducers in `:ebp-kmp` own revision
ordering, draft reconciliation, dedupe selection, unchanged-trigger decisions,
and result construction inside that transaction. Named use cases such as
`applySurface`, `admitEvent`, `replaceReminders`, `replaceTriggers`, and
`commitTriggerOccurrence` sit above the primitive transaction scope so callers
cannot accidentally omit an invariant.

This split gives `:ebp-kmp` one storage-neutral implementation of EBP behavior
while allowing
an in-memory reference store, Jetpacs Room, or a future non-Android store to
supply atomic persistence. A Room transaction is not an EBP concept.

Every public mutation returns only after commit. A storage exception produces
the protocol's storage-failure outcome and no accepted callback, renderer
update, wake attempt, notification, alarm update, or local `on_fire` effect.

## Coroutine and ordering model

Room 3's KMP APIs are suspending and transaction work is coroutine-confined.
The rebuild must not put blocking DAO calls or `runBlocking` behind POC 2's
monitors.

One process-lifetime coroutine actor consumes a bounded `Channel<EbpCommand>`.
Socket frames, UI actions, manifest receivers, alarms, and device trigger
sources all submit commands to this actor. The actor is the single writer and
the ordering authority across protocol and cold-start paths; it is logically
serialized but is not tied to one physical thread.

For each command it:

1. performs framing, structural validation, and expensive JSON work outside a
   database transaction where possible;
2. performs state-dependent decisions and writes in one short Room transaction;
3. waits for the transaction to commit;
4. publishes a committed domain change and schedules platform effects; and
5. enqueues response frames to a separate single-consumer outbound channel.

A slow socket or Android platform callback must not hold a Room transaction or
block the state actor. Platform effects post follow-up commands instead of
synchronously re-entering it. Bounded actor saturation maps to EBP's overload
behavior rather than unbounded memory growth.

## Room 3 schema v1

The current one-table prototype is disposable POC state. The rebuild creates a
new database name and database class with a complete schema version 1 rather
than pretending the prototype or POC 1's Room v4 database is its ancestor.
Every future schema version exports JSON and supplies explicit migration and
reopen tests; destructive migration is forbidden.

All Emacs-derived durable rows are pairing-scoped. Pairing credentials are not
stored as plaintext in Room. Android stores authentication HMAC key material
behind a dedicated Keystore alias. At-rest queued-payload encryption uses a
separate key and alias; keys are never reused across purposes.

| Table | Required contents and invariants |
|---|---|
| `pairing_partitions` | Pairing ID, credential and payload-key aliases, ACTIVE/REVOKING fence, creation/auth metadata. No plaintext token. |
| `pairing_runtime` | Per-pairing queue counter, effective-clock high-water and forward-step corroboration state, and durable READY-disconnection time. |
| `surface_records` | `(pairing_id, surface_id)` key, revision, present/tombstone state, primary spec, `stale_spec`, `stale_after_s`, current view, accepted time, and first-seen ordinal. Present rows require a spec; tombstones retain only their floor until `surface.release` or revocation. |
| `surface_drafts` | `(pairing_id, surface_id, node_id)` key and non-null JSON text. Row absence means no draft; the literal JSON `null` is a present null-valued draft. Display epochs are not stored. |
| `queue_events` | `(pairing_id, queue_seq)` key, pairing-local unique EventId, encrypted complete payload, plaintext byte count, policy, expiry, dedupe key, pending-local state, and trigger attribution. In-flight and replay-pause state are runtime-only. |
| `reminders` | `(pairing_id, owner, reminder_id)` key, `at_ms`, authored order, and complete normalized payload. Alarm/notification identities include the pairing. |
| `reminder_receipts` | Pairing/owner/id/`at_ms` fired tuple. An unchanged tuple preserves it; removal or changed `at_ms` erases it. |
| `trigger_registrations` | `(pairing_id, identity, trigger_id)` key, authored order, normalized entry JSON, and canonical identity. |
| `trigger_runtime` | Nullable throttle floor, one-shot completion, repeating anchor and last-fire floor, and boot-generation receipt. SQL null remains absence, never zero. Silent baselines remain runtime-only. |
| `pairing_themes` | Complete latest accepted theme JSON per pairing. |
| `platform_effects` | Pairing-scoped idempotent host work, dedupe identity, pending/claimed/completed state, lease attempt count, and timestamps. External work is never performed inside Room. |
| `pairing_revocations` / `revocation_artifacts` | A journal that intentionally survives deletion of the pairing partition and resumes Keystore, image, alarm, notification, shortcut, and other external cleanup after a crash. |

Indexes lead with `pairing_id`. Queue EventId uniqueness is
`(pairing_id, event_id)`, not global. Queue dedupe is indexed but not unique
because an in-flight older record may coexist. First-seen and authored ordinal
columns preserve POC 2's ordered-map behavior; ordering by surface ID is not a
compatible substitute.

Foreign-key cascades erase a partition's Room-owned state on revocation. The
revocation journal deliberately has no cascading foreign key, because it must
survive long enough to finish external cleanup. Cascade must never reclaim a
surface tombstone during ordinary operation.

## Mandatory transaction boundaries

| Operation | One committed transaction must include |
|---|---|
| Surface update/remove | ACTIVE check; revision and limit checks; first-seen ordering; snapshot or tombstone write; stale/current-view state; complete draft reconciliation. Return `applied` only after commit. |
| `surface.release` | Verify a retained tombstone, then delete that history and its drafts in one durable step. A present surface is rejected. |
| Draft interaction | Draft write and any durable event admission produced by that interaction. If no event is created, the draft commit still precedes every later admission. |
| View switch | Validate against the stored multi-view spec and update only `current_view`; no fabricated surface revision. |
| Queue admission | ACTIVE check; effective-clock update; pairing-local dedupe excluding the runtime in-flight/retained head; count/byte limits; EventId/record insert; `next_seq` increment. |
| Queue expiry | Effective-clock/high-water update and expiry deletion. Permanent delivery deletes only the resolved record. |
| Reminder replace | Validate total limit; replace exactly one pairing/owner set; delete receipts for removed or changed tuples; preserve unchanged receipts. |
| Reminder fire | Insert the fired receipt and idempotent notification effect together. A conflict means it already fired. Claim, presentation, and completion happen after commit. |
| Trigger replace | Replace one pairing set; carry every runtime field only for a canonically equal normalized entry; reset changed/new entries and delete removed entries. |
| Trigger occurrence | For queue/wake, insert the event and advance queue state together with throttle, one-shot, repeat, and boot runtime. For drop, commit runtime only. Run local effects and remote eligibility after commit. |
| Theme | Replace the complete normalized theme for exactly one pairing. |
| Session READY/disconnect | Clear or persist pairing-level disconnection time used by stale presentation. |
| Revocation | Fence the pairing, journal external artifacts, and erase every Room-owned child in one transaction. Authentication and receivers require ACTIVE. External cleanup resumes from the journal until complete. |

No Room transaction may span a socket write, Compose callback, alarm API,
notification API, Keystore call, image deletion, or local trigger effect.

## Explicitly not stored in Room

- Emacs-side accepted EventId receipts or application work. Those live in the
  receiver-owned `ebp-sqlite.el` database and remain recoverable without a
  Jetpacs process or EBP session.
- Section 19 editor sessions, shadows, deltas, sequence contention, carets,
  completion results, diagnostic overlays, or fontification.
- queue in-flight ownership, replay request ownership, and accumulated summary
  counters that the specification does not require after restart.
- trigger edge/level baselines, which are silently re-established after restart.
- surface input display epochs; widgets rebuild from durable draft/authored
  values after process death.
- dialogs, toasts, pie menus, transient action requests, scroll positions,
  focus, animations, renderer measurements, or Compose state.
- Nav 3 back-stack contents in EBP tables. Nav saveable state is Jetpacs UI
  state and remains separate from EBP `view.switch`.
- pairing tokens or reusable plaintext encryption keys.

## Legacy quarantine and clean cutover

The rebuild opens a new database and never dual-writes or imports POC 1/POC 2
state. Old queue, surface/draft, reminder, trigger, theme, and prototype Room
files remain untouched and quarantined. Production startup contains no probe,
parser, importer, deletion, or fallback for them. Emacs is the authored-state
authority and re-pushes the desired surfaces, reminders, triggers, and theme.

Runtime `FileQueueStore`, `FileSurfaceBacking`, `FileReminderBacking`, and
`FileTriggerBacking` have no production callers. They remain only as portable
backend fixtures and historical compatibility witnesses in `:wire` tests.

After the first Room-only mutation, an older POC 2 binary cannot safely be used
as a downgrade. A downgrade must be blocked or require an explicit export or
clear-data flow; silently reopening stale JSON files is forbidden.

## Implementation work packages

### WP0 — Freeze behavior and spec gaps

- Preserve POC 2 goldens and convert its surface, queue, reminder, trigger,
  firing, and persistence-compatibility tests into backend contract fixtures.
- Run the contracts against an in-memory transactional reference store.
- Add Emacs store contracts proving receipt-plus-work admission, pairing
  isolation, restart recovery, lease recovery, and commit-before-accepted.
- Add a regression proving the current effect-then-receipt dispatcher is not a
  conforming substitute for SPEC 14.4 durable work.
- Add failing regression tests for pairing isolation, stale fields,
  `surface.release`, response-after-commit, current queue-clock rules, and
  revocation.
- Record whether each POC 1/POC 2 difference is preserved, repaired,
  intentionally retired, or moved to Jetpacs policy.

Exit: protocol goldens remain green and every known durability gap has a test
before production Room code is written.

### WP1 — Replace the fork-forward companion with the clean scaffold

- Recreate `llm-poc-3/companion` from the local multimodule architecture
  template and retain the verified Android 14+/KMP/Room 3/Nav 3 toolchain.
- Add the module graph above, including `:core:ebp-store` and feature modules.
- Configure Room KMP constructor/KSP/bundled SQLite from the local Fruitties
  sample and export the new database's complete schema version 1.
- Add module-boundary checks proving `:ebp-kmp` and `:wire` are free of Room,
  Android, Nav, Compose, Jetpacs, and file-storage imports.

Exit: the clean empty scaffold builds on JVM and Android before POC 2 app code
is transplanted.

### WP2 — Build the generic SPI and serialized runtime

- Keep the storage-neutral durable-store SPI, reducers, tests, and memory
  reference implementation in `:ebp-kmp/commonMain`; keep transport, framing,
  and protocol code in `:wire`.
- Add the suspending transaction SPI and in-memory rollback-capable backend.
- Replace monitor/executor entry points with the bounded process-lifetime actor
  and ordered outbound frame channel.
- Keep socket, Keystore, Android sources, and platform effects behind adapters.

Exit: injected storage failure rolls back every affected domain and no response
or effect reports acceptance before commit.

### WP3 — Implement the complete Room schema and adapter

- Add all tables, checks, indexes, foreign keys, DAOs, encryption mapping,
  database builder, and `RoomEbpDurableStore` in `:core:ebp-store`.
- Run the same contracts against in-memory Room on JVM, a reopened file-backed
  Room database, and Room on the Android device.
- Check in schema JSON and migration tests from the first version onward.

Exit: every pairing-scoped DAO has cross-pairing tests and every mandatory
atomic group has rollback fault injection.

### WP4 — Port vertical EBP slices

Port and wire one complete slice at a time:

1. pairing partition, credential adapter, disconnect clock, and revocation fence;
2. surfaces, tombstones, release, stale presentation, current view, and drafts;
3. queue counter, effective clock, dedupe, expiry, capacity, and FIFO replay;
4. reminders, receipts, cold-start re-arm, and tap admission;
5. trigger replace/runtime, atomic trigger-plus-queue commit, effect progress,
   alarms, sources, and recovery;
6. themes and all remaining revocation artifacts.

Each slice lands only after its memory/Room contracts, database reopen test,
storage-failure test, and Android integration test pass.

Exit: the application composition root constructs one database, one Room EBP
store, and one actor. `CompanionStores` constructs no `File*Backing`.

### WP5 — Clean production cutover

- Open the new Room schema without reading, importing, or deleting POC 1/POC 2
  stores; retain old data as a quarantine only.
- Switch the production composition root in one cutover commit.
- Remove the post-accept `SurfaceCacheRepository.accept` write path and every
  production file-store caller.
- Keep the pre-rebuild Git checkpoint and legacy files untouched; recovery is
  an Emacs re-push into the clean store.

Exit: Room is the only live writer and process restart cannot select a stale
legacy store.

### WP6 — Room Flow, ViewModels, Nav 3, and the dumb renderer

- Expose read-only DAO `Flow` values through `:core:data`.
- Convert them to immutable `StateFlow` in feature ViewModels and collect with
  lifecycle awareness in Compose.
- Use `rememberNavBackStack`, saveable-state and ViewModel-store decorators,
  and modular entry providers from local Nav 3 recipes.
- Begin with Pairing, Catalog(pairing ID, surface ID), and Settings keys. Keys
  contain identifiers, never specs, Room entities, or renderer state.
- Render only the selected accepted Room surface. Do not compile a Kotlin copy
  of Compose Catalog into the product.

Exit: an Elisp-authored EBP catalog survives rotation, process death, offline
cold start, reconnect, predictive back, pairing isolation, and revocation on
the Android device.

### WP7 — Step 2.5 workspace hardening

Resume the existing hardening backlog only after the Room cutover is legible.
Prioritize shared JSON utilities, generated contract/renderer manifests,
lookup-table validation, module-boundary lint, one-command JVM/device gates,
POC 1 versus POC 2 regressions, and m3-fidelity DSL findings. Low-risk cleanup
may accompany a vertical slice; broad refactors still require characterization
and at least two demonstrated consumers.

## Cutover gates

POC 3 may call the Room rebuild complete only when:

- the exact current local EBP specification and goldens pass;
- POC 2 behavior contracts pass against both memory and Room, except documented
  corrections required by the specification;
- all persistent state is pairing-partitioned;
- every accepted response and platform effect occurs after commit;
- every accepted action delivered to Emacs has receiver-owned durable
  receipt/work state independent of Jetpacs Room and session lifetime;
- trigger runtime and queue admission share one transaction;
- `surface.update`, remove, release, stale state, drafts, and current view are
  durable with no second revision authority;
- Room is the renderer's only data source and production file stores have no
  callers;
- revocation fences authentication first and crash-resumable cleanup erases
  Room, Keystore, image, alarm, notification, and shortcut state;
- clean startup never reads, mutates, deletes, or falls back to legacy stores;
- JVM, Android device, schema, migration, process-death, 16 KB alignment,
  lifecycle, predictive-back, offline, reconnect, and accessibility gates pass;
  and
- the EBP submodule is reproducibly checked out at the superproject gitlink.

## Step 3 interlock

Step 3 remains after this rebuild and Step 2.5. No Section 19 state enters Room.
`ebp-sync.el` remains generic Emacs 30.1+ code using built-in `jsonrpc.el` and
`track-changes.el`. Jetpacs supplies only buffer selection, editor policy, and
product UX adapters.

For efficient data transfer, `ebp-sync.el` will register each eligible buffer
with `track-changes-register`, defer fetches out of low-level change hooks, use
`:disjoint t` to avoid widening unrelated edits, form one bounded `edit.apply`
splice from each fetched region, keep only one next-sequence request in flight,
and reserve full-text transfer for `edit.open` and explicit resynchronization.
That work does not begin until the Room/Nav/data ownership boundary above is
green and understandable.
