# Adversarial review gate: Jetpacs text editing Phases 0–3

Date: 2026-08-28

Status: **CODE REMEDIATION VERIFIED — Phase 4 remains blocked on the physical
accessibility acceptance gate**

Reviewed superproject range: 2255493..4e0e3a1

Reviewed EBP range: b926827..0b47c8c

Remediation superproject range: 4e0e3a1..46ea511

Remediation EBP range: 0b47c8c..65a0b12

## Decision

The original Phase 0–3 baseline was correctly blocked. Two independently
reproduced findings met the blocking rule:

- **Critical:** a password-bearing occurrence can be accepted with a durable
  queue policy and the renderer-supplied secret is written into the real
  FileQueueStore record.
- **High:** a handed-off password submission with no terminal callback remains
  pending and keeps the native field's secret reachable after the normative
  hard 30-second deadline.

Two Medium findings also required an explicit fix/defer decision before Phase
4: the Python/Kotlin/Elisp validation envelopes disagreed, and mask projection
was strongly superlinear at the 16–64 KiB sizes required by the gate.

Separate remediation commits now close F-01 through F-04 and the one-shot test
gap. Focused device review then found and independently reproduced F-05, a
Critical plaintext password disclosure through Android's accessibility tree;
b18c4a5 fixes that shared presentation seam and its deliberate bypass mutation
is caught by connected tests. A subsequent forced-GC heap inspection found
F-06, a second Critical disclosure: Compose undo staging kept the submitted
password reachable from the currently served view after terminal erasure.
46ea511 purges that undo history, its deliberate purge-removal mutation is
caught by a connected test, and the post-fix forced-GC heap contains zero
copies of the canary. No confirmed Critical, High, or Medium code finding
remains open on 46ea511.

Phase 4 still must not begin. The required physical human TalkBack, Switch
Access, hardware-keyboard, pointer, and touch acceptance was not available.
That is an uncompleted exit criterion, not a confirmed product defect. The
focused remediation evidence and the exact remaining gate are recorded below.
docs/PLAN-jetpacs-text-editing.md therefore is not advanced to “adversarial
review complete; Phase 4 next.”

## Scope, authority, and method

The owning repository is llm-poc-3. EBP wire/session/durability behavior is
owned by the ebp submodule and :wire; toolkit-neutral editing behavior is owned
by :renderer:model and :renderer:compose; Jetpacs presentation is owned by
:renderer:jetpacs. The trust mode was static inspection plus execution of the
reviewed local code, an authenticated local EBP harness, and the explicitly
authorized Pixel Tablet deployment.

EBP artifact precedence was applied exactly as ebp/SPEC.md:49-63 requires:
ebp/SPEC.md is normative, ebp/contract.json is its projection, and Goldens are
witnesses. The important disagreement in this review therefore does not get
resolved in favor of the convenient Golden.

Candidates were classified as:

- **confirmed:** independently reproduced from an authority-backed fixture;
- **refuted:** the challenged behavior matched the governing rule;
- **spec ambiguity:** normative text genuinely permits multiple outcomes;
- **test gap:** a required mutation survived the relevant existing test layer;
- **unverified:** evidence was insufficient to assert either result.

Production was frozen during discovery. Diagnostic tests and fault mutations
lived only in detached worktree /tmp/jetpacs-text-audit.7DJ6fT/review. Every
tracked mutation was restored before another candidate was tested. Each
Critical/High candidate was then rerun serially from a fresh Gradle invocation,
without relying on the first run's conclusion.

## Baseline

Both repositories were clean for tracked files before discovery:

| Repository | Reviewed HEAD | Range |
|---|---:|---:|
| llm-poc-3 | 4e0e3a1 | 2255493..4e0e3a1 |
| llm-poc-3/ebp | 0b47c8c | b926827..0b47c8c |

The superproject range is:

| Commit | Phase contribution |
|---|---|
| 2255493 | Jetpacs component renderer and catalog |
| 8811d61 | text-input semantic reconciliation |
| 342891e | shared editing-controller extraction |
| 3f1c28a | scoped core-renderer overrides |
| 4e0e3a1 | scoped Jetpacs text field |

The clean baseline passed before diagnostics and again after all tracked
diagnostic mutations were restored:

- cd ebp && python3 validate.py — pass; all 27 text-input witnesses included.
- ./test/run-tests.sh — pass; includes warning-as-error Elisp byte compilation.
- The forced broad Gradle gate below — pass, 196/196 tasks executed.
- :renderer:jetpacs:validateDebugScreenshotTest — pass.
- :renderer:material3:validateDebugScreenshotTest — pass.
- :app:assembleDebug — pass.
- git diff --check and git diff --exit-code in the superproject and EBP
  submodule — clean after diagnostics.

The broad gate was:

    cd companion
    ./gradlew --console=plain --rerun-tasks \
      :wire:jvmTest \
      :renderer:model:testDebugUnitTest \
      :renderer:compose:testDebugUnitTest \
      :renderer:compose:compileDebugAndroidTestKotlin \
      :renderer:jetpacs:testDebugUnitTest \
      :renderer:material3:testDebugUnitTest \
      :app:testDebugUnitTest \
      :app:assembleDebug \
      :renderer:jetpacs:validateDebugScreenshotTest \
      :renderer:material3:validateDebugScreenshotTest

No screenshot update task was run.

## Authority and invariant matrix

The table maps the Phase 0–3 invariants to the governing authority, projection,
implementation seam, and evidence. “Verified” is bounded to the named tests; it
is not a claim about an untested path.

| Invariant | Normative/projected authority | Owning seam | Evidence and classification |
|---|---|---|---|
| EBP stays toolkit- and Jetpacs-neutral; canonical nodes remain text_input/editor | ebp/SPEC.md:11-24; docs/ARCHITECTURE-POC3.md:39-61 | EBP contract, generated vocabulary | No alias or downstream name entered EBP; verified |
| The specification outranks projections and witnesses | ebp/SPEC.md:49-63 | ebp/SPEC.md, ebp/contract.json, Goldens | Applied; Golden 03 conflict is confirmed below |
| Scalar-safe JSON and safe-integer bounds | ebp/SPEC.md:125-165 | strict parser, SpecValidator, constructor | 10,000-case astral/safe-integer corpus; transformation path verified, validator parity incomplete |
| Selection counts Unicode scalars and seeds only a new identity/reset epoch | ebp/SPEC.md:2814-2822; ebp/contract.json /text_input_schema/selection | TextInputPresentation, scalar/UTF-16 helpers | Existing unit + connected selection tests and mutation kill; verified for tested paths |
| Local normalization order is single-line, filter, max_length | ebp/SPEC.md:2832-2839; ebp/contract.json /text_input_schema/transform_order | TextInputRules, TextInputController | All 10,000 generated transformations matched; verified |
| max_field_bytes is a JCS UTF-8 budget, including volatile secrets | ebp/SPEC.md:253-270; ebp/contract.json /limits/welcome/max_field_bytes | TextInputController and engine limits | Boundary tests and 10,000 corpus pass; verified for tested values |
| min/max line constraints and single-line newline prohibition | ebp/SPEC.md:2753-2774 | Python/Kotlin/Elisp validators and controller | Runtime normalization verified; Python author/receiver coverage has confirmed gaps |
| state.changed precedes paired on_change with the same value | ebp/SPEC.md:2010-2016 | TextInputController.recordUserEdit | Existing unit test killed the order mutation; connected edit test passed |
| submit injects the current value; each handed-off occurrence concludes once | ebp/SPEC.md:1840-1874; companion/renderer/model/src/main/kotlin/com/calebc42/jetpacs/renderer/model/RendererActionHost.kt:39-60 | controller, DeviceBridge, engine | Current-value paths pass; removing DeviceBridge's AtomicBoolean survived app unit tests, so one-shot proof has a test gap |
| clear_on_submit waits for safe admission and cannot erase a newer edit | ebp/SPEC.md:1936-1950, 2856-2867 | TextInputController.submit | Refusal and generation mutations failed existing tests; verified for tested outcomes |
| Passwords are unseeded, unsaved, unpublished, volatile, drop-only, non-retryable, and erased | ebp/SPEC.md:1870-1874, 1942-1950, 2089-2114, 2776-2785 | validators, controller, DeviceBridge, engine/queue | **Critical and High failures**; not conforming |
| Reconciliation preserves compatible dirty values and clears only on acknowledgement/removal/incompatibility/reset | ebp/SPEC.md:1581-1643 | SurfaceStore/InputDisplay/controller epoch | Unit, connected recreation, rotation, removal/re-add and device observations; verified for tested paths |
| Phase 1 editor refactor introduces no editor regression | EBP editor contract; docs/ARCHITECTURE-POC3.md:269-281 | EditorController and existing wire editor lifecycle | Broad wire/model/Compose suites pass; Unicode-offset mutation failed existing editor tests; no new Jetpacs editor behavior assessed |
| Extension ownership stays singular; core overrides are separate and restricted | docs/ARCHITECTURE-POC3.md:63-72; ebp/SPEC.md:2334-2351 | RendererRegistry, ComposeCoreOverrideRegistry | Scoped override registration/profile tests pass. A pre-range duplicate extension-owner gap is recorded separately |
| Nearest admitted scope wins; roots/dialogs isolate scope; canonical fallback remains outside | docs/ARCHITECTURE-POC3.md:63-72, 254-258 | Compose dispatch and app composition root | Connected scoped-dispatch suite passed and killed isolation mutation; verified |
| jetpacs.scope contributes no layout, semantics, state, interaction, or descendant merge | docs/ARCHITECTURE-POC3.md:254-258 | companion/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsComponentsRenderer.kt:55-64 | Connected semantics tree has no scope owner; verified |
| Only generated Core Node Set members are initially override-eligible | docs/ARCHITECTURE-POC3.md:63-70 | ComposeCoreOverrideRegistry | Registry rejection tests pass for editor/downstream/invented nodes; verified |
| Jetpacs renderer remains Material-free; Material remains default elsewhere | docs/ARCHITECTURE-POC3.md:45-72, 107-113 | companion/renderer/jetpacs/build.gradle.kts and composition root | Dependency mutation failed JetpacsRendererBoundaryTest; both renderer suites/screenshots passed |
| One editable semantics owner; labels/errors/enabled/password/max length are projected without removing hardware actions | ebp/SPEC.md:2795-2839; docs/ARCHITECTURE-POC3.md:250-252, 300-310 | shared semantics + Jetpacs field | Nine connected Jetpacs semantics tests and eight Material/scoped tests pass; manual tree inspection agrees; TalkBack/Switch Access remain unverified |
| Decorations, mask, syntax, and Styles are presentation-only; caret/selection/text layout do not animate | ebp/SPEC.md:2804-2848; plan Styles boundary | :renderer:compose and :renderer:jetpacs | Value/capture tests and source/dependency audit pass; mask performance fails the review threshold |
| Existing Material pixels and all new Jetpacs references remain stable | Phase plan screenshot gates | screenshot tests | Both validators pass without updates; six new field references manually inspected |

Generated mirrors are not acting as handwritten authority:
Vocabulary.kt:1-4 and jetpacs-vocabulary.el:8-13 name their generators and
carry DO NOT EDIT markers. The generated values are read from contract format
9 and drift tests re-read the contract. The independent expected projection in
ebp/validate.py:218-257 is a validator rail, not a generated mirror.

## Confirmed findings

### F-01 — Critical — password data can enter the durable queue

Affected layers: EBP conformance artifacts, Kotlin receiver validation, wire
action dispatch, durable queue.

Authority:

- ebp/SPEC.md:1870-1874 requires a password submit descriptor to name that password
  ID in capture_fields and permits the value only in fields.
- ebp/SPEC.md:2089-2101 requires drop policy, forbids dedupe/ttl, requires an
  authenticated READY session, forbids durable admission, and forbids a
  password in a retry queue or crash recovery.
- ebp/SPEC.md:49-59 says a conflicting Golden must be corrected, not imitated.

Conflicting/defective sources:

- ebp/goldens/text-input.golden:4 declares a password on_submit without
  capture_fields valid.
- companion/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/SpecValidator.kt:990-1005 rejects an authored password value and on_change
  but does not enforce capture, clear_on_submit, or action policy.
- companion/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/SpecValidator.kt:1777-1795 validates capture_fields shape and resolution
  without relating a captured node to its password policy.
- companion/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/CompanionEngine.kt:584-600 merges renderer extraFields into fields
  independently of the descriptor's capture list.
- companion/wire/src/jvmMain/kotlin/com/calebc42/ebp/wire/CompanionEngine.kt:631-664 then admits queue/wake events durably.
- companion/wire/src/jvmTest/kotlin/com/calebc42/ebp/wire/DispatchFieldsTest.kt:49-69 positively expects a password secret supplied by
  extraFields even though its descriptor omits capture_fields.

Deterministic fixture:

1. Create a real CompanionEngine with a real DurableQueue backed by a temporary
   FileQueueStore.
2. Authenticate it, accept a surface containing password text_input ID
   password, and author on_submit with capture_fields [password],
   when_offline queue, and ttl_s 600.
3. Call dispatchAction with a unique secret in extraFields.password.
4. Reopen/read the queue file and assert the secret is absent.

Command in the detached worktree:

    cd companion
    ./gradlew --console=plain :wire:jvmTest \
      --tests 'com.calebc42.ebp.wire.TextEditingAuditDiagnosticTest'

Observed: 3/3 diagnostic assertions failed. The receiver accepted both the
missing-capture descriptor and the explicit-capture queue descriptor. The
queue file was created and contained the unique secret verbatim. A separate
serial rerun reproduced all three failures.

Expected: both documents are content-invalid. Even if malformed accepted state
reaches the engine, a secret-bearing occurrence must not enter durable
admission and must not exist outside authenticated READY.

Impact: durable secret disclosure. The queue is crash-recovery state and can be
reopened/replayed; this meets the review's Critical definition independently
of whether the current catalog happens to author drop.

Remediation boundary:

1. Correct Golden 03 and project the password action constraints into the
   contract where machine-readable validators need them.
2. Enforce password capture ID, drop-only/no-dedupe/no-ttl, READY-only,
   clear_on_submit, and builtin constraints in Python, Kotlin, and the public
   Elisp constructor.
3. Add defense in depth at the action/transport boundary with an explicit
   secret-bearing occurrence type or flag. Do not infer secrecy from arbitrary
   fields and do not let a presentation module choose durability.
4. Permanently test both accept-time rejection and an engine-level assertion
   that no password byte reaches FileQueueStore under any policy/session state.

Required regression tests: table-driven author/receiver tests for every
password descriptor combination, plus a real FileQueueStore reopen test using
a fresh canary and authenticated READY/not-READY variants.

### F-02 — High — the hard password deadline is not enforced

Affected layers: shared TextInputController, typed action host, session/
transport lifecycle.

Authority: ebp/SPEC.md:2103-2114 imposes a hard 30-second monotonic deadline. At
expiry the Companion must close the transport, abandon without retry, and
erase native text/composition, captured fields, encoded buffers, and
correlation state.

Source:

- companion/renderer/compose/src/main/kotlin/com/calebc42/jetpacs/renderer/compose/EditingControllers.kt:152-181 erases a password only from a terminal outcome
  callback or an immediate Ignored handoff.
- companion/renderer/compose/src/main/kotlin/com/calebc42/jetpacs/renderer/compose/EditingControllers.kt:184-194 erases on disposal.
- companion/renderer/model/src/main/kotlin/com/calebc42/jetpacs/renderer/model/RendererActionHost.kt:54-60 promises exactly one later callback for a
  handed-off occurrence, but exposes no deadline conclusion.
- There is no monotonic deadline in the shared action/controller path.

Deterministic fixture: construct TextInputController with a unique password,
dispatch through a fake that returns HandedOff and never invokes its callback,
submit once, wait 30.1 seconds, then assert empty text and
passwordSubmissionPending false.

Command:

    cd companion
    ./gradlew --console=plain :renderer:compose:testDebugUnitTest \
      --tests 'com.calebc42.jetpacs.renderer.compose.PasswordDeadlineAuditDiagnosticTest'

Observed: the test failed after 30.1 seconds; the complete secret remained in
TextFieldState and passwordSubmissionPending remained true. The serial skeptic
rerun reproduced the same result. A temporary connected lifecycle test showed
that ordinary Activity recreation/disposal does erase the field, isolating the
failure to an in-session handed-off occurrence that never concludes.

Expected: expiry no later than the hard deadline, immediate controlled-copy
erasure, no retry, and transport closure. This fixture directly confirms the
field/correlation lifecycle failure; it did not instrument the socket buffer,
so the transport-close and encoded-buffer halves remain unverified rather than
claimed correct.

Impact: reachable secret retention and a permanent locked-field lifecycle dead
end after a lost callback/response. This meets the High definition and also
leaves potentially secret-bearing transport state unbounded.

Remediation boundary: the monotonic deadline belongs to the session/action
transport owner, because only that layer can close a partially written stream
and erase request/correlation buffers. It must deliver one terminal
not-admitted outcome to the controller, which then erases native state. Use an
injectable monotonic clock in permanent tests; cover queued, partially written,
fully written/no-result, cancellation, disposal, and a late duplicate callback.

Required regression test: a fake-clock end-to-end secret occurrence test that
asserts one conclusion, transport closure, no retry/queue record, empty native
state, and no retained correlation/request bytes at the deadline.

### F-03 — Medium — cross-language validators do not implement one envelope

Affected layers: Python reference validator, Kotlin receiver validator, Elisp
public authoring constructor, conformance tests.

Authority: ebp/SPEC.md:2753-2785 and 2856-2867 define line counts, password
constraints, and clear-on-submit constraints. ebp/SPEC.md:49-59 forbids copying a
conflicting witness. contract.json /text_input_schema projects the scalar,
filter, mask, selection, and transformation rules.

Deterministic corpus: seed 20260828, exactly 10,000 cases, 32 rotating scenario
families. It covers astral selection, safe-integer edges, line constraints,
filters, enum fallback, masks, password combinations, builtin submits,
padding, and retained-draft-compatible normalization. Each row carries
normative receiver-valid and author-valid outcomes plus the expected normalized
value.

Commands:

    python3 audit/generate_text_input_corpus.py \
      . /tmp/jetpacs-text-audit.7DJ6fT/text-input-corpus.jsonl

    cd companion
    JETPACS_AUDIT_CORPUS=/tmp/jetpacs-text-audit.7DJ6fT/text-input-corpus.jsonl \
      ./gradlew --console=plain :wire:jvmTest \
      --tests 'com.calebc42.ebp.wire.TextInputCorpusAuditDiagnosticTest'

    JETPACS_AUDIT_CORPUS=/tmp/jetpacs-text-audit.7DJ6fT/text-input-corpus.jsonl \
      emacs --batch -Q -L emacs -L test \
      -l audit/text_input_corpus_audit.el

Observed:

| Implementation/check | Normative mismatches by rule |
|---|---|
| Python receiver reference | clear-on-submit-builtin 312; line-count-order 313; password-authored-secret 312; password-clear-on-submit 312; password-missing-capture 312; password-on-change 312; single-line-line-count 313 |
| Kotlin receiver | password-missing-capture 312; password-clear-on-submit 312; clear-on-submit-builtin 312 |
| Elisp author constructor | password-missing-capture 312 |
| Python/Kotlin parity | single-line-line-count 313; line-count-order 313; password-authored-secret 312; password-on-change 312 |
| Normalization value | 0 mismatches in 10,000 |
| Elisp canonical bytes for author-valid cases | 0 mismatches |

The 27-witness replay sharpened the cause:

- Python and Kotlin reproduce all 27 current Golden outcomes.
- Elisp accepts/rejects the current Golden set except case 02, where strict
  authorship correctly rejects an unknown receiver-fallback enum.
- All three follow Golden 03, but normative prose requires Golden 03's password
  descriptor to be rejected because capture_fields is absent.

Expected: one explicitly role-scoped envelope. Receiver fallback may differ
from strict authoring only where the specification says so; these password,
line, and builtin differences are not such cases.

Impact: a published reference validator can certify invalid documents, while
peers disagree about what is admissible. The password subset enables F-01; the
remaining mismatch is a significant conformance and test-honesty defect.

Remediation boundary: derive table-driven rules from the corrected normative
contract, retain explicit author-versus-receiver fallback distinctions, and
make the 27 witnesses plus deterministic corpus run in Python, Kotlin, and
Elisp CI. The permanent test should report rule-family counts and seed so a
failure is reproducible without saving a 10,000-line artifact.

### F-04 — Medium — mask projection exceeds the scaling gate

Affected layer: shared Compose output transformation used by Material and
Jetpacs.

Authority: the review gate treats a greater-than-8× time increase for a 4×
input as a finding and requires a post-warmup 64 KiB run below two seconds.
ebp/SPEC.md:2841-2848 requires total scalar-safe mask mapping without truncation.

Source: companion/renderer/compose/src/main/kotlin/com/calebc42/jetpacs/renderer/compose/EditingControllers.kt:438-459 repeatedly inserts each mask literal into
TextFieldBuffer. The diagnostic exercised the production
MaskOutputTransformation, not a reimplementation.

Method: sizes 1, 4, 16, and 64 KiB; five warmups; nine timed/allocation samples;
median reported. Allocation used the JDK ThreadMXBean already available to the
JUnit process. Three independent runs showed the same growth shape.

Representative run:

| Projection | Size | Median time | Median allocated |
|---|---:|---:|---:|
| syntax | 1 KiB | 80,508 ns | 16,824 B |
| syntax | 4 KiB | 296,585 ns | 69,344 B |
| syntax | 16 KiB | 248,997 ns | 267,088 B |
| syntax | 64 KiB | 759,358 ns | 1,120,056 B |
| mask | 1 KiB | 525,412 ns | 67,608 B |
| mask | 4 KiB | 2,025,876 ns | 588,528 B |
| mask | 16 KiB | 22,232,464 ns | 7,536,064 B |
| mask | 64 KiB | 301,674,671 ns | 113,629,256 B |

Command:

    cd companion
    ./gradlew --console=plain :renderer:compose:testDebugUnitTest \
      --tests 'com.calebc42.jetpacs.renderer.compose.TextInputPerformanceAuditDiagnosticTest'

Observed: mask time increased about 11× from 4 to 16 KiB and 13.6× from 16 to
64 KiB. The 64 KiB median stayed below two seconds and no crash/ANR occurred.
Syntax projection remained within the gate.

Expected: no greater than 8× time for each 4× input step and no 64 KiB run over
two seconds.

Impact: bounded but materially superlinear work and approximately 114 MB of
allocation for one 64 KiB projection, raising jank/GC risk on large legal
fields.

Remediation boundary: make mask projection construct/coalesce the transformed
output without one shifting insertion per literal, while preserving automatic
offset mapping and scalar boundaries. Add an existing-JUnit performance
regression with warmup, multiple sizes, and a deliberately tolerant ratio;
add a deterministic structural/operation-count assertion if timing is too
noisy for CI.

## Test-honesty mutation pass

Each mutation was applied alone in the detached worktree, the named existing
test layer was run, and tracked source was restored immediately.

| Mutation | Existing evidence | Result | Classification |
|---|---|---|---|
| Dispatch on_change before state publication | EditingControllersTest.textEditPublishesStateBeforeItsAction | Failed as required | protected |
| Clear after a refused submit | EditingControllersTest clear-on-submit cases | Failed as required | protected |
| Publish password through the production presentation binding | host unit then connected Jetpacs semantics | Host unit stayed green; connected test failed | test gap: host fake cannot represent production binding |
| Remove DeviceBridge AtomicBoolean one-shot guard | :app:testDebugUnitTest | All app unit tests passed | **Medium test gap** |
| Let design scope cross into a dialog | host ComposeExtensionRegistryTest then connected ScopedCoreOverrideDispatchTest | Host stayed green; connected test failed | test gap at host layer; connected gate protects packaged behavior |
| Break astral scalar-to-UTF-16 conversion | TextInputContractTest and TextInputPresentationTest | Both failed as required | protected |
| Add a Material3 dependency to :renderer:jetpacs | JetpacsRendererBoundaryTest | Failed as required | protected |

The one-shot gap is material because RendererActionHost.kt:39-60 promises
exactly one terminal outcome and password erasure/clear-on-submit depend on
that promise. Add an app unit test whose fake engine invokes the callback
twice and assert only one main-thread RendererActionOutcome reaches the
controller. Keep the connected password-publication and dialog-isolation
tests, but add host fixtures capable of representing the real binding/scope
transition so those failures do not require a device to detect.

Fakes otherwise represented safe/not-admitted outcomes, queue policy, explicit
capture fields, lifecycle disposal, negotiated profiles, and byte limits in
the paths challenged here.

## Reconciliation and lifecycle results

Verified:

- Authored, empty, locally dirty, acknowledged, reset, restored, incompatible,
  removed/re-added, and password-transition paths are exercised by the owning
  SurfaceStore/controller tests.
- The temporary connected lifecycle harness preserved an ordinary astral
  draft through Activity recreation, disposed a pending secret, and restored
  an empty secret field.
- Autofocus occurred once for one presentation identity, did not steal focus
  on ordinary recomposition, and requested focus for a new identity.
- Paste/input transactions used the same single-line/filter/max-length
  normalization path; Unicode selection mutation was killed by existing tests.
- The generation guard preserved newer text when an older submit later became
  safely admitted.
- Byte-identical removal/re-add uses a new presentation incarnation rather than
  silently reusing native field state.

The connected commands and results were:

| Suite | Result |
|---|---:|
| EditingControllersInstrumentedTest | 2/2 pass |
| JetpacsComponentsSemanticsTest | 9/9 pass |
| EbpSemanticsTest + Material compatibility + ScopedCoreOverrideDispatchTest | 8/8 pass |
| temporary TextInputLifecycleAuditDiagnosticTest | 2/2 pass |

IME composition and autofill/drop-equivalent behavior were covered only
through the shared InputTransformation contract and synthetic Compose edits;
no external autofill provider or long-lived real IME composition session was
observed manually. That portion is unverified, not refuted.

## Scoped registry and trust-boundary results

Verified for the reviewed range:

- nearest nested scope selection;
- canonical Material fallback outside the scope;
- app-to-dialog isolation and root scope reset;
- duplicate core override refusal;
- profile admission;
- only generated Core Node Set override eligibility;
- no downstream extension override;
- jetpacs.scope creates no bounds/semantics/state/interaction owner;
- Jetpacs main sources and dependencies remain Material-free.

One adversarial registry candidate was confirmed but is outside the reviewed
range. companion/renderer/model/src/main/kotlin/com/calebc42/jetpacs/renderer/model/RendererRegistry.kt:81-84
flattens extensionOwners with toMap, so two
extensions claiming one node silently use the last owner instead of refusing
duplicate ownership. The diagnostic
RendererRegistryAuditDiagnosticTest.duplicateCrossExtensionNodeOwnershipIsRefused
failed. git blame attributes the code to 86b85774, which is an ancestor of
2255493's parent, and the packaged owner sets do not overlap. Classification:
**pre-existing Medium test/validation gap, not a Phase 0–3 regression**. Put it
in a named backlog and add a constructor-level duplicate refusal test.

## Presentation, accessibility, and screenshot results

Both screenshot validators passed against existing references with no update
task. The six new Jetpacs field references were also inspected directly:

- compact;
- expanded;
- dark;
- focus with an empty secure field;
- 1.5× text;
- RTL.

No clipping, duplicate editable owner, secret content, obvious error/support
precedence problem, or RTL/large-text break was observed in those deterministic
preview fixtures. This is pixel/visual evidence only; it does not prove live
IME, lifecycle, or accessibility-service behavior.

Connected semantics verified one editable owner, accessible naming, error
precedence, enabled/password/maximum-length state, and retained editing
actions. jetpacs.scope was absent as a semantic owner. Styles and experimental
APIs remain downstream in :renderer:jetpacs, and no Material import or
dependency is present there.

TalkBack and Switch Access were disabled in the saved tablet configuration.
Automation cannot establish spoken announcement order or switch-scanning
usability, so the requested service-level pass remains **unverified**. The
connected semantics assertions are not a substitute for that human gate.

## Device gate

The unchanged reviewed baseline was installed with:

    tools/onboard-tablet.sh --vault shared --emacs-home emacs \
      --skip-pylsp 192.168.1.181:36307

Target: Pixel Tablet, Android 17. The onboarding script installed APK
0.1.0-w4, synchronized 115 Elisp source files, preserved application data, and
reconnected Emacs and the Companion.

The following pre-test settings were recorded and the same values were
confirmed at the end: accelerometer rotation enabled, user rotation 0,
accessibility services disabled, Gboard default IME, font scale 1.0, airplane
mode off, Wi-Fi on, mobile data on, 1600×2560 physical display, density 320.

Observed live behavior:

- The Jetpacs Components Text Field page opened through the normal Apps route.
- Ordinary typing and submit reported the current value and cleared only after
  safe admission.
- Selection, copy, clear, and paste worked with the hardware-input path.
- Rotation retained the ordinary draft and left the secure field empty.
- Restarting the Companion process retained ordinary accepted draft state and
  restored only secure submit count/length, not the secure value.
- With Emacs stopped, the default offline drop submit was not queued and did
  not clear the field. On reconnect the catalog first republished its home
  document, removing the text node; clearing that draft was therefore the
  required ebp/SPEC.md:1589-1594 removal result, not a reconciliation defect.
- The final state had Emacs and Companion reconnected, ordinary touch/pointer
  injection healthy, and the Text Field catalog page visible.

One unique 19-character secure canary was submitted exactly once. The catalog
reported only submit count 1 and scalar length 19. The field was empty after
completion. The canary was absent from:

- the app process's filtered logs;
- Companion private storage searched through its allowed package context;
- the captured Compose/accessibility layout;
- the post-submit screenshot;
- visible catalog state and durable queue records.

This does **not** complete the secret-erasure gate:

- The canary was injected through an adb shell command. Android's adbd audit
  logging recorded that command text, contaminating an all-system-log search.
  That is a harness disclosure, not evidence that product code logged the
  value, but it prevents an honest “absent from all logs” claim.
- The Emacs package is not debuggable through run-as, so its private storage
  and reachable process memory were not exhaustively searched.
- Immutable runtime copies, encoded socket/kernel buffers, and retained JVM/
  Emacs references were not instrumented.

Classification: product log/storage evidence is partially verified; complete
canary absence and controllable-memory erasure are **unverified potentially
high-impact candidates**. A focused re-review must use a canary entry path that
does not place the literal on an adb command line and a test build/harness that
can inspect both endpoint stores, queues, encoded buffers, correlation state,
and reachable secret holders.

A direct M-x catalog invocation emitted a surface re-registration warning
between catalog and hub owners; the normal Apps path worked. This was not tied
to the Phase 0–3 text-editing range and was not promoted to a finding without a
focused reproduction/provenance check.

Physical human TalkBack, Switch Access, touch, pointer, and hardware-keyboard
acceptance was not available. Touch, mouse, and keyboard paths were driven by
device automation; those results must not be represented as the requested
human accessibility pass.

## Refuted candidates and verified-correct areas

- **Offline field was not “lost.”** The node disappeared when the restarted
  Emacs catalog republished home; ebp/SPEC.md:1589-1594 requires clearing on node
  removal. Refuted.
- **Material leaked into Jetpacs.** Source/dependency search and a deliberate
  dependency mutation show the boundary test is live. Refuted.
- **Scope contributes semantics or layout.** Connected unmerged-tree
  inspection found only its children and no owner bounds. Refuted.
- **Dialog inherits app scope.** Connected scoped dispatch rejects the app-only
  scope in a dialog and killed the boundary mutation. Refuted.
- **Normalization disagrees on astral/filter/order cases.** All 10,000 expected
  normalized values matched Kotlin; Elisp canonical values matched every
  author-valid case. Refuted for the generated corpus.
- **Syntax projection violates the performance gate.** Its representative
  64 KiB median was under one millisecond with approximately linear allocation.
  Refuted for this JVM harness.
- **Existing Material pixels changed.** The validator passed without updating
  references. Refuted at the screenshot determinism envelope.

No genuine spec ambiguity survived. Golden 03 is an artifact conflict under
SPEC §2.2, not ambiguous normative prose.

## Diagnostic commands

The intentionally failing diagnostic commands are listed so remediation can
recreate the same fixtures. The audit/ files and AuditDiagnosticTest classes
were temporary and were not committed.

    # EBP projection and all published witnesses
    cd ebp
    python3 validate.py

    # 27-case Elisp author replay
    cd ..
    emacs --batch -Q -L emacs -L test \
      -l audit/text_input_goldens_audit.el

    # deterministic 10,000-case corpus (seed 20260828)
    python3 audit/generate_text_input_corpus.py \
      . /tmp/jetpacs-text-audit.7DJ6fT/text-input-corpus.jsonl

    JETPACS_AUDIT_CORPUS=/tmp/jetpacs-text-audit.7DJ6fT/text-input-corpus.jsonl \
      emacs --batch -Q -L emacs -L test \
      -l audit/text_input_corpus_audit.el

    cd companion
    JETPACS_AUDIT_CORPUS=/tmp/jetpacs-text-audit.7DJ6fT/text-input-corpus.jsonl \
      ./gradlew --console=plain :wire:jvmTest \
      --tests 'com.calebc42.ebp.wire.TextInputCorpusAuditDiagnosticTest'

    # Critical durable-secret fixture
    ./gradlew --console=plain :wire:jvmTest \
      --tests 'com.calebc42.ebp.wire.TextEditingAuditDiagnosticTest'

    # High hard-deadline fixture
    ./gradlew --console=plain :renderer:compose:testDebugUnitTest \
      --tests 'com.calebc42.jetpacs.renderer.compose.PasswordDeadlineAuditDiagnosticTest'

    # mask/syntax performance
    ./gradlew --console=plain :renderer:compose:testDebugUnitTest \
      --tests 'com.calebc42.jetpacs.renderer.compose.TextInputPerformanceAuditDiagnosticTest'

    # pre-existing extension-owner candidate
    ./gradlew --console=plain :renderer:model:testDebugUnitTest \
      --tests 'com.calebc42.jetpacs.renderer.model.RendererRegistryAuditDiagnosticTest'

The connected baseline used the repository's existing connectedDebugAndroidTest
tasks with class selectors for EditingControllersInstrumentedTest,
JetpacsComponentsSemanticsTest, EbpSemanticsTest, Material compatibility, and
ScopedCoreOverrideDispatchTest. The temporary lifecycle class was run alone.

## Original remediation gate

At discovery, Phase 4 was blocked until:

1. F-01 receives a separate EBP/projection correction and production
   remediation commit, with permanent Python/Kotlin/Elisp and real durable-store
   regression tests.
2. F-02 receives a session-owned hard-deadline/transport-erasure remediation
   commit and an end-to-end fake-clock regression.
3. F-03 and F-04 receive explicit fix/defer decisions; any deferral names an
   owner, rationale, and deadline before Phase 4.
4. The DeviceBridge one-shot unit gap is closed.
5. The full clean baseline, both screenshot validators, connected suites, and
   a safe canary/device pass are rerun on the remediated commits.
6. A focused skeptic re-review reproduces the original fixtures and confirms
   they now fail closed without merely teaching the diagnostics a new expected
   answer.

Those code conditions were necessary but not sufficient. The review plan also
requires the physical human accessibility/input acceptance described in the
current gate decision below. docs/PLAN-jetpacs-text-editing.md must not advance
until both sets of conditions are satisfied.

## Remediation and focused skeptic re-review

The remediation was kept separate from the discovery report and from this
report-only update:

| Repository | Commit | Responsibility |
|---|---|---|
| ebp | 65a0b12 | Make password submission constraints normative and project them into the contract, Golden, and validator |
| llm-poc-3 | 18bc51b | Regenerate the mirrors, align Python/Kotlin/Elisp validation, add the deterministic corpus, and add durable-store refusal coverage |
| llm-poc-3 | b123006 | Introduce a typed volatile-secret handoff, one-shot conclusion gate, monotonic 30-second deadline, transport abort, and disposal tests |
| llm-poc-3 | e0354a8 | Replace superlinear mask projection with the shared linear implementation and permanent scaling tests |
| llm-poc-3 | b18c4a5 | Obfuscate password accessibility values at the shared presentation seam and cover both canonical and scoped presentations |
| llm-poc-3 | 46ea511 | Purge Compose password undo history at terminal erasure and cover real user-input history on-device |

Android Credential Manager was deliberately not added. It could provide a
credential-provider or autofill user experience, but it does not enforce EBP
offline policy, durable admission, callback conclusion, transport deadlines,
or Compose accessibility semantics. Adding it would also have crossed the
review's dependency and public-behavior freeze without fixing any confirmed
finding.

### Finding dispositions

| Finding | Focused result | Disposition |
|---|---|---|
| F-01 Critical — password can enter the durable queue | The corrected authority requires drop-only volatile delivery; generated validators agree; real queue-store tests reject the occurrence; the typed secret never becomes a durable action payload | **Resolved** by 65a0b12, 18bc51b, and b123006 |
| F-02 High — hard password deadline absent | A fake monotonic clock proves pending at 29,999 ms, timeout/transport abort/erasure at 30,000 ms, and exactly one terminal outcome despite a late callback | **Resolved** by b123006 |
| F-03 Medium — cross-language envelopes disagree | Forty published witnesses and 10,000 generated cases, seed 20260828, agree in Python, Kotlin, and Elisp | **Resolved** by 65a0b12 and 18bc51b |
| F-04 Medium — mask projection exceeds the scaling gate | Post-warmup 64 KiB median is below 1 ms with approximately linear allocation; every 4× step remains below the 8× threshold | **Resolved** by e0354a8 |
| DeviceBridge one-shot test gap | RendererOutcomeGate and SecretActionAttempt tests exercise duplicate ordinary callbacks, timeout followed by callback, and repeated erasure | **Resolved** by b123006 |
| F-05 Critical — password plaintext in Android accessibility text | Reproduced in raw UIAutomator on the remediated pre-fix build; shared semantics now publish scalar-count bullets for InputText and EditableText | **Resolved** by b18c4a5 |
| F-06 Critical — submitted password remains reachable through Compose undo staging | A pre-fix forced-GC heap rooted two exact canary arrays through TextUndoOperation.preText; the final forced-GC heap contains no Latin-1 or UTF-16 copy | **Resolved** by 46ea511 |

### F-05 — Critical — pre-fix accessibility tree disclosed password text

Authority: ebp/SPEC.md:1870-1874, 2089-2114, and 2795-2839 require password
content to remain volatile and password presentation to expose password state
without creating another secret publication path. The shared-presentation
ownership rule is in docs/ARCHITECTURE-POC3.md:250-252 and 300-310.

Deterministic reproduction on the Pixel Tablet, against e0354a8 before the
F-05 fix:

1. Open Apps → Jetpacs Components → Text Field and enter a unique 19-scalar
   value in One-time secret using individual KEYCODE events.
2. Before submitting, run
   `/usr/bin/adb -s 192.168.1.181:36307 exec-out uiautomator dump /dev/tty`.
3. Inspect the password EditText node.

Expected: `password="true"` and an accessibility value containing only 19
obfuscation bullets. Observed: `password="true"` with the 19-character
plaintext in `text`. Compose Foundation 1.12 visually obfuscated the secure
field but also published the raw value as InputText, which Android selected for
AccessibilityNodeInfo.text. An enabled accessibility service or UI automation
consumer could therefore read the secret before submission. This is a secret
disclosure under the review severity rubric.

b18c4a5 centralizes the correction in
companion/renderer/compose/src/main/kotlin/com/calebc42/jetpacs/renderer/compose/TextInputPresentation.kt:88-138.
Password fields override InputText and EditableText with one U+2022 bullet per
Unicode scalar, convert the UTF-16 selection to scalar offsets, retain the
password property, and leave native editing/autofill actions on the underlying
field. Both Jetpacs and canonical Material presentation call this same
fieldModifier seam.

Permanent connected regressions are in
companion/renderer/jetpacs/src/androidTest/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsComponentsSemanticsTest.kt:269-328
and
companion/renderer/material3/src/androidTest/kotlin/com/calebc42/ebp/companion/render/ScopedCoreOverrideDispatchTest.kt:156-183.
The Jetpacs witness uses `a` + one astral scalar + `b`: the action capture sees
the exact secret, while InputText/EditableText contain three bullets and the
selection is scalar offset 3.

Test honesty was checked by temporarily bypassing the shared password
sanitizer. The focused connected test then failed on raw InputText, raw
EditableText, and the UTF-16 selection offset 4 instead of scalar offset 3. The
mutation was restored immediately; the same test passed, and the clean full
connected classes passed afterward.

### F-06 — Critical — Compose undo staging retained the submitted password

Authority: ebp/SPEC.md:2089-2114 requires terminal password handling to erase
native text, composition, captured fields, encoded buffers, and correlation
state. The review gate additionally treats immutable or still-reachable
product-owned copies as findings; an empty visible field is not sufficient
evidence of erasure.

Deterministic reproduction against b18c4a5:

1. Enter a unique 19-byte ASCII canary in the catalog's One-time secret field
   using individual KEYCODE events and submit it exactly once.
2. Verify that the visible field and accessibility node are empty.
3. Force garbage collection while the Text Field presentation remains live:

       /usr/bin/adb -s 192.168.1.181:36307 shell \
         am dumpheap -g com.calebc42.ebp.companion \
         /data/local/tmp/jetpacs-b18c4a5-final-gc.hprof

4. Pull the HPROF, locate exact primitive-array copies of the canary, and walk
   incoming references to a GC root.

Expected: no still-reachable product-owned copy after the terminal outcome.
Observed: the 47,631,483-byte post-GC heap contained two exact `byte[19]`
copies. One was rooted through this live path:

    ROOT_JNI_GLOBAL
      RemoteInputConnectionImpl.mParentInputMethodManager
      InputMethodManager.mNextServedView
      AndroidComposeView.layoutNodes
      LayoutNode._modifier
      TextFieldTextLayoutModifier.textFieldState
      TransformedTextFieldState.textFieldState
      TextFieldState.textUndoManager
      TextUndoManager.stagingUndo
      TextUndoOperation.preText
      String.value
      byte[19] canary

The terminal callback cleared `TextFieldState.text`, but Compose Foundation
1.12 separately retained the pre-clear user edit in its undo manager. This is
a heap-extractable secret disclosure from the currently served presentation
and therefore Critical under the review rubric.

46ea511 fixes the owning shared-controller seam at
companion/renderer/compose/src/main/kotlin/com/calebc42/jetpacs/renderer/compose/EditingControllers.kt:230-242.
`eraseVolatileState()` now calls `TextFieldState.undoState.clearHistory()`
before and after clearing the text. The first purge removes user-edit staging
that owns the password; the second prevents the clear transaction from leaving
undo/redo state. The permanent
`passwordErasureClearsTextAndComposeUndoHistory` connected test at
companion/renderer/compose/src/androidTest/kotlin/com/calebc42/jetpacs/renderer/compose/EditingControllersInstrumentedTest.kt:79-120
enters text through a real `BasicTextField`, proves undo exists before erasure,
then asserts empty text and no undo or redo history.

Test honesty was checked by temporarily removing both history purges. The
focused connected test failed at the post-erasure `canUndo == false`
assertion. The mutation was restored immediately, the same test passed, and
all three connected renderer suites passed afterward.

The exact 46ea511 tree was then deployed. A fresh 19-character canary was
entered with individual KEYCODE events, appeared only as 19 accessibility
bullets, and was submitted once. An explicit-GC heap dump taken while the
presentation remained live was 48,574,183 bytes. Complete byte searches found
zero copies in compact Latin-1, UTF-16BE, or UTF-16LE representation. The
canary was also absent from all four Android log buffers in the bounded window
(2,290 lines / 334,603 bytes), every Companion private file archived through
`run-as` (128,512 bytes), and Emacs Jetpacs durable state (102,400-byte archive;
only `receipts.sqlite` changed after the boundary). Process recreation left
the secure field empty and restored only submit count and length. All temporary
HPROF, hierarchy, and screenshot files were deleted after verification.

### Clean verification on the remediated commits

The final clean source gate passed:

- `cd ebp && python3 validate.py` — pass: 40 text-input witnesses plus all
  other EBP fixtures.
- `./test/run-tests.sh` — pass, including warning-as-error byte compilation and
  the 10,000-case cross-language corpus with seed 20260828.
- Forced broad Android gate — pass, 208/208 tasks executed: wire, model,
  Compose, Jetpacs, Material, and app unit tests; Android-test compilation; APK
  assembly; and both screenshot validators.
- Shared-controller connected suite — 3/3.
- Jetpacs semantics connected suite — 10/10.
- Material compatibility, EBP semantics, and scoped-dispatch connected suites
  — 9/9 total.
- Both screenshot validators used existing references. No screenshot update
  task ran.

The forced Android command was:

    cd companion
    ./gradlew --console=plain --rerun-tasks \
      :wire:jvmTest \
      :renderer:model:testDebugUnitTest \
      :renderer:compose:testDebugUnitTest \
      :renderer:compose:compileDebugAndroidTestKotlin \
      :renderer:jetpacs:testDebugUnitTest \
      :renderer:jetpacs:compileDebugAndroidTestKotlin \
      :renderer:material3:testDebugUnitTest \
      :renderer:material3:compileDebugAndroidTestKotlin \
      :app:testDebugUnitTest \
      :app:assembleDebug \
      :renderer:jetpacs:validateDebugScreenshotTest \
      :renderer:material3:validateDebugScreenshotTest

Post-warmup mask medians recorded by the permanent performance test were:

| Input | Time (ns) | Allocated bytes |
|---:|---:|---:|
| 1 KiB | 82,677 | 21,776 |
| 4 KiB | 112,696 | 86,288 |
| 16 KiB | 235,589 | 344,336 |
| 64 KiB | 952,313 | 1,376,528 |

For comparison, the diagnostic pre-fix 64 KiB run was approximately
301,674,671 ns and 113,629,256 bytes. The fixed 64 KiB run is far below the
two-second ceiling and no 4× input step produced a greater-than-8× time rise.

### Remediated device and secret gate

The exact b18c4a5 tree was first deployed with the normal onboarding script for
the F-05 focused pass. The user-supplied
`/home/calebc42/.local/share/android/platform-tools/adb` path was
not present; `/usr/bin/adb` (the installed platform-tools binary) was used.
APK data was preserved, 115 `.el` files were synchronized, and source-tree
`.elc` files were excluded.

The first post-fix 19-character acceptance canary was entered only as
individual KEYCODE events and submitted exactly once. Its literal was never an
adb, SSH, grep, or process-command argument. Before submission:

- Android CLI layout exposed exactly 19 bullets and the password state.
- Raw UIAutomator exposed an EditText with `password="true"` and exactly 19
  bullets, with no plaintext.

After submission, the field was empty and the catalog reported only
`Secure submits: 1 · last length: 19`. The post-submit screenshot contained an
empty field and length-only state. Companion process death and relaunch again
showed an empty secure field and retained only count/length.

The acceptance canary was absent from every Companion private file, including
the durable queue, accepted surfaces, drafts, theme, and preferences. An SSH
mtime sweep of the Emacs private HOME showed that the only post-boundary file
was `.emacs.d/jetpacs/var/receipts.sqlite`; its complete 91,136-byte content
contained no canary. This closes the earlier device harness limitation without
requiring Emacs to be debuggable through Android run-as.

The busy main log buffer wrapped the earliest part of that first canary's
window after later lifecycle/UI inspection. The retained portion was scanned
completely and was clean, but it was not misreported as a complete all-log
proof. A second, distinct 19-character diagnostic canary was therefore entered
with individual KEYCODE events and submitted exactly once, then scanned
immediately. Over device timestamps 1787946185.806128396 through
1787946221.763238277, complete bounded in-memory scans found no canary in:

- main: two overlapping chunks, 424 timestamped entries scanned;
- system: one chunk, 86 entries;
- crash: empty for the window;
- kernel: empty for the window.

The second canary was also absent from every complete Companion private file
and from the only post-boundary Emacs file, `receipts.sqlite`. Searches were
performed in the orchestrator's memory after fetching bounded content; neither
canary literal was placed in an adb/SSH/search command line.

The offline admission check used a non-secret draft. With Emacs force-stopped,
the Companion showed its offline state; submitting preserved the field text,
left the submit count unchanged, and left `ebp-queue.json` byte-for-byte
unchanged with no records. After reconnect, the catalog republished its home
document as specified, and the final device state had both processes running
with the Text Field page visible.

Final settings matched the saved state: automatic rotation 1, user rotation 0,
accessibility disabled with no enabled service, Gboard as the default IME,
Wi-Fi 1, and airplane mode 0. The post-fix pass used touch injection and
hardware-key events. The earlier pass also covered selection, copy, delete,
paste, actual rotation/recreation, and ordinary one-shot submit.

This evidence proves disposal of the product-owned typed holders, deadline
correlation, durable records, visible/accessibility state, and the inspected
endpoint stores. The final 46ea511 forced-GC heap additionally proves that the
exact canary is absent from the live Companion heap in all relevant string
encodings. It does not claim impossible erasure of every historical
kernel/socket or remote-runtime copy. No still-reachable Companion-owned copy
was found after the terminal paths, and the permanent fake-clock, disposal,
and real-input undo tests exercise the controllable holders directly.

### Current gate decision

Original remediation-gate items 1 through 6 are satisfied on 65a0b12 and
46ea511, and the subsequently discovered F-05 and F-06 findings are resolved
and focused-review clean. There is no surviving confirmed or unverified
potentially Critical/High code candidate.

Phase 4 nevertheless remains blocked because the requested physical human
TalkBack and Switch Access pass, together with human touch, pointer, and
hardware-keyboard acceptance, was not available. Automation and semantics
tests are evidence, but they cannot prove announcement order or switch-scanning
usability. Once that physical acceptance is performed and no defect is found,
the remaining gate can close and docs/PLAN-jetpacs-text-editing.md can advance.

## Artifact hygiene

- Generated vocabulary mirrors changed only through the documented generators
  in 18bc51b after the contract projection changed; no mirror was hand-edited.
- No screenshot reference was updated.
- No dependency was added. Production and permanent tests changed only in the
  named remediation commits; this final audit update changes only this report.
- No new testing/profiling framework was added.
- Pre-existing source-tree .elc files were preserved and excluded from
  onboarding; none was treated as authority.
- Unrelated untracked workspace files were not cleaned or normalized.
