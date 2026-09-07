# Refound/KMP migration — historical citation map

Status: completed milestone. The row-spike READMEs cite this filename to explain
why those experiments exist.

The plan established the storage-neutral Kotlin boundary, contract-driven JSON,
transaction ownership, and the rule that spikes are evidence rather than a
second production path. Those responsibilities now live in the independent
`../../ebp-kmp` repository and Jetpacs' Room adapter. The production
module graph is documented in
[`ARCHITECTURE-POC3.md`](ARCHITECTURE-POC3.md); current persistence is in
[`PLAN-room3-rebuild.md`](PLAN-room3-rebuild.md).

`emacs/spike/` and the Companion spike test may be inspected as historical
implementation evidence. They do not define current protocol or architecture.

Original plan:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/PLAN-refound-2026-07-28.md
```
