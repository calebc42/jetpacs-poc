# Jetpacs POC documentation

This directory contains cross-repository orientation for the current Jetpacs
POC workspace. It explains how the pieces fit together; it is not a protocol,
API, or implementation authority.

## Start here

| Need | Document or authority |
|---|---|
| Work safely in this workspace | [`../AGENTS.md`](../AGENTS.md) |
| Understand the Elisp-authored design runtime | [`DESIGN-RUNTIME.md`](DESIGN-RUNTIME.md) |
| Choose a platform or applet developer interface | [`DEVELOPER-TOOLING.md`](DEVELOPER-TOOLING.md) |
| Trace the experiments that led to the current design | [`HISTORY.md`](HISTORY.md) |
| Determine EBP behavior | [`../ebp-poc/ebp/SPEC.md`](../ebp-poc/ebp/SPEC.md) |
| Query structured EBP vocabulary | [`../ebp-poc/ebp/contract.json`](../ebp-poc/ebp/contract.json) |
| Understand Jetpacs ownership and dependency boundaries | [`../jetpacs/docs/ARCHITECTURE-POC3.md`](../jetpacs/docs/ARCHITECTURE-POC3.md) |
| Follow the implementation ladder | [`../jetpacs/docs/REWRITE-PLAN.md`](../jetpacs/docs/REWRITE-PLAN.md) |

## Authority

Use the source-of-truth order in [`../AGENTS.md`](../AGENTS.md). In particular:

1. EBP behavior comes from `ebp-poc/ebp/SPEC.md`.
2. `contract.json` is its machine-readable projection, and goldens are scoped
   conformance witnesses.
3. Architecture documents define local ownership and dependency direction.
4. Runtime source and tests show the current implementation and may expose
   drift; they do not amend EBP.
5. Generated vocabularies are outputs, not another authority.

The documents in this directory summarize those sources. When a summary and
an owning artifact disagree, fix or qualify the summary rather than treating
it as a new contract.

## Current and historical material

`DESIGN-RUNTIME.md` and `DEVELOPER-TOOLING.md` describe the current workspace.
`HISTORY.md` records dated evidence and links to the original implementation
journals in Git. Historical test counts, device observations, repository
layouts, and rollback instructions are not current guarantees.

## Maintenance rule

- Keep present-tense reference material here; keep detailed behavior next to
  the source or test that owns it.
- Record a durable decision once and link to its implementation seam.
- Put dated experiments in `HISTORY.md`, including their evidence envelope.
- Do not maintain handwritten copies of closed vocabularies, limits, module
  lists, or test counts when an owning artifact already exposes them.
- Remove resolved TODOs instead of leaving them beside their completed work.
- Check every relative link and search the workspace for an old filename when
  renaming or removing a document.
