# Jetpacs — the durable rebuild

POC 3 rebuilds the conformant rewrite around explicit repository and KMP
boundaries, a Room 3 outbox/cache owned by Jetpacs, an independent SQLite
inbox owned by `ebp.el`, and Navigation 3. The `../ebp-poc/ebp-kmp` repository
contains the storage-neutral KMP durable-store SPI, reducers, memory reference
implementation, transport, framing, and protocol code. Jetpacs consumes those
modules through workspace project directories; Jetpacs-specific policy lives
outside them. The complete ownership graph is in
`docs/REPOSITORY-BOUNDARIES.md`. Start with
`docs/PLAN-poc3-rebuild.md`, then use
`docs/ARCHITECTURE-POC3.md`, `docs/PLAN-room3-rebuild.md`, and
`docs/PLATFORM-RENTAL-REGISTER.md` for the detailed boundaries and local-source
implementation references.

The Android UI boundary is split deliberately. `../ebp-poc/ebp-compose` provides
`:renderer:model` and `:renderer:compose`, implementing EBP's core nodes with
Compose Foundation and no Material dependency. Sibling
`glasspane-material3` owns the optional Material implementation, while sibling
`jetpacs-components` owns the Material-free Jetpacs component language. This
Companion is the composition root for both. Glasspane
apps declare `glasspane.material3`, the separate Jetpacs Components catalog
declares `jetpacs.components`, and EBP 3 advertises each opaque extension
identifier independently from its namespaced node types.

EBP 3.1's optional `semantics` envelope follows the same boundary. Its schema,
accessible-name order, node roles/state defaults, and eight-action limit are
generated from `../ebp-poc/ebp/contract.json`; `:renderer:model` projects them without a
UI toolkit and `:renderer:compose` owns the single Compose Foundation mapping.
The Material renderer consumes that mapping and owns no duplicate generic
accessibility contract. Jetpacs authors the envelope with
`jetpacs-with-semantics`, `jetpacs-semantic-collection`,
`jetpacs-semantic-collection-item`, and `jetpacs-semantic-action`.

Android home-screen widgets use the sibling `:renderer:glance` module and the
same admitted neutral renderer model. One generic provider binds each launcher
instance to any Emacs-authored `widget:<name>` surface, renders accepted Room
state after a cold start, and routes remote clicks through the durable Room
outbox. The profile, lifecycle, security boundary, RemoteViews size guard, and
Grove visual fixtures are documented in
[`docs/GLANCE-WIDGETS.md`](docs/GLANCE-WIDGETS.md).

## Lineage and reusable sources

The tags below are immutable historical snapshots, not code-use boundaries.
Code from every lineage may be copied, cherry-picked, merged, ported, or
adapted. Its lineage does not establish correctness: every reused part must be
built and verified against the current normative
`../ebp-poc/ebp/SPEC.md`. When desired protocol behavior is not defined there,
expand the normative rules and align the contract projection, goldens, and
affected conformance tests before treating the implementation as compliant.

- **`poc/v1`** — the immutable first-PoC tag, closed by its divergence-map
  audit and retained as reusable implementation evidence.
- **`poc/v2`** — the immutable POC 2 tag, closed at
  the end of the M3 catalog sprint and cherry-picked commit-for-commit into
  this branch.
- **`poc/v3-pre-split`** — the last committed POC 3 snapshot before the
  multi-repository extraction working tree.
- **`slop-fork/main`** (this standalone repository) — the current Room-first
  architecture carrying the ported POC 2 sprint and repository split.
- **`main`** — the spec-driven implementation track. It may reuse code from
  any of the preceding lineages under the same conformance rule.

## Rules of construction

1. **The spec remains the cross-platform contract.** First prove required
   behavior against the current spec. Repair the implementation when the spec
   already covers a mismatch. When desired cross-platform behavior is missing,
   expand the normative spec in language- and platform-agnostic terms, align
   its projection and conformance witnesses, and then conform the
   implementation.
2. **Conformance before features.** A rung lands only with its `ebp`
   fixtures green: the wire Goldens (`../ebp-poc/ebp/goldens/wire/` incl. the §9.3
   known-answer vector), the frame/widget/hypertext corpora, and the §24.6
   adversarial vectors that apply to the rung.
3. **Reuse by fitness, not lineage.** Any earlier implementation is eligible
   for reuse. Prefer adapting code that already fits the current specification
   and architecture; treat historical divergence maps and port manifests as
   audit guidance, never as reuse allowlists or prohibitions.
4. **The POC contract is authored in `../ebp-poc/ebp/`.** This repo generates its wire
   vocabulary from `../ebp-poc/ebp/contract.json` and byte-compares its projection
   back (the poc-v1 drift machinery pattern is kept).

## Layout

| Path | What |
|---|---|
| `../ebp-poc/` | Independent EBP POC specification and implementation repositories consumed by Jetpacs |
| `emacs/` | Jetpacs product surfaces, app host, Org presentation adapters, and device integration; EBP libraries come from `../ebp-poc/ebp.el` and `../ebp-poc/ebp-org` |
| `companion/` | Jetpacs Room/Nav/core modules and Android composition root; upstream protocol/renderers are mapped in `settings.gradle.kts` |
| `emacs/apps/packaged-apps/` | Distribution manifest selecting optional downstream applets without owning them |
| `test/` | Jetpacs and cross-repository integration suites |
| `docs/REPOSITORY-BOUNDARIES.md` | Canonical repository graph, physical paths, and extraction manifest |
| `docs/PLAN-poc3-rebuild.md` | Cross-platform execution phases and exit gates |
| `docs/PLATFORM-RENTAL-REGISTER.md` | Built-ins/libraries that POC 3 must rent instead of reimplementing |
| `docs/REWRITE-PLAN.md` | Rung ladder, gates, port manifest |
| `docs/ARCHITECTURE-POC3.md` | Local references, module boundaries, Room/Nav/track-changes plan |
| `docs/EBP3-RENDERER-MIGRATION.md` | Protocol-major and renderer-extension migration contract |
| `companion/TESTING.md` | Android unit, screenshot, semantics, and build gates |

## Org document-link navigation

Org Mode document links keep the Files document host instead of creating a
generic screen titled `Org link`. The responsibility chain is deliberately
inspectable:

1. `jetpacs-org-render--follow` calls built-in `org-open-at-point`; Org alone
   parses the link and resolves its destination buffer and point.
2. The renderer passes `jetpacs-org-render-follow-destination-function` into
   `jetpacs-navigate-thunk`, which captures that observable result and offers
   it to the document host before using the generic drill fallback.
3. `jetpacs-reader-org--present-followed-destination` handles the result only
   when the source is the document currently owned by Files.
4. A same-document result calls `jetpacs-files-retarget-current-edit`, which
   changes only `:mark-pos` and refreshes the existing `edit` view. Its screen
   id, top-bar actions, FAB, and synchronized editor identity are preserved.
5. A different file calls `jetpacs-files-open-path`, so canonicalization,
   configured-root containment, file eligibility, and normal reader selection
   remain in force. A Files refusal is final and cannot fall through to an
   unrestricted buffer drill.
6. A destination that Files cannot represent, such as a non-file utility
   buffer, retains the generic drill fallback.

This split is deterministic within Jetpacs' bounds: link meaning comes from the
exact Emacs/Org implementation (`org-open-at-point` in `org.el`, delegating to
`org-link-open` in `ol.el`), presentation is a choice over the captured
`(source destination position surface)` tuple, and the only
same-document state change is an explicit `:mark-pos` update. To debug a link,
inspect that tuple at
`jetpacs-reader-org--present-followed-destination`, then inspect
`jetpacs-files-current-edit-context`; no parallel link parser or hidden route
table exists.

## Org custom saved views

`jetpacs-org-mode-custom-views` projects compatible built-in single commands
from `org-agenda-custom-commands` as bounded descriptors containing only an
opaque id, label, and kind. `jetpacs-org-mode-custom-view-items` resolves that
id against current configuration and runs the exact native Org command.
Context-dependent, function-backed, composite, and scope-overriding commands
are omitted because a generic flat applet view cannot faithfully or safely
represent them. Final rows are rechecked against the canonical local agenda
scope; custom keys, match expressions, settings, and paths remain in Emacs.
