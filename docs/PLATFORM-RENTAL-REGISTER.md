# Platform-rental register

Status: mandatory implementation gate. This is a current ownership register,
not a dated dependency-version ledger; exact versions live in their owning
version catalogs.

This register records the platform/library primitive that owns each common
subsystem. Before adding a helper, adapter, macro, or compatibility layer,
check this table and demonstrate the missing behavior with a test. A private
API or locally reimplemented subsystem is permitted only behind a narrow,
documented compatibility boundary with an upstream/removal plan.

## Emacs 30.1+ endpoint

| Concern | Authority to rent | POC 3 rule |
|---|---|---|
| JSON-RPC transport, framing, request IDs, dispatch | built-in `jsonrpc.el` | Use public classes/generics/functions. Do not depend on `jsonrpc--*`, predicted IDs, private process filters, timer queue representation, or global `timer-list`. Upstream a missing seam. |
| Durable local records | built-in `sqlite.el` and SQLite subrs | `ebp-sqlite.el` owns schema and checked transactions. No append-only receipt fallback may return conforming `accepted`. Check `sqlite-open` and commit results explicitly. |
| Buffer change capture | built-in `track-changes.el` | `ebp-sync.el` registers deferred trackers, fetches changes outside low-level mutation work, uses `:disjoint t`, and unregisters every lifecycle path. |
| Canonical buffer initialization | `normal-mode`, normal file/local/dir-local/project hooks | One normally initialized visited buffer per document. No hand-selected `auto-mode-alist` shadow environment. |
| Atomic buffer edits | `atomic-change-group`, `replace-region-contents` | Use for rollback/full reseed. Do not mistake buffer atomicity for a disk/SQLite transaction. |
| Save/revert lifecycle | normal visited-buffer save/revert functions and hooks | Do not bypass mode save hooks or defer an accepted durable effect without a recoverable work record. |
| Org search | built-in `org-search-view` and `org-tags-view` | Project their results through one bounded neutral adapter. Applets may choose presentation and modes, but must not ship a parallel index or query engine. |
| Org document presentation | `jetpacs-org-render` neutral model plus scoped host presenters | Reuse the renderer for content. Register link presentation per buffer and surface, and clear that context when an applet unloads; no global presenter takeover. |
| Applet file mutations | `jetpacs-files-create`, `jetpacs-files-rename`, `jetpacs-files-move`, `jetpacs-files-trash` | Reuse the Files allowlist and literal-entry guards. Never overwrite on create/move, follow a symlink out of policy, or silently replace recoverable trash with deletion. |
| External file observation | `file-notify-add-watch`, save/revert hooks | Timer polling is a bounded fallback only when notifications are unavailable. |
| HMAC | `gnutls-hash-mac` | Pass an intentional disposable key copy because the primitive wipes directly supplied key strings. No pure-Elisp HMAC in production. |
| Secure entropy | `iv-auto` | Fail closed when unavailable. Do not shell to `head` or assume `/dev/urandom`. |
| Credential lookup/deletion | `auth-source` provider injected into EBP | EBP stays storage-agnostic. Jetpacs policy selects the backend and forget-pairing deletes through it. |
| Completion | public completion metadata/boundary/all-completions APIs | Do not call `completion--*`. |
| Eglot/Flymake | public Eglot and Flymake APIs on the canonical buffer | Do not call `eglot--*`; upstream a missing headless/observation seam. |
| Command execution | `command-execute` and standard command predicates | Do not reproduce command loop semantics with a direct `call-interactively` wrapper. |
| Active keymaps | `current-active-maps` | Do not reconstruct precedence manually. |
| Images | Emacs image type/size/metadata APIs | Do not maintain separate PNG/JPEG header parsers. |

Minimum-version rule: run the public Emacs modules on both Emacs 30.1 and the
current local Emacs checkout. Current-only convenience APIs require an isolated
version adapter and a 30.1 test.

## Android and AndroidX

| Concern | Authority to rent | POC 3 rule |
|---|---|---|
| Durable Jetpacs application state | Room 3 KMP | One pairing-partitioned Room store; explicit transactions, schema export, migrations, reopen/device/fault tests. UI never writes protocol state. |
| Outgoing durable delivery | Room 3 transactional outbox | Stable EventId, sequence, payload, expiry/dedupe/capacity/runtime updates commit before first send. Delete only after a permanent peer disposition. |
| Secrets | Android Keystore-backed credential adapter | Tokens and reusable encryption keys do not enter Room or logs. Use separate aliases for authentication and sensitive queued payloads. |
| Preferences | DataStore when a general settings store is needed; current Room `app_runtime` for the bridge switch | User/product preferences are not pairing-authored protocol state. The current user-enabled bridge policy is a device-local singleton, never pairing data. |
| User fonts | App-private files (`filesDir/fonts`, the desktop data directory) with a JSON manifest; Compose `Font(File)` on Android and `androidx.compose.ui.text.platform.Font` on the desktop; the system document picker and the AWT file dialog | `UserFontStore` owns the directory, reads a file's own `name`/`OS/2` tables and bounds every read; a design carries only the family identifier (`JetpacsFonts`), never a font file. Receiver presentation state, not pairing or protocol state. |
| Long-lived runtime ownership | Android service plus structured coroutines | `Application` is composition root only. Reader, bounded actor, writer, and reconciliation scopes have explicit owners and cancellation. Android 14/API 34 is the floor; the user-enabled `specialUse` FGS remains best effort and its notification is not liveness evidence. |
| Serialized mutation ordering | `Channel<Command>` coroutine actor | Do not port POC 2 synchronized monitors/executors or invent `WireLock`. No transaction or actor command waits for socket/platform work. |
| Deferrable retryable work | WorkManager | Use for reconciliation/cleanup/update work, not the live socket or exact-time alarms. |
| Exact scheduling | AlarmManager with capability checks | Commit registration/receipt first, invoke platform API afterward, and reconcile on boot/permission changes. |
| Lifecycle UI collection | ViewModel `StateFlow`, lifecycle-aware Compose collection | Follow local architecture-samples state-holder/repository pattern. No process-global raw `JsonObject` UI flows. |
| Navigation | Navigation 3 local recipes | `rememberNavBackStack`, saveable state and ViewModel decorators, identifiers-only `NavKey`, predictive-back/device tests. Do not copy the template's custom unmanaged Navigator. |
| Text editing | state-based Compose text fields, `InputTransformation`, `TextFieldBuffer.changes` | Convert real IME/paste/accessibility transactions to EBP splices. Do not diff whole old/new strings after every edit. |
| Permission results | Activity Result APIs | Do not hand-roll request-code or callback ownership. |
| Images/network/cache | Coil 3 with a secured Ktor/OkHttp fetch path | Preserve EBP DNS/SSRF/redirect/size/format constraints in a custom fetcher/interceptor. Do not maintain a raw HTTP/TLS/chunk/LRU stack. |
| App widgets | Glance with the implemented widget renderer profile | Read accepted Room projections, update explicitly, keep host-instance binding separate, and route actions through the Room outbox. Advertise only implemented widget handlers. |
| Quick Settings | `TileService` | Separate renderer/projection; do not force it through Glance. |
| Accessibility/adaptive UI | Compose semantics and current adaptive components | Treat semantics, large screens, keyboard, contrast, and font scaling as renderer conformance gates. |
| Bundled Emacs engine | GNU Emacs 31.1's own Android port, built unmodified by `jetpacs-emacs-engine` and started through `EmacsNative` | The engine is a pinned AAR (`gradle/engine.lock`: identity and SHA-256); no Emacs tree carries a Jetpacs change. A private `specialUse` service in process `:emacs` runs upstream's non-interactive shape (`setEmacsParams` without an `EmacsService`, `initEmacs --fg-daemon`): no `EmacsService`, activity or window, so nothing feeds the native event queue. The first-start dump, `site-lisp/site-init.el` and the asset `directory-tree` index are Emacs's own mechanisms; the kit is byte-compiled at build time by a host Emacs of the engine's major version. |
| Engine TLS | GnuTLS, nettle, GMP, libtasn1 and p11-kit as the ndk-build ports Emacs's `java/INSTALL` names (engine 31.1+3) | Built unmodified by Emacs's own ndk-build support and pinned by SHA-256 in the engine repository; servers are verified against the system CA store through Emacs's default `gnutls-trustfiles`. No pinned certificates, no gpg (package signatures unchecked). |
| Engine lifecycle | the `:emacs` foreground service, `bindService` from the application context, `BootReceiver`, `SharedPreferences` for the one background switch | `EngineSupervisor` is SPEC #209's engine supervisor: start, stop, restart and keep alive on product policy. The SPEC §5.8 wake (`EngineWake`: once per 60 s, a 10 s hold, the fixed non-exported extra-less target re-checked) is the only bind held for a session; a crash loop (`EngineStarts`) halts and tells the user rather than retrying. In the other direction the engine's foreground service binds the private `CompanionKeepaliveService`, which keeps the Companion's process (and its listener) alive under the app's one notification. |
| Same-UID socket (SPEC §5.3) | `android.net.LocalSocket` and `LocalServerSocket` in the filesystem namespace, `getPeerCredentials` | `LocalSocketTransport` in `:app` (`:host` forbids `android.*`): a 0700 directory, a 0600 socket, the stale socket unlinked before bind, a peer whose uid is not the application's refused. Never the abstract namespace. |
| Engine credential (SPEC §9.1) | the Android Keystore pairing credential, exported once to an app-private 0600 file | `BundledPairing` generates the pairing through `EbpAuth`, keeps the Companion's copy in the Keystore and writes `var/pairing` for the Emacs endpoint; `jetpacs-credentials.el` refuses a file readable by group or other. No DocumentsProvider or export covers HOME; debuggable builds (`run-as`) are the amendment's reduced protection. |
| Engine HOME and vault | app-private `filesDir`, `hasFragileUserData`, `ACTION_CREATE_DOCUMENT` with `java.util.zip` for export, `MANAGE_EXTERNAL_STORAGE` only for the opt-in shared folder | `EngineProvisioner` writes the kit, `init.el`, `install.conf` and `site-init.el` under `files/emacs`, keyed by an engine-plus-kit stamp; `EngineVault` moves the notes without overwriting or deleting; the permission is declared in the bundled overlay and requested on the system page only when the user chooses the shared folder. |

The local AndroidX checkout, local architecture-samples/templates, local
Nav 3 recipes, and local Compose/Glance samples are the implementation
references. Online examples are not substituted for a newer local checkout.

## Kotlin Multiplatform and protocol code

| Concern | Authority to rent | POC 3 rule |
|---|---|---|
| Structured concurrency | kotlinx.coroutines | Runtime receives a caller-owned scope; no `GlobalScope`, unmanaged `Job`, raw executor ownership, or blocking DAO adapter. |
| Data interchange | kotlinx.serialization plus EBP validation | Generate typed contract vocabulary/method/limit projections into `commonMain`. Raw open content remains validated EBP data, not platform objects. |
| Contract drift | `ebp/contract.json`, goldens, one generator pipeline | Generated Kotlin and Elisp are byte/diff checked. Do not maintain parallel lookup tables by hand. |
| Storage semantics | domain transaction SPI plus reference memory backend | `ebp-kmp` owns reducers/invariants; Room owns persistence mechanics. No DAO/entity types in the SPI. |
| Renderer capabilities | installed renderer registries | Derive per-target profiles from actual handlers. Compose, notification, and Glance profiles remain distinct. |
| Common-code purity | a real non-JVM compile target and import lint | A directory named `commonMain` is not proof. JVM file stores/crypto/time/DNS remain adapters. |

## Desktop JVM

The desktop Companion (`companion/desktop`,
[`PLAN-desktop-companion.md`](PLAN-desktop-companion.md)) rents its platform
from the JDK and the operating system; nothing below is reimplemented in
Kotlin.

| Concern | Authority to rent | POC 3 rule |
|---|---|---|
| Durable state | Room 3 with the bundled SQLite driver at the per-user data directory | `buildJetpacsDatabase(directory)` in `:core:database` jvmMain opens the one `jetpacs.db` with the Android builder's driver and query context. Migrations are proved with `room3-testing` against the exported schemas, never by hand-written SQL. |
| Data directory | `JETPACS_DATA_DIR`, else XDG (`$XDG_DATA_HOME/jetpacs`), `~/Library/Application Support/Jetpacs`, `%LOCALAPPDATA%\Jetpacs` | `JetpacsDataDirectory.resolve` is the only place that spells these paths. Pure path logic, tested per operating system. |
| Private directories and files | `java.nio.file` POSIX permissions and attribute views | `ensurePrivateDirectory` applies ebp-java's discipline: `rwx------`, owned by the running user, symbolic links and non-directories refused. Files are written temporary, forced, then moved atomically. Without POSIX permissions or ACLs there is no private place, and the store refuses to run. |
| Pairing token and payload key | the OS keychain through java-keyring (macOS Keychain, Windows Credential Manager, GNOME Secret Service, KWallet) | `KeychainPairingCredentials` over `openSecretStore`. The keychain is probed with a set, get and delete inside a timeout; only when it fails does SPEC §9.1's floor, `credentials.json` in the data directory, take over, and `CredentialProtection.PrivateFile` makes the host show the reduced-protection notice and keep `sensitiveQueueEncrypted` false (SPEC §21.5). |
| HMAC-SHA256 and AES-256-GCM | `javax.crypto` | Keys load once into `SecretKeySpec`s behind operation-only handles; the envelope is the common `OutboxEnvelopeFormat` with a twelve-byte random IV, so one format decodes on both platforms. Tokens never reach Room, logs or exception messages (`TokenContainmentTest`). |
| Key names | `PairingKeyAliases.v1` (commonMain) | One spelling for an Android Keystore entry, a keychain item and a Room partition row. Deriving a name is never evidence of provisioning. |
| Pairing generation | `java.security.SecureRandom` through `EbpAuth.generatePairingId`/`generatePairingToken`/`encodePairingToken` | SPEC §9.1: the Companion generates both values; the token's display form leaves the process only through the pairing screen. |
| Protocol host and window | `companion/host` behind `CompanionHostPlatform` and `HostUiPlatform` | The desktop implements the two seams (`DesktopHostPlatform`, `DesktopHostUi`); it never copies the bridge, the stores or a screen. |
| UI thread | the AWT event queue (`java.awt.EventQueue.invokeLater`) | Renderer outcomes and editor results are delivered where Compose for Desktop composes; no coroutines-swing dependency. |
| Clipboard | `java.awt.Toolkit.systemClipboard` | `clipboard.copy` and the pairing screen's copy button. No share sheet exists, so `share.send` is not advertised. |
| Receiver-local settings | `java.util.Properties` in `settings.properties` inside the data directory | Non-secret presentation preferences (completion narrowing, the design-runtime toggle, the pairing acknowledgement); never EBP state, never a secret. |
| Diagnostics | `java.util.logging`, one logger per tag | The stage timer runs when `JetpacsStages` admits FINE (`-Djetpacs.stages=true`). Nothing logged carries token material. |
| Locale advice | `java.util.Locale`, `java.util.TimeZone`, the localized short time pattern | SPEC §20.1.2 facts at start; no desktop broadcast says they changed. |
| Loopback listener (SPEC §5.2) | `java.net.ServerSocket` bound to 127.0.0.1, as `LoopbackTcpTransport` in `companion/host` | One `CompanionTransport` among others; the bridge never binds a socket itself. |
| Emacs socket (SPEC §5.6 `emacs-unix`) | `java.nio.channels.SocketChannel` over `java.net.UnixDomainSocketAddress` (JDK 16+), as `EmacsUnixDialer` in `companion/desktop` | Inspect the socket file before every dial (`Files.readAttributes` without following links, `Files.getOwner` against the running user's principal); refuse symbolic links, non-sockets and foreign owners terminally. Stream views read and write the channel directly, never `Channels.newInputStream`. Redial 1 s doubling to 15 s with jitter; close a silent peer after ten seconds. |

## Intentionally custom EBP behavior

The platform-rental rule does not mean deleting protocol semantics. These stay
custom, generated, or explicitly validated because the EBP specification owns
them:

- Companion-side strict framing/UTF-8/duplicate-member/depth/safe-number rules;
- EBP handshake, capability, method-direction, state-machine, revision,
  replay, trigger, and Section 19 semantics;
- canonical payload hashing and contract-specific validation;
- typed reducers and transaction laws;
- target/profile-aware surface normalization and unknown-node degradation; and
- scalar-index conversion and EBP sequence/shadow reconciliation.

The Emacs endpoint may delegate the receiver tolerances explicitly permitted by
the SPEC to built-in `jsonrpc.el`; the adversarial Companion still enforces the
required strict receiver boundary.

## Review checklist

For every new subsystem or dependency:

1. Identify its owning platform primitive in this register.
2. Cite the local source/sample and minimum supported version.
3. State the missing behavior that requires an adapter.
4. Keep the adapter narrower than the platform subsystem.
5. Add conformance, restart, failure, and boundary tests proportional to risk.
6. Record whether the code belongs to EBP, reusable `ebp-kmp`, Jetpacs,
   renderer infrastructure, or workspace-only tooling.
7. Re-run the whole-rebuild trigger test: source of truth, transaction owner,
   lifecycle owner, public seam, failure domain, renderer model, or KMP module.

If none changes, the work is an adapter/refactor and must not create a parallel
architecture.
