# Room 3 persistence contract

Status: implemented. The production Companion uses one Room 3 database named
`jetpacs.db`; the current schema is version 2 with exported version 1 and 2
schemas and an automatic 1→2 migration. This document records the boundary
that future storage changes must preserve.

Room is Jetpacs' implementation of accepted Companion state. It is not an EBP
requirement, an authority beside Emacs, or the receiver's durable receipt. The
storage-neutral records, transaction SPI, and reducers live in `:ebp-kmp`.
`:core:database` owns Room mechanics; `:core:ebp-store` maps the EBP SPI onto
them; `:app` supplies the Android composition and platform effects.

## Production composition

`JetpacsApplication` creates one process container, one database, one
`RoomEbpDurableStore`, one bounded `EbpCommandActor`, and one bridge. All
protocol, receiver, alarm, widget, and UI mutations enter through that
serialized command boundary. Expensive parsing can happen before a
transaction; state-dependent decisions and writes happen inside it; responses
and platform work happen only after commit.

The Android builder uses Room's bundled SQLite driver and an IO query coroutine
context. Production startup does not probe, import, rewrite, or delete POC-era
JSON or prototype databases. There is no live dual-write path.

## Schema groups

The exact entity list and schema JSON are authoritative. The current groups are:

| Group | Tables and responsibility |
|---|---|
| Pairing | `pairing_partitions`, `pairing_runtime`: ACTIVE/revoking identity fence, key aliases, queue/effective-clock and READY-disconnect runtime |
| Revocation | `pairing_revocations`, `revocation_artifacts`: journaled cleanup that survives deletion of pairing-owned rows |
| Surfaces | `surface_records`, `surface_drafts`: revisions, present/tombstone state, spec/stale spec, current view, first-seen order, and retained input drafts |
| Outbox | `queue_events`, `issued_event_ids`: encrypted complete payloads, stable ordering/identity, expiry, dedupe, capacity, pending-local state, and safe EventId reuse floors |
| Reminders | `reminders`, `reminder_receipts`: normalized pairing/owner schedules and fired-tuple idempotency |
| Triggers | `trigger_registrations`, `trigger_runtime`: normalized registrations and durable throttle/one-shot/repeat/boot progress |
| Theme/effects | `pairing_themes`, `platform_effects`: latest accepted theme and idempotent post-commit Android work |
| App runtime | `app_runtime`: device-local singleton policy such as the user-enabled background bridge; not pairing-authored EBP state |
| Widgets | `widget_bindings`, `widget_action_tokens`: host instance selection and opaque, revision-bound click capabilities |

All Emacs-derived records are pairing-partitioned. Pairing tokens and reusable
plaintext keys are excluded. Authentication and queued-payload encryption use
different Android Keystore aliases.

## Required atomic groups

| Operation | One committed transaction includes |
|---|---|
| Surface update/remove | Pairing fence, revision/limit checks, present or tombstone row, stale/current-view fields, first-seen order, and complete draft reconciliation |
| `surface.release` | Retained-tombstone check plus deletion of that history and its drafts; a present surface is rejected |
| Draft interaction | Draft state and any durable event admitted by the same gesture |
| View switch | Validation against the stored multi-view spec and update of `current_view` only; no fabricated surface revision |
| Queue admission | Pairing fence, effective clock, dedupe/capacity checks, EventId and payload insertion, sequence advancement, and any owning trigger progress |
| Queue resolution | Delete only the permanently resolved record; interrupted delivery remains replayable with the same EventId |
| Reminder replace/fire | Exact owner-set replacement and receipt preservation/reset; a fire commits its receipt and idempotent notification effect together |
| Trigger replace/occurrence | Canonically equal registrations retain allowed runtime; a queue/wake occurrence commits runtime and outbox admission together |
| Theme | Complete normalized theme replacement for one pairing |
| Widget render/lifecycle | Binding/token changes required for stage-before-publish, removal, host-ID restore, and revocation |
| Revocation | Fence first, journal external artifacts, and erase Room-owned pairing state while leaving cleanup resumable |

No Room transaction spans a socket write, Compose callback, notification,
alarm, widget publication, Keystore operation, image deletion, shortcut API,
or other Android platform call.

## State deliberately outside Room

- Emacs-side EventId receipts and recoverable application work, which live in
  the receiver-owned `ebp-sqlite.el` database.
- Section 19 editor sessions, shadows, deltas, sequence counters, carets,
  completion offers, diagnostics, and fontification.
- Queue in-flight socket ownership and session replay-request ownership.
- Trigger edge/level baselines that the specification requires to be silently
  re-established after restart.
- Dialogs, toasts, pie menus, confirmations, focus, scroll positions,
  animations, and renderer measurements.
- Navigation 3 back stacks. They are receiver presentation state and do not
  compete with EBP `view.switch`.

## Read path and rendering

Committed Room state is projected through read-only repositories or bounded
bridge flows. UI consumers do not compare EBP revisions or write accepted
protocol state. A catalog or restored Nav destination selects a durable
surface by identifiers; the renderer consumes the accepted spec under the
profile installed for that target.

Widgets also read the same accepted surface records after process death. Their
host bindings and action capabilities are additional Room state, not a second
widget model. See [`GLANCE-WIDGETS.md`](GLANCE-WIDGETS.md).

## Failure and cutover rules

A storage exception returns a storage-failure outcome. It cannot emit an
accepted response, renderer update, wake request, notification, alarm update,
shortcut handoff, or local trigger effect. A slow socket or Android callback
cannot hold the state actor's transaction. Actor saturation maps to bounded
overload behavior instead of allocating an unbounded queue.

Legacy file-backed classes may remain in upstream tests as compatibility
fixtures, but production composition must have no `FileQueueStore`,
`FileSurfaceBacking`, `FileReminderBacking`, or `FileTriggerBacking` caller.
Downgrade to a binary that reopens quarantined state is unsupported; it must not
occur silently.

## Verification

Use [`../companion/TESTING.md`](../companion/TESTING.md) for exact commands.
The persistence gate includes:

- storage-neutral reducer contracts against the rollback-capable memory store;
- `:core:database:jvmTest` for DAOs, schema, migration, reopen, pairing
  isolation, widgets, and revocation;
- `:core:ebp-store:jvmTest` for the EBP SPI, encryption envelope, commit order,
  and fault-injected rollback;
- `:wire:jvmTest` for session, queue, reminder, trigger, and replay behavior;
- `:app:testDebugUnitTest`, lint, and debug/release assembly for production
  composition; and
- the API 34 managed-device smoke for the real database, bundled driver,
  Keystore aliases, and private foreground service.

A database build or JVM test alone is not process-death, alarm, widget,
Keystore, or device evidence.
