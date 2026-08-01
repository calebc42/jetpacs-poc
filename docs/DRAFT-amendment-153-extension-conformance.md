# DRAFT amendment #153 — §24.6 extension-modules conformance note

Drafted 2026-08-01 against `ebp/SPEC.md` @ `83d6e08` (amendments through
#152), by RF-3 gate (4) (PLAN-rf3-seam.md S1). Source analysis:
PLAN-rf3-seam.md §Context and the RF-3 suites named below.

**This is a draft for ratification, not an applied edit.** Prose-only: no
golden, contract entry, fixture, or limit changes — squarely I1 exemption
class (a) (PLAN-refound-2026-07-28.md I1). On ratification it lands in the
`ebp` submodule with its SPEC-CHANGES row, and the submodule pointer bump
rides the same change. The `Ratified` column is the maintainer's.

## Why a clause is needed

Three passages jointly assert that a non-registry method always produces
the unknown outcome:

- §12 rule 4: "Unknown request methods receive `-32601`; unknown
  notifications are ignored."
- §11 (:1245-1254): the identifier check precedes any registry consult;
  everything else about a method's dispatch is registry-defined.
- §24.6 item 4 REQUIRES a conformance case for "unknown-request, and
  unknown-notification dispatch".

An implementation carrying a negotiated extension module (RF-3's seam —
the growth path §12's namespace rule and §25's "new OPTIONAL capability"
clause anticipate) departs from that text as literally written: a
registered **and negotiated** extension method dispatches to its handler.
Without a conformance note, item 4's expected outcome is ambiguous on any
implementation that carries extensions, and §24.4 makes an unscoped
conformance claim a process defect. The clause below scopes item 4 and
adds the two verifications that make an extension's presence falsifiable
rather than trusted.

## SPEC-CHANGES row (ready to paste)

> | 153 | 2026-08-01 | §24.6 | **Extension-modules conformance note.** §24.6 item 4's unknown-method and unknown-notification outcomes are evaluated against a session in which no extension module is negotiated. An implementation that registers extension modules (§12 namespace rules; names outside `ebp.`) MUST additionally verify: (a) an extension method that is registered but NOT negotiated in the current session is indistinguishable on the wire from an unknown method — the identical §7.3 outcome, decided before direction, class, or state; and (b) negotiating an extension changes dispatch outcomes only for that extension's registered methods. Rationale: §12 rule 4 and §11's registry language read literally say a non-registry method is always the unknown outcome; a negotiated extension is the sanctioned departure, and without this note item 4 is ambiguous on any implementation carrying one, which §24.4 classifies as a process defect. Prose-only; no golden, contract, or limit changes. | none | |

## SPEC.md edit

- §24.6, after the closing sentence ("An optional-module suite MUST add
  failure and crash-boundary cases specific to that module."), append:

  > Item 4's unknown-method and unknown-notification outcomes are
  > evaluated against a session in which no extension module is
  > negotiated. An implementation that registers extension modules
  > (Section 12 namespace rules) MUST additionally verify that an
  > extension method not negotiated in the current session is
  > indistinguishable on the wire from an unknown method, and that
  > negotiating an extension changes dispatch outcomes only for that
  > extension's registered methods.

## Artifact changes

None. The verifications (a)/(b) already exist as executable evidence in
the reference implementation, which is why ratification adds obligation
text, not new work:

- (a) Kotlin: `ExtensionSeamTest.unnegotiatedTenantRequestIsTheSevenThreeAnswer`
  — the reply of an engine carrying-but-not-granting the tenant compared
  semantically (§24.5) against the pre-seam captured fixture AND against a
  module-free engine's reply; the notification arm in
  `unnegotiatedTenantNotificationIsSilent`. Elisp:
  `ebp-test-module-unnegotiated-request-is-unknown` (equal error objects
  through one client) and `ebp-test-module-ungranted-inbound-is-sealed`.
  Live: `ebp-seam-test-unnegotiated-is-unknown-live` against the real
  `--echo` host.
- (b) Kotlin: the frozen-dispatch half of gate (1) — the entire pre-RF-3
  corpus green untouched with `postAuthDispatchRules` and
  `methodRegistryMatchesContract` diff-empty — plus
  `grantedTenantRequestEchoesThenPulses` scoped to the tenant's methods.
