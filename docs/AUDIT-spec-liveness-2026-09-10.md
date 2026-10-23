# AUDIT — SPEC.md against the liveness/correctness invariants (2026-09-10)

Audit target: `../ebp/SPEC.md` (protocol 3, doc 3.1.0-draft, 5,164 lines).
Line references below are into that file at the audited revision.

Auditing frame: the safety/liveness split developed for the hand rewrite.

- **Safety** (unconditional): nothing bad ever happens — no lost tap, no double
  application, no stale-over-fresh, no wrong edit.
- **Liveness** (conditional): something good eventually happens — the queue
  drains, the screen freshens — guaranteed only *if* a session forms.
- **The invariant**: no correctness property may depend on the other endpoint
  being alive, reachable, or timely. The scheduler killing or freezing either
  process at any moment may delay good things; it must never cause a bad
  thing. Litmus test for every MUST-sentence: *does this sentence stay true if
  the peer is dead, frozen, or slow at any point during it?*

## Verdict

SPEC.md **practices the invariant almost everywhere but states it nowhere**.
No load-bearing liveness-in-correctness violation was found. The findings are
structural: the principle is unwritten, the durable artifacts are
uninventoried, and the conformance suite tests connection death but not
process death. The hand rewrite's job is not to invent better rules; it is to
state the principle the existing rules were always instances of, and wire it
to tests so it cannot drift.

## What already passes

Recorded so the findings can be trusted as exhaustive rather than selective.

- **§13.2 surfaces**: monotonic revisions; `stale` as a benign idempotency
  result, not an error; results as floor-absorption points; tombstones.
  Messages are meaningful in isolation.
- **§14.4 events**: at-least-once delivery with durable CSPRNG EventIds; the
  durable-commitment rule before `accepted` (SPEC.md:1995); an explicit
  written refusal to promise exactly-once external side effects
  (SPEC.md:2007). The hard delivery-semantics decision is already made and
  written down.
- **§10.3 synchronization barrier**: every wait has a local exit; "a
  requester's local deadline MUST NOT be the only thing that ends a `SYNCING`
  phase" (SPEC.md:1215). No absorbing wait states.
- **§5.3 wake**: bounded attempt; "Failure to reach `READY` is not event
  rejection" (SPEC.md:477).
- **§13.5**: "Staleness is a presentation state, not authorization or
  correctness" (SPEC.md:1606) — the whole framing in one sentence.
- **§19 editor**: liveness-dependence is deliberate, explicit, and safe:
  read-only when not `READY`; "Wrong synchronization state MUST result in a
  typed stale outcome or resynchronization, never a wrong edit"
  (SPEC.md:3619); refused-splice restoration after resync (SPEC.md:3682).
- **§15 / §21.2**: durable single-file FIFO pump; persistence-before-delivery;
  freeze → durable-commit → act transaction ordering for triggers; the
  effective-wall-clock high-water-mark discipline so clock changes cannot
  corrupt expiry or throttles (SPEC.md:2188).
- **Absent by design, correctly**: no heartbeats, no keepalives, no graceful
  shutdown message, no session resumption. Every reconnection is a fresh
  handshake with fresh nonces (§10.4).
- **§24.6**: connection-loss adversarial tests around `event.action` (item 8)
  and duplicate delivery after Emacs restart (item 9) already required.

## Findings

### F1 — The invariant is practiced ~105 times and stated zero times

The word "liveness" does not appear in the document. §3's architectural
invariants cover only authority and the code boundary. The safety/liveness
split lives in the implications (stale results, offline policies, barriers)
but no normative sentence establishes it — so nothing stops a future
amendment from violating it. Doctrine without a tripwire drifts (the PoC's
hardest lesson, re-observed).

### F2 — No durable-artifact inventory, no disk-format versioning

The durable artifacts are real and normative but scattered: surface cache and
tombstones (§13), input drafts (§13.6), the queue and its `queue_seq` counter
(§15.1), throttle state and one-shot receipts (§21.2), disconnection time
(§10.4, SPEC.md:1262), pairing token (§9.1), the clock high-water mark
(§15.2), Emacs-side accepted-EventId records (§14.4). No section names them
as a set or requires their on-disk formats to be versioned. This matters
doubly for the single-APK endgame, where **the disk is the wire**: this
inventory *is* the second binding's contract.

### F3 — Manifesto and spec disagree about durability; the spec is right

The manifesto draft says "durability is only ever the Companion's disk," but
§14.4 imposes real durable obligations on Emacs: commit the EventId with the
effect or a durable work item before answering `accepted`
(SPEC.md:1995–2005); retain accepted IDs ≥ 604800 s (SPEC.md:2002). There
are two durability domains — the Companion owns *presentation and pending
input*; Emacs owns *application effect and dedupe memory*. The rewrite must
name both instead of asserting one.

### F4 — Zombie sessions have no disposal rule

The design is complete once a connection *closes*, but a hung-yet-connected
peer (alive, socket open, answering nothing) falls into a gap: the staleness
clock keys on disconnection (§13.5) and never starts; if the Companion
cancels the in-flight event, `1301` pauses the pump, and only `queue.replay`
— which only Emacs can send — resumes it (§15.3). Supersession-by-new-dial
(SPEC.md:427) rescues this only if Emacs redials. Missing: one sentence
making explicit what the design already guarantees — **unilateral close is
always legal and always safe, at any moment, for any reason** — so
hung-session disposal is local policy and closing re-enters the fully
specified disconnected world. (§10.1's "local shutdown MAY transition … to
`CLOSED`" gestures at it; promote it to a stated corollary of F1's
invariant.)

### F5 — Crash-boundary testing is connection-centric, not process-centric

The spec asserts "durably," "atomically," or "one durable transaction" at
roughly a dozen core transitions (queue admission §15.1, dedupe replacement
§15.2, trigger commit-then-fire §21.2 step 3, snapshot persist-and-present
§13.2, the disconnection-time write §10.4) — but the *core* conformance
suite (§24.6) never requires killing a process at those points;
crash-boundary cases are delegated to optional-module suites (SPEC.md:5111).
Kill-at-every-arrow should be a required core test family, generated
mechanically from F2's inventory.

### F6 — Content and binding are entangled in §5.2

The transport-profile section contains rules that are not transport:
single-authority semantics, supersession ordering, and "trigger firing and
reminder presentation are device-lifetime obligations, not session-scoped"
(SPEC.md:439–442) — one of the most important sentences in the document,
buried in a TCP profile. For a future in-APK (files + JNI) binding to be
"just a second transport profile," authority, supersession, and
device-lifetime rules must move up into the session/content layers, leaving
§5 with only addressing, framing, auth floor, and dial direction.

### F7 — Version-label drift, already

§24.1 and §24.2 say "a conforming **EBP 2** Companion/Emacs endpoint"
(SPEC.md:4927, 4955) in a document defining protocol **3**. Small, but it is
the four-version-numbers disease live in the current text.

### F8 (minor) — Timing MUSTs need classification

The 60-second redial lockout after supersession (§5.2, SPEC.md:424) and the
≥10-second handshake-timeout floor (§10.1, SPEC.md:1052–1055) are
policy/anti-livelock rules wearing correctness clothing. Defensible — but
each timing constant should be classified as safety, anti-livelock, or QoS,
so a reader can tell which numbers are contract and which are tuning. (The
password 30-second deadline in §14.6 is genuinely safety — bounding secret
lifetime — and is correctly specified, including its honest "indeterminate
remote outcome.")

## Fix plan for the hand rewrite

Ordered so each step feeds the next:

1. **State the invariant in §3** as two numbered architectural invariants
   plus a conventions rule:
   - *(i)* No requirement conditions correctness on peer liveness,
     reachability, or timeliness; peer availability affects only latency and
     freshness. Either endpoint may close any connection at any moment
     without violating any requirement.
   - *(ii)* Correctness is invariant under process death, freeze, and restart
     of either endpoint at any point; every durable transition must be atomic
     or safely repeatable.
   - Add the litmus sentence to the §2.1 requirement-language rules: a MUST
     whose satisfaction requires a peer response must be written as a
     conditional.
2. **Add a "Durability model" section** (F2 + F3): the two durability domains
   by owner; the complete durable-artifact inventory with a one-line
   crash-consistency rule each; a requirement that every durable format is
   versioned from first write. Fix the manifesto's durability sentence to
   match.
3. **Re-layer §5** (F6): hoist single-authority, supersession, and
   device-lifetime-obligation rules into the session lifecycle and module
   sections; leave transport profiles pure. This is what makes an in-APK
   binding a future one-section addition instead of a rewrite.
4. **Add the zombie-session corollary** (F4) to §10, plus a SHOULD for
   bounded-unresponsiveness disposal as local policy.
5. **Extend §24.6** (F5): a required crash-schedule test family — for each
   step-2 inventory entry, kill the owning process immediately before and
   after its durable commit and assert the §3 invariants. Derive the list by
   grepping the spec for `durabl|atomic|transaction`; the grep doubles as the
   completeness check.
6. **Mechanical tripwires in CI** (locks in F1; catches F7/F8): fix the
   "EBP 2" labels; add spec-lint greps — no "when connected"/"while
   connected" outside freshness clauses; every timing constant tagged with
   its classification; every "durably/atomically" within N lines of a
   conformance-test reference.

## Context

Produced during manifesto/spec-rewrite planning, following the architectural
re-derivation that (a) the Companion is a *role* forced by scheduler
indifference plus manifest-entry-point economics, not inherently a separate
APK, and (b) a future pure C/Java/Elisp implementation inside the Emacs APK
would keep the content contract and replace only the binding. The audit's
layering findings (F2, F6) are what keep that door open.
