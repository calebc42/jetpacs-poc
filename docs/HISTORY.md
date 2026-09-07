# Documentation history

Jetpacs accumulated planning ladders, adversarial audits, protocol-amendment
drafts, generated lookup snapshots, and copied source evidence while POC 3 was
being built. Those records were valuable during implementation, but keeping
them beside current architecture made obsolete proposals look authoritative.

The complete directory immediately before consolidation is preserved at Git
commit `6251e457aaadba8d2daf9ead3d305a7676412212`. Retrieve any original file
without restoring it into the working tree:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/FILE
```

List that snapshot with:

```sh
git ls-tree -r --name-only 6251e457aaadba8d2daf9ead3d305a7676412212 docs
```

Historical material remains implementation evidence. It never overrides the
current EBP specification, contract projection, goldens, architecture, source,
or tests.

## Condensed compatibility pages

These paths remain because source or tests cite their old milestone names. Each
now maps the citation to current owners and includes the recovery command for
its full original text:

- `AUDIT-ja6-2026-07-28.md`
- `AUDIT-plan-spec-adversarial-2026-07-31.md`
- `PLAN-ebp-org-split.md`
- `PLAN-jetpacs-apps.md`
- `PLAN-jetpacs-consumers.md`
- `PLAN-jetpacs-debt-and-scaffold.md`
- `PLAN-jetpacs-widgets.md`
- `PLAN-refound-2026-07-28.md`
- `RESEARCH-A8-2026-07-25.md`
- `SPEC-JC-0-floor.md`

The current architecture, rebuild, Room, renderer, chrome, repository, rental,
and platform-capability documents were also shortened in place. Their original
versions are in the same snapshot.

## Removed audit and review records

```text
AUDIT-T1-T2-2026-07-25.md
AUDIT-full-spec-RAW.md
AUDIT-ja1-ja2-2026-07-26.md
AUDIT-ja3-2026-07-27.md
AUDIT-ja4-2026-07-27.md
AUDIT-ja5-2026-07-28.md
AUDIT-jc-layer-2026-07-24.md
AUDIT-jetpacs-text-editing-phases-0-3-2026-08-28.md
AUDIT-m3-blockers-2026-08-02.data.json
AUDIT-m3-blockers-2026-08-02.md
AUDIT-spec-holes-2026-07-24.md
AUDIT-step-2-2.5-2026-08-02.data.json
AUDIT-step-2-2.5-2026-08-02.md
AUDIT-text-input-contract-2026-08-27.md
AUDIT-verify2-opus-2026-07-24.md
AUDIT-w7-conformance.md
AUDIT-w8-conformance.md
AUDIT-w9-conformance.md
REVIEW-JC-2.md
REVIEW-emacs-30.1-vs-spec-2026-07-25.md
REVIEW-kotlin-core-vs-emacs-c-core-2026-07-25.md
REVIEW-poc-v1-vs-rewrite-2026-07-27.md
SECURITY-intent-alignment-2026-08-31.md
SECURITY-reminder-actions-2026-09-01.md
```

The two security reports were consolidated into [`SECURITY.md`](SECURITY.md).

## Removed protocol drafts and execution plans

```text
DRAFT-amendments-67-73.md
DRAFT-amendments-74-86-P2.md
DRAFT-amendments-87-90.md
DRAFT-amendments-140-152.md
DRAFT-amendments-156-167-m3-tier1.md
DRAFT-amendments-169-172-completion.md
PLAN-amendment-package-2026-07-31.md
PLAN-audit-fixes.md
PLAN-elisp-expansion.md
PLAN-emacs-informed-execution-2026-07-25.md
PLAN-ja4-p1-batches-1-3.md
PLAN-ja6-p1-fixes.md
PLAN-jetpacs-text-editing.md
PLAN-m3-catalog-verification.md
PLAN-navigation-handoff.md
PLAN-paging3-decision-2026-08-13.md
PLAN-poc1-parity.md
PLAN-rf2-c4-hub.md
PLAN-rf2-c5-t4.md
PLAN-rf2-c5-t5.md
PLAN-rf2-c5-tests.md
PLAN-rf2-c6-app.md
PLAN-rf2-kmp-migration.md
PLAN-rf5a-editor-cadence.md
PLAN-someday-zeromq-transport.md
W8-durable-offline-plan.md
W9-feature-parity.md
W10-overload-plan.md
```

Ratified protocol text belongs in `ebp/SPEC.md`, not in a Jetpacs
draft mirror. Completed work is represented by current source and tests.

## Removed ledgers, lookup snapshots, and copied sources

```text
LIBRARY-LEDGER.md
PERFETTO-ORG-RENDERER-2026-08-21.md
lookup-tables/ACTION-REFERENCE.org
lookup-tables/ADAPTIVE-REFERENCE.org
lookup-tables/ANIMATION-REFERENCE.org
lookup-tables/SQLITE-DRIVER-REFERENCE.org
lookup-tables/STATE-REFERENCE.org
lookup-tables/WIDGET-REFERENCE.org
lookup-tables/generate-action-table.py
lookup-tables/generate-sqlite-table.py
lookup-tables/generate-state-table.py
attic/v2-sync-bridge/README.md
attic/v2-sync-bridge/ebp.el
attic/v2-sync-bridge/ebp.el.vs-committed-v2.diff
attic/v2-sync-bridge/jetpacs-emacs-ui.el
attic/v2-sync-bridge/jetpacs-emacs-ui.el.vs-committed-v2.diff
attic/v2-sync-bridge/jetpacs-sync-test.el
attic/v2-sync-bridge/jetpacs-sync.el
```

Dependency versions now come from the owning version catalogs. API facts come
from the exact local source checkout or generated owner artifact. Historical
source snapshots stay in Git rather than in a second source tree under docs.
