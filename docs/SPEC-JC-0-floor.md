# JC-0 application floor — compatibility specification

Status: implemented historical specification. Despite the filename, this is
not normative EBP text. It remains because the floor source, tests, and
aggregate runner cite its JC-0 and section 7 labels.

The current floor is owned by `emacs/jetpacs-async.el`,
`emacs/jetpacs-surfaces.el`, and `emacs/jetpacs-shell.el`. It provides bounded
continuation work, owner-scoped actions/builders, target-profile validation,
surface lifecycle, state-before-action dispatch, safe error isolation, and the
single push/connection seam used by higher application modules.

The floor does not own EBP protocol semantics, an app's domain state, renderer
implementation, or durable Android/Emacs storage.

## Section 7 — current exit gate

`test/jetpacs-floor-test.el` is the focused JC-0 suite. The aggregate
`test/run-tests.sh` also runs warning-as-error byte compilation, upstream EBP
suites, boundary guards, and downstream integration. Current EBP behavior must
be checked against `../../ebp/SPEC.md`, not this compatibility page.

Original implementation specification:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/SPEC-JC-0-floor.md
```
