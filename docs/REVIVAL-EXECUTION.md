# Optional Android platform capabilities

Status: implemented in the current source. This filename is retained because
the architecture historically linked the platform-breadth execution ledger;
the obsolete isolated-worktree promotion instructions are available through
[`HISTORY.md`](HISTORY.md).

Historical POC code supplied implementation evidence, but the current EBP spec,
contract, goldens, architecture, source, and tests govern every behavior.

## Ownership

`wire` owns closed capability schemas, device-report validation, state
predicates, trigger validation/runtime, action admission, durability rules, and
`offline.wake` grant enforcement. The Android app owns current permission
checks, OS observation and effects, exact Intent/package allowlists, encrypted
storage, and platform component lifecycle. Elisp authors the actual SurfaceSpec
IR and application actions.

Android sources produce typed state or trigger occurrences. They do not decide
application policy or introduce a second protocol adapter.

## Capability catalog

`AppCapabilities` reports 18 optional operations from current device state:

| Family | Capabilities |
|---|---|
| Settings and launch | `settings.open`, `intent.start`, `app.launch`, `apps.list` |
| Shortcuts | `shortcut.pin`, `shortcuts.set` |
| Device effects | `vibrate`, `tts.speak`, `volume.set`, `ringer.mode`, `flashlight`, `media.key` |
| Data and visible policy | `clipboard.read`, `screen.keep_on`, `brightness.set`, `dnd.set` |
| State and triggers | `state.get`, `trigger.fire` |

Every report is rebuilt and byte-bounded per connection. The wire validates the
closed argument/result shape, then the host rechecks permission and platform
policy immediately before use. `intent.start` has a wildcard-free allowlist;
extras remain bounded native scalars. `apps.list` returns a sorted, bounded
positive package inventory. Clipboard reads accept bounded plain text without
coercion side effects.

Shortcut ingress uses a pairing-scoped platform ID plus an opaque random token,
then reloads and revalidates private state. See [`SECURITY.md`](SECURITY.md).

## Trigger and state sources

The app advertises 17 trigger types:

```text
time, power, battery.level, screen, headset, airplane, boot,
timezone.changed, package, manual, state.edge, network, wifi.enabled,
bluetooth.enabled, calendar.event, sms.received, call.state
```

Ten types are directly sampleable for `state.get` and predicates:

```text
power, battery.level, screen, airplane, network, headset,
wifi.enabled, bluetooth.enabled, calendar.event, call.state
```

Broadcasts, callbacks, alarms, and content observers remain Android mechanics.
Wire-owned logic decides registration matching, predicates, privacy projection,
throttling, one-shot/repeat progress, durable admission, and `on_fire` order.
Sensitive SMS/call projection is permission- and field-gated. Calendar queries
are bounded and fail closed when the host cannot evaluate a filter.

A queue or wake occurrence commits outbox and trigger runtime together. Local
effects and wake callbacks occur after commit. This build deliberately does not
advertise `offline.wake`: no exact inert OS-local Emacs wake target is
configured, and a durable queue alone is not a wake implementation.

## Quick Settings tiles

The manifest exposes exactly five signature-protected host slots,
`tile:custom1` through `tile:custom5`. A tile stores the exact node-less EBP
surface variant; it has no views, drafts, or parallel UI model. Its profile has
no node types and only four context-less builtins:

```text
surface.open, clipboard.copy, companion.settings.open, trigger.fire
```

`jetpacs-tile-surface` builds that same plist IR in Elisp. Invalid or
unadvertised behavior degrades to a valid inactive tile rather than emitting a
node into the wrong target.

## Security invariants

- OS-invoked exported components are limited and protected or explicitly
  validated; ordinary internal receivers/services are private.
- Internal PendingIntents are explicit and immutable except the narrowly
  required `RemoteInput` case.
- Boot handling accepts only the allowlisted protected system actions.
- Platform shortcut ingress never trusts action JSON or nested Intents.
- Capability and trigger data is bounded before it crosses its owning resource
  boundary.
- A missing permission or unavailable source is explicit and fails closed.

## Verification

Protocol changes start with `../../ebp/validate.py`, followed by the
affected `:ebp-kmp:jvmTest` and `:wire:jvmTest` suites. Jetpacs changes run
focused Elisp ERT or app tests, then the aggregate `test/run-tests.sh` or the
Companion matrix in [`../companion/TESTING.md`](../companion/TESTING.md).

Real-device checks remain mandatory for the affected protected broadcast,
permission, shortcut, alarm/re-arm, offline replay, tile, or OS-effect path.
An emulator/build-only pass is not evidence that those Android behaviors work.
