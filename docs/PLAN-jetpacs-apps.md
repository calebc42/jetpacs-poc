# Jetpacs app-tier milestones

Status: implemented milestone map. Source and tests retain JA labels to identify
the slice that introduced a contract; this page maps them to current owners.
It is not an active build plan.

| Milestone | Current source and evidence |
|---|---|
| JA-1 device coherence | `jetpacs-theme.el`, `jetpacs-clip.el`, `jetpacs-device.el`, and their focused ERT |
| JA-2 navigation/chrome lifecycle | `jetpacs-navigate.el`, `jetpacs-chrome.el`, `jetpacs-buffer.el`, teardown/integration tests |
| JA-3 command exposure | `jetpacs-commands.el`, `jetpacs-keymap.el`, `jetpacs-emacs-ui.el`, and focused tests |
| JA-4 Org engine boundary | Independent `ebp-org`, the Jetpacs adapter, and [`PLAN-ebp-org-split.md`](PLAN-ebp-org-split.md) |
| JA-5 Org presentation | `jetpacs-org-render.el`, dialogs, habits, mode/reader adapters, and their ERT/goldens |
| JA-6 files and launchers | `jetpacs-files.el`, `jetpacs-launcher.el`, and the JA-6 regression suites |

Current app registration, renderer-extension requirements, destinations,
build-within/standalone chrome, guest ownership, and failure isolation are
owned by `emacs/jetpacs-apps.el` and `test/jetpacs-apps-test.el`. See
[`CHROME-VOCABULARY.md`](CHROME-VOCABULARY.md) for the live chrome contract.

The aggregate gate is `test/run-tests.sh`. Original plan:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/PLAN-jetpacs-apps.md
```
