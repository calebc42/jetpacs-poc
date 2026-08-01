# PLAN — RF-2.6: headless JVM loopback host (ratified 2026-08-01)

Expansion of PLAN-refound-2026-07-28.md §RF-2.6 ("CompanionEngine + the Memory stores +
a ServerSocket … **Gate:** the ERT live-loopback suite passes against the host, with the
elisp-scripted fake (`test/ebp-wire-test.el:331`) deleted or demoted to a unit fixture").
[Quoted verbatim; the runbook's `:331` is itself wrong — see the drift note below, and
the correction at source, made 2026-08-01 in RF-1c/C2.]
Ratified by Caleb 2026-08-01, all five decisions as recommended. Branch `rf-2.6`,
stacked on `rf-2c` (same pattern as rf-2c on rf-2b — merges after it).

## Context — what exploration measured (2026-08-01, line-verified on `rf-2c`)

- **The engine is already a headless host minus the socket.** `CompanionEngine(config) {
  bytes -> …}` is a complete instance: every store defaults to a Memory backing
  (CompanionEngine.kt:47-75), `feed(bytes)` is chunk-size independent, the sink receives
  already-framed bytes, and **nothing in `:wire` spawns a thread or needs periodic
  driving** — the durable pump is event-driven; time-trigger alarms only exist if
  `deviceReport.trigger_types` advertises `"time"` (omit it → `timeSchedule()` stays
  empty, no scheduler at all).
- **The socket pump to copy is DeviceBridge.kt:199-227 + :500-619** minus Android: one
  accept thread (newest-wins per SPEC 5.2), one reader thread per connection calling
  `feed()`, sink = `out.write(bytes); out.flush()` with IOException → socket close. All
  14 engine listener hooks are nullable and every invocation is `?.invoke` — a headless
  host leaves them null.
- **The runbook's `:331` cite has drifted**: the fake Companion is
  `ebp-test--start-companion` at test/ebp-wire-test.el:379 (banner :334), returning
  `(:port P :received FN :stop FN)` over a real ephemeral-port TCP listener
  (`make-network-process :server t`).
  [:379 was right when measured on `rf-2c`; E3's own banner rewrite (`16b6c7a`) added
  9 lines above it, so the defun is at **:388** from that commit onward. Banner :334
  is unchanged. Re-verified 2026-08-01, RF-1c/C2.]
- **Fake-based census: 29 live-loopback deftests** — 27 through the fake (25 in
  ebp-wire-test.el + jetpacs-teardown-test.el:210 + jetpacs-integration-test.el:95) and
  2 raw-socket coding-system pins (:866, :1121) that hand-roll literal frames and never
  migrate.
- **Two hard constraints the runbook missed**:
  1. `:received` is load-bearing — 20+ assertions read the fake's recorded inbound list
     (e.g. :482-485 asserts the exact method order hello→auth→replay→ready). A real
     host has no readback channel.
  2. Several tests script **deliberate misbehavior** — bad `server_proof` (:496),
     incomplete welcome (:512), replies withheld to accumulate cancels (:994) — which a
     conformant host cannot produce. Therefore the fake CANNOT be deleted; the gate's
     "deleted or demoted" resolves to **demoted to a unit fixture**.
- **KAT determinism is available end-to-end**: the client nonce is injectable
  (`:client-nonce`), and `CompanionConfig.nonceSource` (CompanionEngine.kt:41) lets the
  host pin the server nonce to the SPEC 9.3 KAT vector — the handshake-proof assertions
  can migrate byte-exact.
- **Welcome parity requirement**: the client hard-requires 8 members
  (ebp.el:1153-1156) and verifies `server_proof` before absorbing anything; the host's
  `surfaceProfiles` must be hand-authored (the app derives its from the renderer —
  app-only). Matching the fake's welcome fixture (:353-377: 8 node types, 2 builtins,
  granted `["theme"]`, the 9-member limits) maximizes test portability.
- **Unstated win**: all 48 `test/smoke-*.el` drivers hardcode `127.0.0.1:8765` + the KAT
  pairing (the same one DeviceBridge.kt:121-122 ships), so a host defaulting to 8765 is
  something they can dial with no device.
  > **Measured correction (2026-08-01, EXIT).** "~40 become runnable" was too strong.
  > Sampling three against the live host: `smoke-offline` completed its push phase;
  > `smoke-parity` connected but reported `applied=0/0`; `smoke-results` failed its
  > READY check because it asserts before any `accept-process-output` — these drivers
  > were written for an interactive Emacs whose idle loop pumps, and `--batch` has no
  > such loop. So the host makes them **dialable**, not green: each needs its own
  > small fix (a pump before the first assertion, a golden-path check). The capability
  > survey says the protocol side is nearly free — 31 of 48 want only
  > `theme`/`surfaces.dialog` (the host's default pair) and the rest are a `--caps`
  > list away (`editor.sync`, `triggers`, `reminders.owner`, `capabilities`,
  > `presentation.toast`, `presentation.pie-menu`, `surfaces.notification`). That
  > per-driver work is RF-1c's, and this is its seed inventory.
- **CI is out of scope**: ci.yml:5-13 explicitly schedules the cross-implementation
  loopback job as RF-1c. RF-2.6's gate runs locally.

## Ratified decisions (2026-08-01)

1. **Host location — RATIFIED: new `:host` Gradle module** (KMP plugin reused with a
   single `jvm()` target, so no new catalog plugin alias; `include(":host")` +
   `implementation(projects.wire)`). Rationale: RF-2c's exit gate pins `:wire`'s jvmMain
   to exactly 7 platform files (a socket host there breaks the inventory); a jvmTest
   fixture has no entry point and drags the host onto the conformance-test classpath;
   RF-1c/RF-3/RF-4/RF-6 all consume the host, which deserves a module, not a test
   fixture. Entry point via a `JavaExec` task (`:host:run`) — no `application` plugin
   needed on KMP.
2. **The fake is DEMOTED, not deleted** (forced by the misbehavior tests — see Context).
   Demotion = the section banner and docstrings re-scoped to "scripted unit fixture for
   client-side adversarial paths"; the runbook cite corrected.
3. **Migration scope — RATIFIED: client-observable subset only.** Tests asserting
   wire ORDER via `:received` keep the fake; tests asserting replies/state/proofs
   migrate to a new `test/ebp-host-test.el` suite dialing the JVM host. No trace/control
   channel on the host in this rung (RF-3's echo tenant is the natural future carrier
   for that).
4. **Port scheme**: `--port N` flag; default **8765** (smoke-driver compatibility);
   `--port 0` = ephemeral with `EBP-HOST PORT=<n>` printed on stdout for the ERT
   launcher.
5. **run-tests.sh integration — RATIFIED: opt-in stage.** The host suite runs only
   when `EBP_HOST_LAUNCH` is set (a command the suite `make-process`es, e.g.
   `java -jar host/build/libs/host-all.jar --port 0 --kat`); unset → skipped with a
   loud notice. Keeps `emacs -Q --batch` self-contained for the 29 existing suites and
   defers CI wiring to RF-1c as scheduled.

## The ladder

### K1 (Fable) — `:host` module: engine + socket pump

- `companion/settings.gradle.kts`: `include(":host")`. New `companion/host/build.gradle.kts`
  (KMP, `jvmToolchain(21)`, `jvm()` only, `jvmMain.dependencies { implementation(projects.wire) }`),
  plus a `JavaExec` task `run` wired to the jvm compilation's runtime classpath and a
  `fatJar`-style task only if the ERT launcher needs one (decide by measuring: `:host:run`
  via Gradle is slow to launch; a jar via `jvmJar` + runtime classpath in a wrapper
  script is the likely shape).
- `companion/host/src/jvmMain/kotlin/com/calebc42/ebp/host/Host.kt` (~150 lines):
  - `fun main(args)`: parse `--port` (default 8765), `--kat` (pin `nonceSource` to the
    SPEC 9.3 `katSn`, banner-log that auth is the public KAT — never a deployment mode).
  - Config: `serverName "headless-host"`, version, KAT pairing (same as
    DeviceBridge.kt:121-122 — that is what the entire test corpus dials),
    `supportedCapabilities = setOf("theme")` (+ flag to extend),
    hand-authored `surfaceProfiles` mirroring the fake's welcome fixture
    (8 node types, builtins `view.switch`/`companion.settings.open`),
    `limits = ` the 9-member core, integer-spelled, `deviceReport` WITHOUT `"time"`
    trigger types (no scheduler), `capabilityHandler = null`.
  - Accept loop: newest-wins (close previous socket), per-connection
    `CompanionEngine(config) { bytes -> synchronized(out) { out.write(bytes); out.flush() } }`,
    8192-byte read loop calling `feed()`, `engine.close("transport closed")` +
    socket close in `finally`. Stores default (Memory) — fresh per connection, which
    matches the fake's per-`ebp-test--with-companion` lifecycle.
  - `--port 0`: print `EBP-HOST PORT=<localPort>` after bind, flush stdout.
- Gate: `:host` compiles; a hand smoke — `ebp-connect` from a scratch Emacs reaches
  READY against it (manual, recorded in the commit body).

### K2 (Fable) — host conformance pin on the Kotlin side

- New `:host` jvmTest (or a `:wire` jvmTest addition if `:host` test wiring is heavy):
  drive one full handshake hello→auth→replay→ready over a REAL loopback socket against
  the running pump (in-process: start the accept loop on an ephemeral port, connect with
  `java.net.Socket`, speak KAT frames, assert the welcome's 8 required members and the
  KAT `server_proof` byte-exact). This pins the pump (threading, flush, newest-wins)
  independent of Emacs, so an ERT failure later bisects to the elisp side.
- Gate: G-wire (361 untouched) + the new host pin green.

### E1 (Opus, zero-discretion census) — the 29-test disposition table

- For each of the 29 live-loopback deftests: MIGRATE (client-observable: replies,
  state transitions, proof verification) / STAY (needs `:received` order, scripted
  misbehavior, or fake-specific silence) / SPLIT (both halves exist). Deliverable: a
  table in the plan doc with per-test rationale, wire-order assertions flagged.
  Exploration predicts roughly: handshake-to-ready (KAT half migrates, order half
  stays), surface revision/remove, replay-drain, event-action statuses/dedupe, floods
  and backpressure MIGRATE; bad-proof/incomplete-welcome/cancel-accumulation/
  redaction/obarray STAY. The census fixes the exact split before any test is written.
- Gate: none (document-only) — but the table is the E2 sheet's input.

### E2 (Fable) — `test/ebp-host-test.el`, the live suite

- New suite: launcher (`ebp-host-test--launch`: `make-process` on `EBP_HOST_LAUNCH`,
  read `EBP-HOST PORT=`, return `(:port :stop)`), `skip-unless` on the env var, then
  the MIGRATE set from E1 rewritten against the real host — assertions only on what the
  client observes (welcome content, KAT proofs — `--kat` mode, revision floors, replay
  counters, queue semantics now REAL instead of scripted `{delivered 0 …}`).
- The two raw-socket coding pins and the STAY set are untouched.
- `test/run-tests.sh`: one appended stanza — if `EBP_HOST_LAUNCH` is set, run
  `ert-run-tests-batch-and-exit "^ebp-host-"` (30th suite); else echo a skip notice.
- Gate: full `run-tests.sh` green twice — without `EBP_HOST_LAUNCH` (29 suites,
  behavior identical) and with it (30 suites, the live gate).

### E3 (Opus) — fake demotion + runbook corrections

- test/ebp-wire-test.el banner :334-339 and `ebp-test--start-companion` docstring
  re-scoped: "scripted unit fixture for client-side adversarial and wire-order paths;
  conformant-path coverage lives in ebp-host-test.el against the RF-2.6 host".
- PLAN-refound §RF-2.6: gate marked met, the stale `:331` cite corrected, the
  "deleted or demoted" clause resolved with the misbehavior-test rationale; STATE/ledger
  rows in the house style.
- Gate: G-elisp full (30 suites with the host up), G-wire, I1.

### EXIT

- From clean: `:wire:jvmTest` + `:host` build + G-app untouched + `run-tests.sh` both
  modes + `python3 ebp/validate.py` + I1.
- The smoke-driver win, measured: pick 3 representative `smoke-*.el` (parity, results,
  offline) and run them against the host on 8765 — record which pass deviceless and
  which genuinely need Android (notifications, taps), as the seed inventory for RF-1c.
- Ledgers: PLAN-refound RF-2.6 gate row; no LIBRARY-LEDGER change (zero new deps).

## Verification summary

Per-step gates as listed; the acceptance harness is E2's dual run. Risk register:
(1) jsonrpc.el client timers + a real host = timing flakes — mitigation: the same
5 s `ebp-test--wait` pump the fake suite uses, no sleeps; (2) per-connection fresh
Memory stores differ from the app's hoisted-stores lifecycle — acceptable for RF-2.6
(the fake had the same per-test lifecycle), revisit at RF-1c if a test needs
cross-connection durability (`ebp-test-event-action-duplicate-after-restart` is the
canary — census will place it); (3) `:host` launch latency in ERT — measure Gradle vs
jar at K1 and pick.

## Critical files

- NEW: `companion/settings.gradle.kts` (+1 line), `companion/host/build.gradle.kts`,
  `companion/host/src/jvmMain/.../Host.kt`, host jvmTest pin, `test/ebp-host-test.el`.
- EDIT: `test/run-tests.sh` (one stanza), `test/ebp-wire-test.el` (banner/docstrings
  only), `docs/PLAN-refound-2026-07-28.md` (gate + corrections).
- UNTOUCHED: `:wire` (all of it — the 7-file jvmMain inventory survives), `:app`,
  `ebp/` (I1), the 29 existing suites' pass/fail behavior in default mode.
