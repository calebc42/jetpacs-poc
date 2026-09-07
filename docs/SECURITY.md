# Android security boundaries

This document records the current Jetpacs Android trust boundaries. The
manifest, implementation, and tests are the executable evidence; this page is
the navigation aid. Protocol-level validation remains owned by the EBP
specification and the `wire` implementation.

## Component exposure

Only entry points that Android or another trusted system component must invoke
are exported:

| Component | Exposure and gate |
|---|---|
| `MainActivity` | Exported only for the launcher `MAIN`/`LAUNCHER` filter |
| `WidgetConfigurationActivity` | Exported for `ACTION_APPWIDGET_CONFIGURE`; revalidates the supplied host ID and exact Jetpacs provider before state access |
| `BootReceiver` | Exported for the four allowlisted protected boot, time, and package-replaced broadcasts |
| `SmsTriggerReceiver` | Exported only behind Android's signature `BROADCAST_SMS` permission |
| `EbpTile1` through `EbpTile5` | Exported only behind `BIND_QUICK_SETTINGS_TILE` |

The bridge service, alarms, reminder interactions, notification actions,
widget provider/action/stale endpoints, and time-trigger receiver are private.
`ManifestSecurityTest` pins this closed exposure set. Adding an exported
component requires an explicit caller, permission or input-validation model,
and a corresponding manifest test.

## Intent and PendingIntent rules

Internal Intents name their exact component. PendingIntents are immutable by
default. The only mutable cases are notification or reminder inline replies
that require `RemoteInput`; their base Intent still targets a private explicit
receiver.

No inbound component accepts executable code, raw ActionDescriptor JSON, a
nested Intent, `Serializable`, or caller-selected component data. Private
receivers reject absent or malformed tuple coordinates before submitting work
to the bounded process actor.

Reminder interactions carry only pairing-owned coordinates. The receiver
reloads the current descriptor from Room and compares its current schedule
tuple before dispatch. A surface-opening reminder uses the private no-display
`ReminderInteractionActivity`; only an exact reloaded `app:*` value can reach
`MainActivity`.

Widget clicks carry a random 128-bit token in a private URI. Room binds the
token to widget ID, pairing, surface, accepted revision, descriptor, and
expiry. Dispatch rechecks every field. New-render tokens are staged before
publishing RemoteViews, and old tokens are pruned only after publication
succeeds. See [`GLANCE-WIDGETS.md`](GLANCE-WIDGETS.md) for the lifecycle.

## Platform capability boundary

The EBP wire layer validates each closed capability argument/result schema.
`AppCapabilities` then performs invocation-time Android checks: current
permission state, exact settings-panel mappings, a wildcard-free Intent
allowlist, positive launchable-package membership, and bounded scalar extras.
Unknown or unimplemented operations fail closed.

Shortcut ingress contains only a pairing-scoped platform ID and random token.
The app reloads the private descriptor, compares the token, validates the
closed envelope and injected arguments, consumes the Activity Intent, and only
then enters the context-less action funnel.

## Durable admission and secrets

A queued or wake action must commit to the Room outbox before a wake request,
local `open_surface` adjunct, or success conclusion. Queue-full and storage
failures cannot produce the external effect. Emacs independently commits its
EventId receipt and recoverable work before returning `accepted`; Jetpacs Room
is not receiver evidence.

Pairing authentication and queued-payload keys use distinct Android Keystore
aliases. Reusable secret material does not enter Room or logs. The application
disables backup and excludes protocol state from device transfer so a new
device cannot inherit an outbox or device-bound pairing.

## Privileged Android work

The user-enabled loopback bridge is a private `specialUse` foreground service.
Its notification reports policy, not process or session liveness. Exact alarms
are used only after a capability check and degrade to an inexact idle-allowed
alarm if access is denied or revoked. Platform effects run only after their
owning durable transaction commits.

## Verification

Run the current app unit, lint, assembly, and API 34 device gates documented in
[`../companion/TESTING.md`](../companion/TESTING.md). The focused evidence
includes:

- `ManifestSecurityTest` for the exported-component set;
- `IntentAllowlistPolicyTest` for exact URI, MIME, and extra-key policy;
- `ReminderNotificationTest` for durable descriptor and PendingIntent
  identities;
- `WidgetPolicyTest` plus Room widget DAO tests for token and lifecycle rules;
- wire tests for stale tuples, forged identity, durable admission, restart,
  and replay; and
- the API 34 composition smoke for installed manifest metadata and Keystore
  alias separation.

The dated 2026-08-31 and 2026-09-01 security reports were consolidated here.
Their original text is recoverable through [`HISTORY.md`](HISTORY.md).
