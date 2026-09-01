// SPDX-License-Identifier: GPL-3.0-or-later
// Platform presentation for SPEC 18.5 notification surfaces and SPEC 18.6
// reminders: one notification channel, a poster, and the alarm receiver that
// fires a reminder at its at_ms. This W7 slice presents; routing a
// notification/reminder tap back to Emacs with its offline policy is the
// deeper integration (the shared ReminderStore/queue must outlive a
// connection) noted for a follow-on.
package com.calebc42.jetpacs.companion

import android.app.AlarmManager
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import com.calebc42.ebp.wire.routeReminderAction
import com.calebc42.ebp.wire.routeReminderTap
import com.calebc42.ebp.renderer.model.arrOrNull
import com.calebc42.ebp.renderer.model.boolOr
import com.calebc42.ebp.renderer.model.longByValue
import com.calebc42.ebp.renderer.model.objOrNull
import com.calebc42.ebp.renderer.model.stringOr
import com.calebc42.ebp.renderer.model.stringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal const val ANDROID_EMACS_PACKAGE = "org.gnu.emacs"

object Notifications {
    const val CHANNEL = "ebp"
    internal const val RECONNECT_CHANNEL = "jetpacs_reconnect"
    internal const val RECONNECT_TAG = "emacs-reconnect"
    internal const val RECONNECT_ID = 7712

    fun ensureChannel(ctx: Context) {
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null)
            mgr.createNotificationChannel(NotificationChannel(
                CHANNEL, "EBP", NotificationManager.IMPORTANCE_HIGH))
    }

    fun post(ctx: Context, tag: String, id: Int, title: String, body: String?,
             ongoing: Boolean = false) {
        ensureChannel(ctx)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .also { if (!body.isNullOrEmpty()) it.setContentText(body) }
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(tag, id, n)
    }

    /**
     * Present the one-tap recovery path after an authenticated Emacs session
     * disappears. The PendingIntent targets Emacs's launcher activity
     * directly; routing through a broadcast receiver would be a forbidden
     * notification trampoline on modern Android.
     */
    fun postReconnect(ctx: Context) {
        val launch = ctx.packageManager
            .getLaunchIntentForPackage(ANDROID_EMACS_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return
        val manager = ctx.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(RECONNECT_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    RECONNECT_CHANNEL,
                    "Emacs reconnection",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        val openEmacs = PendingIntent.getActivity(
            ctx,
            RECONNECT_ID,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(ctx, RECONNECT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Emacs disconnected")
            .setContentText("Tap to open Android Emacs and reconnect Jetpacs.")
            .setContentIntent(openEmacs)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        // Notification permission can be revoked independently of the app.
        // A missing presentation grant must not take down the bridge thread.
        runCatching { manager.notify(RECONNECT_TAG, RECONNECT_ID, notification) }
    }

    /** A completed handshake means the recovery prompt is no longer useful. */
    fun cancelReconnect(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)
            .cancel(RECONNECT_TAG, RECONNECT_ID)
    }

    const val REMINDER_TAG = "reminder"
    private const val MAX_VISIBLE_REMINDER_ACTIONS = 3

    /** SPEC 18.6: a collision-resistant, OWNER-scoped key for a reminder's alarm
     * request code, tap PendingIntent, and notification id — a truncated SHA-256
     * of (owner, id), NOT a trivially-collidable String.hashCode. Reminder ids
     * are unique only within an owner, so two owners sharing an id must not
     * share an alarm slot or replace each other's notification. */
    fun reminderKey(owner: String, id: String): Int {
        val d = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$owner\u0000$id".toByteArray(Charsets.UTF_8))
        return ((d[0].toInt() and 0xff) shl 24) or ((d[1].toInt() and 0xff) shl 16) or
            ((d[2].toInt() and 0xff) shl 8) or (d[3].toInt() and 0xff)
    }

    /** C6, decided once for this file: org.json's `getString` THREW on an absent
     * or non-string member, so the port throws too. Every reminder that reaches
     * the reconciliation came out of the ReminderStore already accepted against
     * the SPEC 18.6 schema, so a fault here is a real bug rather than hostile
     * traffic — and folding a missing `id` into "" would arm the alarm under the
     * WRONG owner-scoped key and orphan it forever, which is strictly worse than
     * the throw. Genuinely optional members (`body`) keep their tolerant fold. */
    private fun JsonObject.requiredString(k: String): String =
        stringOrNull(k) ?: throw NoSuchElementException(k)

    /**
     * SPEC 18.6: reconcile the owner's platform alarms with the new set —
     * CANCEL alarms for removed ids, and (re)arm only tuples that have not
     * already fired. Skipping fired tuples (with the receiver's own fired-state
     * gate) is what stops a re-push of an unchanged, already-past reminder from
     * re-firing. The payload rides the intent so the receiver can present with
     * no live connection.
     */
    fun scheduleReminders(
        ctx: Context,
        stores: CompanionStores,
        owner: String,
        newSet: JsonArray,
        priorSet: JsonArray,
    ) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val store = stores.reminders()
        val nextById = (0 until newSet.size).associate {
            val reminder = newSet[it] as JsonObject
            reminder.requiredString("id") to reminder
        }
        for (i in 0 until priorSet.size) {
            val prior = priorSet[i] as JsonObject
            val id = prior.requiredString("id")
            val next = nextById[id]
            if (next != null && longByValue(prior["at_ms"]) ==
                longByValue(next["at_ms"])) continue
            cancelReminderTuple(ctx, am, owner, id)
        }
        for (i in 0 until newSet.size) {
            val r = newSet[i] as JsonObject
            val id = r.requiredString("id")
            if (store.isFired(owner, id)) continue // already presented — do not re-arm
            val atMs = longByValue(r["at_ms"]) ?: throw NoSuchElementException("at_ms")
            val intent = Intent(ctx, ReminderAlarmReceiver::class.java)
                .putExtra("owner", owner).putExtra("rid", id)
                .putExtra("at_ms", atMs)
            val pi = PendingIntent.getBroadcast(ctx, reminderKey(owner, id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            // C6: `at_ms` PASSES THROUGH the engine verbatim and acceptance checks
            // its integrality BY VALUE, so `"at_ms": 5000.0` is legal, P0-pinned
            // working traffic that org.json's getLong truncated. longByValue is
            // that same two-step read — integer spelling first, then the binary64
            // value truncated — which is exactly what :wire's own ReminderStore
            // does privately. A strict reader would throw inside the engine's
            // reminderListener on the accept path: no alarm ever armed, and the
            // accept faults. getLong threw on an absent member, so the throw stays.
            scheduleExactBestEffort(
                am,
                AlarmManager.RTC_WAKEUP,
                atMs,
                pi,
            )
        }
    }

    private fun cancelReminderTuple(
        ctx: Context,
        alarmManager: AlarmManager,
        owner: String,
        reminderId: String,
    ) {
        PendingIntent.getBroadcast(
            ctx,
            reminderKey(owner, reminderId),
            Intent(ctx, ReminderAlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )?.let { pending ->
            alarmManager.cancel(pending)
            pending.cancel()
        }
        ctx.getSystemService(NotificationManager::class.java)
            .cancel(REMINDER_TAG, reminderKey(owner, reminderId))
    }

    /** SPEC 18.6: re-arm every owner's unfired reminders from the durable store.
     * AlarmManager loses alarms across a reboot or a force-stop, so a cold start
     * (and a wall-clock change) must re-establish them. Arm-only (prior empty):
     * nothing is cancelled, and already-fired tuples stay skipped, so this never
     * re-presents a past reminder. */
    fun rearmAllReminders(ctx: Context, stores: CompanionStores) {
        val store = stores.reminders()
        for (owner in store.owners())
            scheduleReminders(ctx, stores, owner, JsonArray(store.reminders(owner)),
                JsonArray(emptyList()))
    }

    /** SPEC 18.6: present a reminder with a tap route into the Section 14
     * pipeline via ReminderTapReceiver (works whether or not a session is up). */
    fun postReminder(
        ctx: Context,
        owner: String,
        rid: String,
        atMs: Long,
        reminder: JsonObject,
    ) {
        ensureChannel(ctx)
        val contentIntent = reminder.objOrNull("on_tap")?.let { descriptor ->
            reminderPendingIntent(
                ctx,
                owner,
                rid,
                atMs,
                ReminderInteraction.BODY_INDEX,
                descriptor,
                hasInput = false,
            )
        }
        val builder = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(reminder.requiredString("title"))
            .also {
                reminder.stringOr("body").takeIf(String::isNotEmpty)
                    ?.let(it::setContentText)
            }
            .also { contentIntent?.let(it::setContentIntent) }
            .setCategory(Notification.CATEGORY_REMINDER)
            // SPEC 18.6/14.4: NOT setAutoCancel — the tap receiver dismisses only
            // AFTER safe admission (a lost/failed tap keeps its notification).
            .setAutoCancel(false)
        reminder.arrOrNull("actions")?.let { actions ->
            repeat(minOf(actions.size, MAX_VISIBLE_REMINDER_ACTIONS)) { index ->
                val action = actions[index] as? JsonObject ?: return@repeat
                builder.addAction(buildReminderAction(
                    ctx,
                    owner,
                    rid,
                    atMs,
                    index,
                    action,
                ))
            }
        }
        ctx.getSystemService(NotificationManager::class.java)
            .notify(REMINDER_TAG, reminderKey(owner, rid), builder.build())
    }

    private fun buildReminderAction(
        ctx: Context,
        owner: String,
        rid: String,
        atMs: Long,
        index: Int,
        action: JsonObject,
    ): Notification.Action {
        val descriptor = action.objOrNull("on_tap") ?: JsonObject(emptyMap())
        val input = action.objOrNull("input")
        val replyKey = input?.stringOr("key")?.ifEmpty { "reply" }
        val pending = reminderPendingIntent(
            ctx,
            owner,
            rid,
            atMs,
            index,
            descriptor,
            hasInput = input != null,
        )
        val builder = Notification.Action.Builder(
            Icon.createWithResource(ctx, notifIconRes(action.stringOr("icon"))),
            action.stringOr("label"),
            pending,
        )
        if (replyKey != null) {
            builder.addRemoteInput(
                RemoteInput.Builder(replyKey)
                    .also {
                        input.stringOr("hint").takeIf(String::isNotEmpty)
                            ?.let(it::setLabel)
                    }
                    .build(),
            )
        }
        return builder.build()
    }

    private fun reminderPendingIntent(
        ctx: Context,
        owner: String,
        rid: String,
        atMs: Long,
        actionIndex: Int,
        descriptor: JsonObject,
        hasInput: Boolean,
    ): PendingIntent {
        val opensSurface = descriptor.stringOr("open_surface")
            .takeIf { it.startsWith("app:") }
        val target = if (opensSurface == null) ReminderTapReceiver::class.java
            else ReminderInteractionActivity::class.java
        val intent = Intent(ctx, target)
            .putExtra(ReminderInteraction.OWNER, owner)
            .putExtra(ReminderInteraction.REMINDER_ID, rid)
            .putExtra(ReminderInteraction.AT_MS, atMs)
            .putExtra(ReminderInteraction.ACTION_INDEX, actionIndex)
            .also { opensSurface?.let { surface ->
                it.putExtra(ReminderInteraction.OPEN_SURFACE, surface)
            } }
        val requestCode = reminderKey(owner, "$rid/action/$actionIndex")
        val mutability = if (hasInput) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        return if (opensSurface == null) {
            PendingIntent.getBroadcast(
                ctx,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutability,
            )
        } else {
            PendingIntent.getActivity(
                ctx,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutability,
            )
        }
    }

    /** SPEC 18.5: priority -> a per-priority channel (Android channel
     * importance is immutable, so distinct priorities get distinct channels);
     * the meta `channel` identifier namespaces them. */
    private fun channelFor(ctx: Context, channel: String, priority: String): String {
        val id = "ebp:${channel.ifEmpty { "default" }}:$priority"
        val mgr = ctx.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(id) == null) {
            val importance = when (priority) {
                "min" -> NotificationManager.IMPORTANCE_MIN
                "low" -> NotificationManager.IMPORTANCE_LOW
                "high", "max" -> NotificationManager.IMPORTANCE_HIGH
                else -> NotificationManager.IMPORTANCE_DEFAULT
            }
            mgr.createNotificationChannel(NotificationChannel(id, "EBP $priority", importance))
        }
        return id
    }

    /** SPEC 18.5: present a notification:* surface with its meta (channel/
     * ongoing/category/priority/chronometer) and ordered actions (label/icon/
     * on_tap, optional inline reply via `input`, safe-admission `dismiss`). */
    fun postSurface(ctx: Context, surface: String, spec: JsonObject) {
        val meta = spec.objOrNull("meta") ?: JsonObject(emptyMap())
        val priority = meta.stringOr("priority", "default")
        val channelId = channelFor(ctx, meta.stringOr("channel"), priority)
        val id = surface.hashCode()

        // SPEC 13.4/18.5: a notification surface is {body: Node, meta?} — the
        // renderable content lives under `body`, not a top-level `children`.
        var title: String? = null
        val lines = mutableListOf<String>()
        val visit: (JsonObject) -> Unit = { node ->
            if (node.stringOr("t") == "text") {
                val t = node.stringOr("text")
                if (node.stringOr("style") == "title" && title == null) title = t
                else if (t.isNotEmpty()) lines.add(t)
            }
        }
        spec.objOrNull("body")?.let { body ->
            // The body node itself may be a text leaf or a container.
            if (body.stringOr("t") in setOf("row", "column", "box"))
                forEachLeaf(body.arrOrNull("children"), visit)
            else visit(body)
        }
        val b = Notification.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title ?: surface.substringAfter(':'))
            .setOngoing(meta.boolOr("ongoing"))
            .setAutoCancel(!meta.boolOr("ongoing"))
            .setOnlyAlertOnce(true)
        if (lines.isNotEmpty()) b.setContentText(lines.joinToString("  "))
        meta.stringOr("category").takeIf { it.isNotEmpty() }?.let { b.setCategory(it) }
        meta.objOrNull("chronometer")?.let { chrono ->
            // C6: `base_ms` is validated integral BY VALUE (SpecValidator floors an
            // asDoubleOrNull), so 1784700000000.0 is accepted and optLong truncated
            // it. A strict read yields null -> 0L -> the `> 0L` guard below silently
            // drops the chronometer, so the read must be by value too.
            val baseMs = longByValue(chrono["base_ms"]) ?: 0L
            if (baseMs > 0L) {
                b.setUsesChronometer(true).setShowWhen(true).setWhen(baseMs)
                b.setChronometerCountDown(chrono.boolOr("count_down"))
            }
        }
        // SPEC 18.5: actions in authored order; the platform MAY show fewer.
        meta.arrOrNull("actions")?.let { actions ->
            for (i in 0 until actions.size) {
                val a = actions[i] as? JsonObject ?: continue
                b.addAction(buildAction(ctx, surface, id, a))
            }
        }
        ctx.getSystemService(NotificationManager::class.java).notify(surface, id, b.build())
    }

    private fun buildAction(ctx: Context, surface: String, notifId: Int, a: JsonObject): Notification.Action {
        val label = a.stringOr("label")
        val onTap = a.objOrNull("on_tap") ?: JsonObject(emptyMap())
        val input = a.objOrNull("input")
        val replyKey = input?.let { it.stringOr("key").ifEmpty { "reply" } }
        val intent = Intent(ctx, NotificationActionReceiver::class.java)
            .putExtra("surface", surface).putExtra("notif_id", notifId)
            // C6: kotlinx's toString() IS the wire serializer, so the extra keeps
            // its shape; NotificationActionReceiver parses it back leniently.
            .putExtra("on_tap", onTap.toString())
            .putExtra("dismiss", a.boolOr("dismiss"))
            .also { replyKey?.let { k -> it.putExtra("reply_key", k) } }
        val rc = "$surface/$label".hashCode()
        // SPEC 18.5: an inline-reply action needs a MUTABLE PendingIntent so the
        // platform can inject the RemoteInput results; others stay immutable.
        val mutability = if (replyKey != null) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getBroadcast(ctx, rc, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutability)
        val ab = Notification.Action.Builder(
            Icon.createWithResource(ctx, notifIconRes(a.stringOr("icon"))), label, pi)
        if (replyKey != null) {
            ab.addRemoteInput(android.app.RemoteInput.Builder(replyKey)
                .also { input.stringOr("hint").takeIf { h -> h.isNotEmpty() }?.let(it::setLabel) }
                .build())
        }
        return ab.build()
    }

    /** Best-effort action icon name -> a platform drawable (cosmetic; Android
     * rarely draws action icons in the shade). */
    internal fun notifIconRes(name: String): Int = when (name) {
        "close", "cancel", "dismiss" -> android.R.drawable.ic_menu_close_clear_cancel
        "delete", "trash" -> android.R.drawable.ic_menu_delete
        "edit" -> android.R.drawable.ic_menu_edit
        "send", "reply" -> android.R.drawable.ic_menu_send
        "add" -> android.R.drawable.ic_menu_add
        "share" -> android.R.drawable.ic_menu_share
        "save", "done", "check" -> android.R.drawable.ic_menu_save
        "snooze", "alarm", "timer" -> android.R.drawable.ic_popup_reminder
        else -> android.R.drawable.ic_media_play
    }

    fun cancelSurface(ctx: Context, surface: String) {
        ctx.getSystemService(NotificationManager::class.java)
            .cancel(surface, surface.hashCode())
    }

    /** SPEC 21.4: present a trigger on_fire {title?, text} notification. With
     * no title the text becomes the title, matching a simple local alert. */
    fun postTrigger(ctx: Context, notify: JsonObject) {
        val text = notify.stringOr("text")
        val title = notify.stringOr("title").ifEmpty { text }
        val body = if (title == text) null else text
        post(ctx, "trigger", (title + body).hashCode(), title, body)
    }

    /** Visit leaf nodes, descending through row/column/box containers. */
    private fun forEachLeaf(children: JsonArray?, visit: (JsonObject) -> Unit) {
        if (children == null) return
        for (i in 0 until children.size) {
            val node = children[i] as? JsonObject ?: continue
            when (node.stringOr("t")) {
                "row", "column", "box" -> forEachLeaf(node.arrOrNull("children"), visit)
                else -> visit(node)
            }
        }
    }

    private fun collectText(node: JsonObject): String {
        if (node.stringOr("t") == "text") return node.stringOr("text")
        val out = StringBuilder()
        node.arrOrNull("children")?.let { kids ->
            for (i in 0 until kids.size) {
                (kids[i] as? JsonObject)?.let {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(collectText(it))
                }
            }
        }
        return out.toString()
    }
}

/** Only the loss of the authoritative authenticated session is user-visible. */
internal fun shouldPostReconnectNotification(
    authenticated: Boolean,
    wasCurrentSession: Boolean,
): Boolean = authenticated && wasCurrentSession

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val owner = intent.getStringExtra("owner") ?: return
        val rid = intent.getStringExtra("rid") ?: return
        val atMs = intent.getLongExtra("at_ms", -1).takeIf { it >= 0 } ?: return
        val app = ctx.applicationContext as JetpacsApplication
        // SPEC 18.6: markFired commits Room state off the main thread.
        val pending = goAsync()
        if (!app.stores.submit {
            try {
                // The receipt and idempotent presentation work enter Room in one
                // commit. Presentation and its completion marker follow outside
                // that transaction, and cold-start reconciliation retries a
                // crash-stranded effect using the same notification tag/id.
                val createdAt = System.currentTimeMillis().coerceAtLeast(0)
                val store = app.stores.reminders()
                val reminder = store.reminder(owner, rid) ?: return@submit
                if (longByValue(reminder["at_ms"]) != atMs) return@submit
                val effect = PlatformEffectReconciler.reminderNotification(
                    owner, rid, atMs, reminder, createdAt,
                )
                if (store.markFired(
                        owner = owner,
                        id = rid,
                        presentationEffect = effect,
                        expectedAtMs = atMs,
                    )) app.stores.reconcilePlatformEffects()
            } finally { pending.finish() }
        }) pending.finish()
    }
}

/** SPEC 18.5: a notification action tap routes its remote on_tap through the
 * Section 14 pipeline (cold-safe, like a reminder tap). An inline reply's typed
 * text (from RemoteInput) is placed in event.action.fields under the action's
 * key; a `dismiss:true` action cancels the notification only after the tap is
 * safely admitted (§14.4). */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val onTapStr = intent.getStringExtra("on_tap") ?: return
        val surface = intent.getStringExtra("surface") ?: return
        val notifId = intent.getIntExtra("notif_id", 0)
        val dismiss = intent.getBooleanExtra("dismiss", false)
        val replyKey = intent.getStringExtra("reply_key")
        val replyText = replyKey?.let {
            android.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(it)?.toString()
        }
        val app = ctx.applicationContext as JetpacsApplication
        // C6: this string rides a PendingIntent that a PRE-UPGRADE build may have
        // created, so it can still be org.json-spelled JSON sitting in the
        // platform. Parse it leniently (never the strict wire parser) or every
        // already-posted notification's action stops routing across the upgrade.
        // The existing catch covers the new SerializationException /
        // IllegalArgumentException / ClassCastException the same way it covered
        // JSONException.
        val onTap = try { Json.parseToJsonElement(onTapStr) as JsonObject }
            catch (e: Exception) { return }
        val pending = goAsync()
        if (!app.stores.submit {
            try {
                com.calebc42.ebp.wire.routeNotificationAction(
                    app.stores.queue(), CompanionStores.MAX_EVENT_BYTES,
                    onTap, replyKey, replyText, app.stores.liveSession,
                    maxFieldBytes = CompanionStores.MAX_FIELD_BYTES
                ) { status, error ->
                    // SPEC 18.5/14.4: dismiss ONLY after safe admission.
                    if (dismiss && error == null &&
                        status in setOf("queued", "accepted", "duplicate"))
                        app.getSystemService(NotificationManager::class.java)
                            .cancel(surface, notifId)
                }
            } finally { pending.finish() }
        }) pending.finish()
    }
}

/** SPEC 18.6: a reminder tap enters the Section 14 pipeline with the authored
 * offline policy. Works cold (no live engine): queue/wake taps admit to the
 * shared durable queue and replay on the next connect; a drop delivers live
 * only through the current session. */
internal object ReminderInteraction {
    const val BODY_INDEX = -1
    const val OWNER = "owner"
    const val REMINDER_ID = "rid"
    const val AT_MS = "at_ms"
    const val ACTION_INDEX = "action_index"
    const val OPEN_SURFACE = "open_surface"

    fun currentDescriptor(app: JetpacsApplication, intent: Intent): JsonObject? {
        val owner = intent.getStringExtra(OWNER) ?: return null
        val rid = intent.getStringExtra(REMINDER_ID) ?: return null
        val atMs = intent.getLongExtra(AT_MS, -1).takeIf { it >= 0 } ?: return null
        val reminder = app.stores.reminders().reminder(owner, rid) ?: return null
        if (longByValue(reminder["at_ms"]) != atMs) return null
        val index = intent.getIntExtra(ACTION_INDEX, BODY_INDEX)
        return if (index == BODY_INDEX) reminder.objOrNull("on_tap")
        else (reminder.arrOrNull("actions")?.getOrNull(index) as? JsonObject)
            ?.objOrNull("on_tap")
    }

    fun dispatch(ctx: Context, intent: Intent, onFinished: () -> Unit = {}) {
        val owner = intent.getStringExtra(OWNER) ?: return onFinished()
        val rid = intent.getStringExtra(REMINDER_ID) ?: return onFinished()
        val atMs = intent.getLongExtra(AT_MS, -1).takeIf { it >= 0 }
            ?: return onFinished()
        val index = intent.getIntExtra(ACTION_INDEX, BODY_INDEX)
        val app = ctx.applicationContext as JetpacsApplication
        val pendingResults = RemoteInput.getResultsFromIntent(intent)
        if (!app.stores.submit {
          try {
            val reminder = app.stores.reminders().reminder(owner, rid)
                ?: return@submit
            if (longByValue(reminder["at_ms"]) != atMs) return@submit
            if (index == BODY_INDEX) {
                routeReminderTap(
                    app.stores.reminders(),
                    app.stores.queue(),
                    CompanionStores.MAX_EVENT_BYTES,
                    owner,
                    rid,
                    app.stores.liveSession,
                ) { status, error ->
                    if (error == null && status in
                        setOf("queued", "accepted", "duplicate")) {
                        app.getSystemService(NotificationManager::class.java)
                            .cancel(Notifications.REMINDER_TAG,
                                Notifications.reminderKey(owner, rid))
                    }
                }
                return@submit
            }
            val action = reminder.arrOrNull("actions")?.getOrNull(index)
                as? JsonObject ?: return@submit
            val input = action.objOrNull("input")
            val replyKey = input?.stringOr("key")?.ifEmpty { "reply" }
            val replyText = replyKey?.let { key ->
                pendingResults?.getCharSequence(key)?.toString()
            }
            routeReminderAction(
                app.stores.reminders(),
                app.stores.queue(),
                CompanionStores.MAX_EVENT_BYTES,
                owner,
                rid,
                index,
                expectedAtMs = atMs,
                replyText = replyText,
                live = app.stores.liveSession,
                maxFieldBytes = CompanionStores.MAX_FIELD_BYTES,
            ) { status, error ->
                if (action.boolOr("dismiss") && error == null && status in
                    setOf("queued", "accepted", "duplicate")) {
                    app.getSystemService(NotificationManager::class.java)
                        .cancel(Notifications.REMINDER_TAG,
                            Notifications.reminderKey(owner, rid))
                }
            }
          } finally {
            onFinished()
          }
        }) onFinished()
    }
}

class ReminderTapReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        ReminderInteraction.dispatch(ctx, intent, pending::finish)
    }
}

/** Direct notification Activity target for reminder gestures that also open
 * an app surface. Modern Android prohibits a broadcast/service notification
 * trampoline; this non-exported no-display Activity validates the descriptor
 * from Room, selects the cached surface, launches the host, and separately
 * admits the remote occurrence through the same durable router. */
class ReminderInteractionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as JetpacsApplication
        val descriptor = ReminderInteraction.currentDescriptor(app, intent)
        val requested = intent.getStringExtra(ReminderInteraction.OPEN_SURFACE)
        val authorized = descriptor?.stringOr("open_surface")
        if (requested != null && requested == authorized &&
            requested.matches(Regex("app:[A-Za-z0-9][A-Za-z0-9._:/-]*"))) {
            app.requestSurfaceOpenFromPlatform(requested)
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }
        ReminderInteraction.dispatch(this, intent)
        finish()
    }
}
