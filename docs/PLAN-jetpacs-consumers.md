# Jetpacs consumer-layer milestones

Status: implemented milestone map. JC labels remain in source comments and
focused tests as provenance, not as unfinished work.

| Milestone | Current source and evidence |
|---|---|
| JC-0 application floor | `jetpacs-async.el`, `jetpacs-surfaces.el`, `jetpacs-shell.el`, and [`SPEC-JC-0-floor.md`](SPEC-JC-0-floor.md) |
| JC-1 buffer projection | `jetpacs-buffer.el` and `jetpacs-buffer-test.el` |
| JC-2 results/tabular projection | `jetpacs-results.el`, `jetpacs-tablist.el`, and focused ERT |
| JC-3 reusable presentation | `jetpacs-sections.el`, `jetpacs-hypertext.el`, `jetpacs-comint.el`, and focused ERT/goldens |
| JC-4 prompt/dialog floor | `jetpacs-dialog.el` and `jetpacs-dialog-test.el` |
| JC-5 completion bridge | Upstream `ebp-complete.el`, Jetpacs connection seam, and completion tests |

The enduring boundary is that reusable consumers build bounded declarative IR
from explicit Emacs state; application effects remain registered actions. The
aggregate gate is `test/run-tests.sh`.

Original plan:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/PLAN-jetpacs-consumers.md
```
