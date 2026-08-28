# jetpacs llm-poc-3 — the durable rebuild worktree

POC 3 rebuilds the conformant rewrite around explicit KMP boundaries, a Room 3
outbox/cache owned by Jetpacs, an independent SQLite inbox owned by Emacs, and
Navigation 3. The in-tree `:ebp-kmp` module contains the storage-neutral KMP
durable-store SPI, reducers, and memory reference implementation. `:wire`
retains transport, framing, and protocol code and depends on `:ebp-kmp`;
Jetpacs-specific policy lives outside both. Start with
`docs/PLAN-poc3-rebuild.md`, then use
`docs/ARCHITECTURE-POC3.md`, `docs/PLAN-room3-rebuild.md`, and
`docs/PLATFORM-RENTAL-REGISTER.md` for the detailed boundaries and local-source
implementation references.

The Android UI boundary is split deliberately. `:renderer:compose` implements
only EBP's eight core nodes with Compose Foundation; it has no Material or
Styles dependency. `:renderer:material3` is Glasspane's optional Material
implementation, while the Material-free `:renderer:jetpacs` starts Jetpacs' own
component language. This Companion composes both for app surfaces. Glasspane
apps declare `glasspane.material3`, the separate Jetpacs Components catalog
declares `jetpacs.components`, and EBP 3 advertises each opaque extension
identifier independently from its namespaced node types.

EBP 3.1's optional `semantics` envelope follows the same boundary. Its schema,
accessible-name order, node roles/state defaults, and eight-action limit are
generated from `ebp/contract.json`; `:renderer:model` projects them without a
UI toolkit and `:renderer:compose` owns the single Compose Foundation mapping.
The Material renderer consumes that mapping and owns no duplicate generic
accessibility contract. Jetpacs authors the envelope with
`jetpacs-with-semantics`, `jetpacs-semantic-collection`,
`jetpacs-semantic-collection-item`, and `jetpacs-semantic-action`.

## Lineage and walls

- **`slop-fork/poc-v1`** (worktree `../llm-poc`) — the first PoC, closed by
  its divergence-map audit
  (`../llm-poc/docs/AUDIT-ebp2-divergence-map.md`). It is guidance and organ
  donor: read it, port from it, do not merge it.
- **`slop-fork/v2`** (worktree `../llm-poc-2`) — the POC 2 line, closed at
  the end of the M3 catalog sprint and cherry-picked commit-for-commit into
  this branch. Like poc-v1 it is now reference, not a merge source.
- **`slop-fork/main`** (this tree/worktree) — the POC 3 rebuild: the
  Room-first architecture carrying the ported POC 2 sprint.
- **`main`** — the clean-room hand-rebuild track. Sealed from both
  slop-fork lines; nothing here is merged there and nothing there is read
  from here.

## Rules of construction

1. **The spec remains the cross-platform contract.** First prove required
   behavior in implementation and backend contracts against the current spec.
   Then audit any mismatch: repair the implementation when the spec already
   covers it, or expand the spec only in language- and platform-agnostic terms.
2. **Conformance before features.** A rung lands only with its `ebp`
   fixtures green: the wire Goldens (`ebp/goldens/wire/` incl. the §9.3
   known-answer vector), the frame/widget/hypertext corpora, and the §24.6
   adversarial vectors that apply to the rung.
3. **Port, don't rewrite, above the boundary.** Modules classified
   port-safe in the divergence map come across as ports with vocabulary
   updates only. Rewriting them is scope creep.
4. **The contract is authored in `ebp/`.** This repo generates its wire
   vocabulary from `ebp/contract.json` and byte-compares its projection
   back (the poc-v1 drift machinery pattern is kept).

## Layout

| Path | What |
|---|---|
| `ebp/` | Submodule: the governing spec, contract, goldens, validate.py |
| `emacs/` | The elisp client, spec-first (`ebp.el` wire core, then modules) |
| `companion/` | Storage-neutral `:ebp-kmp`, protocol `:wire`, Jetpacs core/app modules, and split renderer implementations |
| `companion/renderer/model/` | Toolkit-neutral renderer registry and EBP JSON readers |
| `companion/renderer/compose/` | Compose Foundation renderer for the EBP Core Node Set |
| `companion/renderer/material3/` | Optional Material 3 implementation, Styles integration, screenshots, and semantics tests |
| `companion/renderer/jetpacs/` | Material-free Jetpacs components, private theme/Styles, generated extension vocabulary, and tests |
| `renderer-extensions/` | Downstream renderer manifests and semantic golden witnesses projected into Kotlin and Elisp |
| `emacs/apps/jetpacs-component-catalog/` | Separate Elisp-authored reference for Jetpacs-owned components |
| `test/` | ERT suites; every wire test is driven by `ebp/goldens/` |
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
