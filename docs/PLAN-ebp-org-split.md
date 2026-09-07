# EBP Org split — implemented milestone

Status: implemented. This filename remains because `emacs/jetpacs-org.el` and
its tests cite the original extraction plan.

The reusable Org protocol engine now belongs to the independent
`../../../ebp-org` repository. It may depend on Emacs, Org, and the EBP
endpoint, but not on Jetpacs presentation or downstream apps. Jetpacs keeps the
small product adapter and Org presentation modules under `emacs/jetpacs-org-*`.

The naming rule is the one retained in
[`REWRITE-PLAN.md`](REWRITE-PLAN.md): wire-and-Emacs code is `ebp-`; product
surface code is `jetpacs-`.

## G8 compatibility gate

`test/jetpacs-org-test.el` proves the adapter's effects: teardown reaches the
upstream engine reset seam, the render/dialog adapter chain loads, and no
duplicate Org engine is introduced. The upstream repository owns its own ERT
and warning-as-error compilation.

Original plan:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/PLAN-ebp-org-split.md
```
