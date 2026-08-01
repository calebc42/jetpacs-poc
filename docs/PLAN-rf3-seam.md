# PLAN — RF-3: the extension-dispatch seam + `jetpacs.echo` (ratified 2026-08-01)

Expansion of [PLAN-refound-2026-07-28.md](PLAN-refound-2026-07-28.md) §RF-3
(:700-727). The parent gate, verbatim:

> **Gate:** (1) entire pre-RF-3 corpus green untouched — the frozen-dispatch
> proof; (2) tenant round-trips elisp↔Kotlin over live loopback **via the
> RF-2.6 host**; (3) tenant method *without* negotiation gets the §7.3
> response, golden-pinned; (4) SPEC conformance note (if §24 needs an
> "extensions present" clause, that is a spec amendment through the normal
> §25 process, not a silent reinterpretation).

## §0 STATE — execution ledger (update at every checkpoint)

| Checkpoint | What | Status |
|---|---|---|
| R0 | This runbook committed | DONE 2026-08-01 `f63ab14` |
| K1 | Kotlin seam: `EbpModule`, `checkModules`, the negotiated route, reservation fix | DONE 2026-08-01 — capture test green pre-seam (fixture is evidence); post-seam `:wire:jvmTest` 41 suites / 371 tests / 0 failures; `postAuthDispatchRules` + `methodRegistryMatchesContract` diff-empty |
| K2 | `:host` grows `--echo` | DONE 2026-08-01 — `:host:jvmTest` 7/7 green (2 new: config pin + socket round-trip); fatJar smoke `--port 0 --kat --echo` prints PORT; no-flag config module-free |
| E1 | Elisp seam: `ebp-client-register-module`, `:modules`, dispatcher gates, wants union | DONE 2026-08-01 — elisp gate-3 capture green pre-seam (ccb1dcc); post-seam `run-tests.sh` exit 0, delineation + byte-compile guards clean, wire suite 61 tests incl. 6 `ebp-test-module-*`; one finding: `string-match-p` needed `case-fold-search` nil or the lowercase-only grammar waved uppercase through |
| E2 | `test/ebp-seam-test.el` live round-trip; run-tests.sh stanza; ci.yml suite count | DONE 2026-08-01 — full `run-tests.sh` exit 0 WITH the live host: RF-2.6 suite 10/10 (plain host) + seam suite 3/3 (`--echo` host); gate (2) met live; unset → loud skip verified; ci.yml elisp name 30→31 |
| S1 | §24 amendment drafted and routed | DONE 2026-08-01 — `DRAFT-amendment-153-extension-conformance.md` committed; **routed to Caleb, ratification pending**; prose-only (I1 exemption (a)); ratification is not an exit condition |
| R1 | Adversarial review: 4 dimensions × 2-refuter verification over the rung diff | DONE 2026-08-01 — 13 of 21 findings survived; the two costliest were THIS rung's: both sides' capability-collision checks validated against the wrong set (host-supported / 8 gated senders) instead of the SPEC 22.1 VOCABULARY — a module capability named after an unsupported core capability would masquerade as the core grant. Fixed with `CORE_CAPABILITIES` + `ebp--core-capabilities`, both contract-pinned; plus the D2 route-gate ordering made falsifiable, the registered-but-ungranted elisp reply pinned wire-EQUAL (not member-subset), emit/dispatch arms isolated, a string-METHOD crash in `check-granted` fixed, the wants-dedup claim given its discriminating test, the live gate-3 check raised to exact member sets, and four runbook claims corrected in place (R1-10..13). 8 findings refuted by the panel |
| EXIT | All four parent gates recorded; dual recording in PLAN-refound | — |

## Context (line-verified 2026-08-01, base `rf-1` @ `71d90ab`)

**The base moved under the approved plan, harmlessly.** The plan was designed
against `rf-1` @ `93f8308` (the RF-2.6 exit); RF-1b (Robolectric) and RF-1c
(the loopback CI job) landed on `rf-1` before execution began. Verified:
`git diff --stat 93f8308..71d90ab` touches **none** of this rung's targets —
`CompanionEngine.kt`, `emacs/ebp.el`, `test/ebp-wire-test.el`,
`test/ebp-host-test.el`, `test/run-tests.sh` are all diff-empty — so every
code anchor below survives. `rf-3` branches from `71d90ab` (same stacking
pattern as rf-2.6 on rf-2c; merges after rf-2c → rf-2.6 → rf-1's RF-1b/1c).

**Where the seam inserts.** `handleRequest`'s §7.3 funnel
(`CompanionEngine.kt:573-615`): request-id check → pre-auth fail-closed →
params-object check (`:593-597`, -32602 **before** any registry consult) →
handshake routing → `isValidMethodName` (`:606-607`, §11: identifier check
precedes the registry) → `METHOD_REGISTRY[method] ?: -32601` (`:608-609`) →
wrong direction/class -32600 (`:610-612`) → wrong state 1204 (`:613-615`).
`handleNotification` mirrors at `:1046-1061` with silent drops, params-object
**last** (`:1061`). The tenant route forks exactly one expression on each
path: the `?:` miss arm.

**Corrections to the parent's own text, carried not silently:**

- The parent says "`ebp.` methods → existing registry; non-`ebp.` methods →
  registered + negotiated handler" (:708-710). **No core method lexically
  begins with `ebp.`** — the registry rows are bare (`session.hello`,
  `surface.update`, `edit.apply`, …). A literal prefix test would route the
  entire core to the tenant path. The discriminator that matches both the
  code and I5's intent is **`METHOD_REGISTRY` membership**; the tenant route
  exists only on its miss.
- The parent's RF-3 ladder note "Gate (2) is unrunnable without the host"
  (:54) predates RF-1c. The loopback CI job now builds `host-all.jar` and
  runs `test/run-tests.sh` with `EBP_HOST_LAUNCH` set — so gate (2) has a CI
  home from birth, and the RF-2.6-era deviation ("live gate runs locally
  only; CI wiring is RF-1c's rung") is **not** inherited. It died when RF-1c
  landed.

## Ratified decisions (2026-08-01)

1. **Registry membership is the route discriminator** (correction above).
   One-line diffs at `:608-609` and `:1053`; everything above and below those
   lines stays byte-identical. Gate (1) is the proof.

2. **The granted check is the route gate — ungranted ≡ unknown.** An
   unregistered OR unnegotiated tenant method receives the exact §7.3 answer
   (-32601 request / silent notification drop) **before** direction, class,
   or state are consulted. Rationale: if class/state ran first, a probe could
   distinguish a carried-but-ungranted extension (-32600/1204) from an
   unknown method — an information leak and an I5 violation. The parent
   sanctions this: "registered **+ negotiated** handler, **else exact current
   §7.3**" (:709-710). Core-mirroring order (class → state → handler) applies
   only *inside* the negotiated route. (`handleEditApply`'s in-handler
   granted check is precedent for -32601-as-invisibility, not for its
   position — core methods are registry rows either way.)

3. **Modules are configuration, not mutation.** Kotlin: a trailing defaulted
   `CompanionConfig.modules: List<EbpModule>` validated at construction by
   `checkModules()` (the `checkLimits` pattern; the host's pre-bind probe
   rejects a bad module config with exit 2 before a port exists). Elisp:
   `ebp-client-register-module` plus a `:modules` config key consumed in
   `ebp-client-create` — registration therefore precedes `ebp-client-start`,
   which guarantees both the wants contribution and the obarray-sentinel
   escape (`ebp.el:942-945` substitutes any method not in the handlers hash
   at decode time; a handler registered late is unreachable that session).

4. **Notification emission is data on the outcome, not a callback.**
   `ModuleOutcome.Ok.notify` carries `(method, params)` pairs; the engine
   validates each against the module's own table (exists, Companion-sender,
   notification-class, state-legal — silently dropped otherwise) and emits
   after the reply through the one outbound funnel (`emit`/`wireSerialize`).
   Handlers stay pure under the engine's `WireLock`; the engine remains the
   only emitter; tenant results keep `respondResult`'s serialize-failure
   -32603 protection. Asynchronous module emission is a **recorded
   non-feature** — the first tenant that needs it (RF-4+) grows the seam
   under its own rung.

5. **The Kotlin tenant lives in the live host process, behind `--echo`,
   default off** (ratified by Caleb 2026-08-01). "Lives in test code only"
   (:720) walls the tenant out of `:wire` and the Android app; it cannot wall
   it out of the process gate (2) requires ERT to dial. `:host` is the
   sanctioned test/loopback fixture (its own header says so; I2's
   no-`jetpacs.*` wall names `:wire`, `ebp.el`, `ebp-data.el`, `ebp-sqlite` —
   deliberately not `:host`). The no-flag host stays behavior-identical to
   RF-2.6+RF-1c.

6. **Gate (3)'s golden is a local checked-in expectation, §24.5 semantic
   comparison.** `ebp/` is frozen (I1) and `validate.py` rejects
   `frames.golden` methods absent from the contract, so the pin cannot live
   there. The Kotlin side checks in the expected §7.3 reply as a JSON
   fixture (`{"jsonrpc":"2.0","id":…,"error":{"code":-32601,"message":
   "Method not found","data":{"kind":"method-not-found"}}}`) compared via
   `jsonValueEquals`; the elisp side pins the shape member-by-member
   (`ebp-test--seven-three-error-p`) AND full error-object equality
   against a `no.such` miss reply on the same session — wording corrected
   at R1 (finding R1-13): the elisp pin is a predicate-plus-equality, not
   a checked-in encoded fixture. **Capture discipline: both pins run
   green against the pre-seam tree first** — evidence, not aspiration.
   Probes use object params (non-object answers -32602 before the
   registry, `:593-597`).

7. **`checkModules` forbids the `ebp.` namespace — and RF-4 will relax
   exactly that.** I3 reserves `ebp.` until ratified per §25; RF-4a's tenant
   is `ebp.data`, which arrives *with* its ratified registry entry. The
   relaxation ("an `ebp.`-namespace module requires a matching contract.json
   registry entry") is RF-4's to make. Recorded so the check reads as a
   decision, not an accident.

8. **The seam fixes a reservation defect it would otherwise create.**
   `checkLimits()`'s prospective §4.5 welcome reservation builds its
   worst-case `granted` array from `config.supportedCapabilities` alone
   (`:2402-2404`); once module capabilities are grantable, a granted array
   containing them is bytes the reservation never counted. Both
   `buildWelcome` (`:2285`) and the prospective array move to one shared
   `effectiveCapabilities = supportedCapabilities + moduleCapabilities`. A
   construction-failure test pins it.

9. **Scope note.** The Kotlin *inbound*-notification module route has no
   live tenant exercise (the echo tenant's notification is Companion→Emacs);
   it is covered by K1 unit tests. The first Emacs→Companion tenant
   notification arrives with a real module.

## The ladder

Branch `rf-3` from `rf-1` @ `71d90ab`. One commit per checkpoint; rollback =
`git reset --hard` to the prior checkpoint.

**R0 — this runbook.** `docs(plan)` commit, ahead of the code (house
precedent).

**K1 — the Kotlin seam** (`:wire` commonMain + jvmTest).
`ExtensionModule.kt` (new): `EbpModule(namespace, capability, methods:
Map<String, MethodSpec>, handler)`, `ModuleNotification`, `ModuleOutcome`
(`Ok(result, notify)` / `Fail(code, message, kind, data)`), `fun interface
ModuleHandler` — mirrors the `CapabilityHandler`/`CapabilityOutcome` shape.
`CompanionEngine.kt`: `modules` config param (trailing, defaulted);
`moduleCapabilities`/`effectiveCapabilities`/`moduleMethods` vals;
`checkModules()` in `init` before `checkLimits()` — namespace valid +
lowercase + not `ebp(.)` + no duplicate/dot-prefix nesting; capability valid
+ lowercase + not `ebp.` + not in `supportedCapabilities` + distinct;
methods non-empty, namespace-prefixed, valid, lowercase, not a
`METHOD_REGISTRY` key, distinct across modules; states non-empty. The two
miss-arm diffs; `dispatchModuleRequest` (granted-as-route-gate → class -32600
→ state 1204 → handler with `ContentInvalid`→1201 / any→-32603 containment →
`respondResult` + `emitModuleNotifications` | `respondError`);
`dispatchModuleNotification` (silent mirror, params-object last);
`emitModuleNotifications`; `buildWelcome` + `checkLimits` on
`effectiveCapabilities`. `ExtensionSeamTest.kt` (new; own local factory per
TestSupport's no-blended-factory rule; neutral `x.test` namespaces for
mechanics, `jetpacs.echo` for tenant-fidelity cases): the gate-3 golden
FIRST, green pre-seam; then registration-validation per rule; negotiation;
granted round-trip with sink order pinned; -32600/1204 inside the route;
handler containment; inbound-notification route; reservation construction
failure. *Gate:* `cd companion && ./gradlew :wire:jvmTest` — every existing
suite green; `postAuthDispatchRules` and `methodRegistryMatchesContract`
diff-empty.

**K2 — `--echo`.** `Host.kt`: `jetpacsEchoModule()` — `jetpacs.echo.ping`
`(EMACS, request, READY)` echoing params with one
`ModuleNotification("jetpacs.echo.pulse", params)`; `jetpacs.echo.pulse`
`(COMPANION, notification, READY)`; `hostConfig(kat, capabilities, echo =
false)`; `--echo` flag + USAGE line. One ping round-trips both message
classes end-to-end. `HostConformanceTest.kt`: echo config carries the module
and constructs; default config carries none. *Gate:* `./gradlew :host:jvmTest
:host:fatJar`; manual smoke `--port 0 --kat --echo` prints `EBP-HOST PORT=`.

**E1 — the elisp seam.** `emacs/ebp.el`: `modules` struct slot;
`ebp-client-register-module (client namespace capability handlers)` with the
checkModules-mirroring validations (methods additionally must not collide
with the handlers hash); `:modules` consumed in `ebp-client-create`;
`ebp-client--module-of` (namespace prefix match) +
`ebp-client--module-ungranted-p` (`seq-contains-p` — granted is a raw
vector); the request dispatcher's ungranted arm IS the literal miss arm
(-32601), the notification dispatcher's falls to the logged-ignore arm;
`ebp-client--check-granted` consults the module registry on alist miss;
`ebp-client-start` wants = `(delete-dups (append config-wants
module-capabilities))` (the Companion rejects duplicate wants -32602);
`forget-pairing` docstring notes modules survive (code registration, like
the handlers hash). Tenant rows never enter `ebp--method-capabilities` (its
contract pin is bidirectional). `test/ebp-wire-test.el` additions
(`ebp-test-module-*`, additive only): elisp gate-3 fixture green pre-seam
first; validation rules; `:modules` at create; wants union via the fake's
recorded hello (the dedup half got its own discriminating test at R1,
finding R1-8: a caller whose `:wants` already names the module capability
sends it once); ungranted request ≡ `no.such` miss reply ≡ fixture;
ungranted notification → zero wire bytes; granted notification dispatches;
outbound gate; forget-pairing. *Gate:* `test/run-tests.sh` fully green
(delineation guard: `ebp.el` gains only `ebp-` symbols; floor + contract
pins untouched).

**E2 — the live suite.** `test/ebp-seam-test.el` (new, `^ebp-seam-`
selector, loads `ebp-host-test.el` for helpers; test symbols `ebp-seam-test-`
— never `jetpacs-echo-`, that prefix belongs to the unrelated JA-3d toast
bridge): (1) `tenant-round-trip` — `:modules` echo registration, granted
contains `jetpacs.echo`, ping result echoes params, pulse recorder sees the
payload; (2) `unnegotiated-is-unknown-live` — no modules, raw ping → -32601
`method-not-found` (gate 3's live half); (3)
`registered-but-unsupported-gates-outbound` — the module registered
against the PLAIN host, whose supported set lacks the capability, so the
real wants-intersect-supported omits it and the outbound ping signals
`ebp-ungranted` (description corrected at R1, finding R1-11: a wants
override cannot produce this case — `ebp-client-start` unions module
capabilities into wants unconditionally; host non-support is the only
route to registered-but-ungranted).
`ebp-host-test--start-host` gains an optional COMMAND arg
(backwards-compatible) so the seam suite appends ` --echo`. `run-tests.sh`
stanza after the RF-2.6 host stanza, same loud-skip discipline. `ci.yml`
elisp job name `30 → 31 ERT suites` (verified: the live exit run counts
exactly 31 `Ran N tests` lines); the loopback job runs the suite live
with no further change. *Gate:*
`EBP_HOST_LAUNCH="java -jar companion/host/build/libs/host-all.jar --port 0
--kat" test/run-tests.sh` — host suite AND seam suite green; unset → both
skip loudly.

**S1 — the §24 note.**
`docs/DRAFT-amendment-153-extension-conformance.md`: §24.6 item 4 (+ §12
rule 4) as literally written asserts a non-registry method is always -32601;
a negotiated tenant departs from that, so a clause is needed. Direction:
item-4 outcomes are evaluated with no extension negotiated; an
implementation registering extensions MUST additionally verify (a) an
unnegotiated extension method is wire-indistinguishable from an unknown
method and (b) negotiating an extension changes dispatch outcomes only for
that extension's registered methods — noting (a)/(b) are exactly what this
rung's suites demonstrate. The draft is **routed**, not landed: it enters
`ebp/` only on Caleb's ratification (I1 exemption class (a), prose-only,
pointer bump rides it). Ratification is not an exit condition.

**EXIT.** From clean: `./gradlew clean :wire:jvmTest :host:jvmTest` +
`:app:testDebugUnitTest :app:assembleDebug`; `python3 ebp/validate.py` +
`git status --short ebp/` empty + submodule pointer unmoved;
`./gradlew :host:fatJar` + `EBP_HOST_LAUNCH=… test/run-tests.sh`. Gate (1)
inventory (completed at R1, finding R1-10 — the literal claim must name
EVERY modified pre-RF-3 file, not only the test-side ones): seam
implementation — `CompanionEngine.kt` (the two miss-arm forks + module
plumbing), `emacs/ebp.el` (the seam), `Host.kt` (`--echo`); test corpus,
additive only — `ebp-wire-test.el` (new tests + fixture),
`HostConformanceTest.kt` (two RF-3 pins appended, RF-2.6/RF-1c tests
untouched), `ebp-host-test.el` (backwards-compatible optional arg);
harness — `run-tests.sh` (stanza), `ci.yml` (suite-count name). Every
OTHER pre-RF-3 file is diff-empty, and within the modified test files
every pre-existing test is diff-empty. Dual
recording: this ledger gains SHAs; PLAN-refound's ladder row and a §RF-3
status block land in the same commit, plus the backfilled RF-2c/RF-2.6
ladder-row DONE markers the dual-recording rule was owed. Push, PR, CI, and
merge order (rf-2c → rf-2.6 → rf-1 → rf-3) are Caleb's.
