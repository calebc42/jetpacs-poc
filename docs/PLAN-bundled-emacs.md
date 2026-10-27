# Bundled Emacs plan

Status: active. E0, the engine build and harvest, and E1, the headless
boot spike, are done (2026-10-26) and the spike is a go; E2, SPEC
amendment #209, E3, the bundled flavor end to end, E4, the Unix socket and
the bound-service wake, E5, the lifecycle policy, E6, the startup work, and
E7, CI, distribution and the documents, landed 2026-10-27, and the device
gates ran the same day (see "Device gates"; seven regressions found there
are fixed). Still open: the items listed under the device gates, the
engine log export, the lisp subtree trim, the first engine release and the
CI runs that depend on it, per-ABI release APKs, and the open questions at
the end. The decisions were taken by the owner on 2026-10-26.

The outcome is one APK that a person who has never used Emacs installs and
opens: the `bundled` product flavor of `companion/app` carries GNU Emacs as
an invisible engine process and a managed init, while the `standalone`
flavor stays today's Companion paired with an Emacs the user installs. This
is the bundled Android build D9 of
[`PLAN-desktop-companion.md`](PLAN-desktop-companion.md) wished for. The
ownership boundaries are those of
[`ARCHITECTURE-POC3.md`](ARCHITECTURE-POC3.md) and
[`REPOSITORY-BOUNDARIES.md`](REPOSITORY-BOUNDARIES.md); every new library
or adapter is checked against
[`PLATFORM-RENTAL-REGISTER.md`](PLATFORM-RENTAL-REGISTER.md) first.

## The constraint that shapes everything

GNU Emacs prohibits LLM-generated contributions (the `AGENTS.md` at the
root of every Emacs tree: "search and analyse, instead of generating any
code"). Therefore:

- The engine is built from the unmodified, signature-verified Emacs 31.1
  release tarball with Emacs's own `configure` and `make`, in the sibling
  repository `jetpacs-emacs-engine`. **No patch, no fork, no generated line
  in any Emacs tree.** Copying pristine Java sources, resources and the
  keep-rules file into a Jetpacs build, unmodified, is GPL redistribution.
- Every line of integration is Jetpacs-owned Kotlin, Gradle, shell and
  Elisp in Jetpacs repositories.
- If a milestone proves a change inside the Emacs tree unavoidable, work
  stops there, the exact lines and reason are recorded, and the owner
  authors the change; nothing is sent upstream.

The earlier `jetpacs-native` fork and the `ebp-java` Android hosts
implemented the inverse design (a Companion vendored into the Emacs APK);
their mechanics are ported where useful, never linked, and the fork is not
extended.

## Decisions

Owner decisions (2026-10-26):

- **Liveness.** Always-on by default with an off switch: the engine runs as
  a foreground service in its own process with one "Jetpacs is running"
  notification, as today's bridge service does. Settings can turn
  background running off; the engine then runs only while the app is
  visible, plus the SPEC §5.8 wake for background events.
- **Notes storage.** App-private by default, with export and backup. A
  settings option "Store notes in a shared folder" moves the vault to
  `/sdcard/Jetpacs` after the All-files-access prompt.
- **Packaging.** Two product flavors with distinct application IDs:
  `bundled` (`applicationIdSuffix ".bundled"`) and `standalone` (today's
  app). One codebase, flavor source sets.
- **Engine home.** The sibling repository `jetpacs-emacs-engine`; the
  Companion pins an engine identity and the AAR's SHA-256 in
  `companion/gradle/engine.lock`.

Owner decisions (2026-10-27), after the device gates:

- **Names.** The bundled flavor is "Jetpacs"; the standalone flavor stays
  "Jetpacs Companion".
- **First run.** No default applet: a fresh install opens on the hub, the
  Elisp REPL, with the Files tree beside it; applets stay a choice in Apps.
- **Wording.** The bundled app never sends its user to an Emacs of their
  own: the Companion's text and the kit's hints name Jetpacs or the notes
  engine there (`HostWording`, `jetpacs-bundled-p`).

Decisions by analysis:

- **Emacs 31.1 release.** The fork tracks master for the canvas image type,
  which a display-less engine never uses; the startup Java and `android.c`
  are identical between 31.1 and the fork's base; ebp.el's floor is 30.1.
- **Headless mode: service-less `--fg-daemon`.** A Jetpacs-owned service in
  process `:emacs` calls `EmacsNative.setEmacsParams(..., service = null,
  ...)` and `EmacsNative.initEmacs(["libandroid-emacs.so",
  "--fg-daemon=jetpacs"], dump)`, the shape of upstream's
  `EmacsNoninteractive.main1`. No `EmacsService`, no activity, no window,
  no Emacs notification, and no writer into the native event queue. The
  alternative, `EmacsService.startEmacsService` with `--fg-daemon`, is
  rejected: `EmacsService.onConfigurationChanged` enqueues dpi and
  night-mode events that only a display terminal drains, and
  `android_select` returns at once while any event is queued, so the first
  dark-mode toggle would spin Emacs at full CPU.
- **Transport.** `loopback-tcp` first (it works today), `loopback-unix`
  (SPEC §5.3) as the bundled default once E4 lands: no port collision
  between a bundled and a standalone install, no loopback exposure, and
  both processes share one UID so ebp.el's owner check passes. Until E4
  the bundled flavor listens on 8766 (`EngineFlavor.loopbackPort`, named
  in the provisioned `init.el`) so that a standalone install on the same
  device keeps SPEC §5.2's 8765 and neither Companion answers the other's
  Emacs.
- **The kit lives in the engine's HOME, not in `/assets`.** Emacs stats
  assets through the `directory-tree` index its own build produced; the
  Companion's assets are invisible to it. The provisioner copies the staged
  `jetpacs-onboarding/payload` into HOME under a stamp.
- **HOME is `<filesDir>/emacs/`**, a subdirectory, so dumps, `.emacs.d`
  and `site-lisp` stay out of the Companion's files root.

## Target architecture

```
APK com.calebc42.jetpacs.companion.bundled
├── process (main): JetpacsApplication, MainActivity, DeviceBridge,
│     JetpacsBridgeService, receivers, widgets, tiles, EngineSupervisor
└── process :emacs : JetpacsEngineService (FGS, "Jetpacs is running")
      └── thread "Emacs main thread": stderr→logcat, LANG, dump lookup,
            setEmacsParams(service = null), initEmacs(--fg-daemon=jetpacs)
            → ebp.el dials the Companion and authenticates (SPEC §9)
```

```
files/
├── emacs/                        Emacs HOME
│   ├── emacs-<fingerprint>.pdmp  first-start dump (E6: shipped, kit-preloaded)
│   ├── engine.stamp, dump.stamp  engine id + APK install time that produced them
│   └── .emacs.d/
│       ├── init.el               the managed composition-root block
│       └── jetpacs/              today's install root, layout unchanged
│           ├── emacs/ org/       kit copied from assets/jetpacs-onboarding/payload
│           ├── apps/ var/ apps.el user.el   user state, preserved
│           ├── install.conf      E3: vault=<files>/vault, home_mode=bundled
│           └── var/pairing       E3: 0600, the Emacs endpoint's credential
├── vault/org/                    app-private notes
└── ebp/companion.sock            E4: loopback-unix listener, 0600
```

`DeviceBridge` is the Companion endpoint and never writes Elisp.
`EngineProvisioner` and `EngineSupervisor` are the *engine supervisor*, a
third component that SPEC amendment #209 names: it owns the Emacs host's
init and lifecycle the way a user's init and a service manager do on a
desktop.

## Facts the plan rests on

Verified 2026-10-26 against `~/workspace/emacs-31.1` (pristine 31.1),
`~/workspace/ebp/SPEC.md` and this repository; file:line cites are in the
engine repository's README and the session plan.

- `EmacsApplication` and `EmacsService` are final, but their entry points
  are public static: `findDumpFile`, `apkFileName`, `dumpFileName`,
  `startEmacsService`, `getLibraryDirectory`; `EmacsNative.setEmacsParams`
  takes `(AssetManager, filesDir, libDir, cacheDir, dpiX, dpiY,
  scaledDensity, uiMode, classPath, EmacsService?, apiLevel)` and
  `initEmacs(argv, dumpFile)` never returns; a null service leaves
  `android_init_gui` false, sets no `LANG` and no logcat pipe; `classPath`
  is the APK whose mtime stamps every `/assets` file.
- `--fg-daemon` sets `daemon_type = 1` without fork; `dispnew.c` skips the
  Android terminal for a daemon; `startup.el` skips the initial frame when
  `(daemonp)` and forces `server-start` (a failure is `kill-emacs 1`);
  `loadup.el` dumps on Android on the first start when `(not
  noninteractive)` and continues in the same process. `-batch` exits the
  process when its script ends.
- The native event queue is written only by JNI `send*` entry points of
  `EmacsService`, `EmacsWindow`, `EmacsDesktopNotification` and the input
  connection, and drained only by the Android terminal's read hook.
- JNI hardcodes `org/gnu/emacs/...` class names, so the Java package stays
  `org.gnu.emacs` (the AAR's namespace); the applicationId is free.
- `java/install_temp` of Emacs's build holds `lib/<abi>/` and `assets/`
  with the `directory-tree` index; `EmacsConfig.java` is generated;
  `R.java` is aapt's and must not be copied. Emacs's Java references
  `R.style.EmacsStyle`, `R.drawable.emacs`,
  `R.layout.sdk8_notifications_view`, `R.xml.preferences`.
- The nested Android configure takes `ANDROID_CFLAGS` and adds a bare `-O`
  only when no `-O` is present; `ANDROID_CFLAGS=-O2` therefore builds at
  `-O2`. NDK r27c passes Emacs's own 16 KB page-size probe. A full build of
  one ABI takes about 135 s on the 24-core host.
- SPEC §5.8 already defines "Android, same application, by bound service"
  as a wake target; nothing defines an application starting Emacs at launch
  or keeping it alive; §2.3 has no term for the hosting application. §3
  invariant 8 is liveness *independence* of correctness and does not
  forbid keeping a peer alive. Transport profiles and wake are not in
  `contract.json`; `validate.py` checks none of §5.
- ebp.el already dials `loopback-unix` (`ebp--open-unix-socket`, uid owner
  check); `jetpacs--start-1` handles `loopback-tcp` and `emacs-unix` only;
  the receipts store is the builtin `sqlite-open`, so the engine is built
  with SQLite (`deps/sqlite` in the engine repository).

## Change order

Each item ships alone with its own evidence. The standalone flavor's unit,
lint, assembly and device gates stay green through every item; the gate
command is `assembleStandaloneDebug` for the existing app and
`assembleBundledDebug` where the pinned AAR is present.

1. **E0 — Engine build and harvest (done 2026-10-26).** `jetpacs-emacs-engine`:
   `versions.env`, `tools/env.sh`, `tools/fetch-upstream.sh` (sha256 and
   GPG against the GNU keyring), `tools/build-abi.sh` (fresh extraction,
   `configure`, `make all`, `make -C java install_temp`),
   `tools/harvest.sh` (lib, assets, pristine Java with the generated
   `EmacsConfig.java`, res, `proguard.conf`, reference APKs,
   `MANIFEST.json`, a 16 KB page-alignment check), `tools/package-aar.sh`
   (an AGP 9.2.1 library with namespace `org.gnu.emacs`, an empty manifest
   and consumer keep rules), `deps/sqlite`. Evidence: both ABIs build;
   `MANIFEST.json`; every `.so` 16 KB-aligned; `libemacs.so` exports
   `Java_org_gnu_emacs_EmacsNative_initEmacs`; features list SQLITE3 and
   PDUMPER; AAR size. Recorded: engine `31.1+1`, commit `03d8f4e` of the
   engine repository; arm64-v8a and x86_64 build in 137 s and 135 s on the
   24-core host; features `MODULES NOTIFY INOTIFY PDUMPER SECCOMP SQLITE3
   THREADS ZLIB`; 4,436 asset files, 182 MB uncompressed; the AAR is
   59.7 MB (sha256 `183480db…42df4`, pinned in `companion/gradle/engine.lock`).
   Two findings on the way: `ANDROID_CFLAGS=-O2` is needed or the nested
   configure builds at `-O`; and `exec/configure` receives only CC, LD, AS,
   AR and CFLAGS, so `LDFLAGS` and `LOADERFLAGS` are exported around the
   top-level configure for `libexec1.so` and `libloader.so` to come out
   16 KB-aligned (the harvest refuses otherwise).
2. **E1 — Headless boot spike on a device (done 2026-10-26, go).** `companion/app`:
   the `engine` flavor dimension, `gradle/engine.lock` and
   `verifyBundledEngine`, `src/bundled/AndroidManifest.xml`
   (`JetpacsEngineService` in `:emacs`, `hasFragileUserData`),
   `EngineFlavor` (per flavor), `engine/JetpacsEngineService.kt`,
   `engine/EngineRuntime.kt`, `engine/EngineHome.kt` (home layout and the
   first provisioner), `engine/EngineSupervisor.kt`; the application class
   gates the engine process before Room, the KeyStore or the bridge; the
   launcher activity starts the engine. Evidence on the Pixel Tablet and the
   S21 Ultra: the `:emacs` process starts with no activity; first-run dump
   time; second-start time to `after-init`; a dark-mode toggle and a
   font-scale change leave the process idle; ten idle minutes; `am kill`
   restarts it; `call-process`, `sqlite-open` and a local socket work;
   ebp.el reaches READY over loopback-tcp with the known-answer pairing;
   Room and the KeyStore are never opened in `:emacs`.
   Recorded on the Pixel Tablet (Android 17, arm64-v8a, 4 KB pages, screen
   locked throughout, the standalone Companion and its Emacs left running on
   8765; the bundled flavor listens on 8766, see Decisions):
   - both foreground services start from the launcher activity
     ("Background started FGS: Allowed … uidState: TOP"); the `:emacs`
     process holds no activity; the engine's libraries are extracted to
     `lib/arm64/` (`useLegacyPackaging`);
   - first start: `initEmacs` at 32.998 s, loadup from the APK's `.elc`,
     "Dumping fingerprint" at 33.675, "Dump complete" at 34.354 (hot
     14.3 MB, cold 4.2 MB, 18.7 MB file), "Starting Emacs daemon" at
     36.309: 3.3 s to a live daemon including the self-dump;
   - the provisioner copied the kit (5.6 MB) and wrote `init.el`;
     Jetpacs's init ran (`var/receipts.sqlite`, `org-persist`, the vault's
     `org/`), Emacs dialed 127.0.0.1:8766 and the Companion's Room holds 16
     accepted surfaces (`app:hub` at revision 4, the `jetpacs.*` apps,
     `m3catalog`, `org-mode`) with no READY loss recorded: the session is
     READY, so `call-process` (ebp.el's nonce source through the exec
     loader), `sqlite-open` and the daemon's own local server socket all
     work;
   - CPU of `:emacs` while connected: 0 ticks in 30 s; after `cmd uimode
     night yes`: 0.0 %; after `night no`: 0.1 %; after `font_scale 1.15`:
     0.0 %; restored: 0.1 % (no event-queue spin, as predicted for the
     service-less daemon);
   - `run-as … kill -9`: "Scheduling restart of crashed service … in
     1000ms", new process 1.4 s later, dump-backed start to "Starting Emacs
     daemon" in 2.2 s, session re-established on 8766;
   - ten idle minutes while connected: 24 ticks (0.24 s of CPU, 0.0 % of
     one core);
   - memory after the restart: PSS 110 MB, RSS 234 MB (native heap 35 MB);
   - the only non-engine log lines from `:emacs` are five SELinux
     `avc: denied { search }` audits from `jetpacs-files.el` probing the
     `org.gnu.emacs` and `com.termux` private directories (an E3 item) and
     two "Attempt to remove non-JNI local reference" warnings;
   - defect found and fixed: `BuildConfig.ENGINE_ID` was the string
     "null" because the Gradle script declared the engine values after the
     flavor block that read them; the stamp now carries `31.1+1`.
   Gotchas: `run-as PKG sh -c …` is denied on the tablet while direct
   `run-as PKG ls|cat|kill …` works; the shell user cannot signal the
   engine, `run-as` can; the classifier declined force-stopping the
   owner's standalone apps, which is why the bundled port exists.
3. **E2 — SPEC amendment #209, bundled deployments (done 2026-10-27, ebp commit after `9114c28`; prose only).** §2.3
   terms *bundled deployment* and *engine supervisor*; §5.8: a supervisor
   MAY start, stop, restart and keep alive the Emacs host on product
   policy, which is not a wake and changes no correctness requirement; the
   bound-service wake keeps every rule and its application-fixed target
   satisfies the "user has configured" rule by construction, so
   `offline.wake` MAY be granted; §5.1: a diagnostics screen instead of a
   pairing screen; §9.1: application-provisioned pairing, the Emacs
   endpoint's floor as an application-private 0600 file outside any
   exported subtree, debuggable builds named as reduced protection.
   Platform-neutral so the bundled desktop build shares it. Evidence:
   `validate.py` green; D9 in `PLAN-desktop-companion.md` points here.
4. **E3 — Bundled flavor end to end over loopback-tcp (done 2026-10-27).** Generated pairing
   in the KeyStore and `var/pairing` for Emacs; `install.conf` with the
   private vault; `jetpacs-credentials-file` and `home_mode=bundled` in
   `jetpacs-init.el`; the launcher seam (`EmacsIntents` per flavor); the
   reconnect notification restarts the engine; a one-screen first run;
   `ManifestSecurityTest` covers the overlay; `BundledSmokeTest` by `am
   instrument`. Evidence: a fresh install reaches the hub with nothing
   typed; an update with an unchanged engine does not re-dump.
   Design notes: the generated pairing is resolved on every Companion
   start from the Emacs copy (`var/pairing` names the id; the partition
   must be active and its key in the KeyStore), and anything else yields a
   fresh pairing, because the token cannot be read back out of the
   KeyStore. The host has no revocation flow yet, so a replaced pairing
   leaves its old partition active in Room; E5's diagnostics "Reset
   pairing" must land SPEC §9.1 revocation (fence, erase, re-provision)
   for both copies. The dump cache is keyed on the engine identity alone
   (`dump.stamp`), the kit copy on engine plus APK install time
   (`engine.stamp`).
   Recorded on the Pixel Tablet: the Companion generated pairing
   `a53ef6d2…` on first start and wrote the 73-byte 0600 Emacs copy;
   `install.conf` (format 3, `vault_mode=private`, `home_mode=bundled`)
   and `files/vault/org/` were provisioned; the engine's first start took
   3.1 s to "Starting Emacs daemon" with the self-dump, and the first
   surface on the new identity was accepted 3.9 s after `initEmacs`, 16
   surfaces in all; a reinstall of the same APK re-copied the kit
   (`engine.stamp` moved) and kept the dump and the pairing; the SELinux
   audits left are reflink `ioctl` probes of `copy-file`, a root-directory
   listing and the exec loader's own lookups, not sandbox probes.
   `jetpacs-credentials.el` (4 ERT cases, byte-compiles clean) is the
   68th foundation module and passes the delineation and layering guards;
   unit tests: standalone 43, bundled 46. Not done here: the instrumented
   `BundledSmokeTest` (the Room witness and the spike script stood in) and
   a look at the first-run screen on an unlocked device.
5. **E4 — loopback-unix and the bound-service wake (done 2026-10-27; the wake's device gate waits for E5).** `LocalSocketTransport`
   in `:app` (FILESYSTEM `LocalSocket.bind` + `LocalServerSocket(fd)`, 0700
   directory, 0600 socket, peer-UID check), a path-bearing
   `TransportStatus`, a `loopback-unix` branch in `jetpacs--start-1`,
   `EngineWake` (coalesced, once per 60 s, `bindService` from the
   application context with no extras, released at READY or 10 s),
   `offline.wake` granted. Evidence: a reminder tap with the engine dead
   wakes it within 10 s; standalone and bundled coexist on one device.
   Design notes: `LocalSocketTransport` lives in `:app` and implements the
   `:host` seam; `TransportStatus.ListeningPath` carries the path. The
   wake has two entry points: a live engine that is not READY fires
   `DeviceBridge.onWake` through the wire engine's own 60 s bound, and a
   cold sender with no engine reaches `CompanionStores.wakeRequester`
   through a stand-in `LiveSession` that loses `drop` events exactly as a
   missing session does and forwards durable `wake` admissions; the
   stand-in exists only when the flavor supplies a wake, so the standalone
   flavor is unchanged. `EngineWake` applies the 60 s and 10 s bounds over
   injected platform calls (`EngineWakeTest`). Not covered yet: trigger
   firings admitted with no engine (`TriggerFiringService` holds its own
   session slot in wire), and the device gate for the wake itself, which
   needs E5's background-running switch to leave the engine dead.
   Recorded on the Pixel Tablet: `files/ebp/companion.sock` is
   `srw-------` in a `drwx------` directory; `/proc/net/unix` lists the
   listener (flags 00010000) and the connected pair; the bundled flavor
   binds no loopback TCP port while the standalone install keeps 8765; the
   provisioned `init.el` names `jetpacs-transport 'loopback-unix` and the
   socket path; the engine reached its daemon 2.2 s after `initEmacs` from
   the dump and the generated pairing's 16 surfaces were re-accepted 4.4 s
   after `initEmacs` over the socket. `EngineWakeTest` (3 cases) pins the
   bounds; the entry suite gained the loopback-unix dial case.
6. **E5 — Lifecycle policy and product polish.** "Run in background"
   setting, notification text and "Pause", boot and update restarts, crash-
   loop guard, export/backup, the shared-folder option, a read-only
   diagnostics screen.
   First slice (2026-10-27): `EngineSupervisor` owns the policy
   (SharedPreferences `jetpacs-engine/background`, default on): on, the
   engine is the foreground service, started by the launcher activity, by
   `BootReceiver` after a reboot or an update, and by any "open Emacs"
   request; off, `MainActivity.onStart`/`onStop` bind and unbind the
   service so the process lives only while the window is visible, and the
   SPEC 5.8 wake covers background events. `JetpacsEngineService` starts
   its foreground notification only when started (a bare bind shows none),
   takes "Pause" (stop until the next launch or wake) and "Restart"
   actions, and on destroy runs upstream's shutdown shape
   (`EmacsNative.shutDownEmacs` then the process ends) so a restart is a
   fresh process. The crash-loop guard (`EngineStarts`, pure, tested)
   halts the third start inside ten minutes, writes `engine.halted`, posts
   "Jetpacs engine stopped", and a user launch clears it. The host's
   `HostUiPlatform.ExtraSettings(bridge)` slot composes the bundled
   flavor's "Engine" section in Settings: the switch, SPEC §5.1's profile
   and address, the process and session state, "Restart engine" and
   "Rebuild engine cache". The trigger-firing cold wake landed with it:
   ebp-kmp's `TriggerFiringService.coldSession` (commit `4c67813`, with a
   FiringServiceTest case authored under the `offline.wake` grant) is the
   stand-in the host sets, so a firing admitted with no engine reaches
   the platform's wake like a cold tap does. Recorded on the Pixel Tablet:
   two `run-as kill -9` in a row left no `:emacs` process, `engine.halted`
   written at the third start ("engine started 2 times in the last ten
   minutes; halting"), the "Jetpacs engine stopped" notice posted; a
   relaunch of the already-resumed launcher activity, which only yields
   `onNewIntent`, cleared the halt and the history, started a fresh engine
   and the socket pair reappeared within 14 s. Found on the way: the
   first version restarted from `onCreate`/`onStart` only, which a resumed
   singleTop activity never sees again, and counted the halted start
   against the next launch.
   Second slice (2026-10-27), decision 2: `EngineVault` keeps the vault
   mode in `files/emacs/vault.mode` (private unless moved), the
   provisioner's `install.conf` follows it, and the Engine section's
   "Notes" rows offer "Export notes" (the vault zipped through the
   system's document picker, `EngineExport`), "Allow a shared folder"
   (the All files access system page; the overlay manifest declares
   `MANAGE_EXTERNAL_STORAGE`, never requested at install) and, once
   granted, "Store notes in a shared folder" / "Keep notes private":
   `EngineSupervisor.switchVault` stops the engine, waits for its process
   to end, copies what the target vault lacks without overwriting or
   deleting (`EngineVault.mergeCopy`), records the mode and starts the
   engine again. `EngineVaultTest` pins the merge and the archive. Still
   open in E5: log export, and the device gates for the wake, for
   background-off binding and for the vault move (the tablet stays locked,
   so Settings cannot be driven from here).
7. **E6 — Startup time and size.** Shipped per-ABI dumps, a kit-preloaded
   dump through `site-lisp/site-init.el`, build-time byte-compilation,
   asset trimming before the index is rebuilt; targets ≤ 2 s tap-to-READY
   on the Pixel Tablet and ≤ 60 MB per ABI.
   First slice (2026-10-27): the bundled variants byte-compile the staged
   kit at build time (`compileBundledDebugOnboardingKit` over
   `app/kit-byte-compile.el`, one batch Emacs of the engine's major
   version, 201 files in 3.5 s, `ebp-websocket.el` left to source) into a
   second generated asset root the provisioner copies beside the sources.
   Pixel Tablet, same engine and pairing: the daemon still comes up 2.37 s
   after `initEmacs`, the first surface is accepted 3.34 s after it (was
   4.4 s with sources) and all 16 by 4.56 s. The engine repository's build
   2 (commit `17a9842`) trims the Info manuals from the assets and rebuilds
   `directory-tree` with Emacs's own tool: 4,347 asset files, 161 MB
   uncompressed, the AAR 53.7 MB (was 59.7 MB).
   Second slice (2026-10-27), the preload: `init.el` now logs two
   timestamps that bracket the kit load, which showed the dump loads in
   0.05 s and the kit load was the whole 2.1 s. The provisioner writes
   `site-lisp/site-init.el`, which Emacs's own loadup loads just before
   the first-start dump, and it preloads the kit's libraries in
   `(require 'jetpacs)` order (`EngineFiles.PRELOAD`, 69 features: ebp,
   the Jetpacs foundation and, through them, Org, url and the rest) but
   never the composition root, the credential reader, the apps or the
   package browser. Four loadup facts it works around: the bootstrap
   load-path lacks the lisp subdirectories (Org, url, net), so it adds
   them; `user-emacs-directory`, `temporary-file-directory` and
   `user-mail-address` are delayed-init variables still nil at loadup, so
   it sets each to the value startup.el will compute (the engine's HOME is
   fixed per install; `ol.el` reads the mail address at load; Org's temp
   directories otherwise land in HOME); and the pdumper refuses overlays,
   which a library evaluated in `*scratch*` had left there, so overlays go
   before the dump (loadup swallows a failing dump silently; a probe dump
   found this). The dump stamp carries the kit again, so an update dumps
   afresh. Pixel Tablet, dump-backed start: `init.el` at 0.07 s after
   `initEmacs`, kit loaded at 0.55 s, daemon at 0.66 s, first surface
   accepted at 1.25 s (was 3.34 s), all 16 by 2.17 s; the dump is 33 MB
   and the first start after an install or engine change, which builds
   it, reaches the daemon in 5.9 s. The engine also sweeps the `babel-*`
   and `org-persist-*` temporary directories and interrupted dumps that
   earlier processes left in the cache and HOME before each start (a
   killed process never runs `kill-emacs-hook`). Remaining E6 items: a
   shipped dump is no longer worth its release recipe (one 5.9 s start
   per install); the lisp subtree trim beyond `info/` stays open.
8. **E7 — CI, distribution and documentation.** Engine release workflow,
   Companion CI with the pinned AAR, release signing, the documents listed
   in the routing table.
   Landed 2026-10-27. The engine repository (commit `fa4e916`) gains
   `.github/workflows/engine.yml`: both ABIs on a clean `ubuntu-latest`
   runner (JDK 17; the NDK, platform and build-tools `versions.env` names,
   installed through `sdkmanager`; the signed tarball fetched and verified
   there) for every `engine-<version>+<build>` tag, which must match
   `versions.env`, and on manual dispatch; a tag publishes a GitHub
   release. `tools/publish-release.sh` stages release-named copies (GitHub
   rewrites `+` in asset names, so the assets spell the id with `-`; the
   bytes and the digest are the same), publishes a developer's own `dist/`
   as the release the Companion pins, and from CI fills only the assets a
   release lacks: the first asset wins, because `assets/build_info` carries
   the build time and a rebuild never reproduces a pinned digest. In the
   Companion, `tools/fetch-engine-aar.sh` downloads the pinned asset and
   checks it against `gradle/engine.lock`; `ci.yml` gains `bundled`
   (`verifyBundledEngine`, the bundled unit gate, `lintBundledDebug` and
   the debug APK, with Emacs 31.1 on the runner for the kit
   byte-compilation) and `api34-bundled` (the managed-device composition
   smoke with the x86_64 engine packaged); `release.yml` builds the release
   APKs of both flavors for a `companion-*` tag and attaches them to the
   release, signed when the keystore secrets exist. `app/build.gradle.kts`
   takes the release signing from `JETPACS_RELEASE_KEYSTORE` and its three
   companion variables, so the keystore never enters the repository and a
   build without them creates no signing config. `tools/onboard-tablet.sh
   --bundled` builds, installs and launches the bundled debug APK with no
   Termux, ssh or provisioning phase. Documents: `ARCHITECTURE-POC3.md`
   (the bundled engine and the supervisor), `REPOSITORY-BOUNDARIES.md`,
   `LOCAL-REPOSITORY-LAYOUT.md` and `AGENTS.md` (the engine repository),
   `PLATFORM-RENTAL-REGISTER.md` (five rows), `SECURITY.md` (the `:emacs`
   boundary, the socket, the credential file, `run-as`),
   `ONBOARDING-device.md` and `companion/TESTING.md` (gates, CI, signing,
   the onboarding switch). Evidence on this host: `lintBundledDebug` green
   (48 warnings, no error); `assembleRelease` with a throwaway keystore
   yields `app-standalone-release.apk` (66.5 MB) and
   `app-bundled-release.apk` (118.0 MB, both ABIs), which `apksigner verify
   --print-certs` reports as V2-signed by that key; the bundled and
   standalone unit gates pass (57 and 43 tests). The CI jobs themselves run
   green only once the engine release `engine-31.1+2` carries the pinned
   AAR (publish from the developer's `dist/` first, then push).

## Device gates (2026-10-27)

Run on the Pixel Tablet, unlocked, with the owner watching; each fix is its
own commit in `jetpacs-poc`.

- **First run.** A cleared install shows the first-run screen with
  Android's notification prompt above it ("Allow Jetpacs to send you
  notifications?"); the engine starts at launch, before Start is tapped,
  dumps in 5.4 s, reaches the daemon in 6.1 s and accepts the first
  surface within a second of it. The hub is the Elisp REPL (owner decision
  2026-08-06) and a fresh install enables no applet.
- **Found and fixed, the preload (6d329ed1, 5492ece0).** The E6 dump made
  the composition root's ten `defvar` seeds no-ops: no navigation rail or
  bottom bar, no Tools or Settings in the drawer (the owner saw this first),
  `org-directory` and the Files roots outside the vault. Two preloaded
  libraries also seeded Org's inbox, agenda files and starter notes into
  the loadup-time `~/org`. The dump now unbinds the root's seeds, the seven
  Org-seeding libraries load at init (61 of 68 features stay preloaded; a
  dump-backed start reaches the daemon in 1.0 s, was 0.66 s), and
  `tools/engine-preload-audit.el` fails on this class of mistake (it fails
  on the previous list with exactly the three problems the tablet showed).
- **Background running off.** The engine's foreground notification goes;
  the engine restarts once (the stop ends its process, the visible bind
  starts a new one, 2.3 s to the daemon) and stays bound while the window
  is visible; Home ends it within three seconds.
- **Found and fixed, lifecycle (34142844).** Leaving the app posted the
  high-importance "Jetpacs engine stopped" prompt every time; the bundled
  flavor now supplies none. The crash-loop guard counted every visible bind
  and wake as a start and halted the engine at the third open inside ten
  minutes, for good; a clean stop now forgets the history and a launch or
  a visible window clears the halt. Four Home-and-reopen rounds in a minute
  no longer halt.
- **Name (d354cd8b).** At the owner's request the bundled flavor is
  "Jetpacs" and the standalone flavor stays "Jetpacs Companion".
- **Wake (SPEC 5.8).** With background running off, an Orgzly note
  scheduled a few minutes ahead armed two inexact alarms (a fresh install
  has no exact-alarm grant), and the main process, started by the alarm
  with no engine, posted the reminders. A tap on Orgzly's reminder
  (`orgzly.home`, `when_offline` `wake`, `open_surface` `app:orgzly`)
  started the `:emacs` process for the bind 45 ms later; the wake bound,
  the daemon was up at 1.5 s and the wake was released at READY 2.7 s
  after the tap; the queued event was delivered (`queue_events` empty) and
  Orgzly's notebooks were re-read from the vault.
- **Found and fixed, restart (2f04dcb4).** With background off, Restart
  engine (and Rebuild engine cache, and the vault move) ended the engine
  and never brought it back: the visible window was read after `stop()`
  had cleared it. Now a new engine serves 2.5 s after the tap.
- **Export.** Found and fixed (33c867f6): the export and the move skipped
  "symbolic links" by comparing canonical and absolute paths, and the
  private vault's own root fails that comparison on Android
  (`/data/user/0` links to `/data/data`), so the export was a 22-byte
  empty archive. Now the archive holds all 14 vault files.
- **Vault move.** Run into `/sdcard/Jetpacs`, which on this tablet is the
  standalone install's live vault, with the owner's go-ahead and a
  checksummed backup: "Allow a shared folder" opens the system's All files
  access page for "Jetpacs"; after the grant the row offered the move only
  once the screen was reopened (fixed in fff08e76, it re-reads the grant on
  resume); the move copied the one file the shared folder lacked, changed
  none of its 26 files, rewrote `install.conf` (`vault_mode=shared`,
  `vault=/storage/emulated/0/Jetpacs`), restarted the engine onto it in
  1.6 s, and the hub's REPL reported `org-directory` in the shared folder.
  Afterwards the test note was removed, the grant revoked (which makes
  Android kill the app's processes; the sticky service restarted the
  engine) and the bundled app cleared.

Resolved after the gates, at the owner's direction (2026-10-27):

- **The design runtime's component styles (71a2aeb6).** The baseline binds
  all 130 slots and the `jetpacs.design` contract allowed 128 per scope
  since #206 and #207, so with the experimental design runtime on every
  foundation surface was refused (1201, `component_styles`), in either
  flavor. The bound is now 256 (`docs/workspace/DESIGN-RUNTIME.md` states
  it and its rule); on the tablet the hub and the other foundation
  surfaces are accepted with all 130 bindings.
- **The design runtime switch never reached Emacs over the Unix socket
  (58f740b9).** A `LocalSocket` close did not wake the session's blocked
  reader, so no abort ever ended a bundled session; now each switch brings
  a fresh handshake about four seconds later.
- **Wording (6027213a).** The offline banner, the waiting screen, the
  dropped-tap toast, the app store's consent and the kit's "in Emacs"
  hints use bundled wording; on the tablet the banner reads "The notes
  engine isn't running / Showing the last screen it sent. / Settings /
  Start it". The Settings hub's "Emacs Settings" entry keeps its name: it
  holds the engine's own options.

Still open:

- Background running on shows two ongoing notifications (engine and
  bridge), where decision 1 asked for one; turning it off restarts the
  engine once.
- A note with a scheduled time gets a second reminder from the Org Mode
  app, without a tap action (the known duplicate-reminder item).
- Under heavy machine load the desktop tests once crashed in Room's bundled
  SQLite driver (`PairingDao.setReadyDisconnectedAt`, SIGSEGV); idle, the
  same tree passes twice. Worth its own look.

## Routing table

| Change | Owning seam | Minimum evidence |
|---|---|---|
| E0 engine | `jetpacs-emacs-engine` | `MANIFEST.json`, alignment check, AAR |
| E1 spike | `companion/app` bundled source set, `EngineFlavor` | device log table above |
| E2 amendment | `../ebp/SPEC.md` §2.3, §5.1, §5.8, §9.1 | `validate.py`, SPEC-CHANGES row |
| E3 end to end | `engine/EngineProvisioner`, `Pairing.kt`, `emacs/jetpacs-init.el` | fresh-install smoke |
| E4 transport and wake | `LocalSocketTransport`, `CompanionTransport`, `EngineWake` | socket modes, wake within 10 s |
| E5 policy | `EngineSupervisor`, settings, `Notifications.kt` | toggle and export checks |
| E6 startup | engine repository, provisioner | timing and size tables |
| E7 distribution | workflows, `TESTING.md`, docs | CI green with the pinned AAR |

## Completion gates

The bundled flavor is conforming only while all of these hold:

- no Emacs source tree carries a change made for Jetpacs;
- the standalone flavor's behavior, classpaths and gates are unchanged;
- the engine process never opens Room, the KeyStore or the bridge;
- Emacs dials and authenticates like any other endpoint (SPEC §5, §9); the
  Companion never writes Elisp and never passes code, arguments or files
  through a start or wake signal;
- the Emacs endpoint's credential lives only in the application-private
  0600 file; no DocumentsProvider or other export covers HOME;
- every `.so` in the engine is 16 KB page-aligned;
- `validate.py` stays green across the amendment.

## Deliberately not taken

- Patching Emacs, native compilation, GnuTLS, libxml2 or tree-sitter
  ports, SAF `/content/` access (it needs an `EmacsService`), Emacs's own
  notifications or clipboard.
- Running Emacs inside the Companion's main process.
- Extending the fork or ebp-java's Android hosts.
- The bundled desktop build beyond sharing E2's amendment; ABIs beyond
  arm64-v8a and x86_64; Play Store submission.

## Open questions

- The final application ID of the bundled flavor at release.
- Whether `info/` and the trimmed lisp subtrees are missed by any applet.
- F-Droid reproducibility of the engine build; Play's policy on
  `MANAGE_EXTERNAL_STORAGE` for the shared-folder option.
- Per-ABI release APKs: `splits.abi` is module-wide and would rename the
  standalone flavor's artifacts, so GitHub Releases carry the two-ABI
  bundled APK (118 MB) for now; an App Bundle would serve Play.
