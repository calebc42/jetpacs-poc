# Jetpacs Components catalog

**Jetpacs Components** is the separate Elisp-authored reference app for
Jetpacs' own emerging design language. It coexists with the **Glasspane
Material 3 Catalog** and does not rename, replace, or add entries to that
Material inventory.

The first slice demonstrates three real `jetpacs.components` nodes:

- **Action** — one full-width command target using the ordinary action path;
- **Choice** — one full-row checkbox target with Emacs-owned boolean state;
- **Panel** — labeled containment whose heading and descendants remain
  independently accessible.

Home links to one detail screen per component. Each detail shows purpose,
anatomy, interactive states, and the actual Elisp/EBP form. The app declares
`:requires-extensions '("jetpacs.components")`, owns the `jpcatalog` surface,
and registers only the bounded `jpcatalog.open`, `jpcatalog.activate`, and
`jpcatalog.choice` actions. `M-x jetpacs-component-catalog` returns to its Home
screen.

The catalog deliberately exercises the real application loop: one Action
increments Emacs state exactly once, and Choice commits the injected boolean
before requesting a refreshed document. Component rendering and presentation
state remain receiver-owned; application decisions remain in Emacs.

Run its focused gate from the repository root:

```sh
emacs -Q --batch \
  -L emacs \
  -L emacs/apps/jetpacs-components \
  -L emacs/apps/jetpacs-component-catalog \
  -l test/jetpacs-component-catalog-test.el \
  -f ert-run-tests-batch-and-exit
```

The whole Elisp gate is `test/run-tests.sh`; Android unit, screenshot, and
device-semantics commands are maintained in `companion/TESTING.md`.
