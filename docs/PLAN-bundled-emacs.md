# Bundled Emacs plan

Status: active. E0, the engine build and harvest, and E1, the headless
boot spike, are done (2026-10-26) and the spike is a go; E2, SPEC
amendment #209, and E3, the bundled flavor end to end, landed 2026-10-27.
Every later item is open. The decisions were taken by the owner on 2026-10-26.

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
5. **E4 — loopback-unix and the bound-service wake.** `LocalSocketTransport`
   in `:app` (FILESYSTEM `LocalSocket.bind` + `LocalServerSocket(fd)`, 0700
   directory, 0600 socket, peer-UID check), a path-bearing
   `TransportStatus`, a `loopback-unix` branch in `jetpacs--start-1`,
   `EngineWake` (coalesced, once per 60 s, `bindService` from the
   application context with no extras, released at READY or 10 s),
   `offline.wake` granted. Evidence: a reminder tap with the engine dead
   wakes it within 10 s; standalone and bundled coexist on one device.
6. **E5 — Lifecycle policy and product polish.** "Run in background"
   setting, notification text and "Pause", boot and update restarts, crash-
   loop guard, export/backup, the shared-folder option, a read-only
   diagnostics screen.
7. **E6 — Startup time and size.** Shipped per-ABI dumps, a kit-preloaded
   dump through `site-lisp/site-init.el`, build-time byte-compilation,
   asset trimming before the index is rebuilt; targets ≤ 2 s tap-to-READY
   on the Pixel Tablet and ≤ 60 MB per ABI.
8. **E7 — CI, distribution and documentation.** Engine release workflow,
   Companion CI with the pinned AAR, release signing, the documents listed
   in the routing table.

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
