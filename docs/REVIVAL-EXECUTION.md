# Platform breadth revival execution

Status: implemented on the isolated `revive-ebp-platform-breadth` worktrees on
2026-09-01. Local conformance and build gates are green. The real-device gate
remains required before promotion because the available host cannot start an
x86_64 emulator without hardware acceleration.

## Objective and authority

Revive useful POC 1/2 platform breadth without reviving an older protocol.
Historical source is implementation evidence, not forbidden lineage and not a
specification. Code from any POC may be copied, cherry-picked, merged, ported,
or adapted when the result conforms to the current EBP specification.

The authority order for this work is:

1. `ebp-poc/ebp/SPEC.md` (3.1.0-draft, format 11 during this execution).
2. Its `contract.json` projection and named goldens.
3. Current EBP/Jetpacs architecture boundaries.
4. Current runtime and tests.
5. Historical POC implementations.

No normative change was needed. The existing specification already defines
the capability catalog, device reports, state predicates, trigger types,
fixed tile surface variant, durability rules, and `offline.wake` authority.
Where older code disagreed, the implementation was adapted or rebuilt.

The ownership invariant is unchanged: EBP owns validation, session rules,
action admission, and durability semantics; the Android host owns permission
checks, OS observation and effects, accepted presentation, and encrypted
storage; Jetpacs Elisp authors the actual wire IR and application actions.

## Isolation and promotion unit

Implementation was deliberately kept out of the locally dirty source trees.
The review unit consists of three sibling worktrees on the same branch:

| Repository | Starting commit | Purpose |
|---|---|---|
| `revival/jetpacs` | `daab56c` | Android adapters, Elisp tile builder, tests, and these docs |
| `revival/ebp-poc/ebp-kmp` | `01e88db` | Contract validators, capability catalog, state predicates, trigger runtime, and tile surface storage |
| `revival/jetpacs-components` | `d6f6e7e` | Align the downstream renderer library's minimum SDK with the API 34 app |

The EBP specification checkout remained unchanged. Clean sibling projects
used by the aggregate build were mounted read-only into the revival umbrella;
they are not part of this change.

The umbrella `AGENTS.md` and `README.org` were updated in place to remove the
obsolete clean-room/no-reuse policy. Other pre-existing umbrella and child
repository changes were preserved.

Promote the three repositories as one reviewed compatibility set, in this
order: EBP KMP/wire, Jetpacs Components' API-floor correction, then Jetpacs.
Do not copy build output or replace the dirty source checkouts wholesale.

## Donor ledger

The immutable evidence points are `poc/v1` at `98f7051`, `poc/v2` at
`ffea317`, and `poc/v3-pre-split` at `e00f3f8`.

| Donor | Reused idea | Treatment in the current implementation |
|---|---|---|
| v1 `DeviceCapabilities.kt` | Broad Android capability catalog and permission vocabulary | Rebuilt behind the current `CapabilityCatalog`; removed loose intents, coercive clipboard conversion, and old result shapes |
| v1 `StateSampler.kt` | Platform sampling mechanisms and shared predicate evaluation | Adapted into `TriggerSources`, `CalendarEventSource`, and wire-owned `StatePredicateEvaluator`; current report/state shapes are authoritative |
| v1 `CalendarTriggers.kt` | One observer plus next-boundary scheduling | Adapted with bounded instance queries, one global ongoing set, permission fail-closed behavior, and host-assisted filtered predicates |
| v1 `TileSlots.kt` | Five fixed manifest slots, listening refresh, immutable platform handoff | Adapted to exact `tile:*` SurfaceSpec storage and the Section 14 funnel; raw action JSON in Intents was discarded |
| v1 `TriggerHost.kt` | Android observation sources and event projection | Source mechanics retained where useful; wire trigger validation, gates, throttle, durability, and local responses remain the owners |
| v1 `jetpacs-device.el` / `jetpacs-triggers.el` | Authoring/API examples | Not copied as a second endpoint API; current `ebp.el` remains the sole protocol owner |
| v2/v3 `AppCapabilities.kt` | Small current-generation Android adapter seam | Expanded from the existing two-capability shape to the full catalog rather than restoring the v1 protocol adapter |
| v2/v3 `TriggerSources.kt` | Current bridge and device-lifetime firing seam | Expanded from battery-only observation while preserving the current durable `TriggerFiringService` and Room actor |
| v2/v3 wire capability/trigger modules | Current closed-schema, admission, and persistence architecture | Extended directly; no parallel validator, trigger store, or action dispatcher was introduced |

This ledger is descriptive, not an allowlist. Future historical code is judged
the same way: useful mechanics may be reused; current normative behavior must
be proved at the owning seam.

## Delivered capability slice

The Android device report is rebuilt for every connection. It advertises the
full 18-entry Section 20.3 catalog, current named permission state, exact
positive allowlists, bounded launchable packages, trigger capabilities, state
types, and unavailable trigger reasons. Wire code validates each closed Args
and Result shape before and after the Android handler.

| Capability | Host behavior |
|---|---|
| `settings.open` | Exact advertised settings-panel mapping |
| `intent.start` | Wildcard-free tuple allowlist; URI/user-info checks; native scalar extras only; no serialized or nested platform objects |
| `app.launch`, `apps.list` | Positive launchable-package inventory; sorted, bounded paging |
| `shortcut.pin`, `shortcuts.set` | Pairing-scoped platform IDs; private descriptors; opaque random launch token; bounded PNG decode; rollback on platform refusal |
| `vibrate`, `tts.speak` | Bounded platform operations with typed failure |
| `volume.set`, `ringer.mode`, `media.key` | Exact enum/level mapping through Android audio services |
| `flashlight` | Permission-checked torch selection and handoff |
| `clipboard.read` | Plain text only, bounded at `max_field_bytes`; no `coerceToText` side effects |
| `screen.keep_on` | StateFlow-backed policy applied only to the visible Activity |
| `brightness.set`, `dnd.set` | Special-access checks immediately before the platform write |
| `state.get` | Shared state sampler and predicate evaluator; unavailable samples reported explicitly |
| `trigger.fire` | Existing manual-trigger registration path only |

Shortcut launch is an exported-Activity trust boundary. The launcher Intent
contains only a pairing-scoped platform ID and random token. The app reloads
the descriptor from private storage, compares the token in constant time,
revalidates the closed envelope and injected arguments, consumes the retained
Activity Intent, and then enters the context-less Section 14 action funnel.

## Delivered trigger and state slice

All 17 Section 21.5 trigger types are advertised. Sampling and occurrence
production stay in the Android layer; registration matching, state gates,
privacy projection, throttling, durable admission, pending-local recovery, and
`on_fire` ordering remain in the wire/device-lifetime firing service.

| Source family | Implementation |
|---|---|
| `time` | Existing exact/inexact alarm adapter and reboot/update re-arm |
| `power`, `battery.level`, `screen`, `airplane` | Protected system broadcasts plus initial level baselines |
| `headset` | Audio-device callback with bounded optional name |
| `wifi.enabled`, `bluetooth.enabled` | Current platform state plus protected change callbacks; permission fail-closed |
| `network` | Default-network callback with a platform-identity occurrence book, exact available/lost transitions, and transport projection |
| `boot`, `timezone.changed` | Protected manifest broadcasts routed through the serialized store actor |
| `package` | Package-added/removed protected broadcasts with exact package projection |
| `calendar.event` | One content observer, bounded Instances query, next-boundary callback, global ongoing set, and host-evaluated filtered predicates |
| `sms.received` | `BROADCAST_SMS`-protected receiver; bounded sender/body; body appears only for `include_body` |
| `call.state` | Permission-gated phone-state callback; number appears only for `include_number` and matching filters |
| `state.edge` | Shared predicate evaluator over trackable level state |
| `manual` | Existing `trigger.fire`/builtin path |

Sensitive substitution approval is represented as an exact `(source, sink)`
pair; blanket approval is not representable. Queued SMS and call payloads are
accepted only when the host attests to its pairing-scoped Android KeyStore
encryption. State and trigger predicates share one evaluator, and a filtered
calendar predicate that cannot be answered by the host fails closed.

`policy: "wake"` and every ActionDescriptor with `when_offline: "wake"` are
rejected unless the current session granted `offline.wake`. The wake callback
rechecks the same current grant after durable admission. This Android build
does not advertise `offline.wake`: it has no configured exact inert OS-local
Emacs target, and a durable queue is not a wake implementation.

## Delivered tile and authoring slice

The Companion exposes exactly five signature-protected Quick Settings host
slots: `tile:custom1` through `tile:custom5`. A tile is stored as the exact
node-less Section 13.4 object; it has no views, drafts, resettable inputs, or
parallel UI AST. The profile advertises no node types and only the four
receiver-local/context-less builtins the host can execute reliably:
`surface.open`, `clipboard.copy`, `companion.settings.open`, and
`trigger.fire`.

`jetpacs-tile-surface` builds that same plist wire IR in Elisp. Its validator
rejects field capture, authored `tile` injection, and builtins that require a
surface node, view, or dialog. Builder failure degrades to a valid inactive
tile rather than emitting a node into the wrong SurfaceSpec variant.

## Android security decisions

- The bridge, alarms, reminder taps, and notification action receivers remain
  non-exported.
- The SMS receiver is exported only with the signature `BROADCAST_SMS`
  permission. The five exported tile services require the signature
  `BIND_QUICK_SETTINGS_TILE` permission.
- `BootReceiver` accepts only the four protected system actions required for
  reboot, time change, and app-update re-arm.
- Platform shortcut ingress never trusts action JSON or a nested Intent from
  the exported launcher Activity.
- All internal PendingIntents are explicit. They are immutable except for the
  existing notification inline-reply PendingIntent, whose mutability is
  required for `RemoteInput` and whose receiver is non-exported.
- `intent.start` has no wildcard or fall-through resolution policy, and extras
  never cross the Android boundary via `Serializable` or `Parcelable`.

## Verification and remaining device gate

Run in order, stopping on the first disagreement with the normative corpus:

1. `cd revival/ebp-poc/ebp && python3 validate.py` and vocabulary drift check.
2. `cd revival/ebp-poc/ebp-kmp && ./gradlew :ebp-kmp:jvmTest :wire:jvmTest`.
3. Focused ERT for widgets/devtools, then `revival/jetpacs/test/run-tests.sh`.
4. `cd revival/jetpacs/companion && ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :wire:jvmTest :core:database:jvmTest assembleDebug`.
5. The owning Jetpacs Components test/build after the API-floor alignment.
6. On API 34+ hardware: install the app, grant only the permission under test,
   and exercise shortcut launch, each protected trigger source, restart/re-arm,
   an offline queued occurrence followed by replay, and all five tile slots.

The local spec validator, vocabulary drift checks, focused and full wire JVM
tests, the full Jetpacs aggregate Elisp/integration suite, Jetpacs Components
unit tests, Android unit tests, Android-test compilation, and debug assembly
have passed during this execution. The managed API 34 test APK was produced,
but its x86_64 emulator could not boot because this host lacks hardware
acceleration. That is an environmental block on device evidence, not a
substitute pass; promotion still requires step 6 on a real or accelerated
device.
