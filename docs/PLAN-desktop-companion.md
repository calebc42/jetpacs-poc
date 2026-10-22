# Desktop Companion plan

Status: active. D0, the toolchain spike, D1, the multiplatform renderer
modules, D2, the fonts, D3, the Material seams, D4, the JVM database and
credentials, and D5, the desktop composition root, are done (2026-10-21).
Every later item is open.

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
- **Shared host.** The protocol host core (`DeviceBridge`, `CompanionStores`,
  `CompanionProcess`, `CompanionRenderer`) and the window composition (the
  Nav 3 host, the screens and the overlays) live in `companion/host`, a
  Kotlin Multiplatform module both composition roots depend on. A platform
  implements exactly two seams, `CompanionHostPlatform` and `HostUiPlatform`;
  Android's implementations are the code that used to be inline, and the
  Android app's behavior, classpaths and suites are unchanged by the move.
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
4. **D3 — Material platform seams (done 2026-10-21).** The image loader
   and cache are common code typed on `ImageBitmap`; only the decode is a
   seam, BitmapFactory on Android and Skia on the desktop under the same
   limits. The canvas `text` op measures with a `TextMeasurer` on the
   desktop while Android keeps its native canvas. The renderer uses one
   Gregorian calendar by name and formats dates with `Locale.ROOT`, which
   fixed two Android defects: the picker read ISO years through the
   locale's ICU calendar, and date strings carried the locale's digits.
   Dynamic color stays Android's alone. The Material-line seams narrowed to
   the calls that differ: the tonal toggle button, the toggle shapes, the
   slider states, their finish callback and a range's active ends, and the
   checkable menu item; the toggle and slider bodies are common again, which
   also fixed the desktop discrete slider that could skip an authored value.
   Evidence: the 19 plain Material tests run on `jvmTest` beside
   `testAndroidHostTest`, `CalendarsTest` pins the calendar under Thai,
   Japanese, Arabic and Persian locales, and `MaterialRendererTest` on the
   desktop decodes and shows a PNG, keeps the placeholder for an undecodable
   payload, draws canvas text, publishes the exact authored slider value,
   composes a connected toggle, and announces a checkable item's state.

### Core lane

5. **D4 — JVM core actuals (done 2026-10-21).** `buildJetpacsDatabase(directory)`
   opens Room 3 with the bundled SQLite driver inside a private per-user
   directory (`JetpacsDataDirectory`: `JETPACS_DATA_DIR`, XDG, Application
   Support, `%LOCALAPPDATA%`), and the module's first migration test drives a
   version-1 database through both auto-migrations with `room3-testing`. A
   common `PairingCredentials` interface (provision, erase, proof provider,
   envelope codec; `PairingKeyAliases.v1` names keys identically everywhere)
   has two implementations: `AndroidKeyStorePairingCredentials` over the
   existing Keystore code, and `KeychainPairingCredentials` over a
   `SecretStore` that is the OS keychain through java-keyring when a probe
   succeeds and otherwise SPEC §9.1's floor, an owner-only `credentials.json`
   written atomically. `CredentialProtection` tells the host which one it
   got. The desktop rows are in the rental register. Evidence:
   `:core:database:jvmTest` (31: reopen, private directory, data directory,
   migration), `:core:ebp-store:jvmTest` (32: provisioning, proof handle,
   codec tampering, file floor, token containment; the live-keychain case is
   skipped with its reason on a host without a Secret Service). Not here: a
   SPEC §9.1 pairing-token and pairing-ID generator; `wire` still has none
   and the app ships a compiled-in development token, so D5's first
   prerequisite is that generator.

### Host lane

6. **D5 — Desktop composition root (done 2026-10-21).** The protocol host
   core and the window composition moved out of `:app` into `companion/host`
   behind `CompanionHostPlatform` and `HostUiPlatform` (three commits, each
   with the Android unit, lint, assembly and classpath-parity gates green).
   `companion/desktop` then became the desktop Companion: `main` builds a
   `DesktopCompanion` (Room in the private data directory,
   `KeychainPairingCredentials` over the OS keychain or the SPEC §9.1 floor,
   the one pairing, `CompanionProcess` and `DeviceBridge` over the desktop
   adapters), starts the loopback listener on `--port`/`JETPACS_PORT`
   (default 8765, shown on the pairing screen) and opens one window that
   composes the shared tree; closing it stops the bridge. The first run
   generates the pairing per SPEC §9.1 and shows the token once with the
   port, the Emacs init snippet and, on the file floor, the
   reduced-protection notice; later runs read the identity back from Room.
   `JETPACS_DEV_PAIRING=public-known-answer` substitutes the public SPEC §9.3
   credential with a window-title suffix and a banner. The desktop advertises
   `theme`, `surfaces.dialog`, `presentation.toast`, `presentation.snackbar`,
   `presentation.pie-menu`, `editor.sync` and `data.model`, only the app and
   dialog profiles, no `share.send`, and never `offline.wake`. `wire` gained
   the SPEC §9.1 generator (`EbpAuth.generatePairingId`,
   `generatePairingToken`, `encodePairingToken`), and `emacs/jetpacs-init.el`
   gained `jetpacs-host` and `jetpacs-port`. Evidence: `:desktop:test` 20
   (configuration, pairing lifecycle, a scripted Emacs over a real loopback
   socket pairing with the generated token, receiving the reduced welcome,
   pushing a surface into the presentation, accepting a dispatched
   `event.action` and raising a snackbar, and the headless window);
   `test/smoke-desktop.el` against `:desktop:run` on port 8766 with the
   development pairing: READY, `presentation.snackbar` granted, `app:m3catalog`
   and `app:glasspane` both `applied`. Not done here: the interactive
   `JETPACS_SMOKE_WAIT_ACTION` click (the action round trip is proved by
   `DesktopCompanionTest`), the keychain path on a host with a Secret Service
   (this machine has none), the `:app` instrumented suites and the
   `:core:ebp-store` device suite with the new
   `AndroidKeyStorePairingCredentialsTest` (the phone's wireless debugging
   endpoint went away mid-session; both compile), and re-pairing (a new
   identity needs a new data directory until the SPEC §9.1 revocation flow
   has a UI on either host).
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

### Distributions lane

10. **D9 — Four distributions.** The wish is one matrix: a standalone
    Android Companion on the user's own init (today's app, whose onboarding
    stages a managed Elisp kit into the user's Emacs), a standalone desktop
    Companion on the user's own init (D5 plus the `emacs-unix` socket of
    D6), a bundled desktop build that carries an Emacs per operating system
    and runs it as a headless child daemon on a Jetpacs-managed init, and a
    bundled Android build that embeds Emacs for Android in the Jetpacs APK
    on a managed init. The two bundled builds need the same first step: an
    amendment to SPEC §5.8, which today says a desktop Companion must not
    launch or signal an Emacs process, written for the wake mechanism but
    without a carve-out for a Companion-owned child. After it come the
    daemon lifecycle (start, stop, crash recovery), init isolation, and
    per-platform packaging in D8's distributions. Planned, not scheduled:
    it needs its own plan document once D5 and D6 land. Evidence: the
    amendment with its contract and goldens aligned, then one bundled
    build pairing with its own daemon on a clean machine.

## Routing table

| Change | Owning seam | Minimum evidence |
|---|---|---|
| D0 toolchain | `companion/desktop`, `companion/gradle/libs.versions.toml` | Headless render test, `:desktop:run`, `:app` compile |
| D1 renderer conversion | ebp-compose, jetpacs-components, `companion/renderer/material3` build files | Unit tests on both targets, `:app` assembly |
| D2 fonts | jetpacs-components `renderer/jetpacs` | JVM design-fixture render |
| D3 Material seams | `companion/renderer/material3` | `jvmTest` plus unchanged Android screenshots |
| D4 database and credentials | `:core:database`, `:core:ebp-store`, rental register | Reopen, migration and credential tests on the JVM |
| D5 composition root | `companion/host`, `companion/desktop`, `REPOSITORY-BOUNDARIES.md` | Desktop Emacs pairing, surface push and action round-trip (`test/smoke-desktop.el`, `DesktopCompanionTest`) |
| D6 `emacs-unix` | `companion/desktop` transport adapter | Pairing and push against `ebp-unix.el` |
| D7 capability report | `companion/desktop` | Emacs-side negotiation test |
| D8 packaging | `companion/desktop`, testing modules, `companion/TESTING.md` | Installable artifact and JVM golden corpus |
| D9 distributions | `../ebp/SPEC.md` §5.8 first, then `companion/desktop`, `companion/app` | Amendment aligned; a bundled build pairs with its own daemon |

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
- Running Emacs inside the desktop Companion process; a bundled Emacs is a
  child process, and D9's amendment comes before it.

## Open questions

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
- On a headless Linux session with no Secret Service (this WSL machine),
  java-keyring finds no backend and `openSecretStore` falls back to the
  private file within the probe timeout; the keychain path itself has not
  yet run on a desktop with a keyring. Run `JavaKeyringSecretStoreTest` on
  one, and start the desktop Companion there to see the keychain notice.
- Re-pairing on either host: the desktop shows its generated token once and
  offers no "forget this pairing"; a new identity needs a new data
  directory. The SPEC §9.1 revocation flow (fence, drain, erase the keychain
  items) needs a UI on both hosts.
- The Emacs side keeps `jetpacs-pairing-token` as a plain variable in the
  init file, short of SPEC §9.1's private-storage floor; an `auth-source`
  path belongs to `ebp.el` and `jetpacs-init.el`, outside this plan.
