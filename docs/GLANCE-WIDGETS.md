# Generic Glance widgets

Jetpacs exposes one Android `AppWidgetProvider`. Each Android widget instance
stores a durable Room binding from its host-assigned ID to one pairing and one
arbitrary `widget:<name>` EBP surface. Adding an Emacs applet widget therefore
does not add a Kotlin provider, action, or data model.

The implementation floor is Android 14 (API 34).

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

After a READY connection ends, the cached live body remains current until its
authored `stale_after_s` deadline. At the deadline the provider chooses the
atomic `stale_spec` when present, otherwise the cached live spec, and labels the
widget Offline. A missing, removed, malformed, or revoked surface gets a small
non-actionable status view. Revoking a pairing deletes its widget bindings and
tokens in the same Room revocation transaction.

The foreground bridge service and its ongoing notification are only
best-effort reconnection aids. Android may kill the Emacs process, and Emacs'
own documentation notes that a notification may remain after its originating
process has died. Jetpacs never treats notification presence as a liveness
signal; cached Room state and replayable events are the correctness boundary.

## Click path

Rendered clicks contain only a random 128-bit token in a private URI. The
provider, configuration activity, confirmation activity, and explicit click
receiver are not exported. The Room token row binds that opaque
capability to the widget ID, pairing, surface, accepted revision, exact authored
descriptor, and expiry. Before dispatch Jetpacs rechecks all of those facts
against the current binding and present surface.

Tokens for a new render are staged before Android receives their PendingIntents.
Tokens from the prior render are pruned only after `updateAppWidget` succeeds.
This ordering leaves both process-death windows usable without accepting a
token for stale content. Removal cascades token deletion; restore rekeys the
binding and invalidates all old tokens.

Every remote ActionDescriptor uses `dispatchSurfaceOccurrence`, the same
durable `event.action` path used by the foreground UI. It retains the authored
surface and `revision_seen`, admits `queue` and `wake` events to the Room outbox
before claiming success, and lets normal queue replay deliver them to Emacs.
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

## Lifecycle verification matrix

| Case | Implementation and automated evidence |
|---|---|
| Multiple instances and arbitrary names | Room binding primary key per host ID; `WidgetDaoTest.multipleInstancesCanBindToArbitrarySurfaces` |
| Resize | `onAppWidgetOptionsChanged` re-renders from the same snapshot; Glance projection and `WidgetPolicyTest` pin selection inputs |
| Removal | `onDeleted` deletes the binding; the Room foreign key cascades tokens and is covered by `WidgetDaoTest` |
| Stale content | Persisted disconnect/stale fields drive pure deadline policy in `WidgetPolicyTest` |
| Revocation | The revocation transaction erases bindings and tokens; `WidgetDaoTest.revocationImmediatelyErasesBindingsAndTokens` |
| Reboot/update/time change | `BootReceiver` re-arms existing platform work and requests an all-instance Room render |
| Process death | Rendering has no live-session dependency; durable queue restart/replay is covered by EBP's kill matrix |
| Offline taps and replay | `SurfaceOccurrenceTest` pins surface/revision/args admission; the durable queue kill matrix pins replay with stable event IDs |
| Host backup/restore | `onRestored` rekeys bindings and invalidates old tokens; `WidgetDaoTest.restoreRekeysBindingAndInvalidatesOldTokens` |
| Binder size | Pre-publication Parcel measurement and fallback; `WidgetPolicyTest.remoteViewsBudgetLeavesBinderHeadroom` |

Focused local gates:

```sh
cd companion
./gradlew :renderer:glance:testDebugUnitTest \
  :core:database:jvmTest \
  :app:testDebugUnitTest \
  :app:assembleDebug
```
