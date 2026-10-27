# Companion testing

Run commands below from `jetpacs/companion`. Gradle needs the configured JDK
and Android SDK; it writes only normal Gradle/build outputs plus screenshot
references when the explicit update task is used.

## Focused component gates

```sh
./gradlew :renderer:model:jvmTest :renderer:model:testAndroidHostTest
./gradlew :wire:jvmTest --tests '*SemanticsTest'
./gradlew :renderer:compose:testAndroidHostTest :renderer:compose:compileKotlinJvm
./gradlew :renderer:compose:compileAndroidDeviceTest
./gradlew :renderer:jetpacs:testAndroidHostTest :renderer:jetpacs:compileKotlinJvm
./gradlew :renderer:jetpacs:compileAndroidDeviceTest
./gradlew :renderer:jetpacs-screenshots:compileDebugScreenshotTestKotlin
./gradlew :renderer:material3:jvmTest :renderer:material3:compileAndroidDeviceTest
./gradlew :renderer:material3-screenshots:compileDebugScreenshotTestKotlin
./gradlew :renderer:compose-testing:testDebugUnitTest
./gradlew :renderer:material3:testAndroidHostTest --tests '*MaterialWidgetsGoldenReplayTest*'
./gradlew :renderer:material3:testAndroidHostTest --tests 'com.calebc42.jetpacs.material3.PatchRecompositionTest'
```

The two headless gates run under Robolectric (`@Config(sdk = [34])`, native
graphics) with no device. `:renderer:compose-testing` replays every
`widgets.golden` line through the core components. The Material golden replay
mounts the production Material renderer against `RecordingMaterialHost`
through `MaterialTestController` and proves that every `widgets.golden` and
`jetpacs-material3.golden` line renders or degrades without throwing, that
every bare descriptor line reaches the host exactly once, that text inputs
and toggles publish state before their change action, and that an
unadvertised type degrades to its children. Image nodes read
`LocalMaterialImageSource`, which the controller stubs so no socket opens.
Use the controllers for new renderer tests instead of hand-rolled inert
hosts.

`PatchRecompositionTest` counts node renders through `LocalNodeRenderObserver`
(the controller's `onNodeRender` parameter) and pins that a `surface.data`
patch, a templated-row insert, and a whole-document update with one changed
node run only the root and the changed identity path. material3 compares Json
parameters by reference since `feat(material3): skip by reference and count
node renders across a patch`; sharing comes from `Materializer.share`, so the
count fails if the store stops handing back the previous revision's
instances for untouched subtrees. The same suite pins the per-surface
display state (PLAN-responsiveness C3): a draft published for one surface
runs that surface's stateful node and nothing of another surface composed
beside it (`aDraftOnOneSurfaceRunsNoNodeOfAnother`, mounted through the
controller's multi-surface `start`). `LazyRenderItemIdentityTest` pins the
C4a contract: a lazy row's projection, and the `Ref` wrapper every
JSON-keyed `remember` in the renderer uses, compare the JSON by reference —
the same instance is the same authored value, an equal copy is a new
revision.

The model suite pins registry-derived profiles, semantic name/default/state
projection, and the no-Material dependency boundary for EBP, wire, core,
renderer model, and the Foundation renderer. `SemanticsTest` consumes the
shared EBP semantic golden witnesses and proves builtin, feature, capture, and
unknown-member admission behavior. The Compose controller tests pin input
normalization, state-before-action ordering, typed safe-admission clearing,
secret erasure, `publish_state`, distinct JCS field/editor byte budgets,
Unicode-scalar editor splices, and no-echo remote adoption. They also pin the
synchronized opening/READY/composing/stale/offline/closed lifecycle,
composition-atomic adoption, typed edit outcomes, and retained Unicode
text/selection seeding across a fresh session.
Their device test drives the same controllers through real state-based Compose
fields and IME semantics. The Material renderer's Android tests cover
the shared Compose projection as well as receiver-owned component roles and
single-click-target behavior. The Jetpacs module pins generated extension
admission, private theme derivation, singular Compose dispatch, state-before-
action ordering, local Editor projection, toolbar transforms, logical-line
gutter indexing, and the no-Material dependency boundary. Experimental Compose
Styles opt-in exists only in the two downstream design renderer modules, never
in EBP, `:renderer:model`, or `:renderer:compose`.

## Room-only composition and Android 14 floor

Run the JVM persistence and production compilation gates with:

```sh
./gradlew \
  :ebp-kmp:jvmTest \
  :wire:jvmTest \
  :core:database:jvmTest \
  :core:ebp-store:jvmTest \
  :host:jvmTest \
  :host:testAndroidHostTest \
  :app:testStandaloneDebugUnitTest \
  :app:lintStandaloneDebug \
  :app:lintStandaloneRelease \
  :app:assembleDebug \
  :app:assembleRelease
```

The Room suite includes reopen, revocation, reminder/effect, and fault-injected
queue-plus-trigger rollback coverage, and the surface record's authored tree
and data document round trip (`RoomEbpDurableStoreTest`). The desktop's core
actuals (D4) run in the same two tasks: `:core:database:jvmTest` opens the
database through `buildJetpacsDatabase(directory)`, proves the directory is
owner-only and refuses symbolic links, resolves the data directory per
operating system, and migrates a version-1 database through both
auto-migrations with `room3-testing`; `:core:ebp-store:jvmTest` provisions a
token into an in-memory and a file-backed `SecretStore`, checks the proof
handle against `EbpAuth`, tampers with every envelope part, proves the
credential file's atomic writes, and asserts the token reaches only the
credential file (`TokenContainmentTest`). `JavaKeyringSecretStoreTest` talks to
the real OS keychain and is skipped, with the reason, where none answers. The app's
`RoomSurfaceBackingTest` drives the surface backing through the memory
reference store and pins row-level persistence: an accepted update, patch,
removal or view switch writes one `surface_records` row and leaves every
other row and its accepted-at stamp untouched, a draft-clearing snapshot
commits the record and the reconciled drafts together, and the whole-catalog
replace skips rows whose content is already on disk. On a device, the engine
reports per-stage timing of every inbound `surface.update` and `surface.data`
frame (decode, apply, materialize, admit, commit, reply, listener, in
microseconds) under the `JetpacsStages` logcat tag once
`adb shell setprop log.tag.JetpacsStages DEBUG` is set and the connection
re-established; nothing is timed otherwise
(`SurfaceDataEngineTest.aStageObserverSeesEveryStageOfAnUpdateAndAPatchAndNothingOtherwise`);
since PLAN-responsiveness C2c the `listener` stage is the store reads and the
hand-off to `SurfacePresenter`, whose serial thread logs each presentation as
`present <surface> wait=… run=…` under the same tag (`SurfacePresenterTest`:
returns at once, runs in order on one thread, a failure stops nothing).
The connection's inbound half is `FrameReader`: it decodes frames on the
reader thread and the process actor runs one engine command per complete
frame, so a partial frame holds neither the actor nor the engine monitor
(`FrameReaderTest.aUiActionIsAnsweredWhileAMultiChunkFrameIsMidFeed`; the
engine's parsed-frame and decoder-fault entry points are pinned by
`CompanionEngineTest`). The actor itself has two lanes
(`EbpCommandActorTest`): the bounded process lane, and the action lane the
bridge's dispatch executor uses for what the user did — taken first by the
one worker and never saturated, so a tap or a keystroke neither waits behind
queued process work nor surfaces as an overload toast. Typed drafts
coalesce (PLAN-responsiveness C2d): the store's `draftFlushScheduler`, which
the bridge sets to a 400 ms window on the action lane, turns a burst of
keystrokes into one draft write, and the engine flushes before any event
leaves (`SurfaceStoreTest.keystrokesCoalesceIntoOneScheduledDraftWrite`,
`ActionEventTest.anEventFlushesTheScheduledDraftWriteFirst`).
The engine half of
the same gate carries the EBP §13.8 data-model suites: `DataGoldenReplayTest`
(every `ebp/goldens/data.golden` vector through the materializer),
`DataModelContractTest`, `SurfaceStoreDataTest` and `SurfaceDataEngineTest`
(`surface.data` patches, floors — a stale patch is answered before any
apply, materialize or admit runs — draft acknowledgement, the `data.model`
gate), `DataOpsGoldenReplayTest` (every `ebp/goldens/data-ops.golden`
vector, including the §13.8.4 `insert` op), and the EBP §13.8.5 template suites: `SurfaceStoreTemplateTest`
(expansion, re-expansion on patch with identity following the bound key,
nested scopes, grant and namespace rejections, restore, a view root as an
identity root) and the `item` injection case in `SurfaceDataEngineTest`.
The renderer's `TemplateSourcePathTest` (Robolectric, material3) pins the
identity-path grammar: a tap inside a templated lazy column in a scaffold
body reports the path the engine keyed the item on. The Elisp side of the
amendments is gated by `test/jetpacs-data-test.el`, which replays the same
corpus (38 vectors). Production source must contain no
`FileQueueStore`, `FileSurfaceBacking`, `FileReminderBacking`, or
`FileTriggerBacking` caller.

The Android 14 managed-device gate is:

```sh
./gradlew :app:pixel2Api34StandaloneDebugAndroidTest
```

That task installs an API 34 AOSP image and runs the production composition
smoke: it requires exactly API 34, opens the clean Room database, verifies the
ACTIVE pairing and distinct credential/payload Keystore aliases, and checks
that the `specialUse` foreground service is private. CI runs this as the
`api34-device` job with KVM enabled.

## Generic Glance widget gate

Run the renderer, Room lifecycle, policy, and app composition checks with:

```sh
./gradlew \
  :renderer:glance:testDebugUnitTest \
  :core:database:jvmTest \
  :app:testStandaloneDebugUnitTest \
  :app:assembleDebug
```

The Glance suite pins first-authored eligible size selection, conservative
empty-body projection, and the Grove Capture/Agenda visual witnesses. The Room
suite covers multiple bindings, process-death-safe token replacement, removal,
atomic overlapping host-ID restore, invalid restore rollback, READY-marker
compare-and-set, and pairing revocation. App policy tests pin exact stale
deadlines, READY-generation ownership, cold process-exit inference, earliest
host scheduling, the rolling READY watchdog, resize inputs, and the
716,800-byte marshalled RemoteViews cap. EBP's `SurfaceOccurrenceTest` and
durable-queue kill matrix cover offline admission, process death, and replay.
The API 34 composition smoke resolves the widget provider metadata and pins the
configuration/private-receiver export boundary. The full lifecycle mapping is in
`../docs/GLANCE-WIDGETS.md`.

## Screenshot references

```sh
./gradlew :renderer:jetpacs-screenshots:validateDebugScreenshotTest
./gradlew :renderer:material3-screenshots:validateDebugScreenshotTest
```

The Jetpacs gallery covers compact and expanded widths, dark mode, 1.5× font
scale, focus/hover, disabled controls, selected state, and nested content. Its
text-field gallery adds outlined, filled, error, disabled, syntax, empty secure,
focused, and RTL states. The six text-field references join the five original
component references. Six local-editor references add compact/expanded widths,
dark mode, 1.5× text, focus/read-only, syntax with logical line numbers,
toolbar/chromeless presentation, and RTL gutter placement. One synchronized
reference adds the compact offline and stale status rows. Five Phase 6
references add compact and expanded completion/documentation layouts, dark
mode, 1.5× text, RTL, authoritative syntax roles, diagnostics, eldoc, and
toolbar-command presentation. Four Tabs references cover compact and expanded
widths, dark mode, and 1.5× text. All 27 reviewed references live under
`renderer/jetpacs/src/screenshotTestDebug/reference/`.

The navigation screenshot source additionally exercises the fixed, scrollable,
navigator, and measured adaptive Tabs presentations; hierarchical Section
Navigator; RTL; large text; and an open bounded popup. New references for
those previews stay intentionally absent until their rendered outputs have
been reviewed; do not use the update task merely to make validation green.

The deterministic Material gallery covers a 3×3 window matrix (400/610/900 dp by
400/500/1000 dp), a dark 610×500 configuration, and 1.5× font scale at
400×500. Its 11 references live under
`renderer/material3/src/screenshotTestDebug/reference/`. The gallery uses
fixed strings, no clock, network, device state, animation clock, or EBP
session.

The Material catalog projection preview additionally covers its four-tab strip
at compact and expanded widths, dark mode, and 1.5× text. Those four candidate
references remain intentionally absent until their rendered outputs have been
reviewed; do not run the update task merely to make validation green.

The model and Compose unit suites also time and allocation-sample syntax and
fontification projection at 1, 4, 16, and 64 KiB after warmup. The Phase 6
baseline recorded 64 KiB medians of 657,842 ns / 952,128 allocated bytes for
local syntax and 906,675 ns / 50,352 allocated bytes for authoritative
fontification. Every 4× size step remained below the 8× time ceiling and both
64 KiB runs remained well below two seconds. These deterministic JVM checks
guard growth trends; they are not device-frame benchmarks.

Update references only after reviewing the rendered change:

```sh
./gradlew :renderer:jetpacs-screenshots:updateDebugScreenshotTest
./gradlew :renderer:material3-screenshots:updateDebugScreenshotTest
```

Reference updates are evidence of an intentional visual change, not a way to
make a validation failure disappear.

## Device semantics

With one authorized device connected:

```sh
./gradlew :renderer:compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.ebp.renderer.compose.EditingControllersInstrumentedTest

./gradlew :renderer:jetpacs:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsSemanticsTest

./gradlew :renderer:material3:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.material3.ui.JetpacsComponentsSemanticsTest

./gradlew :renderer:compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.ebp.renderer.compose.EbpSemanticsTest

./gradlew :renderer:material3:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.material3.ScopedCoreOverrideDispatchTest

./gradlew :renderer:material3:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.material3.LazyColumnPinnedTest
```

The shared editing class enters normalized text through a real
`BasicTextField` and edits an astral Unicode selection through a real editor;
it proves state-before-action ordering and exactly one scalar splice. The
Jetpacs class checks Action, Choice, Panel, Text Field, and local or
synchronized Editor
semantics, enabled/checked/error/maximum-length state, one-control/one-target behavior,
descendant preservation, autofocus, normalized text entry, exact ordinary
action dispatch after `state.changed`, safe-admission clearing, volatile
password capture/erasure, Editor save/value dispatch, toolbar edits, read-only
inertness, offline text retention, synchronized action refusal, and a single
editable Editor owner. Its Phase 6 cases additionally pin one READY editable
owner and none while offline, bounded completion visibility, stable selection
and documentation indices, lazy documentation, diagnostic error/status
semantics, occurrence-time command selection, and immediate offline tooling
removal. The first Material class checks
its receiver-owned catalog and single-choice components. A third case mounts a
Material `OutlinedTextField` beside the custom Styles host;
that is the device regression for keeping Material3's binary ABI compatible
with the Compose 1.12 Styles API. The EBP class asserts heading, pane title,
live region, error, state description, collection/item, traversal, determinate
and indeterminate progress, and custom actions in the semantics tree. Its
Foundation button dispatches one custom action through the ordinary action sink
exactly once and retains one click target.
`ScopedCoreOverrideDispatchTest` proves nearest-scope selection, canonical
Material fallback outside scope, extension-node ownership inside scope,
universal semantics on the interaction-owning overridden node, the invisible
scope boundary, and app-to-dialog admission isolation.
`LazyColumnPinnedTest` checks configured sticky geometry, fail-closed raw
members, keyed pixel retention while a live snapshot inserts earlier content
and enables pinning, and command-scroll placement below the active sticky
header.

On 2026-10-06 every class above, plus `ChromeStylesSeamTest` and the app's
`PresentedScreenInstrumentedTest`, `HarpPresentedScreenTest` and
`OrgzlyPresentedScreenTest`, passed on a Galaxy S21 Ultra (Android 15) over
wireless ADB. `SurfaceDataPatchRetentionInstrumentedTest` (EBP §13.8: a
`surface.data` re-materialization keeps a lazy column's scroll position and
shows the bound value, through the Companion's real installation) passed there
the same day, run with `am instrument` against the deployed debug Companion
rather than through `connectedDebugAndroidTest`, which would have uninstalled
it afterwards. The same class gained `aTemplatedListReExpandsAndKeepsItsScrollPosition`
for amendment #195 (a template over `/rows` patched and re-expanded with a
row inserted above the viewport), not yet run on a phone then. On
2026-10-07 `SurfaceDataPatchRetentionInstrumentedTest` (all three methods,
`aTemplatedListReExpandsAndKeepsItsScrollPosition` on a phone for the first
time) passed on the Galaxy S21 Ultra, again through `am instrument` against
the debug Companion installed with `:app:installDebug`; each
re-materialization now passes the previously shown tree as `previous`
(`Materializer.share`) and asserts `assertSame` on an untouched row before
it is shown. The whole `:renderer:material3:connectedAndroidDeviceTest`
module (16 tests: the three material3 classes above plus
`ChromeStylesSeamTest`) passed there the same day as a regression of
comparing Json parameters by reference. Two checks needed
phone-aware assertions: fixed tab widths agree
only to within one pixel when the row width is not a multiple of the tab
count, and the Harp save flow dismisses the soft keyboard before clicking
Save, because the keyboard window otherwise swallows the click. Note that
`connectedDebugAndroidTest` installs the debug-signed Companion and uninstalls
it afterwards: a Companion already on the device under another signing key
was gone, with its data, after the first run failed with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, so redeploy through
`tools/onboard-tablet.sh` once the connected runs are over.

These tests do not replace manual TalkBack/Switch Access checks on the target
tablet. Verify pane announcements, heading navigation, collection position,
expanded state, and the existing labeled swipe actions; execute one custom
action and confirm Emacs handles its ordinary action once. Restore every
accessibility setting afterward, then confirm reconnect and ordinary touch
interaction. For Text Field, also exercise IME submit, paste normalization,
selection, rotation/restore, hardware keyboard, pointer focus, error
announcement, and one secure submit whose secret never appears in the catalog
readout. For Editor, also exercise multiline selection, save, single-line
Enter, syntax, line-number scroll alignment, toolbar snippets/line operations,
read-only selection, disabled state, chromeless presentation, autofocus-once,
rotation/restore, and preservation across a compatible repaint. Keyboard/focus,
font-scale, and touch-target checks remain part of the same device envelope.

Phase 4 manual acceptance passed on the Pixel Tablet on 2026-08-28. The user
reported successful TalkBack, Switch Access, hardware-keyboard, pointer, touch,
and Editor-specific checks above; the device was left reconnected with ordinary
touch interaction healthy.

For the Phase 5 live gate, open **Jetpacs Components → Editor → Synchronized /
Live** and verify the in-memory buffer, not a local draft:

1. Type ordinary and astral text, move the caret, select in both directions,
   and save once; confirm Emacs receives each edit once and the save count
   increases once.
2. Start an IME composition while changing the same buffer from Emacs; confirm
   the composition is not split and the winning value/selection appears after
   reconciliation without a duplicate delta.
3. Disconnect Emacs. Confirm the displayed value remains, the offline status
   appears immediately, and text, Save, Enter, completion, and toolbar actions
   are inert with no draft or queued action.
4. Reconnect without repushing the catalog. Confirm a fresh session opens from
   the visible value and selection. Then reconnect with an explicit newer
   surface snapshot and confirm that snapshot wins.
5. Rotate during READY and offline states, remove/re-add the editor page, and
   restart the Companion; confirm session identity closes/reopens correctly
   and process death does not pretend the volatile buffer was persisted.

The Phase 5 automated, screenshot, and five connected suites passed on the
Pixel Tablet on 2026-08-28. The reviewed APK and 115-file managed Elisp tree
were redeployed, Emacs and the Companion reconnected, and the live synchronized
fixture was left visible in READY. The user subsequently reported that all five
interaction checks above passed. That report is the manual acceptance evidence;
it is not inferred from instrumentation. Phase 5 is complete.

For the Phase 6 live gate, open **Jetpacs Components → Editor**, scroll to
**Synchronized / Live**, and verify the real process-volatile Emacs Lisp
fixture:

1. Put the caret after the final `jpc`. Confirm a bounded candidate list
   appears; choose one candidate once and verify exactly its insertion reaches
   the buffer without a duplicate edit or caret jump.
2. Long-press a visible candidate. Confirm its documentation appears only
   after the long press, remains scrollable and attached to that row, then
   disappears when further typing narrows the row away or replaces the offer.
3. Confirm Elisp syntax colors are present. Move the collapsed caret onto the
   seeded diagnostic and confirm its error message is announced and shown in
   preference to eldoc; move elsewhere and confirm current eldoc appears.
   Type a small and then a larger edit while a fresh Emacs batch is pending;
   text, caret, and selection must not jump.
4. Select a non-empty region and activate **Indent selection** once. Confirm
   Emacs changes exactly that occurrence-time region once. Disconnect Emacs
   and confirm candidates, documentation, diagnostics/eldoc, and command
   actions disappear or become inert while the retained editor is read-only;
   reconnect and confirm a fresh READY session restores tooling.
5. With TalkBack and then Switch Access, confirm the editor is one named
   editable control, each completion and toolbar item is a separate labeled
   action, the error is announced, documentation can be requested, and focus
   can leave the editor. Repeat candidate selection and the command with a
   hardware keyboard, pointer, and touch; rotate once and confirm ordinary
   editing and selection remain healthy. Restore all changed settings.

The Phase 6 automated gate, all 23 Jetpacs plus 11 unchanged Material
references, and all five connected suites passed on the Pixel Tablet on
2026-08-28. The reviewed APK and 115-file managed Elisp tree were redeployed,
Emacs and the Companion reconnected, and **Synchronized / Live** was left
visible in READY. Manual acceptance above remains pending and must be recorded
from the user's observations rather than inferred from instrumentation.

`espresso-core` is pinned directly to the stable
AndroidX Test 1.7/3.7 release line because Compose UI Test 1.12's older
transitive Espresso cannot initialize on Android 17/API 37.

## Desktop host

The Compose for Desktop Companion lives in `desktop/` over the shared `host/`
module ([`../docs/PLAN-desktop-companion.md`](../docs/PLAN-desktop-companion.md)).

```sh
./gradlew :desktop:test
JETPACS_DATA_DIR=$(mktemp -d) ./gradlew :desktop:run
JETPACS_DEV_PAIRING=public-known-answer JETPACS_PORT=8766 ./gradlew :desktop:run
JETPACS_DEV_PAIRING=public-known-answer ./gradlew :desktop:run --args="--transport emacs-unix"
```

`:desktop:test` is headless: `RendererLinkTest` composes the Material and
Jetpacs theme providers on the JVM and fails if a Google Compose UI jar or a
`jvmstubs` jar reaches the test classpath; `DesignFontsTest` proves the
bundled IBM Plex files load through `JetpacsJvmFonts.loader`;
`MaterialRendererTest` composes Material `RenderNode` through
`MaterialTestHost` and proves the D3 seams; `DesktopConfigTest` covers
`--port`, `--data-dir`, `JETPACS_PORT`, `JETPACS_DATA_DIR` and the exact
`JETPACS_DEV_PAIRING` literal; `DesktopPairingTest` generates a pairing on the
file floor, reopens by identity without the token, proves the token is in no
file but `credentials.json`, substitutes the development credential, and
refuses a recorded pairing whose token is gone; `DesktopCompanionTest` is the
end-to-end run without a window: a scripted Emacs over a real loopback socket
on an ephemeral port pairs with the generated token, receives a welcome that
grants no `offline.wake` and advertises exactly the app and dialog profiles,
pushes `app:m3catalog` into the presentation, accepts an `event.action` the
bridge dispatched and raises a snackbar; `CompanionWindowTest` renders the
window at 1280 by 800 through a first run, the pairing screen with the ID,
token, port, init snippet and reduced-protection notice, then the catalog
waiting at the endpoint, and writes `desktop/build/spike/pairing.png` and
`window.png`; its second run names the emacs-unix profile and socket.
`EmacsUnixDialerTest` is SPEC 5.6 from the Companion's side over a fake
daemon Emacs (`ServerSocketChannel` on a Unix socket in a private temporary
directory, the dialer's sleeps and jitter injected): the full pairing script
over the socket, a socket that appears late dialed after 750 then 1500 ms, a
binder close followed by a redial to a second READY session, a
Companion-initiated close redialed at once, an owner mismatch that is
terminal and never dialed, a mute peer closed after the first-byte deadline,
stop while sleeping and while connected, and the 1 s doubling to 15 s
schedule. The host module's `PairingSnippetTest` pins the init snippet per
profile on both targets.

`:desktop:run` opens the window and needs a display; under WSLg Skiko falls
back from OpenGL to software rendering. The first run shows the pairing
screen; paste its snippet into a desktop Emacs init, or start with
`JETPACS_DEV_PAIRING=public-known-answer` so the default `jetpacs-init.el`
values and the smoke scripts work. The Emacs-side gate, from the repository
root against a running desktop Companion:

```sh
JETPACS_PORT=8766 emacs -Q --batch -l test/smoke-desktop.el
```

It dials `JETPACS_HOST`/`JETPACS_PORT` with `JETPACS_PAIRING_ID` and
`JETPACS_PAIRING_TOKEN` (the known-answer defaults), waits for READY, pushes
the Material catalog and Glasspane (with a temporary demo Org corpus) and
exits 0 when both are `applied`; `JETPACS_SMOKE_WAIT_ACTION=1` then waits up
to a minute for a click on a catalog demo button and reports the accepted
`m3catalog.demo`. With `JETPACS_TRANSPORT=emacs-unix` (and `JETPACS_SOCKET`,
or ebp.el's default path) this Emacs binds the socket instead and the
Companion started with `--transport emacs-unix --socket PATH` dials it;
running the script a second time against the same Companion shows its SPEC
5.7 redial reach READY again:

```sh
JETPACS_DEV_PAIRING=public-known-answer JETPACS_TRANSPORT=emacs-unix \
  JETPACS_SOCKET=$XDG_RUNTIME_DIR/ebp-smoke/ebp.sock ./gradlew :desktop:run
JETPACS_TRANSPORT=emacs-unix JETPACS_SOCKET=$XDG_RUNTIME_DIR/ebp-smoke/ebp.sock \
  emacs -Q --batch -l test/smoke-desktop.el   # twice
```

The desktop module reads the wire golden from the `ebp` checkout under
`JETPACS_REPOSITORIES_ROOT`.

The multiplatform renderer modules (`:renderer:model`, `:renderer:compose`,
`:renderer:jetpacs`, `:renderer:material3`) have no `testDebugUnitTest`:
their host tests run as `testAndroidHostTest`, their JVM compile as
`compileKotlinJvm`, and their instrumented suites as
`connectedAndroidDeviceTest`. Pin `ANDROID_SERIAL` when the phone is attached
over two ADB transports, and wake it first; a Samsung window-manager crash on
activity launch otherwise fails whole suites.

## Broad gate

```sh
./gradlew :wire:jvmTest \
  :renderer:model:jvmTest :renderer:model:testAndroidHostTest \
  :renderer:compose:testAndroidHostTest :renderer:compose:compileKotlinJvm \
  :renderer:compose:compileAndroidDeviceTest \
  :renderer:jetpacs:testAndroidHostTest :renderer:jetpacs:compileKotlinJvm \
  :renderer:jetpacs:compileAndroidDeviceTest \
  :renderer:material3:testAndroidHostTest :renderer:material3:jvmTest \
  :renderer:material3:compileAndroidDeviceTest \
  :renderer:compose-testing:testDebugUnitTest \
  :app:testStandaloneDebugUnitTest \
  :app:assembleDebug \
  :renderer:jetpacs-screenshots:validateDebugScreenshotTest \
  :renderer:material3-screenshots:validateDebugScreenshotTest
```

Compilation and screenshots prove deterministic code and pixels. Behavior that
crosses persistence, reconnect, offline delivery, Android navigation, IME, or
accessibility-service boundaries still needs its focused device flow.

## Tablet deployment

After the broad and connected gates pass, return to the `jetpacs` root and
refresh both the Companion APK and managed Elisp tree through the repository's
one-command path:

```sh
cd ..
tools/onboard-tablet.sh --vault shared --emacs-home emacs --skip-pylsp SERIAL
```

Install mode builds `:app:assembleStandaloneDebug`, reinstalls it with app data
preserved, provisions the selected Emacs home, and prints the installed package
version and managed-tree audit. Pass the current serial from `adb devices -l`;
wireless ADB ports do not remain stable across reconnects.

## Bundled engine

The `bundled` product flavor (`docs/PLAN-bundled-emacs.md`) embeds GNU Emacs
as the `:emacs` process. It exists only when the pinned engine AAR is
present: `gradle/engine.lock` names the engine (`31.1+1`) and its SHA-256,
and the AAR is read from `../jetpacs-emacs-engine/dist/` beside this
checkout or from `$JETPACS_ENGINE_AAR`. Without it every `bundled*` task is
disabled and the unflavored anchors build the standalone flavor alone.

```sh
./gradlew :app:verifyBundledEngine :app:assembleBundledDebug :app:testBundledDebugUnitTest
```

The bundled variants also byte-compile the staged Elisp kit at build time
(`compileBundledDebugOnboardingKit`, `app/kit-byte-compile.el`) with the
host Emacs named by `JETPACS_HOST_EMACS` (else `emacs` on PATH), which must
be the engine's major version; a host without one skips the step with a
warning and the device loads the sources. The step reports the files it
left to source (today only `ebp-websocket.el`, which needs the `websocket`
package).

Device evidence goes through `tools/engine-spike.sh` (one adb device,
`ANDROID_SERIAL` pinned):

```sh
tools/engine-spike.sh install        # assembleBundledDebug + adb install -r
tools/engine-spike.sh launch         # the launcher activity starts both services
tools/engine-spike.sh watch 120      # JetpacsEngine, Emacs and bridge logcat lines
tools/engine-spike.sh status         # processes, engine HOME, stamps, dump, listener
tools/engine-spike.sh cpu 30         # CPU share of :emacs over 30 s (expect 0.0 %)
tools/engine-spike.sh night on       # dark theme: the event-queue hazard test
tools/engine-spike.sh kill           # kill -9 through run-as; the sticky service restarts
tools/engine-spike.sh init-el        # the provisioned init.el and install.conf
```

What a healthy first start shows: `initEmacs [libandroid-emacs.so,
--fg-daemon=jetpacs] … dump=(none, loadup will dump)`, "Dump complete"
about 1.4 s later, "Starting Emacs daemon", then the engine dialing the
Companion's SPEC 5.3 socket `files/ebp/companion.sock` (0600 in a 0700
directory; `grep companion.sock /proc/net/unix` shows the listener and the
connected pair) and `surface_records` rows for the generated pairing in the
Companion's Room. A second start names the dump and reaches the daemon in
about 2 s. No loopback TCP port is bound by the bundled flavor.

Notes: the bundled flavor's socket keeps it apart from a standalone install
and its Emacs on 8765; `run-as PKG sh -c …` is denied on the Pixel Tablet,
so probe with one direct `run-as PKG ls|cat|kill …` per step; the shell
user cannot signal the engine process, `run-as` can; the launcher activity
starts the engine even behind the keyguard.

## Restored Orgzly Foundation applet

`OrgzlyPresentedScreenTest` consumes screen JSON exported by the sibling
`orgzly-native/test/verify-applet.sh` from the actual applet builders. It checks
light/dark rendering, independent note/fold action targets, and that edited
field publication uses an ID captured by Save. It installs the production
icon resolver and Foundation overrides in the test host. Run it with:

```sh
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.companion.OrgzlyPresentedScreenTest
```

The four checks passed on the Pixel Tablet / Android 17 on 2026-09-05.
Filesystem mutation and stale-reference behavior are tested separately by the
applet's ERT suite; this fixture does not claim a live Emacs session.

## Harp synthetic renderer flow

`HarpPresentedScreenTest` loads actual Elisp-generated JSON fixtures from
`app/src/androidTest/assets/harp` through the Companion renderer installation.
It checks opaque profile selection, captured editor fields, light/dark charts,
and exact dose-action arguments. It uses an inert recording host and synthetic
records; it does not connect to a live Emacs vault or prove transport replay.

From `jetpacs-poc/`, regenerate fixtures with
`../harp-native/test/verify-applet.sh`, then copy the five corresponding `screens/*.json`
files into the test assets. Run the instrumentation class on a device after
`:app:assembleDebug :app:assembleStandaloneDebugAndroidTest`.
