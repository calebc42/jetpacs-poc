# Desktop Companion plan

Status: active. D0, the toolchain spike, D1, the multiplatform renderer
modules, and D2, the fonts, are done (2026-10-21). Every later item is open.

The outcome is a Compose for Desktop Companion host at `companion/desktop`.
It renders the same accepted EBP surfaces as `:app` against a desktop or
daemon Emacs, through the same protocol core, durable store and renderer
model. Android remains the primary target, and no item may regress it. The
ownership boundaries are defined in
[`ARCHITECTURE-POC3.md`](ARCHITECTURE-POC3.md) and
[`REPOSITORY-BOUNDARIES.md`](REPOSITORY-BOUNDARIES.md). Every new library or
adapter is checked against
[`PLATFORM-RENTAL-REGISTER.md`](PLATFORM-RENTAL-REGISTER.md) first.

The register and the repository-boundary document describe current
ownership, so their desktop rows land with the items that create that
ownership (D4 and D5), not with this plan.

## Why a desktop host is a port, not a rebuild

The protocol, durability and most rendering code already compile for the JVM.
The inventory below was taken on 2026-10-21.

| Module | Today | Desktop need |
|---|---|---|
| `:ebp-kmp`, `:wire` (ebp-kmp) | KMP with a `jvm()` target | None |
| `:core:model`, `:core:data`, `:core:navigation`, `:core:testing` | KMP with `jvm()`, no androidMain | None |
| `:core:database` | KMP with `jvm()`; `DatabaseBuilder.android.kt` is the only actual | A JVM builder (D4) |
| `:core:ebp-store` | KMP with `jvm()`; `AndroidEbpStoreCrypto.kt` is Keystore-backed | A JVM credential adapter (D4) |
| `:renderer:model`, `:renderer:compose` (ebp-compose) | Android library plugin, no `android.*` imports | KMP conversion (D1) |
| `:renderer:jetpacs` (jetpacs-components) | Android library plugin, no `android.*` imports; IBM Plex fonts through `R.font` | KMP conversion (D1) and a font seam (D2) |
| `:renderer:material3` | Android library plugin; five files use Android APIs | KMP conversion (D1) and platform seams (D3) |
| `:renderer:glance` | Android widgets | Not ported |
| `companion/app` | `DeviceBridge.kt`, `CompanionRenderer.kt`, `SurfacePresenter.kt`, `Pairing.kt` and `RoomStoreBackings.kt` have no Android imports; 21 of 34 files are Android platform integration | A desktop composition root (D5) |

The material3 seams were `BitmapFactory` decoding in `ImageLoader.kt`,
`ImageCache.kt` and `ContentNodes.kt`, `android.graphics.Paint` text in
`VisualizationNodes.kt`, dynamic color through `Build` and `LocalContext` in
`ThemeModel.kt`, `android.icu` calendars in `InputNodes.kt`, `android.os.Trace`
in `LayoutNodes.kt`, and the Android-only `LocalLifecycleOwner` alias in
`Renderer.kt`. D1 closed them; see
[`PLAN-desktop-d1-renderers.md`](PLAN-desktop-d1-renderers.md).

The EBP specification already defines this host:

- SPEC §5.2 `loopback-tcp` applies to a desktop Companion without variation.
  `DeviceBridge` listens on 127.0.0.1:8765 with plain `java.net`.
- SPEC §5.6 `emacs-unix` is the profile for a daemon Emacs serving
  shorter-lived desktop frontends. Emacs binds and the Companion dials.
  `ebp.el` implements it in `ebp-unix.el`.
- SPEC §5.8 grants no wake on desktop. A desktop Companion never launches or
  signals Emacs.
- SPEC §9.1 requires keystore-backed storage for the pairing token where the
  platform provides it, on the terms SPEC §21.5 and §23.3 set for sensitive
  data, and defines the reduced floor otherwise.

## Decisions

- **Module home.** The host is `companion/desktop`, a module in the existing
  `companion/settings.gradle.kts` build. It reuses the
  `JETPACS_REPOSITORIES_ROOT` mapping and type-safe accessors, and one build
  checks both hosts.
- **Credentials.** Token and HMAC key material live in the operating-system
  keychain from the first credential item: Secret Service on Linux, Keychain
  on macOS, Credential Manager on Windows. The SPEC §9.1 floor (a file only
  the user can read, with the reduced protection shown at pairing) is a
  fallback used only when no keychain service is reachable, and it is never
  silent.
- **Material line.** Shared renderer modules declare Google's `androidx`
  Compose, Material 3 and adaptive coordinates in common code, which Android
  resolves as before and a standalone JVM target compiles against Google's
  published stubs. Only the Companion build substitutes JetBrains' desktop
  builds, and only on JVM classpaths. The shipping Android app keeps the
  versions it was validated against.
- **Scope.** The desktop host advertises only capabilities it implements. It
  ports no widgets, Quick Settings tiles, SMS, call, Bluetooth, alarms,
  foreground service, shortcuts or Intents.

## Dependency facts

Verified against Google Maven and Maven Central on 2026-10-21, then confirmed
by the D0 build. The pins live in `companion/gradle/libs.versions.toml`.

| Artifact | Desktop status | Desktop source |
|---|---|---|
| Google `material3` 1.5.0-alpha25 | `jvmStubs` only | JetBrains `org.jetbrains.compose.material3:material3` 1.13.0-alpha02 |
| Google `adaptive`, `adaptive-layout`, `adaptive-navigation3` 1.3.0-alpha09 | `jvmStubs` only | JetBrains `org.jetbrains.compose.material3.adaptive:*` 1.3.0 |
| Google `navigation3-ui` 1.1.2 | `jvmStubs` only | JetBrains `org.jetbrains.androidx.navigation3:navigation3-ui` 1.1.2 |
| Google `navigation3-runtime` 1.1.2 | Real `desktop` variant | Google; the desktop classpath resolves 1.1.7 |
| Google `lifecycle-runtime-compose` 2.10.0 | Real `desktop` variant | Google, unchanged |
| Google `room3-runtime` 3.0.0-rc01, `sqlite-bundled` 2.7.0-rc01 | Real `jvm` variants | Google, unchanged |
| Google `material-icons-extended` 1.7.8 | Desktop variant pulls Google-built Compose UI 1.6.0 | JetBrains `org.jetbrains.compose.material:material-icons-extended` 1.7.3 |
| Compose foundation, ui, runtime | Android uses BOM 2026.08.00 (1.12.0) | JetBrains plugin `org.jetbrains.compose` 1.13.0-alpha02; 1.12.2 is the stable line |

Further facts:

- The JetBrains Material 1.13.0-alpha02 desktop jar contains every Material
  composable the renderer uses, `ContainedLoadingIndicator` and the toggle
  button variants included. Its one naming difference is that Google's
  `MenuAnchorType` alias does not exist; the renderer names
  `ExposedDropdownMenuAnchorType`, which both lines have.
- JetBrains desktop artifacts require kotlin-stdlib 2.2.20. The Companion
  builds with Kotlin 2.3.21, AGP 9.2.1 and Gradle 9.5.1.
- JetBrains `navigation3-ui` 1.1.2 requires Google `navigation3-runtime`
  1.1.7, a patch release on the Companion's 1.1 line. Gradle resolves it
  upward on the desktop classpath only, so the Android pin of 1.1.2 does not
  move. JetBrains `navigation3-ui` 1.2.0-rc01 would require runtime 1.2.0.

The D0 desktop runtime classpath resolves to these lines:

| Group | Resolved |
|---|---|
| Compose runtime | Google `androidx.compose.runtime` 1.13.0-alpha03, the only real runtime jar |
| Compose foundation, ui, animation, material, material3 | JetBrains 1.13.0-alpha02 |
| Adaptive, adaptive-layout, adaptive-navigation, adaptive-navigation3 | JetBrains 1.3.0 |
| Navigation 3 | Google runtime 1.1.7, JetBrains UI 1.1.2 |
| Lifecycle, savedstate, navigationevent | Google 2.11.0, 1.5.0 and 1.1.2 |
| Skiko | 0.153.0 |

The JetBrains coordinates for runtime, lifecycle, savedstate,
navigationevent and window-core are empty redirect jars, so no class is on
the classpath twice. Lifecycle resolves to 2.11.0 on desktop while Android
stays on 2.10.0.

## Change order

Each item ships alone with its own evidence. The Android `:app` build and its
existing gates must pass after every item.

### Spike lane

1. **D0 — Toolchain spike (done 2026-10-21).** `companion/desktop` is a
   Kotlin/JVM Compose Desktop module inside the existing build. Its
   throwaway `SpikeApp` screen renders a `WideNavigationRail`, a
   `ButtonGroup`, a `ToggleButton`, a `LoadingIndicator`, a
   `HorizontalFloatingToolbar` and a `ListDetailPaneScaffold` from the
   JetBrains builds. It pushes a shared `:core:navigation` `JetpacsNavKey`
   through `NavDisplay`, using the same `NavBackStackSerializer` construction
   as `JetpacsNavigation.kt`. It decodes the `01-hello.bin` wire golden
   through the `:wire` `FrameDecoder`. Everything compiled under Kotlin
   2.3.21, AGP 9.2.1 and Gradle 9.5.1 with the Compose Multiplatform 1.13.0-alpha02
   plugin, so no fallback to the 1.12 line was needed. The module stays and
   grows into D5. Evidence: `SpikeRenderTest` renders the screen headless
   at 1280 by 800, asserts every region, pushes the surface key and writes
   `desktop/build/spike/spike.png`; `:desktop:run` opened the window under
   WSLg; `:app:compileDebugKotlin` and `:wire:jvmTest` still pass.

### Renderer lane

2. **D1 — Multiplatform renderer modules (done 2026-10-21;** execution map:
   [`PLAN-desktop-d1-renderers.md`](PLAN-desktop-d1-renderers.md)**).** `:renderer:model`,
   `:renderer:compose`, `:renderer:jetpacs` and `:renderer:material3` move from
   the Android library plugin to `kotlin.multiplatform` plus
   `android.kmp.library` with `jvm()`, the shape of
   `companion/core/model/build.gradle.kts`. Sources move to `commonMain`;
   Google UI dependencies stay in `androidMain` and JetBrains ones go in
   `jvmMain`. Owners: ebp-compose for the first two, jetpacs-components for
   the third, this repository for Material. Evidence: each module's unit
   tests pass on both targets and `:app` assembles unchanged.
3. **D2 — Font seam (done 2026-10-21).** The jetpacs module's JVM jar
   carries the IBM Plex files from its Android res directory, and the JVM
   `PlexFonts` builds the same families as Android through a loader the host
   installs (`JetpacsJvmFonts.loader`), because turning a classpath resource
   into a font is desktop-only API that the standalone build, which compiles
   against Google's stubs, cannot name. `companion/desktop` installs the one
   platform call in `DesktopFonts`. Evidence: `DesignFontsTest` renders a
   `jetpacs.design` text naming `plex-serif` through the public renderer
   entry points and reads its text layout: the family is the three bundled
   Plex Serif resources at weights 400, 500 and 600, and without the loader
   it is the platform serif.
4. **D3 — Material platform seams.** Image decoding produces `ImageBitmap`
   through an expect/actual, visualization text draws with Compose text
   measurement, dynamic color becomes a provider that is empty on desktop,
   and calendar math uses `java.time` or `kotlinx-datetime`. The SSRF, size
   and format guards in `ImageLoader.kt` stay in common code. Evidence: the
   Material renderer tests run on `jvmTest` and the Android screenshot suite
   is unchanged.

### Core lane

5. **D4 — JVM core actuals.** A `DatabaseBuilder.jvm.kt` opens Room 3 with the
   bundled SQLite driver at a per-user data path. A keychain-backed credential
   adapter meets SPEC §9.1 on Linux, macOS and Windows, with the surfaced floor
   as its only fallback. The desktop rows enter the rental register here.
   Evidence: `:core:database:jvmTest` reopen and migration cases, and a
   credential test proving token bytes never reach Room or logs.

### Host lane

6. **D5 — Desktop composition root.** `main()` builds the process container,
   `CompanionStores`, `DeviceBridge` and the presentation flows that
   `JetpacsApplication` owns on Android, and hosts the navigation host, pie
   menu, dialog and confirmation overlays in a window. Window size feeds
   `bridge.windowChanged`. The desktop rows enter
   `REPOSITORY-BOUNDARIES.md` here. Evidence: desktop Emacs with `ebp.el`
   dials 127.0.0.1:8765, pairs, receives a Glasspane surface and a Material
   catalog page, and one action round-trips.
7. **D6 — `emacs-unix` dialer.** The Companion dials a daemon Emacs socket
   through the JDK's `UnixDomainSocketAddress` under SPEC §5.6, reusing the
   same engine. The ebp-java host tier's dialer is the porting reference.
   `loopback-tcp` stays the default. Evidence: pairing and a surface push over
   the socket against `ebp-unix.el`.
8. **D7 — Desktop capability report.** A desktop device report advertises
   only implemented capabilities, such as clipboard, `state.get`,
   `trigger.fire`, `manual` and `time` triggers, and tray notifications if
   they are built. Everything else is reported unavailable. Evidence: an
   Emacs-side negotiation test shows the reduced set and that no Android-only
   trigger type is accepted.
9. **D8 — Packaging and tests.** Compose Desktop native distributions for deb,
   rpm, dmg and msi. JVM variants of the headless controllers in
   `:renderer:compose-testing` and `:renderer:material3-testing`, and the
   golden-corpus loader. The desktop command matrix enters
   [`../companion/TESTING.md`](../companion/TESTING.md). Evidence:
   `:desktop:packageDistributionForCurrentOS` produces an installable artifact
   and the golden corpus passes on `jvmTest`.

## Routing table

| Change | Owning seam | Minimum evidence |
|---|---|---|
| D0 toolchain | `companion/desktop`, `companion/gradle/libs.versions.toml` | Headless render test, `:desktop:run`, `:app` compile |
| D1 renderer conversion | ebp-compose, jetpacs-components, `companion/renderer/material3` build files | Unit tests on both targets, `:app` assembly |
| D2 fonts | jetpacs-components `renderer/jetpacs` | JVM design-fixture render |
| D3 Material seams | `companion/renderer/material3` | `jvmTest` plus unchanged Android screenshots |
| D4 database and credentials | `:core:database`, `:core:ebp-store`, rental register | Reopen, migration and credential tests on the JVM |
| D5 composition root | `companion/desktop`, `REPOSITORY-BOUNDARIES.md` | Desktop Emacs pairing, surface push and action round-trip |
| D6 `emacs-unix` | `companion/desktop` transport adapter | Pairing and push against `ebp-unix.el` |
| D7 capability report | `companion/desktop` | Emacs-side negotiation test |
| D8 packaging | `companion/desktop`, testing modules, `companion/TESTING.md` | Installable artifact and JVM golden corpus |

## Completion gates

The desktop host is conforming only while all applicable gates hold:

- `:ebp-kmp`, `:wire` and `:core:*` gain no desktop policy;
- the Android `:app` builds and its unit, screenshot and device gates stay
  green through every item;
- renderer `commonMain` source imports neither `android.*` nor a JetBrains-only
  API;
- both hosts share one SurfaceSpec IR and one renderer model;
- the desktop host advertises only capabilities it implements;
- the desktop host never launches, wakes or signals Emacs (SPEC §5.8);
- token material lives only in the keychain, or in the SPEC §9.1 floor that
  the user was shown at pairing;
- every accepted mutation is still ordered after its Room commit.

## Deliberately not taken

- Moving the Android app to the JetBrains Compose line.
- A separate repository or a separate Gradle build for the desktop host.
- Porting Glance widgets, Quick Settings tiles, SMS, call state, Bluetooth,
  alarms or the foreground service.
- A browser or Node-hosted Companion.
- Running Emacs inside the desktop Companion process.

## Open questions

- `VisualizationNodes.kt` and `Renderer.kt` use `java.util.Calendar` for the
  month grid and the clock while the date picker used `android.icu`; for a
  Buddhist- or Japanese-calendar locale the two disagree. D3 should settle
  one calendar for the renderer.
- Compose Preview Screenshot Testing does not configure the KMP Android
  plugin, so the screenshot suites live in `renderer/jetpacs-screenshots`
  and `renderer/material3-screenshots`. If the plugin learns KMP, they can
  fold back.

- Under WSLg, Skiko cannot create an OpenGL context and falls back to
  software rendering. The window works, but frame cost there is not
  representative. Measure rendering on a native Linux, macOS or Windows host
  before drawing performance conclusions.

- JetBrains Material alphas trail Google's. `ContainedLoadingIndicator` is
  missing today, and D0 may find more gaps that D1 or D3 must route around
  without reimplementing Material.
- Whether the ebp-compose conversion in D1 waits for a stable Compose
  Multiplatform 1.13.
- How the credential adapter behaves on a headless Linux session with no
  Secret Service.
