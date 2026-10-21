# D1: multiplatform renderer modules

Status: implemented. This is the execution map for item D1 of
[`PLAN-desktop-companion.md`](PLAN-desktop-companion.md). Every step landed
on 2026-10-21; each records its commit below.

The outcome is that `:renderer:model`, `:renderer:compose`,
`:renderer:jetpacs` and `:renderer:material3` build as Kotlin Multiplatform
modules with an Android and a JVM target, so the desktop host in
`companion/desktop` can link them. D1 is structural: Android behavior does
not change, and the proof is the evidence that exists today. Android-only
code moves verbatim into `androidMain` behind small seams whose JVM side is an
honest placeholder until D2 (fonts) and D3 (Material seams).

| Module | Path | Repository |
|---|---|---|
| `:renderer:model` | `ebp-compose/renderer/model` | ebp-compose |
| `:renderer:compose` | `ebp-compose/renderer/compose` | ebp-compose |
| `:renderer:jetpacs` | `jetpacs-components/renderer/jetpacs` | jetpacs-poc |
| `:renderer:material3` | `companion/renderer/material3` | jetpacs-poc |

`:renderer:compose-testing`, `:renderer:material3-testing` and
`:renderer:glance` stay Android libraries; D8 owns the test-support modules.

## Facts the design rests on

Verified on 2026-10-21 against the Kotlin 2.3.21 Gradle plugin, the Google
Maven metadata and the JetBrains desktop jars.

- A source set whose only targets are `jvm` and `androidJvm` has no metadata
  compilation (`KotlinMetadataTargetConfigurator.isMetadataCompilationSupported`
  returns false). Its code compiles only inside the two platform
  compilations, so it may use JDK APIs, it sees the jvm-only `:wire` module
  that 23 renderer files import, and its Compose dependencies resolve per
  platform.
- Google's foundation, ui and animation 1.12.0, material3 1.5.0-alpha25 and
  adaptive 1.3.0-alpha09 publish an `android` variant and a `jvmStubs` variant
  that carries the whole common API, the Styles API included. Google's
  runtime has a real desktop jar. So `commonMain` keeps the Google
  coordinates and the Compose BOM: Android resolves exactly as today, and a
  standalone build compiles the JVM target against stubs.
- Running on desktop needs the JetBrains jars at link time. Only the
  Companion build hosts the desktop, so only it substitutes JetBrains
  coordinates, and only on JVM classpaths.
- Every androidx import in the three Compose modules resolves in the
  JetBrains 1.13.0-alpha02 desktop jars except three: `MenuAnchorType` is a
  Google typealias for `ExposedDropdownMenuAnchorType`, which both lines
  have; `dynamicDarkColorScheme`/`dynamicLightColorScheme` and `LocalContext`
  are Android-only by intent. `Renderer.kt` also names the Android-only
  `androidx.compose.ui.platform.LocalLifecycleOwner` in full; the
  multiplatform `androidx.lifecycle.compose.LocalLifecycleOwner` is what it
  delegates to on both lines. `ContainedLoadingIndicator` and
  `TonalToggleButton` exist on the JetBrains line.
- The Compose Preview Screenshot Testing plugin (alpha15 and alpha16) acts
  only on the classic Android plugins. It never configures a
  `com.android.kotlin.multiplatform.library` module.
- The KMP Android DSL on AGP 9.2.1 opts into everything explicitly:
  `androidResources { enable = true }`, `withHostTestBuilder {}.configure {
  isIncludeAndroidResources = true }`, `withDeviceTestBuilder {
  sourceSetTreeName = "test" }`. Source sets are `androidHostTest` and
  `androidDeviceTest`; tasks are `testAndroidHostTest`,
  `compileAndroidDeviceTest` and `connectedAndroidDeviceTest`. There is no
  `debugImplementation`, `buildFeatures` or `testOptions`. The precedent is
  `companion/core/database/build.gradle.kts`.

## Decisions

- **Google coordinates in common code, JetBrains on desktop classpaths.**
  `companion/build.gradle.kts` owns one dependency-substitution rule that
  runs at resolution time, reads the configuration's
  `org.jetbrains.kotlin.platform.type` attribute, and only when it is `jvm`
  maps `androidx.compose.{animation,foundation,ui,material}` to
  `org.jetbrains.compose.*`, `androidx.compose.material3` and
  `androidx.compose.material3.adaptive` to their JetBrains groups, and the
  `material-icons-*` modules to JetBrains' icons. `androidx.compose.runtime`
  is never substituted. Android and metadata configurations are untouched.
  The ebp-compose and jetpacs-components builds learn nothing about
  JetBrains; their standalone JVM target compiles against Google stubs and
  must not run Compose on `jvmTest`.
- **Screenshot suites move to thin Android libraries.**
  `jetpacs-components/renderer/jetpacs-screenshots` and
  `companion/renderer/material3-screenshots` apply the screenshot plugin with
  the same threshold, depend on the KMP module, and receive the test file and
  every reference image unchanged. Reference paths are keyed by package and
  file, not by module, so the 57 and 11 images keep validating.
- **Opt-in API for the screenshot fixtures.** The 17 Jetpacs internals the
  screenshot test uses become public behind
  `@RequiresOptIn InternalJetpacsRendererApi`. Only the jetpacs module and
  its screenshot module opt in.
- **Seams keep the Android side verbatim.** Each `expect` names the item that
  replaces its JVM placeholder.

| Seam | Replaces | JVM side in D1 |
|---|---|---|
| `ImageLoader.kt`, `ImageCache.kt` and `LocalMaterialImageSource` move whole to `androidMain`; `RenderImage` calls `expect fun rememberNodeImage(url): ImageBitmap?` | `ContentNodes.kt` Bitmap state | `null`, the placeholder renders (D3) |
| `expect fun platformDynamicColorScheme(dark): ColorScheme?` | `ThemeModel.kt` dynamic color | `null` |
| `parseIsoDateUtc`, `isoDateFromUtcMillis`, `utcCalendarDayOfWeek`, `utcYear` | `InputNodes.kt` `android.icu` | `GregorianCalendar` in UTC, same lenient semantics |
| `expect fun DrawScope.drawCanvasText(...)` | `VisualizationNodes.kt` `android.graphics.Paint` | no-op (D3) |
| `beginTraceSection`, `endTraceSection` | `LayoutNodes.kt` `android.os.Trace` | no-op |
| `expect object PlexFonts { sans; serif; mono }` | `JetpacsDesignStyleAdapter.kt` `R.font` | the default sans, serif and monospace families; D2 then ships the files in the JVM jar and builds the families through the host's `JetpacsJvmFonts.loader` |

The Android app and `MaterialTestController` keep compiling unchanged
because `ImageLoader`, `ImageCache` and `LocalMaterialImageSource` keep their
names in `androidMain`.

D3 (jetpacs-poc, 2026-10-21) closed the image, canvas-text and calendar seams:
the loader and cache are common code on `ImageBitmap` with only the decode
platform-specific, the canvas text measures with a `TextMeasurer` on the
desktop, and the calendar functions are common Gregorian code. The dynamic
color and trace seams stay, as does `PlexFonts` under D2's loader.

## Build shape

Every module becomes `kotlin { jvmToolchain(21); jvm(); android { namespace;
compileSdk = 37; minSdk = 34; … } }` and drops `compileOptions` and
`buildFeatures`. Sources move from `src/main` to `src/commonMain`, host tests
from `src/test` to `src/androidHostTest` (model: `src/commonTest`, so its
plain JUnit tests also run on `jvmTest`), device tests from `src/androidTest`
to `src/androidDeviceTest` with their manifests, and the IBM Plex fonts from
`src/main/res` to `src/androidMain/res`. Dependencies that only Android uses
move to `androidMain` so the Android classpath stays identical:
`activity-compose`, `kotlinx-coroutines-android` and `ui-tooling-preview` in
material3. The former `debugImplementation(ui-test-manifest)` goes to the
test source sets. Jetpacs and material3 enable Android resources and
host-test resources for Robolectric. Jetpacs adds `-Xexpect-actual-classes`.

## Steps

E is `ebp-compose`, C is `jetpacs-components`, P is `companion`, R is this
repository. Every commit leaves all three builds green.

1. **S0 — Baseline (done).** Record both repositories' status, the `:app` debug and
   release runtime classpaths, each module's host-test count, both screenshot
   validations (57 pass; 11 pass plus the four `MaterialCatalogProjectionTabs`
   previews whose references are intentionally absent) and the font count in
   the debug APK.
2. **S1 — E: `:renderer:model` (ebp-compose `daceb66`).** Moves; `tools/gen-node-members.py` output
   path; `RendererBoundaryTest` scans every `src/*Main` directory, requires
   that some exist, and guards `commonMain` against `android.`,
   `org.jetbrains.skia`, `java.awt` and `javax.swing`; README task names.
3. **S2 — E: `:renderer:compose` (ebp-compose `bdc9185`).** Moves; README.
4. **S3 — R: substitution rule and path follow-ups (`4817c99b`).** The rule;
   `jetpacs-platform-tools` workbench path; `docs/workspace/DESIGN-RUNTIME.md`;
   `companion/TESTING.md` model and compose tasks; the `AGENTS.md` broad gate
   becomes `./gradlew testDebugUnitTest testAndroidHostTest jvmTest
   :desktop:test assembleDebug`, because the current one silently skips KMP
   host tests.
5. **S4 — R: jetpacs screenshots extracted (`421a787a`)** while jetpacs is still an
   Android library. Opt-in annotation; new module; both settings files;
   TESTING.md.
6. **S5 — R: `:renderer:jetpacs` (`ce5cbab7`).** Moves with resources; `PlexFonts`;
   `tools/check-projections.sh` paths; `JetpacsRendererBoundaryTest` walks
   the new source sets and fails on zero files; README; TESTING.md.
7. **S6 — R: material3 screenshots extracted (`002d0474`).** New module; TESTING.md;
   `docs/material3/README.md`.
8. **S7 — R: `:renderer:material3` (`1bfcff86`).** Moves; the five seams; the
   `LocalLifecycleOwner` and `ExposedDropdownMenuAnchorType` swaps; paths in
   `tools/check-material3-projections.sh`, `NodeSupportPinTest`,
   `MaterialTypedReadsTest`, `VariantHostRenderTest`,
   `test/jetpacs-icon-lint-test.el`, the two lookup-table generators;
   `docs/material3/README.md`; TESTING.md.
9. **S8 — R: desktop links the renderers (this commit).** `companion/desktop` depends on
   `:renderer:material3` and `:renderer:jetpacs`; `SpikeRenderTest` composes
   `EbpTheme` and `ProvideJetpacsTheme` around a text and asserts a pushed
   primary color reaches the Material scheme, and guards the test classpath
   against any `jvmstubs` jar or Google Compose UI jar. The desktop plan's D1
   entry, dependency facts and open questions are corrected; TESTING.md
   gains the new task names.

Historical verification logs in `LOCAL-REPOSITORY-LAYOUT.md`,
`COMPONENTS-AUTOMATIONS-CONSOLIDATION.md` and `material3/MIGRATION.md` keep
the task names they recorded.

## What the run found

- The first device run of a suite with the phone's screen off lost tests
  to a Samsung window-manager null-task crash on activity launch; waking
  the phone and returning to the launcher first clears it. With the phone
  attached over two ADB transports, AGP installs on both and the concurrent
  runs crash each other's instrumentation; pin `ANDROID_SERIAL`.
- Robolectric host tests and the `androidDeviceTest` manifest both work
  under the KMP Android plugin; neither fallback below was needed.
- The signature-drift risk materialised in `InputNodes.kt`: JetBrains'
  Material 1.13.0-alpha02 lacks `TonalToggleButton`, the square and round
  toggle shapes and the expressive checkable `DropdownMenuItem`, orders
  `ToggleButton`'s parameters differently, names the slider state
  constructors' range differently and exposes the dropdown popup as an
  extension rather than a scope member. Four seams in
  `MaterialLineSeams.kt` cover those regions: `ToggleButtonVariant`,
  `CheckableMenuItem`, the dropdown's `OptionsMenu`, and `RenderSlider` as
  a whole. Their Android actuals are the original code verbatim; their JVM
  actuals are placeholders on JetBrains' API that D3 replaces, and the
  seams fold back when the two lines converge. D3 then returned the toggle
  and slider bodies to common code and narrowed the seams to the calls
  that differ: `TonalToggle`, `toggleShape`, `sliderState`,
  `rangeSliderState`, the three `State*Slider` forwarders with a range's
  `activeStart`, `activeEnd` and `range`, `CheckableMenuItem`, and
  `OptionsMenu`.
- The app's debug runtime classpath differs from the baseline in one line:
  the test-manifest library no longer reaches the app through
  `:renderer:compose`. The release classpath is identical.
- `docs/material3/lookup-tables/MODIFIERS-REFERENCE.org` is stale against
  the October contract and `Attributes.kt`; that drift predates D1 and is
  left for its own change.

## Risks

| Risk | Detection | Fallback |
|---|---|---|
| Robolectric cannot resolve `ComponentActivity` in a KMP host-test manifest | The jetpacs golden replay or a material3 Robolectric test fails to launch | A `src/androidHostTest/AndroidManifest.xml`; failing that, a rule that registers the activity on the shadow package manager before the compose rule |
| The `androidDeviceTest` manifest is ignored | An `aapt2` dump of the device-test APK lacks the host activity | A small Android library holding the host activities, used only by `androidDeviceTest` |
| Fonts or `R.font` change | `compileAndroidMain`; the APK font count; jetpacs screenshot diffs | Check `androidResources.enable` and the namespace |
| Signature drift between the Google and JetBrains lines beyond imports | The Companion's `compileKotlinJvm` | A local seam whose Android side stays verbatim, logged for D3 |
| Variant ambiguity when a host test depends on an Android library | A resolution error | `localDependencySelection { selectBuildTypeFrom = listOf("debug") }` |
| JVM-target mismatch on the Android compilation | A compile error | `jvmTarget = JVM_21` on the Android target |

## Verification

Each step runs the owning build's gates, then every consumer build's. The
standalone builds need `ANDROID_HOME`; device suites run on the attached
device through `connectedAndroidDeviceTest` for the library modules only,
never `:app`'s connected tests.

- E: `python3 tools/gen-node-members.py --check`,
  `python3 test/test_renderer_extension_generator.py`, `./gradlew
  :renderer:model:jvmTest :renderer:model:testAndroidHostTest
  :renderer:compose:testAndroidHostTest :renderer:compose:compileKotlinJvm
  :renderer:compose:compileAndroidDeviceTest
  :renderer:compose-testing:testDebugUnitTest`.
- C: `tools/check-projections.sh`, `test/run-tests.sh`, `./gradlew
  :renderer:jetpacs:testAndroidHostTest :renderer:jetpacs:compileKotlinJvm
  :renderer:jetpacs:compileAndroidDeviceTest
  :renderer:jetpacs-screenshots:validateDebugScreenshotTest`.
- R: `tools/check-material3-projections.sh`, `test/material3/run-tests.sh`,
  the icon-lint ERT, the lookup-table generators with `git diff
  --exit-code`, and `jetpacs-platform-tools` `./gradlew clean test
  installDist` followed by `doctor`.
- P: `./gradlew :renderer:material3:testAndroidHostTest
  :renderer:material3:compileKotlinJvm
  :renderer:material3:compileAndroidDeviceTest
  :renderer:material3-testing:compileDebugKotlin
  :renderer:material3-screenshots:validateDebugScreenshotTest
  :renderer:glance:testDebugUnitTest :app:testDebugUnitTest
  :app:compileDebugAndroidTestKotlin :app:lintDebug :app:assembleDebug
  :app:assembleRelease :desktop:test`.
- Parity after S7: the `:app` runtime classpaths, host-test counts,
  screenshot results and APK font count equal the S0 baseline;
  `:desktop:dependencyInsight --configuration runtimeClasspath --dependency
  androidx.compose.material3` reports the substitution rule.
- Final broad gate in P: `./gradlew testDebugUnitTest testAndroidHostTest
  jvmTest :desktop:test
  :renderer:jetpacs-screenshots:validateDebugScreenshotTest
  :renderer:material3-screenshots:validateDebugScreenshotTest assembleDebug`.
