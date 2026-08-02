// SPDX-License-Identifier: GPL-3.0-or-later
// The SPEC 11 method registry: sender, class, and legal states per method.
// Mirrors ebp/contract.json (format 6); WireConformanceTest pins the two
// together so registry drift is a test failure, not a runtime surprise.
package com.calebc42.ebp.wire

enum class Sender { EMACS, COMPANION, EITHER }

data class MethodSpec(
    val sender: Sender,
    val isRequest: Boolean,
    val states: Set<SessionState>,
)

private val SR = setOf(SessionState.SYNCING, SessionState.READY)
private val R = setOf(SessionState.READY)

// RF-3 (review finding R1-1): the SPEC 22.1 capability registry VOCABULARY,
// not this host's supported subset. checkModules refuses a module capability
// in it — a module capability named after a core capability the host happens
// not to support would otherwise become grantable and flip every core
// string-keyed granted gate (toast.show live on a host that withheld it,
// editor.sync unlocked with its #84 limits floor skipped, the device report
// emitted with zero reserved §4.5 budget). ExtensionSeamTest pins this
// against contract.json's capabilities list, both directions.
val CORE_CAPABILITIES: Set<String> = setOf(
    "surfaces.notification", "surfaces.widget", "surfaces.tile",
    "surfaces.dialog", "presentation.toast", "presentation.pie-menu",
    "theme", "reminders.owner", "editor.sync", "capabilities", "triggers",
    "offline.wake",
    // RF-4a (#154, ratified 2026-08-02): the ebp.data capability — the first use of the
    // reserved prefix, legal precisely because it is spec-ratified.
    "ebp.data",
)

val METHOD_REGISTRY: Map<String, MethodSpec> = mapOf(
    "session.hello" to MethodSpec(Sender.EMACS, true, setOf(SessionState.CONNECTED)),
    "auth.response" to MethodSpec(Sender.EMACS, true, setOf(SessionState.CHALLENGED)),
    "session.ready" to MethodSpec(Sender.EMACS, true, setOf(SessionState.SYNCING)),
    // SPEC 5.2 (#152): sent on the old session's transport before the
    // supersession close; the emit itself lands with the reconnection rung.
    "session.superseded" to MethodSpec(Sender.COMPANION, false, SR),
    "surface.update" to MethodSpec(Sender.EMACS, true, SR),
    "surface.remove" to MethodSpec(Sender.EMACS, true, SR),
    // SPEC 13.1 (#141): explicit floor retirement; the apply lands with RF-3+.
    "surface.release" to MethodSpec(Sender.EMACS, true, R),
    "queue.replay" to MethodSpec(Sender.EMACS, true, SR),
    "event.action" to MethodSpec(Sender.COMPANION, true, SR),
    "state.changed" to MethodSpec(Sender.COMPANION, false, R),
    "dialog.show" to MethodSpec(Sender.EMACS, true, R),
    "toast.show" to MethodSpec(Sender.EMACS, false, R),
    "pie_menu.show" to MethodSpec(Sender.EMACS, false, R),
    "pie_menu.dismiss" to MethodSpec(Sender.EMACS, false, R),
    "theme.set" to MethodSpec(Sender.EMACS, false, SR),
    "reminders.set" to MethodSpec(Sender.EMACS, true, SR),
    "edit.open" to MethodSpec(Sender.COMPANION, false, R),
    "edit.delta" to MethodSpec(Sender.COMPANION, false, R),
    "edit.caret" to MethodSpec(Sender.COMPANION, false, R),
    "edit.close" to MethodSpec(Sender.COMPANION, false, R),
    "edit.complete" to MethodSpec(Sender.COMPANION, true, R),
    "edit.resync" to MethodSpec(Sender.EMACS, true, R),
    "edit.apply" to MethodSpec(Sender.EMACS, true, R),
    "diagnostics.show" to MethodSpec(Sender.EMACS, false, R),
    "eldoc.show" to MethodSpec(Sender.EMACS, false, R),
    "fontify.show" to MethodSpec(Sender.EMACS, false, R),
    "capability.invoke" to MethodSpec(Sender.EMACS, true, R),
    "triggers.set" to MethodSpec(Sender.EMACS, true, SR),
    // SPEC 27 (#154, ratified 2026-08-02): the data projection module — grant-gated
    // stubs answer until RF-4b/4c land the apply.
    "data.schema" to MethodSpec(Sender.EMACS, true, R),
    "data.changeset" to MethodSpec(Sender.EMACS, true, R),
    "log.error" to MethodSpec(Sender.EITHER, false, SR),
    "rpc.cancel" to MethodSpec(Sender.EITHER, false, SR),
)
