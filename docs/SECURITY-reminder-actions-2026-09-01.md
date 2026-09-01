# Best Practices and Security Alignment Update: Reminder PendingIntents

- **Improvement description:** Reminder notification body/action gestures now
  carry only durable reminder coordinates. The receiving component reloads the
  current pairing-scoped descriptor and checks its schedule tuple before
  routing. A gesture that opens an app surface targets a private Activity
  directly instead of using a notification trampoline.
- **Priority level:** High — prevents component hijacking, stale action replay,
  application-semantic injection, and unsafe intent redirection.
- **Alignment action:** PendingIntents are immutable by default. Inline reply
  is the only mutable case and its base Intent still has an explicit private
  target. No nested Intent is accepted or forwarded. The private no-display
  Activity opens only the exact `app:*` value reloaded from Room.

## Files modified

- `companion/app/src/main/AndroidManifest.xml`
- `companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/Notifications.kt`
- `companion/app/src/test/kotlin/com/calebc42/jetpacs/companion/ManifestSecurityTest.kt`
- `companion/app/src/test/kotlin/com/calebc42/jetpacs/companion/ReminderNotificationTest.kt`

## Implementation diff

```diff
--- a/companion/app/src/main/AndroidManifest.xml
+++ b/companion/app/src/main/AndroidManifest.xml
@@
+        <activity
+            android:name=".ReminderInteractionActivity"
+            android:exported="false"
+            android:excludeFromRecents="true"
+            android:noHistory="true"
+            android:theme="@android:style/Theme.NoDisplay" />
```

```diff
--- a/companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/Notifications.kt
+++ b/companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/Notifications.kt
@@
+        val target = if (opensSurface == null) ReminderTapReceiver::class.java
+            else ReminderInteractionActivity::class.java
+        val intent = Intent(ctx, target)
+            .putExtra(ReminderInteraction.OWNER, owner)
+            .putExtra(ReminderInteraction.REMINDER_ID, rid)
+            .putExtra(ReminderInteraction.AT_MS, atMs)
+            .putExtra(ReminderInteraction.ACTION_INDEX, actionIndex)
+        val mutability = if (hasInput) PendingIntent.FLAG_MUTABLE
+            else PendingIntent.FLAG_IMMUTABLE
@@
+        val reminder = app.stores.reminders().reminder(owner, rid)
+            ?: return@submit
+        if (longByValue(reminder["at_ms"]) != atMs) return@submit
@@
+        val descriptor = ReminderInteraction.currentDescriptor(app, intent)
+        val requested = intent.getStringExtra(ReminderInteraction.OPEN_SURFACE)
+        val authorized = descriptor?.stringOr("open_surface")
+        if (requested != null && requested == authorized &&
+            requested.matches(Regex("app:[A-Za-z0-9][A-Za-z0-9._:/-]*"))) {
+            app.requestSurfaceOpenFromPlatform(requested)
+            startActivity(
+                Intent(this, MainActivity::class.java)
+                    .setAction(Intent.ACTION_MAIN)
+                    .addFlags(
+                        Intent.FLAG_ACTIVITY_NEW_TASK or
+                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
+                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
+                    ),
+            )
+        }
```

## Testing and verification

1. `ManifestSecurityTest` proves the interaction Activity is non-exported,
   filter-free, no-history, excluded from recents, and no-display.
2. `ReminderNotificationTest` proves effect payloads retain the accepted
   descriptor and owner/action coordinates cannot share PendingIntent keys.
3. `:app:testDebugUnitTest` compiles the Android implementation and runs the
   manifest/security tests.
4. `:wire:jvmTest` verifies stale tuples and forged injected identity are
   rejected before durable action admission.
