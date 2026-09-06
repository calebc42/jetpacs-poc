# Historical implementation record

This is a compact index of the experiments that produced the current design
runtime and UI seams. It preserves provenance without making dated plans,
repository layouts, test counts, device state, or rollback instructions look
like current operating guidance.

The original journals remain available in Git:

| Record | Last complete snapshot | Retrieve with |
|---|---:|---|
| Elisp design runtime experiment | `3a25d21` | `git show 3a25d21:docs/ELISP-DESIGN-RUNTIME-EXPERIMENT.md` |
| Grove design fidelity program | `7a29782` | `git show 7a29782:docs/GROVE-DESIGN-FIDELITY.md` |
| Jetpacs and Glasspane UI polish | `1335a6d` | `git show 1335a6d:docs/JETPACS-UI-POLISH.md` |

Use those snapshots when investigating a particular decision or old result.
Do not execute their rollback commands against the current workspace: they
describe a dated multi-repository checkout that has since advanced, and the
named Grove repository is no longer present here.

## 2026-09-01: design-runtime experiment

The experiment started at the annotated tag
`checkpoint/pre-elisp-design-runtime-2026-09-01`. It tested whether bounded,
Elisp-authored profiles could control a Foundation renderer without changing
EBP or sending executable code.

Durable outcomes:

- `jetpacs.design` became an app-target extension with typed admission,
  bounded compilation, profile caching, and a default-off Companion switch.
- Profiles remained inert Elisp data and ordinary screen IR; the receiver
  compiled them into native styles.
- Renderer installation, cached-surface recovery, accessibility, and compiler
  performance received unit and physical-device witnesses.
- Room schemas and the normative EBP protocol did not change for the
  experiment.

The experiment's exact tool versions, per-repository SHAs, benchmark samples,
exceptions, and reverse-revert sequence belong to the archived snapshot. They
are evidence for that checkpoint only.

## 2026-09-02: Grove design-fidelity program

Grove was used as a demanding visual and interaction fixture: multiple IBM
Plex families, literal light/dark palettes, flat reveal-swipe rows, custom
controls, and a one-month planning calendar. The program incrementally added
theme-role projection, canonical Foundation overrides, component slots, host
chrome traversal, and Grove-facing profiles.

Durable outcomes now represented in current source include design-native text,
icons, buttons, chips, dividers, section headers, cards and rows, menus,
switches, disclosures, and month grids. The work also produced shared typed
swipe dispatch and the rule that an override declines whenever it cannot honor
the complete canonical node.

The menu audit found normative prose behind the shipped contract and
implementations. EBP amendment #187 aligned the menu shape, added
`trailing_text`, and enforced `on_tap`; the current authority is
[`../ebp-poc/ebp/SPEC.md`](../ebp-poc/ebp/SPEC.md), not the historical finding.

The Grove repository and its screenshots are absent from the current
workspace. Its tablet observations remain useful implementation evidence but
are not a live conformance or fidelity gate.

## 2026-09-02 to 2026-09-03: host and catalog polish

The follow-on program applied the design runtime to Jetpacs host screens and
Glasspane, then made the component catalog and Design Lab exercise the same
Foundation controls.

Durable outcomes:

- `jetpacs.baseline` became the host fallback profile through one presentation
  seam, while app-owned presentation remained respected.
- Bounded scaffold traversal preserved drawer, adaptive dock, FAB, globals,
  snackbar, and back behavior through a presentation wrapper.
- Neutral chrome slots connected profile typography/color/shape to tabs,
  rails, drawers, and snackbars without reversing module dependencies.
- Host and Glasspane rows moved through the shared list-item seam; menu and
  icon-button names were corrected at their interactive controls.
- Closed dropdowns joined the Foundation override set, and nested
  `jetpacs.scope` stopped masking an enclosing design scope.
- The Visual editor and Design Lab adopted switches, disclosures, dropdowns,
  stable paths, and digest-addressed edits. Structural profile editing stayed
  available through the inert Source view.

Detailed device observations and test totals are in the archived snapshot.
Treat them as dated witnesses and rerun the owning suites before making a
current render claim.

## Why the journals left the active documentation

The journals mixed three different jobs: design rationale, progress tracking,
and verification logs. As later slices landed, resolved gaps remained in
"remaining" lists and corrections appeared hundreds of lines after the claim
they replaced. Current rationale now lives in [`DESIGN-RUNTIME.md`](DESIGN-RUNTIME.md)
and next to its owning source; Git retains the chronological audit trail.
