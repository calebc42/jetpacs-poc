# PLAN — RF-1 close-out: RF-1b (renderer tests) + RF-1c (cross-implementation CI)

Expansion of PLAN-refound-2026-07-28.md §RF-1. RF-1a closed 2026-07-31 (four jobs +
the three-red gate). This doc covers the two remaining rungs, both unblocked as of
RF-2.6. Branch `rf-1`, stacked on `rf-2.6`.

The understand phase was measured, not read: two scouts built scratch copies of
`companion/` under `/tmp` and actually ran Robolectric, Compose UI tests, Roborazzi
and Paparazzi against this toolchain. Everything below with a number is a measurement.

## What the runbook got wrong (11 defects, line-verified 2026-08-01; 14 after C2)

**RF-1c's paragraph (:398-401) is describing work RF-2.6 already did.**

- `test/ebp-wire-test.el:331` — **FALSIFIED**. That line is a session-state assertion;
  the fake is at :388 (banner :334). This is the *same* stale cite RF-2.6 corrected for
  its own gate text — the RF-1c paragraph was left untouched. Five more sites carry it.
- "no such harness exists today" — **FALSIFIED**. `companion/host/` + `test/ebp-host-test.el`
  (10 tests) shipped yesterday; the same file records it DONE 170 lines below.
- "dials the host **instead of** the elisp-scripted fake" — **DRIFTED**. That is RF-2.6's
  completed gate, and "instead of" contradicts RF-2.6's ratified decision 2: the fake is
  DEMOTED, not replaced. All 55 of its deftests still run.

**RF-1b's paragraph (:390-396).**

- The deferral premise ("a framework introduced the week before the conversion churns
  every call site") — **EXPIRED**: C6, RF-2b, RF-2c and RF-2.6 are all done.
- "the string-literal fixture rule" as substitute protection — **DRIFTED**: the rule
  self-scopes to "the whole RF-1→C6 window", which closed, and it is mechanically
  unenforced.
- "SDK-36 shadow-jar risk" — **MISFILED, not merely stale**. It is not a compatibility
  risk: Robolectric 4.16.1 runs this app at SDK 36 unprompted. It is a **203.5 MiB
  download at test runtime into `~/.m2`**, which is outside everything
  `gradle/actions/setup-gradle@v4` caches. That is a CI caching task, and the offline
  pinning mitigation was verified green with `~/.m2` moved away entirely.
- "render snapshots, never goldens" — **HOLDS as a rule, UNREFLECTED**: 5 prose sites,
  0 code/dir/gitignore. Under D1 below RF-1b produces no reference images, so the rule
  stays reserved rather than applied.

**And the deferral's own justification is measurably insufficient**: neither substitute
protection catches an open P1 that a 60-line Robolectric test catches in 0.175 s.

**Three more, found while executing C2 (2026-08-01) — the list above was a hypothesis;
these are what line-verification added.**

- **The `:331` cite has NINE sites, not six.** `grep -arn 'ebp-wire-test.el:331'` over
  `docs/ .github/ test/ emacs/` returns PLAN-refound :401/:490/:542, PLAN-rf26-host:5,
  PLAN-amendment-package :64 and :122, AUDIT-plan-spec-adversarial :218 and :227, and
  this document's own line 15. (Line 15 stays as written — it names `:331` *as* the
  falsified cite.) All eight others are annotated in place.
- **The RF-2.6 correction block is itself stale.** PLAN-refound:542 says
  "`ebp-test--start-companion` is at :379 today" — and the very commit that wrote that
  sentence, `16b6c7a`, added the 9 banner/commentary lines that moved the defun to
  **:388**. It was wrong on arrival. `:331` was never right either: it was already the
  session-state assertion at `e8945b6`, before RF-2.6 touched the file.
- **PLAN-refound:539 undercounts `:host:jvmTest`.** It says "3 socket-level pins";
  `HostConformanceTest.kt` has carried **4** `@Test`s since `bd3f634` added
  `aConfigThatFailsEngineValidationIsRejectedBeforeAnySocketExists`, which is the
  review-findings commit that landed *before* the status block was written.

**One defect on the list I read differently.** Defect 8 says the `:47` dependency row
"drifted vs the prose on wording, blocker vocabulary, and ladder order". Wording and
ladder order hold — the row drops "Compose", and RF-1c executed first while the table
still lists RF-1b above it. Blocker vocabulary does *not*: the row's "RF-2b exit" and
the prose's "deferred past C6" name the same event in the two vocabularies the table
uses elsewhere, so that third clause is not a defect. The row was corrected for the
first two, plus the misfiled risk.

## Decisions (made autonomously; all reversible, all recorded)

1. **Behaviour assertions, NOT image snapshots.** The determinism worry that motivated
   the question is retired by measurement — Roborazzi PNGs were byte-identical across
   cold/warm daemons, Debian JDK 21 vs the Temurin 21 CI installs, and `LANG=C/TZ=UTC`,
   because rasterisation is CPU Skia and all 234 fonts are bundled in
   `nativeruntime-dist-compat`. So the deciding factor is diagnostic value per unit of
   maintenance, and there behaviour wins outright: the spike found an open P1 in 0.175 s
   *with a control that names its cause*, where an image diff would say only "the picture
   changed" and could not separate "expansion lost" from "the theme moved 1 dp". The
   renderer's contract here is wire→semantics (SPEC 16-17), not visual design; nothing in
   the tree pins visual design today.
   Recorded for whenever images are wanted: **Roborazzi**, not Paparazzi (Paparazzi is
   *mutually exclusive* with Robolectric in one test task — measured — and its task
   descriptions literally print "golden images", colliding with the repo's noun rule);
   component-sized captures (~8 KB) not full-screen xxhdpi (338 KB); plugin-managed
   verify, because bare-library verify **self-heals** (it overwrites the reference on
   failure, so the next run passes); and `@GraphicsMode(NATIVE)` is mandatory — the
   LEGACY default silently captures draw-op markers instead of pixels.
2. **Robolectric `4.16.1`, literal pin.** 4.15.1 cannot run at all while `targetSdk=36`
   (class-level `initializationError`), so 4.16 is a hard floor. The pin must be literal:
   maven-metadata reports *both* `<latest>` and `<release>` as `4.17-beta-2`, so any range
   lands on a beta.
3. **`testOptions { unitTests { isIncludeAndroidResources = true } }` in the same commit
   as the dependency, plus a positive `Build.VERSION.SDK_INT == 36` assertion.** Omitting
   the block does not fail — it silently runs the whole suite on **Android 6.0.1 / SDK 23**
   with no warning. That is the most dangerous trap in this rung and the assertion is what
   makes it impossible to hit unnoticed.
4. **Accept the debug-manifest consequence, and record it.** `debugImplementation(
   ui-test-manifest)` is required for Compose tests (it cannot be `testImplementation` —
   Robolectric reads the *binary* manifest from the debug variant's `.ap_`), and it injects
   an exported `androidx.activity.ComponentActivity` into the shipped debug manifest
   (+247 B). The release APK is untouched. Since the debug APK is exactly what the device
   smoke drives, this goes in the ledger and in ci.yml's exclusions comment rather than
   being discovered later.
5. **RF-1c is scoped to the CI job, not to the 48 smoke drivers.** Two documents (this
   session's own PLAN-refound:570-581 and PLAN-rf26-host.md) assign the smoke-driver
   conversion to RF-1c, and the defect hunt is right that it dwarfs the job. But the
   runbook's RF-1c is "the cross-implementation *job*", and 33 of the 48 drivers require
   `adb shell input` taps and can never be deviceless. [The 33 is wrong — measured in C3:
   **43** of the 48 are tap/hardware-bound and only 5 are deviceless-capable, of which 2
   are green today. The decision's *conclusion* is if anything strengthened.] RF-1c
   therefore delivers the job,
   the `:host:jvmTest` coverage gap, the doc corrections, and a **measured inventory**;
   the conversion of the deviceless-capable subset is re-filed as **RF-1d**. This is a
   scope correction and is called out as one.

## RF-1b — the ladder

### B1 (Fable) — the stack, with the silent-degradation trap closed

- `libs.versions.toml`: `robolectric = "4.16.1"` + the library row.
- `app/build.gradle.kts`: `testOptions { unitTests { isIncludeAndroidResources = true } }`;
  `testImplementation(platform(libs.androidx.compose.bom))` (the test configuration does
  NOT inherit the BOM), `testImplementation(libs.robolectric)`,
  `testImplementation(libs.androidx.compose.ui.test.junit4)`,
  `debugImplementation(libs.androidx.compose.ui.test.manifest)`. All Compose test
  artifacts are BOM-managed at 1.10.4 — **no new version pins beyond Robolectric**.
- NEW `RobolectricEnvironmentTest.kt`: asserts `Build.VERSION.SDK_INT == 36` and the
  package name — the pin that makes decision 3 enforceable rather than aspirational.
- Gate: `:app:testDebugUnitTest` — the 26 existing tests unaffected (measured: they are,
  including `NodeSupportPinTest`, whose `user.dir` walk was the named hazard and does not
  move), plus the new environment pin.

### B2 (Fable) — the renderer behaviour tests, including the P1 as a RED

- `RendererBehaviourTest.kt` driving the real renderer:
  `DeviceBridge(RuntimeEnvironment.getApplication()) { _, _ -> }` constructs fine under
  Robolectric, and `RenderNode(node, RenderCtx("s1", bridge))` renders a real JSON tree.
- The P1 reproduction plus its control — prepend loses expansion (RED), append keeps it
  (GREEN). The control is what turns "a test fails" into "positional slot movement is the
  cause".
- Committed RED, in a `test(app):` commit, exactly as the C5/H3a pin-before-fix pattern.

### B3 (workflow: diagnose → fix; Fable verifies) — close the P1

The audit's proposed fix (`key(cc.path)` in both child loops) was applied in the scratch
copy and **the defect survived**, so the root cause is undiagnosed and must be found, not
guessed. A diagnosis workflow fans out over the composition path
(`RenderChildren`/`RenderColumnChildren` → `RenderNode` → `RenderCollapsible`, and
wherever expansion state actually lives), proposes candidate causes with evidence, and an
adversarial pass tries to refute each. Then the fix, and B2's RED goes GREEN.

If the root cause turns out to be deeper than RF-1b should carry, the fallback is
explicit: leave the test RED-but-`@Ignore`d with the diagnosis recorded, and file the fix
as its own rung. That would be a documented partial, never a silent one.

### B4 (Fable) — ledger + CI cost

- LIBRARY-LEDGER's Robolectric row currently carries bare coordinates with **no version**
  and a Verification cell that records only the deferral rationale — which does not
  satisfy the ledger's own admission rule. Rewrite with `4.16.1`, the dated empirical
  verification (SDK 36 auto-selected; `android-all-instrumented:16-robolectric-13921718-i7`,
  213,431,622 B; 26 pre-existing tests unaffected; JDK 21 sufficient), and the
  debug-manifest consequence from decision 4.
- ci.yml: cache `~/.m2/repository` on the app job so the 203.5 MiB is paid once, not per
  run (measured: 27.5 s cold vs ~6 s warm).

## RF-1c — the ladder

### C1 (Fable) — the fifth job, and the coverage gap it closes

- NEW `loopback` job: `setup-java 21` + `setup-gradle` + `purcell/setup-emacs 30.1`,
  `./gradlew :host:fatJar`, then `EBP_HOST_LAUNCH=... test/run-tests.sh`. The jar needs
  no Android SDK and no submodule. Measured cost: ~+3.2 s on a ~49 s script.
- **`:host:jvmTest` runs in NO job today** — 4 socket-level pins with no CI coverage.
  Add it to the wire job (it is a Gradle JVM job already) or to the new one; the new one
  is the better home because it owns the host.
- ci.yml corrections forced in the same commit: the header says "the four jobs, no new
  dependencies" (both halves now false), :11-12 names RF-1b/RF-1c as later rungs, and
  the elisp job's name says "29 ERT suites" — already stale at 30 since RF-2.6.
- Gate, in RF-1a's own idiom: the job must be shown to BITE. Break the host (e.g. make
  the welcome omit a required member) on a throwaway branch, watch the loopback job go
  red while the elisp job stays green — the cross-implementation red that no existing job
  can produce — then revert.

### C2 (Opus) — the 11 runbook defects

Correct RF-1b/RF-1c's paragraphs in place with dated notes, including the `:331`→`:388`
cite at all six sites, and cross-reference the real scope that lives 170 lines away.

**Done 2026-08-01.** Nine sites, not six — see the C2 addendum above. RF-1b's paragraph
carries a four-bullet dated correction (deferral expired, fixture rule lapsed, the
SDK-36 risk misfiled with the measured numbers, the noun rule reserved not applied) and
no DONE row, because that rung is still in flight. RF-1c's paragraph is marked
SUPERSEDED with the original left standing, followed by a DONE block in the RF-2.6
idiom. The `:47` ladder row is corrected and RF-1c/RF-1d rows added.

### C3 (Fable) — the smoke inventory, and RF-1d filed

Classify all 48 `test/smoke-*.el`: deviceless-capable vs tap-requiring (33 mention
`adb shell input`/`dumpsys`), and for the capable subset record the specific blocker
(the measured one is asserting before any `accept-process-output`). File RF-1d in
PLAN-refound's ladder with this inventory as its input.

**Done — see §RF-1d below.** The parenthetical above does not survive measurement:
only **4** drivers mention `adb shell input` and **2** mention `dumpsys`. The 33 was a
grep artifact. The real split is **43 tap/hardware-requiring, 5 deviceless-capable**,
and the dominant blocker is not the `--batch` pump but the host's **8-node-type
profile**.

## RF-1d — the smoke-driver inventory (measured 2026-08-01)

Every one of the 48 `test/smoke-*.el` drivers was run once against the RF-2.6 headless
host, `emacs -Q --batch -L emacs -l test/smoke-NAME.el` from the worktree root, with a
22 s wall clock per driver and the host launched as

```
java -jar companion/host/build/libs/host-all.jar --port 8765 --kat \
  --caps capabilities,triggers,surfaces.notification,reminders.owner,\
presentation.toast,presentation.pie-menu
```

i.e. the widest cap set the host will accept. `rc` below is that run's exit status:
`0`/`1` = the driver reached its own verdict, `124` = it never concluded inside 22 s
(it is sitting in a tap window), `255` = an uncaught elisp `error`.

**Classification.** *T* = tap/hardware-required: the verdict cannot be reached without
a finger, `adb shell input`, `adb shell dumpsys`, a force-stop/relaunch, or a human
reading the screen. *D* = deviceless-capable: the driver only dials `127.0.0.1:8765`
and its verdict is entirely protocol/client state.

### The 5 deviceless-capable drivers

| Driver | `:wants` | Default `theme`+`surfaces.dialog` enough? | rc | What still stands in the way |
|---|---|---|---|---|
| `smoke-attrs` | `theme` | yes | **0** | Nothing. Green against the host today. §16.5 universal attributes on core nodes; the verdict is `applied`, which is a real protocol assertion here because attributes reject rather than degrade |
| `smoke-device` | `theme` | yes | **0** | Nothing. Green today. READY + `applied` on a core-node push — the smallest honest cross-implementation gate in the library |
| `smoke-widgets` | `theme` | yes | **0** | Green but **vacuous**. Its 7 content nodes (`rich_text`, `icon`, `badge`, `section_header`, `empty_state`, `progress`, `date_stamp`) are not in the host's profile, and SPEC 16.2 makes an unadvertised type **degrade**, not reject (`SpecValidator.kt:87`) — so `applied` cannot distinguish "rendered" from "silently dropped". Needs the host to advertise the full node set before it means anything |
| `smoke-parity` | `theme` | yes | **0** | Same vacuity, at 64× the size: `applied=64/64` over `ebp/goldens/widgets.golden`. The whole point of the driver is the §10.2/§16.2 gate, which this host cannot exercise. (RF-2.6 recorded `applied=0/0` for it; that was a cwd artifact — the driver reads `ebp/goldens/widgets.golden` by *relative* path, so from anywhere but the repo root it finds zero vectors and reports a vacuous pass) |
| `smoke-amend-129-132` | `theme`, `presentation.pie-menu` | **no** — needs `--caps presentation.pie-menu` | **1** | Half green: #132 (an invalid `pie_menu.show` answered by `log.error` with `reason="pie-menu-invalid"`) **PASSES**. #129 (the welcome reports `current_view` for a present multi-view surface) **cannot** pass — it deliberately disconnects and reads the *second* welcome, and `HostServer` gives every dial a fresh set of Memory stores, so nothing survives the reconnect. Needs a host with a per-pairing store lifetime, not a per-connection one |

**None of the five has the `--batch` pump defect.** Each pumps with
`accept-process-output` before every assertion it makes.

### The 43 tap/hardware-requiring drivers

| Driver | rc | Why it can never be deviceless |
|---|---|---|
| `smoke-a8-coldstart` | 0 ⚠ | phase2 needs the button tapped *while disconnected* (adb), then a fresh process |
| `smoke-a8-dialog-done` | 1 | orchestrator types into the field and sends KEYCODE 66 (IME Done) |
| `smoke-a8-editor` | 1 | `adb shell input text x` after focusing the field at its end |
| `smoke-capability` | 124 | device capabilities — `vibrate`, `clipboard.read`. The host grants the `capabilities` capability but its `deviceReport` carries no `caps`, so every invoke answers **1001 cap-unsupported** |
| `smoke-chrome` | 124 | adb taps the fab, opens the drawer, pulls to refresh |
| `smoke-clip` | 255 | severs the adb forward, taps a copy button, reads the device clipboard back. Also dies on `card` (unadvertised) |
| `smoke-comint` | 124 | "type into the input row and hit Enter" |
| `smoke-complete` | 255 | `adb shell input text e`, then tap the candidate row. Also dies on `editor` (unadvertised) |
| `smoke-counter` | 124 | tap the +1 button |
| `smoke-dialog` | 0 ⚠ | tap OK/Cancel on the device |
| `smoke-dialog-prompts` | 124 | four phases, each a tap or a typed answer on the tablet |
| `smoke-disabled` | 124 | the driver taps the Demote toolbar chip; PASS is that *no* `state.changed` follows |
| `smoke-durable-reminder` | 0 ⚠ | the alarm fires on-device with no session; the harness inspects `ebp-reminders.json` across a force-stop |
| `smoke-durable-trigger` | 0 ⚠ | `adb shell dumpsys battery set level 15` |
| `smoke-editor` | 124 | type on the device; Emacs's mirror updates |
| `smoke-enum` | 124 | tap Banana; PASS is a screenshot after a reorder |
| `smoke-float-arg` | 124 | tap "Echo 2.0" |
| `smoke-floor` | 124 | tap TAP ME or ping on the tablet |
| `smoke-hypertext` | 124 | tap the Reload nav icon; the device fetches an https image |
| `smoke-image` | 0 ⚠ | PASS is visual — a red square renders, the SSRF-rejected image shows the placeholder |
| `smoke-inputs` | 124 | adb drives taps/swipes on checkbox/slider/disabled button |
| `smoke-ja2` | 124 | six hardware claims, every one tap-originated |
| `smoke-ja3` | 124 | P1-TAP-ROW / P2-TAP-FAB / P3-TAP-BACK-MX |
| `smoke-ja4` | 255 | device taps run the org verbs. Also the only driver that passes **no `:wants` at all** (granted `[]`), and it dies on `scaffold` (unadvertised) |
| `smoke-ja5` | 124 | "a human performs the tap named at each mark" — eight of them |
| `smoke-ja6` | 124 | taps, long-presses, device typing, device saves |
| `smoke-layout` | 124 ⚠ | adb taps tab B, swipes the card, drag-reorders. **Would be a false green if it ever concluded**: `ok` starts `t` and nothing sets it false when a gesture simply never lands |
| `smoke-notif` | 124 | expand the notification shade and tap Snooze |
| `smoke-offline` | 0 ⚠ | tap while disconnected, then force-stop and relaunch |
| `smoke-picker` | 124 | narrow by typing *on the tablet*, then tap a candidate |
| `smoke-pie` | 124 | the pie menu is a gesture surface; PASS is what the human selects |
| `smoke-reminder-tap` | 124 | tap the fired notification |
| `smoke-results` | 124 | tap a result card, then Next |
| `smoke-sections` | 124 | long-press a section header |
| `smoke-snackbar` | 0 ⚠ | visual only |
| `smoke-syntax` | 0 ⚠ | PASS is a screenshot: keywords/strings/comments in distinct colours |
| `smoke-textinput` | 124 | type into both fields and fire IME Done; masking is visual |
| `smoke-theme` | 0 ⚠ | PASS is a screenshot of the mirrored palette |
| `smoke-theme-mirror` | 255 | TAP-TOGGLE drives `modus.toggle`. Also dies on `badge` (unadvertised) |
| `smoke-toolbar` | 124 | adb taps each toolbar chip. Also `editor` is unadvertised |
| `smoke-trigger` | 124 | `adb shell dumpsys battery set level N` |
| `smoke-views` | 124 | adb taps the `view.switch` button |
| `smoke-viz` | 124 | tap a chart point and a day cell |

⚠ = **exits 0 with no device attached.** Nine drivers (`a8-coldstart`, `dialog`,
`durable-reminder`, `durable-trigger`, `image`, `offline`, `snackbar`, `syntax`,
`theme`) report success against the host while proving nothing they claim to prove —
`smoke-dialog` sets its `done` flag from the *error* callback and exits 0 on a 1201,
`smoke-durable-trigger` exits 0 after `triggers.set` was refused 1101. Wiring any of
these into CI as-is would install a green light on a dead gate. **RF-1d must fix the
false greens before it converts anything**, because a driver that cannot fail is worse
in CI than one that cannot pass.

### What RF-1d is actually blocked on

Ranked by how much it buys:

1. **The host advertises 8 node types.** `HOST_NODE_TYPES` (`Host.kt:29-30`, wired into
   both targets by `hostProfiles()` at `:43-56`) is the Core Node Set only, with
   `features: []`, for both the `app` and `dialog` targets;
   the app advertises 39. Four drivers die outright on it with an uncaught elisp
   `error` from `jetpacs-shell--gate-spec` (`card`, `editor`, `scaffold`, `badge`), and
   every driver whose verdict is "applied" is silently vacuous because §16.2 **degrades**
   an unadvertised type instead of rejecting it. Nothing else on this list matters as
   much.
2. **`editor.sync` cannot be granted at all.** `--caps editor.sync` makes the host
   refuse to start: `limits.max_editor_bytes must be at least 65536`, and `hostLimits()`
   (`Host.kt:60-70`) declares only the nine-member limits core. Four drivers
   (`a8-editor`, `complete`, `editor`, `picker`) want it.
3. **`deviceReport` is empty by design.** No `caps` → `capability.invoke` answers 1001
   (2 drivers). No `trigger_types` → `triggers.set` answers 1101 (2 drivers). Both are
   deliberate — a headless host has no battery and no vibrator — so these drivers are
   permanently device-bound, not merely blocked.
4. **Stores are per-connection.** `HostServer` gives each dial fresh Memory stores, so
   no driver that disconnects and reconnects can observe its own prior state
   (`amend-129-132` #129).
5. **The `--batch` pump defect is real but small.** Five drivers assert before their
   first `accept-process-output` — `a8-coldstart` (:86 vs :182), `comint` (:45 vs :75),
   `hypertext` (:90 vs :129), `results` (:53 vs :75), `sections` (:48 vs :88) — but in
   every case the early assertion is a *local* Emacs check (a buffer exists, a mode
   claims the skin) that passes fine in batch. **RF-2.6's diagnosis of `smoke-results`
   was wrong**: it does pump, for up to 20 s, before its READY check, and in this run
   it reached READY and passed six checks before parking in the tap window.

**Coverage of the host's default cap pair.** 32 of the 48 drivers want only `theme`,
only `surfaces.dialog`, or both, so the host's default grants them everything they ask
for. The remaining 16 split as `editor.sync` 4, `capabilities` 2, `triggers` 2,
`surfaces.notification`+`reminders.owner` 2, `reminders.owner` 1,
`presentation.pie-menu` 2, `presentation.toast`+`surfaces.dialog` 2, and one
(`smoke-ja4`) that asks for nothing.

**Environment note.** This sweep required binding `127.0.0.1:8765`, which a live
`adb forward` held. The forward was removed for the duration and restored identically
afterwards; no driver ever reached the physical device. Any future run of this
inventory must do the same, or it measures the phone instead of the host.

## EXIT

All gates from clean: `:wire:jvmTest`, `:host:jvmTest`, `:app:testDebugUnitTest` +
`assembleDebug`, `validate.py`, `run-tests.sh` in both modes, I1. An adversarial review
over the whole RF-1 diff (the RF-2.6 review found 4 real defects in that rung's own work,
two of them mine — assume this one has some too). PLAN-refound gains DONE rows for RF-1b
and RF-1c and the RF-1d row. Push/PR are the user's.
