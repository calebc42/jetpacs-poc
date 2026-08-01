# PLAN — RF-1 close-out: RF-1b (renderer tests) + RF-1c (cross-implementation CI)

Expansion of PLAN-refound-2026-07-28.md §RF-1. RF-1a closed 2026-07-31 (four jobs +
the three-red gate). This doc covers the two remaining rungs, both unblocked as of
RF-2.6. Branch `rf-1`, stacked on `rf-2.6`.

The understand phase was measured, not read: two scouts built scratch copies of
`companion/` under `/tmp` and actually ran Robolectric, Compose UI tests, Roborazzi
and Paparazzi against this toolchain. Everything below with a number is a measurement.

## What the runbook got wrong (11 defects, line-verified 2026-08-01)

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
   `adb shell input` taps and can never be deviceless. RF-1c therefore delivers the job,
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

### C3 (Fable) — the smoke inventory, and RF-1d filed

Classify all 48 `test/smoke-*.el`: deviceless-capable vs tap-requiring (33 mention
`adb shell input`/`dumpsys`), and for the capable subset record the specific blocker
(the measured one is asserting before any `accept-process-output`). File RF-1d in
PLAN-refound's ladder with this inventory as its input.

## EXIT

All gates from clean: `:wire:jvmTest`, `:host:jvmTest`, `:app:testDebugUnitTest` +
`assembleDebug`, `validate.py`, `run-tests.sh` in both modes, I1. An adversarial review
over the whole RF-1 diff (the RF-2.6 review found 4 real defects in that rung's own work,
two of them mine — assume this one has some too). PLAN-refound gains DONE rows for RF-1b
and RF-1c and the RF-1d row. Push/PR are the user's.
