# Responsiveness and navigation plan

Status: active. Item C0 (one Room row per accepted surface mutation) landed in
ebp-kmp `dcc2aad` and jetpacs-poc `c1e19e0`. The first slice has landed:
M1 (jetpacs-poc `e1e1bd1`), M2 (ebp-kmp `716a75b`, jetpacs-poc `3de748a`),
E2a (ebp.el `78957cc`), C1 (ebp-kmp `a028324`, jetpacs-poc `95c070b`), E1
(jetpacs-poc `daeed8b`), E5 (jetpacs-poc `24f65cf`), C7a (jetpacs-poc
`12b5bb3`) and the harp pilot A1a (harp-native `651b2bb`). The glasspane
pilot A2a was not taken: `glasspane-areas--on-filter` rebuilds the drill's
chip rows, notes, project cards, file and archive rows, so it is a shape
change, not a value move; the apps lane names a replacement. Every other
item below is ordered, unstarted work, cut into steps that each ship alone
with their own evidence. Record the before/after numbers for the first slice
on a device before starting the later lanes.

The companion command matrix lives in `companion/TESTING.md` and is not
repeated here. The Emacs gate is targeted ERT, warning-as-error byte
compilation, then `test/run-tests.sh` for broad changes (`AGENTS.md`).

## Why the whole re-push felt faster than a patch

A `surface.data` patch does, on the device, everything a `surface.update`
snapshot does except parse the frame. `SurfaceStore.patch` applies the ops,
canonicalizes the whole document (`Materializer.checkDocument`), re-walks the
whole authored tree (`materializeWithItems`), aligns it against the previous
revision (`share`), runs the full `SpecValidator`, persists, and only then the
engine's surface listener walks the tree again for retained-presentation paths
before publishing the `StateFlow`. A frame twenty times smaller therefore saved
only its parse. Meanwhile the fixed cost of every mutation had grown: the
record carries `authored_spec_json` and `data_json` beside `spec_json`, and
until C0 every present surface's row was decoded, deep-compared, re-encoded and
rewritten per mutation. Patches made mutations more frequent, so the device
paid that growth more often.

On the Emacs side the patch path is cheap: `jetpacs-shell-data-patch` runs no
builder and `jetpacs-data-apply-ops` copies only along the pointer path. But no
application calls it. Every handler in jetpacs-poc, glasspane, grove-native and
harp-native uses `jetpacs-app-defer-refresh`, so every tap still pays the full
chrome-stack rebuild (`jetpacs-chrome--build` rebuilds every stacked screen on
an ordinary push, root first), one serialization for the frame-size gate and
one for the wire, and two `synchronous=FULL` SQLite commits (reserve the
revision, absorb the floor) on top of the receipt commit the action itself
made. The patch machinery is in place; the work is to make both ends cheap per
mutation and then to use it.

## First slice

Seven small changes, each independently shippable, ordered so the measurement
exists before the first optimisation and the cheapest wins land first. Record
the before/after numbers for each in the measurement section below, naming the
device, the APK type and the Emacs build, in the style of
`ARCHITECTURE-POC3.md`.

1. **M1 — tap→push in devtools (Emacs).** `jetpacs-devtools` already records
   handler time, push/patch bytes and send→reply time. Stamp the `push` and
   `patch` events with the time from `jetpacs-shell-push` entry to the send,
   and report `push.at − action.at` per surface as "tap→push" in
   `jetpacs-devtools-report`. Evidence: an ERT case in
   `test/jetpacs-devtools-test.el` that a dispatched action followed by a
   deferred push yields one tap→push sample.
2. **M2 — stage timer in the engine (Companion).** Behind the existing
   profiler toggle, time the five stages of one inbound surface frame in
   `CompanionEngine.feed`: apply/parse, materialize, admit, persist, listener.
   Log them per revision. Evidence: a `CompanionEngineTest` case asserting the
   five samples are emitted for one `surface.data` frame when the toggle is on
   and none when it is off.
3. **E2a — no commit after the reply (Emacs, ebp.el).**
   `ebp-client--absorb-floor` always calls `ebp-store-raise-floor`, an
   `INSERT … ON CONFLICT` plus `COMMIT`, although the reported revision is the
   one `ebp-client--reserve-revision` committed one hop earlier. Keep a
   known-persisted floor per surface in the client and skip the store write
   when the reported floor does not exceed it. Evidence: an `ebp-wire-test.el`
   witness counting store commits per push (two becomes one), and the existing
   restart-above-floor witnesses unchanged.
4. **C1 — optimistic Back (Companion).** `view.switch` goes tap → actor →
   engine monitor → `SurfaceStore.switchView` → Room commit → surface listener
   → `AppSurfaceRegistry.publish`; the new view cannot draw before the commit.
   Publish the resolved view from the in-memory record first and persist
   afterwards (`switchView` already mutates `currentView` before
   `tryPersistRecord`; move the listener call between them in
   `CompanionEngine.executeBuiltin`). Route `surface.open` directly to
   `DeviceBridge.requestSurfaceOpen` without the actor hop. Evidence: a
   Robolectric test that the surface's `StateFlow` carries the new view before
   the backing's `putRecord` runs; `PatchRecompositionTest` unchanged.
5. **E1 — reuse cached lower screens on a drill (Emacs).**
   `jetpacs-chrome--cache-compatible-p` requires `jetpacs-chrome--same-stack-p`,
   an identical stack, so a drill (which grows the stack) never reuses the
   cache and re-runs the root builder, re-analyses every screen
   (`jetpacs-chrome--gate-view`) and re-copies the drawer per view
   (`jetpacs-chrome--namespace-ids`). Relax the check to "the cached entries
   are an `eq` suffix of the live stack", reuse any record whose `:entry` is
   `eq` and whose `:before` budget snapshot matches, and set
   `jetpacs-chrome--target-refresh-view` to the new screen in
   `jetpacs-chrome--drill-1` and `jetpacs-chrome--push-or-undo`. Evidence:
   `test/jetpacs-chrome-test.el` — a drill runs only the new screen's builder;
   budget starvation behaviour (`jetpacs-chrome-max-screens`) unchanged.
6. **E5 — no idle wait on rotation (Emacs).** `jetpacs-chrome--on-window-changed`
   schedules every chrome surface through `jetpacs-shell--schedule-repush`, a
   0.5 s idle timer. A size-class flip should defer by zero
   (`jetpacs-flow-continue` or `run-at-time 0`) and keep the idle debounce for
   registry mutations only. Evidence: ERT case that a window change pushes
   without idle time.
7. **C7a — socket basics (Companion).** Set `tcpNoDelay` on accepted sockets;
   make `persistReadyConnectionChange` submit to the actor instead of
   `runBlocking` on the accept thread. Evidence: `WireConformanceTest` and the
   bridge tests green; reconnect timing on device.

Then pilot the apps lane with one handler per app before converting more:
**A1a** harp `harp--on-filter` (landed: the journal and document pages are a
row template over `/rows`, the filter is one `surface.data` patch on the
first page) and **A2a** a value-only glasspane handler — not
`glasspane-areas--on-filter`, whose drill rebuilds chips, notes, cards and
file rows; see the apps lane — bound through `jetpacs-ref` and patched with
`jetpacs-app-defer-patch` or `-sync`, each measured with M1 against the
refresh it replaces.

## Next slice

The first slice is in `main`. Do these in order; each is one commit with
its own evidence, as above.

1. **Measure the first slice on a device — done 2026-10-21**, see
   "Numbers, 2026-10-21" in the measurement section. Baseline workload from the
   measurement section. Emacs: `jetpacs-devtools-profile` on, read the
   "Tap → send" rows of `jetpacs-devtools-report` and the tap→send and prep
   figures in `jetpacs-devtools-action-log-buffer` for a harp tag filter
   (one patch) against a `harp.page` tap (a re-push), a chrome drill, and a
   Back. Companion: `adb shell setprop log.tag.JetpacsStages DEBUG`, reconnect,
   and record decode/apply/materialize/admit/commit/reply/listener for a
   `surface.update` and a `surface.data` frame. Write the numbers into the
   measurement section with device, APK type and Emacs build. They decide
   the order of 2–4.
2. **C2a — one actor command per complete frame — done 2026-10-21.**
   `FrameReader` reads 64 KiB slices and decodes on the connection thread;
   the actor runs `CompanionEngine.feedFrame` once per complete frame and
   `feedFailure` once per decoder fault (the byte-level `feed` is the same
   two behind the engine's own decoder, for tests). Evidence:
   `CompanionEngineTest` ordering cases unchanged plus the parsed-frame and
   fault cases; `FrameReaderTest.aUiActionIsAnsweredWhileAMultiChunkFrameIsMidFeed`.
3. **C3 — input displays per surface.** Drop `displays` from `RenderCtx`;
   per-surface state read by the stateful nodes; `RenderCtx` compared on
   surface, path, `sourcePath`, design scope. Evidence: a
   `PatchRecompositionTest` case that a draft on surface B runs no node of
   surface A.
4. **C4a — identity keys in composition.** `LazyRenderItem` compares its node
   by reference; `remember(children)` and `remember(node)` sites in
   `LayoutNodes.kt`, `CoreComposeRenderer.kt`, `VisualizationNodes.kt` key on
   identity or revision. Evidence: the insert-at-head case keeps later rows'
   state; the scroll-retention instrumented test.
5. **C2b–d — the rest of the actor split** (C6a, the reducer's stale check
   first, is done 2026-10-21 in ebp-kmp), then **E3** (Org render memoization) and **A2a** (a value-only
   glasspane handler: the todo-filter chips or the agenda mode, after
   confirming it moves values only), then **E2b** (reserve revisions in
   blocks), **C5** (cold start), **C8** (release build, baseline profile,
   macrobenchmark).

Known environment notes from the first slice: three Jetpacs ERT cases
(`jetpacs-clip-golden`, `jetpacs-hypertext-table-cell-cap`,
`jetpacs-integration-w10-ceiling-is-real-not-stubbed`), three ebp.el wire
cases that expect an unwritable directory, and harp's checkdoc step fail
on upstream `main` when run as root on a from-source Emacs 30.1; none is
touched by the slice. `jetpacs-entry-test.el` and `jetpacs-teardown-test.el`
need the `jetpacs-authoring` checkout beside the others.

## Measurement first

Emacs: set `jetpacs-devtools-profile`. `jetpacs-devtools-report` gives the
build wall clock per surface (the whole chrome stack) and last-push bytes;
`jetpacs-devtools-action-log-buffer` gives per event the handler time inside
`jetpacs--dispatch`, the push/patch bytes and the send→reply round trip. M1
adds tap→push, the one gap. Not measured and not needed per tap: the gate
serialization, the SQLite commits (E2a removes one; the receipt and reserve
commits remain and are counted by the ebp-wire witnesses).

Companion: there is no startup, navigation or frame-time gate on a device;
`companion/TESTING.md` samples only JVM projection work. M2 adds per-stage
timing on the engine thread: `adb shell setprop log.tag.JetpacsStages DEBUG`,
reconnect, and every inbound `surface.update` and `surface.data` frame logs
decode, apply, materialize, admit, commit, reply and listener in
microseconds. C8 adds a macrobenchmark that walks catalog →
surface → drill → Back → one patch and records startup, first frame of a
surface, Back latency and patch-to-frame.

Baseline workload. Reuse the removed Perfetto study
(`git show d65d4bd:docs/PERFETTO-ORG-RENDERER-2026-08-21.md`): fifteen taps of
the visibility control after six warm-ups on the 611-line `orgro-manual.org`,
release APK compiled with `speed-profile`. Add the two cases already measured
in `ARCHITECTURE-POC3.md`: the Grove quick-pick (an 866-byte `surface.data`
frame against an 18.9 KB stack snapshot on a Galaxy S21 Ultra over an adb port
forward) and the Orgzly fold (204 bytes against an 11 KB set of `/rows`).
Every number recorded in this document names the device, the APK type, the
Emacs build and the command.

### Numbers, 2026-10-21 — the first slice on a device

Setup: Galaxy S21 Ultra (SM-G998U, Android 15), **debug APK**
(`:app:assembleDebug` at jetpacs-poc `b2aa6b36` with ebp-kmp `a028324`,
`adb install -r`), wireless adb (`adb-tls-connect`) with
`adb forward tcp:8765 tcp:8765`; desktop GNU Emacs 31.1 from source
(native-comp) as `emacs -Q --daemon` with `jetpacs-devtools-profile` on,
harp-native `651b2bb`, ebp.el `78957cc`. Harp needs the Companion's
"Experimental Elisp design runtime" switch on (otherwise the launcher says
"Unavailable: requires jetpacs.design" and the root screen fails to build).
Workload: a synthetic 30-entry journal with three heading tags
(`harp-page-size` 20) opened from the profiles root; every tap is a real
tap on the phone. `JetpacsStages` as set above.

Emacs, from `jetpacs-devtools-action-log-buffer` (ms):

| Tap | Frame | Handler | Tap → send | Prep | Send → reply |
|---|---|---|---|---|---|
| tag chip (`harp.filter`), 5 taps | `surface.data`, 4 ops, 1,843–3,536 B | 0.2–0.3 | 0.3–0.4 | 0.1–0.2 | 59.6–86.2 |
| Next / Previous (`harp.page`), 3 taps | `surface.update`, 115 ops, 18,870 B | 0.1 | 34.0–57.9 | 33.8–57.8 | 75.3–80.0 |
| Journal / Documents nav (`harp.open`, a chrome drill), 3 taps | `surface.update`, 115 ops, 18,870–18,876 B | 0.1 | 29.8–34.1 | 29.6–33.9 | 76.2–90.4 |
| a profile from the root (`harp.profile`) | `surface.update`, 110 ops, 18,324 B | 49.5 | 33.3 | 33.1 | 95.6 |
| Back | none; `view.switched` arrives as an event | 0.1 | — | — | — |

`jetpacs-devtools-report`: builders last 5.1 ms, max 30.9 ms over six
pushes; "Tap → send" last 34.0 ms, max 57.9 ms, prep 33.8 ms over eleven.

Companion, `JetpacsStages` on the engine thread (ms; min–max over the taps
above):

| Frame | decode | apply | materialize | admit | commit | reply | listener | total |
|---|---|---|---|---|---|---|---|---|
| `surface.update` 18.9 KB, 6 frames | 7.7–8.3 | 0.3–1.9 | 7.3–15.3 | 9.8–15.5 | 11.0–15.7 | 0.2–0.4 | 2.0–3.1 | 38.8–52.0 |
| `surface.data` 1.8–3.5 KB, 5 frames | 0.5–1.6 | 1.0–3.8 | 10.8–18.3 | 12.4–14.0 | 11.1–22.0 | 0.2–0.3 | 2.3–7.0 | 42.3–62.2 |

The reconnect drain (ten `surface.update` frames back to back) ran 5.8–85.7
ms per frame; the largest was `app:m3catalog` with 17.9 ms of decode.

What the numbers say:

1. **On the device a patch costs what a snapshot costs.** materialize, admit
   and commit are 35–50 ms of both, and the patch pays *more* in materialize.
   The patch saves decode (8 → 1 ms), the Emacs prep (34 → 0.3 ms) and 17 KB
   of wire; it saves nothing in the engine. The device-side patch win is C6
   (the stale check first, bounds along the op paths, `share` fused into the
   walk, re-materializing only under the op paths).
2. **Every frame waits for Room before it replies or publishes.** commit is
   11–22 ms of every frame and runs before reply and listener. C1 moved the
   listener ahead of the persist for `view.switch`; whether the surface frames
   may do the same under §3.1 is the decision to settle before C2c.
3. **Transport is 20–30 ms per round trip.** send → reply minus the engine
   total leaves 20–30 ms in the socket path and the adb forward (and tcpNoDelay
   is already on). Decode of the 18.9 KB frame is 8 ms, so C2a's payoff is a
   tap answered during a multi-chunk feed, not this frame's latency.
4. **The Emacs re-push prep is 30–58 ms with builders at 5–31 ms of it.** The
   remainder is the gates, the analysis and the profile's size measurement
   (which serializes the spec once more — measure with the profile off before
   crediting E-items). A drill and a page tap push the same 115-op stack, so
   E1's reuse saves builder time, not bytes.
5. **M2 cannot rank C3 and C4a.** The listener stage is 2–3 ms and composition
   is not timed at all; their evidence stays the `PatchRecompositionTest`
   counter until C8's frame timing exists. The order of the next slice
   therefore stays C2a, then C6a pulled ahead of C3 and C4a (the measured
   bottleneck is the engine's patch path), with C2c's §3.1 question settled
   on the way.

### Numbers, 2026-10-21 — after C2a

Same device, APK type, Emacs build, wireless forward, workload and taps as
above, twenty minutes later, on jetpacs-poc `7fb691d0` with ebp-kmp
`a65f3f0` (`FrameReader`, one actor command per complete frame, the parse on
the reader thread). The `decode` stage is now the reader's measurement of
the frame's final slice: terminator scan, UTF-8 and JSON parse.

Emacs (ms): tag chip patches ×4, tap→send 0.3–0.4, send→reply 68.7–102.8;
Next/Previous ×3, tap→send 31.3–57.3, send→reply 78.0–95.0; drills ×3,
tap→send 27.8–31.1, send→reply 70.9–88.6; an entry form push (`harp.form`,
17,613 B) tap→send 27.6, send→reply 60.3.

Companion, `JetpacsStages` (ms; min–max):

| Frame | decode | apply | materialize | admit | commit | reply | listener | total |
|---|---|---|---|---|---|---|---|---|
| `surface.update` 18.9 KB, 6 frames | 8.5–10.5 | 0.2–1.8 | 11.1–15.6 | 11.6–15.3 | 14.9–29.8 | 0.2–0.4 | 2.4–5.7 | 53.9–77.2 |
| `surface.update` 17.6 KB form, 1 frame | 5.7 | 0.2 | 6.7 | 10.2 | 10.4 | 0.3 | 3.0 | 36.5 |
| `surface.data` 1.8–3.5 KB, 4 frames | 0.7–1.2 | 1.6–3.9 | 11.6–22.5 | 12.0–16.9 | 18.7–33.6 | 0.2–0.4 | 4.4–5.3 | 56.9–83.7 |

Reading: single-frame latency is unchanged by C2a, as the morning's numbers
predicted — decode, apply, materialize and admit sit inside the earlier
ranges or within a few ms of them, and the only stage that moved, commit
(Room, +4 to +12 ms on both frame kinds), is one the reader never touches;
treat that as run-to-run variance on a freshly installed, not yet
dex-optimised build, not an effect. What C2a changed is structural and is
pinned by `FrameReaderTest`, not by these numbers: a partial frame now holds
neither the actor nor the engine monitor, so a tap during a snapshot's
arrival is answered at once. The reconnect drain right after the install,
with the screen off, ran 62–212 ms per frame with commit at 21–115 ms —
the phone throttled, not the reader; a drain on an unlocked phone is still
unmeasured.

## Implemented baseline

| Area | Current implementation evidence |
|---|---|
| Action send path | A READY `drop` action is serialize → socket write with no Room on the path (`CompanionEngine`); `drop` is the default policy |
| Optimistic inputs | Switches, checkboxes and text inputs flip local state immediately; the store and drafts follow (`InputNodes.kt`) |
| Hidden surfaces | Per-surface `StateFlow`s in `AppSurfaceRegistry`; an update to an unselected surface never recomposes the selected one |
| Structural sharing | `Materializer.share` returns the previous instance for every untouched subtree; material3 skips by reference under strong skipping (`PatchRecompositionTest`) |
| Room row per mutation (C0) | `IncrementalSurfaceBacking` in ebp-kmp `SurfaceBacking.kt`; `SurfaceStore` hands the backing the one changed record; `RoomSurfaceBacking.put` writes it alone; `SurfaceDao.getFirstSeenOrdinal` replaces the whole-row read; an unbound tree stores once (`RoomSurfaceBackingTest`, `SurfaceStoreTest`, `SurfaceStoreDataTest`) |
| Emacs dispatch order | Handler → receipt commit → reply, exactly SPEC 14.4; the deferred re-push never delays the reply; no blocking wait in the dispatch extent (`jetpacs-surfaces.el`, `ebp.el`) |
| One analysis per screen | Chrome analyses each view once and the shell gates reuse the merged facts (`jetpacs-shell--analysis-capture`) |
| Memos that exist | Agenda, habits and LaTeX (`ebp-org-with-cache`); the reader's tick-based fingerprint (`jetpacs-reader-org.el`); async loader pushes coalesced through one zero-delay timer (`jetpacs-async.el`) |
| Companion-local Back | `view.switched` truncates the Emacs stack with no push (`jetpacs-chrome--on-view-switched`) |

## Change order

Three lanes. Within a lane the order is by payoff; across lanes the first
slice above decides. Each item lists what runs now, the fix, and the smallest
step that can ship on its own.

### Companion lane

- **C0 (done).** One row per mutation. See the baseline table.
- **C1.** Optimistic `view.switch` and direct `surface.open`. First slice.
- **C2.** Two lanes instead of one actor. Today every UI action and every
  inbound 8 KiB chunk go through one `EbpCommandActor` (single worker,
  capacity 64); the socket reader blocks per chunk, `CompanionEngine.feed` and
  `dispatchSurfaceAction` are both synchronized, Room runs inside a nested
  `runBlocking`, and the listener's full walks run under the same monitor. A
  tap during a large snapshot waits behind every queued chunk, and 64 pending
  commands show the overload toast. Steps: (a) **done** — `FrameReader` reads
  until a frame is complete and the actor runs one command per frame, the
  parse on the reader thread outside the monitor; (b) a separate
  UI-action lane whose capacity never produces the toast; (c) hold the monitor
  only for the state mutation, running `inputDisplays`, incarnation
  projection and `publish` after `feed` returns; (d) debounce draft
  persistence in `SurfaceStore.putDraft` within the 500 ms SPEC 14.6 ceiling.
  Evidence per step: `CompanionEngineTest` ordering cases green; a new case
  that a UI action is answered while a multi-chunk frame is mid-feed.
- **C3.** Input displays per surface. `DeviceBridge` republishes
  `store.inputDisplays()` — all statefuls of all surfaces — on every accepted
  snapshot; the root `RenderNode` bakes it into `RenderCtx`, a data class
  copied into every child, so any change anywhere makes every visible node's
  context unequal and the whole tree recomposes with a map compare per node.
  Steps: (a) a per-surface `StateFlow` (or `derivedStateOf` filtered by
  `ctx.surface`) read by the stateful nodes through a `CompositionLocal`;
  (b) drop `displays` from `RenderCtx` and compare it on surface, path,
  `sourcePath` and design scope only. Evidence: `PatchRecompositionTest` case —
  a draft on surface B runs no node of surface A.
- **C4.** Identity keys for JSON values in composition. kotlinx `JsonObject`
  equality deep-walks even the same instance. Sites: `remember(node)` for the
  variant host, `remember(children)` for lazy keys and `remember(itemsJson)`
  in `LayoutNodes.kt`; `remember(node)` in `CoreComposeRenderer.kt`;
  `remember(node.toString())` in `VisualizationNodes.kt`, which serializes the
  chart node on every recomposition; `LazyRenderItem` in `Attributes.kt`
  compares its node structurally per visible row. Steps: (a) an identity key
  wrapper and reference compare in `LazyRenderItem`; (b) keep `itemOffsets`
  across revisions, invalidating only rows whose key vanished or whose
  instance changed, so the retained scroll effect no longer restarts with
  `scrollToItem(chunk, 0)`; (c) chunk keys for batchable rows that do not
  shift on an insert ahead of them; (d) the chart keyed on revision and path.
  Evidence: `PatchRecompositionTest` insert-at-head case keeps later rows'
  state; the scroll-retention instrumented test.
- **C5.** Cold start. `JetpacsProcessContainer.create` is a main-thread
  `runBlocking`: Room open, two KeyStore key checks, pairing activation, then
  `initializeProductionProjections` constructs five stores whose constructors
  each call `backing.load()`, each a full `restore(pairingId)` that reads every
  table and decrypts every outbox envelope; then `DeviceBridge` eagerly builds
  `AppCapabilities.deviceReport`, querying and labelling every launcher
  activity on the device. Steps: (a) one `DurableSnapshot` shared by the five
  constructors; (b) `deviceReport` computed only through its provider lambda,
  with `loadLabel` after the `take(32)` and an incremental byte budget; (c)
  Room and KeyStore opened off the main thread with only the bridge start
  gated on them, not `setContent`. Evidence: macrobenchmark startup before and
  after; `RoomEbpDurableStoreTest` unchanged.
- **C6.** Patch fast path in the reducer. Steps: (a) **done 2026-10-21**
  (ebp-kmp: the floor check precedes apply, materialize and admit in
  `SurfaceStore.patch`; a stale patch is timed as decode and reply alone);
  (b) document bounds along the op paths
  (depth per path, octets as the cached total adjusted by the removed and
  inserted values, full JCS only near the limit); (c) fuse `share` into the
  walk, returning the previous instance when a node's resolved members equal
  it; (d) later, re-materialize only nodes whose bindings, predicates or
  template arrays sit under an op path. Evidence: `MaterializerShareTest`,
  `DataGoldenReplayTest`, `DataOpsGoldenReplayTest` with the goldens untouched;
  M2 stage times.
- **C7.** Connection and transport. (a) First slice: `tcpNoDelay`, and
  `persistReadyConnectionChange` submitted rather than blocking. (b) Seed cached
  surfaces on `Dispatchers.Default` concurrently with the listener bind
  instead of before the first accept; start the listener before trigger and
  reminder re-arm work. (c) Serialize outbound frames off the engine monitor.
  (d) `JetpacsBridgeService.enable` idempotent in memory so rotation enqueues
  no Room write. Evidence: `WireConformanceTest` chunking cases; reconnect
  timing on device.
- **C8.** Build and gates. A `release` build type with R8 and resource
  shrinking, `androidx.profileinstaller`, a `:baselineprofile` module with the
  macrobenchmark of the measurement section. Evidence: `:app:assembleRelease`;
  numbers recorded here.
- **C9.** Durable outbox incremental writes (`queue` and `wake` actions only).
  `DurableQueue.admit` serializes every queued record to count bytes and
  `persist` encrypts and rewrites the whole outbox, then the accepted reply
  does it again. The prepared-outbox write path already exists in
  `RoomEbpDurableStore`; insert one row, delete one row, encrypt only the new
  event, keep a running byte total. Evidence: `RoomEbpDurableStoreTest` queue
  rollback cases.
- **C10.** An authoring guard for long `column` bodies with `scroll` that
  should be `lazy_column` (`RenderColumn` composes every child eagerly):
  an Elisp lint in the applet validator, or a renderer heuristic above a child
  count. Evidence: applet-mcp validator test.

### Emacs lane

- **E1.** Chrome drill cache. First slice.
- **E2.** Floor persistence. (a) First slice: skip the post-reply commit.
  (b) Reserve revisions in blocks — persist floor plus N once and hand out
  from memory; SPEC 13.1 allows gaps — so the reserve commit leaves the critical
  path for N−1 of N pushes. Owner `ebp.el` (`ebp-client--reserve-revision`,
  `ebp-client--persist-floor`). Evidence: `ebp-wire-test.el` restart-above-floor
  witnesses, plus one that a crash between reservation and send restarts above
  the block.
- **E3.** Org render memoization. `jetpacs-org-render--upgrades` runs
  `org-element-parse-buffer` per render, `jetpacs-buffer--render-region` runs
  `font-lock-ensure` per render, and inline images are re-read and re-encoded
  per render. Memoize the upgrades and the data URIs per buffer,
  `buffer-chars-modified-tick`, fold generation and limits, on the reader's
  fingerprint pattern in `jetpacs-reader-org.el`. Evidence:
  `test/jetpacs-org-render-test.el` cache-hit case; devtools build time on
  `orgro-manual.org`.
- **E4.** Agenda and habits invalidation. `ebp-org-with-cache` keys on every
  agenda buffer's modified tick, so a TODO toggle from the device invalidates
  the namespace and the deferred re-push re-runs `org-agenda` on the main
  thread. Either per-file stamps in the key, or the A lane patching the one
  row so the agenda recomputes lazily. Evidence: `ebp-org` tests.
- **E5.** Rotation. First slice.
- **E6.** Per-push trims. Fold `jetpacs-data-bound-p`, `-templated-p` and
  `-conditional-p` into the `jetpacs-shell--analyze-spec` facts (three walks
  become none); cache the namespaced drawer per surface, view id and drawer
  instance; hand the frame-size byte count to devtools instead of
  re-serializing under the profiler. Evidence: existing shell and chrome ERT.
- **E7.** `jetpacs-data-array-ops` scale. Hash-set membership instead of
  `memq`, skip the LCS when the keys are identical in order (the toggle case:
  member sets only), and lower `jetpacs-data-diff-max-elements` to about 200.
  Evidence: `test/jetpacs-data-test.el` corpus unchanged.
- **M1.** First slice.

### Apps lane

The rule for authors, as the applet catalog states it: patch with
`jetpacs-app-defer-patch` or `jetpacs-app-defer-sync`; refresh only when the
shape of the tree changed. Convert value-only handlers, keyed by what moves:

- **A1.** harp-native: `harp--on-filter`, `harp--on-page` and the other
  handlers whose change is confined to bound values; `harp--on-form` and
  `harp--on-open` keep the refresh. Start with `harp--on-filter` (first slice).
- **A2.** glasspane: `glasspane-areas--on-todo-filter`,
  `glasspane-agenda--on-area-filter`, `glasspane-agenda--on-set-mode`, list
  toggles and the inline TODO chip, as a keyed row template with
  `jetpacs-app-defer-sync` of the row array. `glasspane-areas--on-filter` is
  not a candidate as the drill stands: the selection rebuilds the chip rows,
  the declaration notes, the project cards, the file rows and the archive
  rows at once, so a filter is a shape change until those parts are bound;
  take the todo-filter chips or the agenda mode first, after checking that
  the handler moves values only.
- **A3.** grove-native: keep the planning pilot; extend to the reminders list.
- **A4.** jetpacs-poc surfaces where values move but shape does not: the
  launcher and apps hub, `jetpacs-tablist` views, Org toolbar toggles.

Evidence for every conversion: the app's ERT suite, and an M1 tap→push sample
beside the one for the refresh it replaced.

## Routing table

| Change | Owning seam | Minimum evidence |
|---|---|---|
| M1 | `emacs/jetpacs-devtools.el`, `emacs/jetpacs-shell.el` | `test/jetpacs-devtools-test.el` |
| M2 | ebp-kmp `wire/.../CompanionEngine.kt` | `CompanionEngineTest` |
| C1 | ebp-kmp `SurfaceStore.switchView`, `CompanionEngine.executeBuiltin`; `companion/app/.../DeviceBridge.kt` | Robolectric view-before-persist test |
| C2 | `FrameReader.kt`, `DeviceBridge.kt` reader loop and `dispatchExecutor`; `CompanionEngine.feedFrame`; ebp-kmp `EbpCommandActor.kt` | `CompanionEngineTest`, `FrameReaderTest` |
| C3 | `DeviceBridge.inputDisplays`; material3 `Renderer.kt`, `InputNodes.kt` | `PatchRecompositionTest` |
| C4 | material3 `LayoutNodes.kt`, `Attributes.kt`, `VisualizationNodes.kt`; ebp-compose `CoreComposeRenderer.kt` | `PatchRecompositionTest`, scroll-retention instrumented test |
| C5 | `JetpacsProcessContainer.kt`, `CompanionStores.kt`, `AppCapabilities.kt` | macrobenchmark startup |
| C6 | ebp-kmp `SurfaceStore.kt`, `Materializer.kt`, `JsonPointer.kt` | golden replays, `MaterializerShareTest` |
| C7 | `DeviceBridge.kt`, `JetpacsBridgeService` | `WireConformanceTest`, device reconnect |
| C8 | `companion/app/build.gradle.kts`, new `:baselineprofile` | `:app:assembleRelease`, benchmark report |
| C9 | ebp-kmp `DurableQueue.kt`; `RoomStoreBackings.kt`, `RoomEbpDurableStore.kt` | `RoomEbpDurableStoreTest` |
| C10 | `jetpacs-applet-mcp` validator or material3 `LayoutNodes.kt` | validator test |
| E1 | `emacs/jetpacs-chrome.el` | `test/jetpacs-chrome-test.el` |
| E2 | `ebp.el/lisp/ebp.el`, `ebp-sqlite.el` | `ebp-wire-test.el` |
| E3 | `emacs/jetpacs-org-render.el`, `jetpacs-buffer.el`, `jetpacs-hypertext.el` | `test/jetpacs-org-render-test.el` |
| E4 | `ebp-org/lisp/ebp-org.el` | ebp-org tests |
| E5 | `emacs/jetpacs-chrome.el` | chrome ERT |
| E6 | `emacs/jetpacs-shell.el`, `jetpacs-chrome.el` | shell and chrome ERT |
| E7 | `emacs/jetpacs-data.el` | `test/jetpacs-data-test.el` |
| A1–A4 | the owning app repository | the app's ERT suite plus an M1 sample |

## Completion gates

- Companion: `:ebp-kmp:jvmTest :wire:jvmTest :core:database:jvmTest
  :core:ebp-store:jvmTest :app:testDebugUnitTest` green (the TESTING.md JVM
  gate); `PatchRecompositionTest` carries the C3 and C4 cases; the C8
  macrobenchmark numbers for startup, first surface frame, drill, Back and
  patch-to-frame are recorded in this document with device and APK type.
- Emacs: targeted ERT and byte compilation per change, `test/run-tests.sh`
  for E1 and E6; devtools tap→push and build time recorded for the baseline
  workload before and after each E item.
- Invariants that no item may move: SPEC 13.2 whole-snapshot semantics and
  the complete multi-view document; SPEC 13.6 draft reconciliation; SPEC 14.4
  handler → receipt → reply; Back never fabricates an EBP action; `applied`
  means durable (C1 publishes first but still answers after the commit).

## Deliberately not taken

- Per-node subscription and node-tree patching by identity: parked behind the
  render-counter measurement (`ebp/slop-docs/A2UI-harvest.md` §8a); trees ride
  in snapshots until the numbers demand otherwise.
- Progressive streaming of a snapshot (§9 of the same harvest).
- `derivedStateOf` per bound member; node identity plus shared instances give
  the granularity without a scope object per node.
- Companion-local write-back of bound inputs into the document (the SPEC
  13.8.4 informative note): a protocol change, not a performance item.
