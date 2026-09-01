# Best Practices and Security Alignment Update

Date: 2026-08-31

## Area: component exposure

Priority: High

Files: `companion/app/src/main/AndroidManifest.xml`

Implementation: `JetpacsBridgeService`, alarm receivers, reminder/tap receivers,
notification-action receivers, and widget action/stale/provider receivers are
explicitly non-exported. `MainActivity` is exported only for its launcher
filter. `BootReceiver` is the sole exported receiver; it both declares and
explicitly allowlists only the protected system boot/time/package actions
before doing any work. The widget configuration activity is necessarily
exported for the external launcher, but accepts only
`ACTION_APPWIDGET_CONFIGURE` plus a current host ID whose installed provider is
exactly Jetpacs.

Representative diff:

```diff
+ <service android:name=".JetpacsBridgeService"
+     android:exported="false"
+     android:foregroundServiceType="specialUse" />
+ <receiver android:name=".ReminderAlarmReceiver" android:exported="false" />
+ <receiver android:name=".NotificationActionReceiver" android:exported="false" />
+ <receiver android:name=".WidgetActionReceiver" android:exported="false" />
+ <receiver android:name=".WidgetStaleReceiver" android:exported="false" />
+ <activity android:name=".WidgetConfigurationActivity"
+     android:exported="true" />
```

Tests: API 34 instrumentation resolves the service, provider, configuration,
confirmation, action, and stale components through `PackageManager`; it pins
the one-required-export widget boundary and the `specialUse` service type.

## Area: PendingIntent integrity

Priority: High

Files: `JetpacsBridgeService.kt`, `Notifications.kt`, `TriggerAlarms.kt`

Implementation: every alarm, service action, content tap, and ordinary
notification action uses an explicit component and `FLAG_IMMUTABLE`. The one
inline-reply action that must accept `RemoteInput` remains mutable but still
targets the app's private explicit receiver. Stable owner/id or trigger keys
prevent unrelated intents from replacing one another.

Representative diff:

```diff
+ Intent(context, JetpacsBridgeService::class.java).setAction(ACTION_STOP)
+ PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
```

Tests: existing notification/action tests compile against the explicit
receivers; the API 34 app smoke verifies the production manifest.

## Area: widget click and stale-alarm capabilities

Priority: High

Files: `companion/app/src/main/AndroidManifest.xml`,
`companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/Widgets.kt`,
`companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/WidgetLifecycle.kt`

Implementation: a rendered click carries only a random 128-bit lowercase-hex
token in an exact `jetpacs-widget-action://action/<token>` URI. The private
explicit receiver rejects a wrong action, scheme, authority, path cardinality,
query, fragment, user-info, expiry, binding, pairing, surface, or revision.
Glance's local AndroidX `actionSendBroadcast(Intent)` implementation preserves
the explicit component, and `ApplyAction.kt` constructs the resulting
`PendingIntent` with `FLAG_IMMUTABLE`. The stale refresh likewise uses an
explicit private receiver and an immutable `PendingIntent`; it carries no
authored or pairing data.

Representative diff:

```diff
+ Intent(context, WidgetActionReceiver::class.java)
+     .setAction(WIDGET_ACTION_BROADCAST)
+     .setData(WidgetActionUri.create(row.token))
+ PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
+ <receiver android:name=".WidgetActionReceiver" android:exported="false" />
+ <receiver android:name=".WidgetStaleReceiver" android:exported="false" />
```

Tests: `WidgetPolicyTest` covers expiry/binding/surface/revision matching and
Parcel limits; `WidgetDaoTest` covers stage-before-publish retention, token
pruning, removal, restore invalidation, and revocation. API 34 instrumentation
pins the private receiver declarations.

## Area: durable widget action admission

Priority: High

Files: `companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/Widgets.kt`,
`wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/ContextlessEvents.kt`

Implementation: widget actions re-enter `dispatchSurfaceOccurrence` with the
pairing-bound token's exact surface and accepted revision. `queue` and `wake`
must commit to the existing Room outbox before Jetpacs wakes Emacs or performs
an authored local `open_surface` adjunct. Queue-full and storage failures do not
claim safe admission. Kotlin never interprets application action names.

Representative diff:

```diff
+ dispatchSurfaceOccurrence(
+     queue = app.stores.queue(),
+     descriptor = descriptor,
+     surface = action.token.surfaceId,
+     revisionSeen = action.token.revision,
+     live = app.stores.liveSession,
+ ) { status, _ -> safelyAdmitted = status != null }
+ if (!durable || safelyAdmitted) openSurface(...)
+ if (safelyAdmitted && policy == "wake") JetpacsBridgeService.enable(context)
```

Tests: `SurfaceOccurrenceTest` proves offline Room admission retains surface,
revision, arguments, and queued time. The EBP durable-queue kill matrix proves
restart/replay with stable event IDs; app tests pin token validity.

## Area: inbound intent handling

Priority: Medium

Files: `Notifications.kt`, `TriggerAlarms.kt`, `JetpacsBridgeService.kt`

Implementation: private receivers require their tuple-identifying extras and
return before mutation if any value is missing or invalid. Reminder alarms now
carry and verify `at_ms`, so a stale alarm cannot mark a replacement reminder
as fired. Work is handed to the bounded process actor before Room or protocol
state is touched. Service commands use private constant actions rather than
forwarding a nested or caller-supplied intent.

Tests: `ReminderBackingTest` rejects a stale expected tuple and proves the
receipt/effect atomic backing is not invoked; wire actor tests cover bounded
ordering and failure isolation.

## Area: foreground and alarm privileges

Priority: Medium

Files: `AndroidManifest.xml`, `JetpacsBridgeService.kt`, `TriggerAlarms.kt`

Implementation: the user-visible service declares the narrow `specialUse`
type and calls `startForeground` immediately. Its low-importance notification
says only that background bridging is enabled and explicitly warns that
Android may stop the process; it never asserts connectivity. Exact scheduling
uses `SCHEDULE_EXACT_ALARM` only when available and falls back to
`setAndAllowWhileIdle` after a denied capability check or race-time
`SecurityException`.

Tests: API 34 instrumentation checks the service type. App lint and both debug
and release manifests are CI gates.
