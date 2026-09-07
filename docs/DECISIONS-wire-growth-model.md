# Wire vocabulary growth model

Status: informative design decision. Normative behavior lives in the EBP spec
and contract; this page explains how to choose a compatible growth mechanism.

## Decision

Classify every new member or value on two axes:

1. Is it cosmetic, so ignoring it changes presentation but not the authorized
   behavior?
2. Is it constraining, so ignoring it would remove a guard, narrow condition,
   expiry, confirmation, predicate, or other limit?

Then inspect the host object's posture: open-with-degrade or closed-and-reject.

| Host posture | Cosmetic addition | Constraining addition |
|---|---|---|
| Open value space with a specified harmless fallback | The receiver may degrade exactly as specified | Negotiate/withhold; never strip the constraint |
| Closed object or value space | Withhold until advertised or update both endpoints in a protocol-major change | Reject or negotiate; never execute a less-constrained base behavior |

The safe asymmetry is deliberate. Rejecting an unsupported construct is loud
and recoverable. Ignoring an unsupported constraint can silently authorize more
than the sender requested.

## Examples

- An unknown icon name may render the specified placeholder because the action
  and accessible meaning remain bounded by the core contract.
- A confirmation, `ttl_s`, offline policy, trigger predicate, or capability
  condition cannot be dropped. Doing so would perform more work than authored.
- A cosmetic member added to an otherwise closed object still needs
  sender-withholding or a coordinated schema change: sending it early would make
  an older receiver reject the whole object.
- A design-system node belongs in a positively negotiated renderer extension
  rather than in EBP merely because one receiver can draw it.

## Growth procedure

For each proposed wire addition:

1. Name the observable behavior and whether the addition is cosmetic,
   constraining, or load-bearing.
2. Identify whether the containing schema/value space is open or closed.
3. Choose an existing mechanism: specified degradation, feature/profile
   negotiation, renderer-extension ownership, or a coordinated protocol change.
4. Update normative prose first when semantics change.
5. Update `contract.json`, generators, goldens, and every affected validator or
   sender gate in the same change.
6. Prove both admission and use. A validator accepting a member does not prove
   that the renderer or dispatcher honors it.
7. Test partial understanding in the safe direction: unsupported behavior must
   do less or fail, never run after its guard has disappeared.

If none of the existing mechanisms can express the change, stop and treat that
as an architecture decision rather than inventing a receiver-local exception.

## Relationship to renderer profiles

Target profiles are positive claims about installed handlers. An extension node
is emitted only when both its exact node type and owning extension are
advertised. Canonical-node presentation overrides do not change this wire
schema: they select an installed rendering of an already-admitted node.

See [`EBP3-RENDERER-MIGRATION.md`](EBP3-RENDERER-MIGRATION.md) for the current
renderer boundary.

## Verification

The owning EBP change runs `validate.py`, generator drift checks, named goldens,
and affected Kotlin/Elisp conformance suites. A UI-affecting addition also needs
the renderer/profile tests and device or screenshot evidence appropriate to its
observable behavior.
