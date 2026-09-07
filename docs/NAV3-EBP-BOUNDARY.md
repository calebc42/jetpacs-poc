# Navigation 3 and EBP boundary

Jetpacs uses AndroidX Navigation 3 for receiver-owned application destinations.
It does not use Navigation 3 to model the views inside an EBP multi-view
SurfaceSpec.

## State ownership

| State | Authority | Nav3 key |
|---|---|---|
| Pairing/setup | Jetpacs | `Pairing` |
| Present-surface catalog | Jetpacs over the accepted surface cache | `Catalog(pairingId)` |
| Selected EBP surface | Jetpacs | `Surface(pairingId, surfaceId)` |
| Companion settings | Jetpacs | `Settings` |
| SurfaceSpec and revision floor | EBP `SurfaceStore` | never stored in a key |
| Multi-view `current_view` | EBP `SurfaceStore` | never stored in a key |
| Dialog, pie menu, confirmation | Current EBP session / process host | never stored in a key |

All Nav3 keys belong to one closed serializable hierarchy. The Android host
retains `NavBackStack<JetpacsNavKey>` and uses that hierarchy's generated sealed
serializer rather than erasing it to `NavKey`. A restored `Surface` key is
reconciled against the durable present-surface set before it can render. A
removed surface is popped to the catalog; a hidden surface update changes only
that surface's StateFlow and cannot navigate or recompose the selected one.

## Back precedence

1. Platform windows and EBP overlays dismiss themselves first.
2. A selected EBP view with the authored chrome back descriptor executes that
   exact `view.switch` descriptor through `DeviceBridge.action`.
3. Otherwise Nav3 pops `Surface`, `Settings`, or repair setup to its prior
   receiver-owned destination.
4. Back at the root exits the Activity; it never fabricates an EBP action.

This preserves the existing EBP rule that only `view.switch` or an update with
`current_view` changes the in-surface view. Nav3 transitions do not emit
`view.switched`. UI navigation callbacks run only while their NavEntry is
`RESUMED`, and the authored in-surface Back handler is likewise enabled only for
the resumed entry, so an exiting entry cannot mutate either navigation state.

## Process death and disconnection

`rememberNavBackStack` saves the identifier-only stack across Activity
recreation and supported process-state restoration. The durable EBP store is
independent: `DeviceBridge` seeds every cached `app:*` surface before accepting
a new connection, then publishes a hydration barrier atomically with the
catalog IDs. Nav reconciliation waits for that barrier, so it cannot mistake a
not-yet-loaded cache for removal. The catalog and a restored Surface route work
while Emacs is offline; reconciliation then supplies a catalog root and removes
genuinely stale keys.

## EBP interaction

The optional `surface.open` builtin represents an explicit user gesture that
selects a present `app:*` surface. Surface update acceptance remains cache
mutation only; the receiver handles `surface.open` locally and stores only the
target surface ID in Navigation 3.

App launchers and app-owned rails can pair local selection with `app.open` or
an app route verb so Emacs retains semantic app/route state. A feature-gated
remote `open_surface` adjunct requests the same local selection before the
remote event is independently delivered. Neither mechanism turns a later
`surface.update` into navigation, so a background refresh cannot seize the
visible receiver destination.
