# Jetpacs documentation

This directory documents the current Jetpacs implementation. Start with the
architecture, then follow the owning document for the subsystem being changed.
Dated audits, implementation transcripts, and protocol-amendment drafts are
historical evidence, not current authority; their pre-consolidation versions
remain available through [`HISTORY.md`](HISTORY.md).

## Authority

When documents and code disagree, use this order:

1. [`../../ebp/SPEC.md`](../../ebp/SPEC.md) for normative EBP
   behavior.
2. [`../../ebp/contract.json`](../../ebp/contract.json) and
   its named goldens as the machine projection and conformance witnesses.
3. [`ARCHITECTURE-POC3.md`](ARCHITECTURE-POC3.md) and the focused Jetpacs
   boundary documents below.
4. Runtime source and tests, which reveal implementation state but do not
   silently amend EBP.

Generated vocabulary and renderer projections are outputs. Change their owning
specification or extension manifest, then run the documented generator.

## Current guides

| Document | Use it for |
|---|---|
| [`ARCHITECTURE-POC3.md`](ARCHITECTURE-POC3.md) | System ownership, module graph, runtime flows, and architectural invariants |
| [`REPOSITORY-BOUNDARIES.md`](REPOSITORY-BOUNDARIES.md) | Independent repositories, dependency direction, and local workspace composition |
| [`PLAN-poc3-rebuild.md`](PLAN-poc3-rebuild.md) | Current implementation status, change ordering, and completion gates |
| [`PLAN-room3-rebuild.md`](PLAN-room3-rebuild.md) | Implemented Room schema, transaction boundaries, and non-durable state |
| [`EBP3-RENDERER-MIGRATION.md`](EBP3-RENDERER-MIGRATION.md) | EBP 3 renderer-extension ownership, profiles, and projection generation |
| [`material3/README.md`](material3/README.md) | Optional Jetpacs Material 3 authoring, native rendering, and catalog |
| [`CHROME-VOCABULARY.md`](CHROME-VOCABULARY.md) | App chrome terminology, placement rules, composition, and back behavior |
| [`NAV3-EBP-BOUNDARY.md`](NAV3-EBP-BOUNDARY.md) | Receiver navigation versus EBP multi-view state |
| [`GLANCE-WIDGETS.md`](GLANCE-WIDGETS.md) | Generic widget rendering, persistence, click capabilities, and lifecycle gates |
| [`REVIVAL-EXECUTION.md`](REVIVAL-EXECUTION.md) | Current optional Android capabilities, triggers, state sources, and tiles |
| [`SECURITY.md`](SECURITY.md) | Android component exposure, Intent/PendingIntent rules, and security tests |
| [`PLATFORM-RENTAL-REGISTER.md`](PLATFORM-RENTAL-REGISTER.md) | Platform primitives to reuse instead of rebuilding |
| [`ONBOARDING-device.md`](ONBOARDING-device.md) | Installing and onboarding the Android/Emacs pair |
| [`REWRITE-PLAN.md`](REWRITE-PLAN.md) | Historical W0-W10 milestone map and current owners of those guarantees |
| [`DECISIONS-wire-growth-model.md`](DECISIONS-wire-growth-model.md) | Informative open/closed wire-vocabulary growth rationale |

`companion/TESTING.md` owns Android commands and device/screenshot matrices.
`test/run-tests.sh` owns the aggregate Elisp and cross-repository integration
gate. Do not duplicate their evolving command lists here.

## Historical citations

Some source and tests still cite milestone filenames such as
`PLAN-jetpacs-apps.md` or `SPEC-JC-0-floor.md`. Those files intentionally remain
as short compatibility pages that map old rung names to current source and
tests. They are not active plans. [`HISTORY.md`](HISTORY.md) identifies the
snapshot containing their full original text.

## Documentation rules

Keep a document here only when it does at least one of these jobs:

- defines a current Jetpacs ownership or trust boundary;
- explains a supported public workflow;
- records a durable design decision not expressed by the normative EBP spec;
- maps a still-used historical citation to its current implementation; or
- provides verification instructions that have one clear owner.

Prefer present-tense descriptions and links to owning source/tests. Do not add
raw audit dumps, generated lookup tables, copied source trees, or completed
execution journals beside current guides. Preserve those through Git history
and summarize only the conclusion that still governs the implementation.
