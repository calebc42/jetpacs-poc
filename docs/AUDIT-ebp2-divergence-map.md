# AUDIT — EBP 2.0.0-draft divergence map

**What this is.** The port-safe vs must-rebuild partition of the Jetpacs PoC
against the promoted EBP spec (`ebp/llm-poc/SPEC.md`, protocol 2, document
2.0.0-draft, contract format 6, ebp commit `1910466`), plus the spec-weakness
register the comparison surfaced. Produced 2026-07-22 to inform the
rewrite-vs-freeze-vs-retrofit decision; it is the audit's static half. The
dynamic half (runtime behavior only tests can witness) is listed at the end.

**Method.** The format-5 `contract.json` was byte-synced to this
implementation at ebp amendment #30, so the format-5 → SPEC.md delta is a
high-confidence violation list without reading every line. That mechanical
diff was then confirmed and extended by a module-by-module inventory of both
endpoints (~24k lines elisp, ~16k lines Kotlin across 75 files).

**Headline.** The delta is structural, not additive: five full inversions
(notification ↔ request), a new session lifecycle, a new durability protocol,
and a renamed error registry. The wire cores of both endpoints
(~3.5k lines Kotlin, ~4.5k lines elisp) must be rebuilt or heavily reworked;
the renderers, capability effectors, org layer, and app chrome
(~7k lines Kotlin, ~18k lines elisp) survive a rewrite as ports. One piece of
good news: the durable queue already lives on the Companion and Emacs is the
client — the *responsibility split* matches the spec; only the protocol shape
around it does not.

---

## 1. Wire delta (mechanical, from the contract diff)

### 1.1 Method-level

| Area | PoC today | SPEC 2.0.0-draft | Class |
|---|---|---|---|
| `surface.update` | notification `{surface, revision, spec, ttl_s?, stale_spec?, current_view?}` | request; `ttl_s`→`stale_after_s`; + `reset_input_ids`; result `{status, revision, present}` applied/stale idempotency (§13.2) | INVERSION |
| `surface.remove` | notification `{surface}`, hard delete | request `{surface, revision}`; revisioned tombstone, floors retained until revocation (§13.3) | INVERSION |
| `event.action` | notification `{action, args?, surface?, revision_seen?, fields?, queued_at?}` | request; `event_id` + `occurred_at_ms` REQUIRED; `queued_at_ms`; `dialog_id` context; result `accepted/duplicate/stale/rejected`; Emacs retains EventId receipts 604800 s; `1500 event-retry` (§14.4) | INVERSION |
| `state.changed` | notification `{id, value, surface?}` | `surface` + `revision_seen` REQUIRED; flush barrier vs `surface.update`; ≤500 ms debounce; state-before-action flush (§14.6, P1 #2) | BREAKING |
| `session.hello` | `{protocol, client, wants, features?}` → `{nonce}` | `{protocol, client, pairing_id, client_nonce, wants}` → `{server_nonce}` (§9.2) | BREAKING |
| `auth.response` | `{nonce, mac}`; HMAC over `"ebp1:client:…"`, 80-bit Crockford token | `{pairing_id, client_nonce, server_nonce, client_proof}`; HMAC over `"EBP/2 client:PID:CN:SN"`, 16-octet base64url token + 32-hex pairing ID, normative KAT; welcome overhauled (`surface_profiles`, revision+tombstone floors, `input_state`, `limits` + reservation math) (§9, §10.2, §4.5) | INVERSION |
| `session.ready` / SYNCING | absent — binary `helloComplete` | NEW: CONNECTED→CHALLENGED→SYNCING→READY, sync barrier, ordered pre-READY flush (§10.1–10.3) | NEW |
| `queue.replay` | result `{delivered, expired, duplicate_request?}`; rows deleted after frame *write* | result `{delivered, rejected, expired, remaining, blocked_by}`; single pump, durable `queue_seq` FIFO, delete only after permanent result, `1600 queue-busy`, expiry high-water mark (§15) | BREAKING |
| dialogs | `dialog.show` notification (bare node); dismissal via custom `prompt.dismiss` event | `dialog.show` request `{dialog_id, spec, style?}` held outstanding; completion via `dialog.submit`/`dialog.dismiss` **builtins**; result `{status, value?, fields?}`; `max_dialogs`; submit frame-size pre-check (§18.1) | INVERSION |
| editor sync | Companion legs ride `event.action` (`edit.open/delta/…` as action names, key `file`, int session); `edit.apply`/`edit.resync` inbound *notifications*, self-validated; `completions.show` client notification | First-class methods both directions; keys `document` + `editor_id`; 32-hex CSPRNG session; `edit.complete` Companion *request*; `edit.apply`/`edit.resync` Emacs *requests* answered `{status, seq}`; OPEN/STALE/CLOSED machine; `diagnostics` not `diags`; `completions.show` GONE (§19) | INVERSION |
| `triggers.set` | result `{}` | result `{count}`; atomic validation incl. permission descriptors; unchanged-ID runtime-state preservation (P1 #1); occurrence transaction (§21.1–21.2) | BREAKING |
| `reminders.set` | `{reminders, owner?}` → `{}` | `owner` REQUIRED; `{count}`; fired-state tuple rules (§18.6) | BREAKING |
| `capability.invoke` | result wrapped `{result?}` | result IS the catalog row, unwrapped; closed args; typed 1001/1002/1003 (§20.2) | BREAKING |
| `pie_menu.show` | `{categories, center_label?, buffer?}` | `{menu_id, categories, center_label?}`; per-ID replace; `max_pie_menus`; drop-only (§18.3) | BREAKING |
| `toast.show` | `{text}` | + `duration_s` 1..10 (§18.2) | ADDITIVE |
| `log.error` | companion-only | either direction; overload close protocol (§22.3) | ADDITIVE |

### 1.2 Registry-level

- **Errors**: `1201 spec-invalid`→`content-invalid`; PoC's pre-auth code is
  `1000`, spec's is `1200`; NEW `1204 session-state`, `1500 event-retry`,
  `1600 queue-busy`, `1601 queue-full`; `1400 frame-too-large` removed
  (oversize now closes, §6.2).
- **Actions**: offline default flipped `queue`→`drop`; `ttl_s` required for
  queue/wake and forbidden for drop; `capture_fields` is NEW (occurrence-time
  atomic capture — no PoC equivalent); `wake` gated on `offline.wake` grant +
  OS-target re-check; builtins gain `trigger.fire`, `dialog.submit`,
  `dialog.dismiss`.
- **Nodes**: slider `steps`→`values`; `enabled` on every input node;
  universal attributes (`pad`, `corner`, `border`, `alpha`, `clip`,
  `align_self`, min/max dims); `single_line` U+000A prohibition (P1 #3);
  password lifecycle (volatile-only, 30 s deadline, erasure — §14.6);
  presentation-identity preservation for collapsible/tabs (§16.1).
- **Negotiation**: flat `node_types` → per-target `surface_profiles`
  (`node_types`+`builtins`+`features`); welcome `limits` object with floors
  and the §4.5 reservation inequality.
- **Envelope**: request IDs must be strings ≤64 octets (PoC accepts numbers);
  duplicate-member rejection; header-section 8192 limit.

---

## 2. Module partition — Kotlin (companion)

### Must-rebuild (~1.5k lines)
- `JetpacsAuth.kt` — wrong HMAC strings, token format, no pairing ID, no KAT.
- `JetpacsConnection.kt` — no SYNCING/READY; wrong method classes for
  surface/dialog/editor traffic; wrong welcome; wrong pre-auth code.
- `ActionReceiver.kt` + `JetpacsDatabase.kt` (queue schema) — no `event_id`,
  no `occurred_at_ms`, no `queue_seq`, delete-after-write (loses events on
  connection death mid-flight — the spec's central durability guarantee), no
  4-status result handling, no `capture_fields`, no password path.
- `JetpacsDialogState.kt` — dismissal emits `prompt.dismiss` instead of
  completing an outstanding request.
- `EditorSync.kt` message layer — wrong carrier, keys, session type,
  request/notification classes. **Keep** the shadow/splice/UTF-16 math and
  `applyExternal` gating logic.

### Rework in place (~2k lines)
- `FrameCodec.kt` — framing is already byte-accurate; add close-on-oversize,
  8192 header cap, duplicate-CL close, leading-zero rule.
- `Envelope.kt` — string-only IDs, duplicate-member rejection.
- `SurfaceStore.kt`/`SurfaceManager.kt` — add tombstones, input-draft store +
  §13.6 reconciliation, `reset_input_ids`, applied/stale results.
- `TriggerHost.kt` — pipeline/gates/interpolation are close in spirit;
  violations: throttle state is an in-memory static map (spec: persist across
  restart), battery hysteresis re-baselines on every replace-set (violates
  §21.1 unchanged-ID preservation), no boot-generation receipt, no durable
  one-shot completed marker, result `{}` not `{count}`, no §21.2 transaction.
- `Reminders.kt` — required owner, `{count}`, fired-state tuple rules.
- `DeviceCapabilities.kt` — unwrap results; audit closed args per catalog.
- `InboundPipeline.kt` — concept matches §22.2/22.3; re-key to new classes.

### Port-safe (~7k+ lines)
All `Sdui*.kt` renderers (~5.9k), `ThemeBridge`, `SyntaxHighlight`,
`IconMap`, `RadialMenu`, `ReorderableList`, `NotificationRenderer`,
widget/tile chrome, `EmacsWaker`, the app module (MainActivity, Onboarding,
Settings). Vocabulary updates only (slider `values`, universal attrs,
`enabled`).

---

## 3. Module partition — elisp (client)

### Must-rebuild (~2.9k lines)
- `jetpacs.el` (867) — handshake/auth (nonces, proofs, pairing ID), session
  lifecycle (add SYNCING, the §10.3 barrier, `session.ready`), welcome
  absorption (profiles, floors, tombstones, limits, `input_state`).
- `jetpacs-sync.el` (923) — editor module: new methods/keys/classes; answer
  `edit.complete`; issue `edit.apply`/`edit.resync` as requests and handle
  typed stale results.
- `jetpacs-minibuffer.el` (919) — dialogs: `dialog.show` as a held request
  with builtin completion, replacing the `prompt.*` action family.
- `jetpacs-complete.el` (247) — invert: serve `edit.complete` instead of
  pushing `completions.show`.

### Rework in place (~1.5k lines)
- `jetpacs-surfaces.el` (785) — revisions persist ✓, outbox conflation ✓;
  change `surface.update`/`remove` to requests, absorb applied/stale results,
  author `reset_input_ids`, and add the **event.action server**: validate
  allowlist/args/revision, return the 4-status result, keep the EventId
  receipt store (NEW subsystem — the PoC has no event-ID layer at all), and
  the P1 #2 `state.changed` reconciliation rules.
- `jetpacs-triggers.el` (296), `jetpacs-device.el` (401) — result shapes,
  unwrapped capability results, required owner.
- `jetpacs-theme.el` (454) — port-safe except the `base` question (§5 below).

### Port-safe (~18k lines)
Every ABOVE-BOUNDARY module (org layer 2797, files 1064, shell 853, demo,
settings, apps, satellites…) and the WIRE-ADJACENT builders (widgets 1440,
hypertext 859, buffer 758, keymap 719, sections, results, tablist, spec,
source, async, comint) — vocabulary-only updates. `jetpacs-lint.el` derives
its tables from `ebp/contract.json` at load, so most vocabulary changes are
absorbed by pointing it at format 6.

---

## 4. Obligations with no PoC counterpart (both sides, from SPEC §24)

1. Emacs durable EventId receipt store + `accepted`-means-committed rule
   (§14.4) — the at-least-once/exactly-once-effect boundary.
2. Companion input-draft store + §13.6 compatibility reconciliation +
   welcome `input_state`.
3. The §4.5 welcome reservation inequality and limit floors.
4. Password lifecycle (§14.6): volatile-only, 30 s deadline, transport-close
   erasure semantics.
5. Trigger occurrence transaction (§21.2) and unchanged-ID state carry-over
   (§21.1): persisted throttle floors, one-shot markers, schedule anchors,
   boot-generation receipts, edge baselines.
6. Overload close protocol (`1401` + close, §22.3) on both endpoints.
7. Conformance rungs themselves: the wire fixtures, chunked decode, KAT.

---

## 5. Spec-weakness register (PoC features with no SPEC.md home — adjudicate each)

| # | Feature | PoC evidence | Disposition options |
|---|---|---|---|
| W1 | `theme.set.base` (three-way theme choice) | ThemeBridge + `themeBase` flow + ebp amendment #3 | Amend §18.4 (small, additive) or drop |
| W2 | Binding layer (`list`/`board`/`calendar` layouts, transforms, chrome) | format-5 `binding` block, BINDING.md, spec-compiler modules | Deliberately client-side? If it never crosses the wire, document as out-of-scope; if it does (saved views), spec needs a home |
| W3 | Toolbar `line` op + `line_ops` (promote/demote/move-up/move-down) | SduiToolbar, org toolbars, **orgseq depends on it** | Amend §17.7 — likely a real hole |
| W4 | `pie_menu.show.buffer` | jetpacs-keymap radial flow | Fold into categories or drop |
| W5 | `1400 frame-too-large` diagnostic | FrameCodec sender-side refusal keeps connection | Spec now closes silently — weaker DX; consider a SHOULD-level pre-close `log.error` |
| W6 | Quick-Settings tile slots (`tile:*` namespace) | TileSlots.kt, SurfaceManager routes it | §13.1 has app/notification/widget only — spec hole or platform extension namespace |
| W7 | Trigger UX actions `trigger.test`/`trigger.toggle` | jetpacs-triggers handlers | Ordinary app-level actions — probably fine unspecified; confirm |
| W8 | Devtools/instrumentation surface | jetpacs-devtools.el | Out of contract scope; confirm |
| W9 | Editor `command` args | Spec §17.7 requires `edit.command` args incl. `seq`/`cursor`/`sel_*` — PoC sends a leaner shape | Covered by rebuild; listed for completeness |

---

## 6. The mechanical detectors (already in place)

Both endpoints pin themselves to `ebp/contract.json` and the goldens:
- Kotlin: `WireVocabulary.kt` is *generated* from the contract;
  `WireGoldenConformanceTest` replays the golden corpus.
- elisp: `jetpacs-lint.el` derives tables from the contract at load;
  `emacs/build-contract.el` round-trips it; `jetpacs-tests.el` carries golden
  snapshots and drift gates.

Re-pointing both at **format 6 + the recaptured goldens** converts most of
§1 into failing tests automatically. That flip is step zero of any
compliance path and costs little — do it first, whatever direction is chosen.

## 7. What the static map cannot see (the audit's dynamic half)

Only runtime tests can witness: the kill matrix (§24.6 items 8–9 — process
death around event write/result), replay interruption and FIFO holds, the
`state.changed`/`surface.update` race barrier under real concurrency,
debounce flushing, staleness clocks across process death, trigger firing
across reboot (boot receipts, baseline carry-over), password erasure, and
overload behavior per traffic class. These become the conformance suite of
whichever implementation goes forward; SPEC §24.5–24.6 and
`ebp/llm-poc/validate.py` + `goldens/wire/` are the harness seeds.

## 8. Reading of the evidence

A retrofit must rebuild ~4.4k lines of wire core inside both endpoints while
every surviving module's assumptions (fire-and-forget notifications, binary
session state, no event IDs) are dismantled underneath it. A from-scratch
wire core with organs ported (renderers, capabilities, org layer — roughly
25k of the 40k lines) reaches the same endpoint with conformance tests green
from the first frame. The freeze option costs nothing now and defers the
same rebuild to the clean-room track. The one decision this map cannot make
is who the rewrite is for.
