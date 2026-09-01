# Generic Glance widgets

Jetpacs exposes one Android `AppWidgetProvider`. Each Android widget instance
stores a durable Room binding from its host-assigned ID to one pairing and one
arbitrary `widget:<name>` EBP surface. Adding an Emacs applet widget therefore
does not add a Kotlin provider, action, or data model.

The implementation floor is Android 14 (API 34).

The launcher opens one configuration activity with
`ACTION_APPWIDGET_CONFIGURE`. It accepts only a current host ID whose provider
is exactly `JetpacsWidgetProvider`, then writes the pairing/surface binding to
Room before returning `RESULT_OK`. Its full-width controls remain usable in a
narrow host window and at large font scales. This activity is the sole
OS-invoked widget component that must be exported; the provider's click,
confirmation, and stale-alarm endpoints remain private.

Android can replace every host ID during restore. `onRestored` snapshots and
rekeys the complete old-to-new map in one Room transaction, including an
overlapping ID permutation, cascades every old click token, and marks
`OPTION_APPWIDGET_RESTORE_COMPLETED` only when the whole map succeeded. The
protocol database remains excluded from cloud backup and device transfer: an
unpaired new device does not inherit an outbox or device-bound pairing. When no
safe local binding survived, the restored instance deliberately returns to
configuration instead of inventing one.

## Rendering boundary

`:renderer:glance` consumes the admitted EBP JSON node tree directly, beside
the Compose renderer. It does not introduce an app-specific AST. Its welcome
profile advertises only the members it translates:

- `text`, `icon`, `row`, `column`, `box`, `surface`, `lazy_column`, `spacer`,
  `divider`, `card`, `button`, `icon_button`, `badge`, `section_header`, and
  `empty_state`;
- the universal `key`, `id`, and `semantics` members;
- the `name` and `description` semantics members;
- the local, implementation-neutral `surface.open` builtin;
- widget wrapper members `title`, `body`, `empty`, `header_action`, and
  `size_variants`.

The EBP widget profile bounds the complete atomic document at 128 nodes, 40
lazy items, depth 12, eight size variants, and 716,800 marshalled RemoteViews
bytes. The first authored size variant whose minimum width and height fit the
current instance wins; resizing selects again without changing the surface
revision or emitting an event.

Glance is the authoring and rendering API. Android launchers still host widgets
through the platform's RemoteViews transport, including widgets authored with
Glance. Jetpacs therefore marshals the composed RemoteViews into a `Parcel`
before publishing it. Content over the advertised byte cap is replaced by a
small safe status view, leaving headroom below Android's binder transaction
limit.

## Cold and stale rendering

Accepted surfaces, their stale policy, pairing runtime state, widget bindings,
and click capabilities all live in Room. Provider startup reads those tables
directly, so a launcher update after process death can render the last accepted
surface without an Emacs connection or a reconstructed EBP session.

The newest transport generation owns READY. Replacing or losing that READY
session records one durable disconnect timestamp; reaching READY clears it and
immediately re-renders every instance. The cached live body remains current
until its authored `stale_after_s` deadline. Rendering at or after that exact
boundary chooses the atomic `stale_spec` when present, otherwise the cached
live spec, and labels the widget Offline.

A private explicit alarm requests a refresh at the next deadline. While READY,
the same alarm acts as a rolling process-death watchdog. On a cold start,
Jetpacs uses Android's self-only historical process-exit record when it is
newer than the accepted snapshot, with process start as a conservative
fallback, then compare-and-sets the missing disconnect marker. Alarm delivery
is Android best effort, but every provider callback re-evaluates the deadline
from Room, so it cannot turn old content fresh. Binding-table observation and
committed `widget:*` surface callbacks fan updates out to all affected host IDs.

A missing, removed, malformed, or revoked surface gets a small non-actionable
status view. Revoking a pairing deletes its widget bindings and tokens in the
same Room revocation transaction; the binding observer then replaces every
orphaned host view.

The foreground bridge service and its ongoing notification are only
best-effort reconnection aids. Android may kill the Emacs process, and Emacs'
own documentation notes that a notification may remain after its originating
process has died. Jetpacs never treats notification presence as a liveness
signal; cached Room state and replayable events are the correctness boundary.

## Click path

Rendered clicks contain only a random 128-bit token in a private URI. The
provider, confirmation activity, and explicit click receiver are not exported;
the separately validated configuration activity is the launcher-facing
exception described above. The Room token row binds that opaque capability to
the widget ID, pairing, surface, accepted revision, exact authored descriptor,
and expiry. Before dispatch Jetpacs rechecks all of those facts against the
current binding and present surface.

Tokens for a new render are staged before Android receives their PendingIntents.
Tokens from the prior render are pruned only after `updateAppWidget` succeeds.
This ordering leaves both process-death windows usable without accepting a
token for stale content. Removal cascades token deletion; restore rekeys the
binding and invalidates all old tokens.

Every remote ActionDescriptor uses `dispatchSurfaceOccurrence`, the same
durable `event.action` path used by the foreground UI. It retains the authored
surface and `revision_seen`, admits `queue` and `wake` events to the Room outbox
before claiming success, and lets normal queue replay deliver them to Emacs.
Wake and the optional local `open_surface` adjunct occur only after durable
admission succeeds; a queue-full or storage failure cannot navigate or wake as
though the tap were safe. A `drop` adjunct remains receiver-local, matching the
ordinary EBP action router.

Object or string confirmations run before event creation. Kotlin does not know
what “capture”, “mark done”, or any other app action means. The sole local click
is the advertised generic `surface.open` builtin.

## Emacs authoring and acceptance fixtures

`jetpacs-widget-surface` builds the atomic wrapper and
`jetpacs-widget-size-variant` adds adaptive bodies. `jetpacs-check-profile` with
the `widget` profile catches unsupported nodes before a push. For example:

```elisp
(jetpacs-widget-surface
 "Inbox"
 (jetpacs-button
  "Capture"
  (jetpacs-action "my-applet.capture" :ttl-s 86400 :when-offline 'wake))
 :size-variants
 (list (jetpacs-widget-size-variant
        280 160
        (jetpacs-column (jetpacs-text "A roomier presentation")))))
```

`jetpacs-grove-capture-widget-fixture` and
`jetpacs-grove-agenda-widget-fixture` are deterministic visual acceptance
documents. Their canonical bytes match `widget-surfaces.golden`; their Grove
action names are opaque fixture data, not Android behavior.

The downstream `grove` repository now also authors the live
`widget:grove.capture` and `widget:grove.agenda` surfaces in Elisp over its
explicit Org snapshot. Those applet surfaces are the user-facing acceptance
case; the in-tree fixtures remain protocol goldens and do not duplicate Grove
semantics in Kotlin.

## Lifecycle verification matrix

| Case | Implementation and automated evidence |
|---|---|
| Multiple instances and arbitrary names | Room binding primary key per host ID; `WidgetDaoTest.multipleInstancesCanBindToArbitrarySurfaces` |
| Resize | `onAppWidgetOptionsChanged` re-renders from the same snapshot; Glance projection and `WidgetPolicyTest.resizeUsesCurrentConservativeMinimums` pin selection inputs |
| Removal | `onDeleted` deletes the binding, cascades its tokens, and reschedules the remaining instances; `WidgetDaoTest.rerenderStagesBeforePublishThenPrunesAndRemovalCascades` |
| Stale content | Persisted READY loss, exact pure deadlines, earliest-host scheduling, and READY watchdog policy are pinned in `WidgetPolicyTest` |
| Reconnect | `ReadyConnectionTracker` gives the newest transport sole authority; READY clears the Room timer and fans out a live render |
| Revocation | The revocation transaction erases bindings and tokens; Room observation refreshes the orphaned host IDs; `WidgetDaoTest.revocationImmediatelyErasesBindingsAndTokens` |
| Reboot/update/time change | `BootReceiver` re-arms existing platform work and requests an all-instance cold Room render, which also replaces the stale alarm |
| Process death | Rendering has no live-session dependency; cold READY-loss inference and watchdog policy are unit-tested, and durable queue restart/replay is covered by EBP's kill matrix |
| Offline taps and replay | `SurfaceOccurrenceTest` pins surface/revision/args admission; the durable queue kill matrix pins replay with stable event IDs |
| Host backup/restore | `onRestored` atomically snapshots and rekeys the complete map, invalidates old tokens, and reports completion; `WidgetDaoTest.restoreSnapshotsAnOverlappingIdPermutationAtomically` and invalid-map coverage |
| Binder size | Pre-publication Parcel measurement is failure-contained and oversize content falls back to a small status view; `WidgetPolicyTest.remoteViewsBudgetLeavesBinderHeadroom` |

Focused local gates:

```sh
cd companion
./gradlew :renderer:glance:testDebugUnitTest \
  :core:database:jvmTest \
  :app:testDebugUnitTest \
  :app:lintDebug :app:lintRelease \
  :app:assembleDebug :app:assembleRelease
```

The API 34 managed-device smoke additionally resolves the installed provider
metadata and asserts that only the validated configuration activity is
exported among widget-specific entry points.
