# Jetpacs Components catalog

**Jetpacs Components** is the separate Elisp-authored reference app for
Jetpacs' own emerging design language. It coexists with the **Glasspane
Material 3 Catalog** and does not rename, replace, or add entries to that
Material inventory.

The catalog demonstrates three real `jetpacs.components` nodes plus scoped
presentations of two canonical EBP controls:

- **Action** — one full-width command target using the ordinary action path;
- **Choice** — one full-row checkbox target with Emacs-owned boolean state;
- **Panel** — labeled containment whose heading and descendants remain
  independently accessible;
- **Text Field** — canonical `text_input` behavior presented by the
  Foundation-only `JetpacsTextField` while inside `jetpacs.scope`;
- **Editor** — the complete local `editor` tier, including multiline and
  single-line input, save/enter actions, published drafts, syntax, logical
  line numbers, toolbar snippets and line operations, autofocus, read-only,
  disabled, and chromeless states.

Home links to one detail screen per component. Each detail shows purpose,
anatomy, interactive states, and the actual Elisp/EBP form. The app declares
`:requires-extensions '("jetpacs.components")`, owns the `jpcatalog` surface,
and registers only bounded `jpcatalog.*` actions. Text changes use the ordinary
state-before-action path without refreshing the active IME session. Non-secret
submits retain only their demonstration value. The secure demonstration sends
its value solely through `capture_fields`, records only its scalar length, and
destroys the volatile Elisp string in place. `M-x jetpacs-component-catalog`
returns to its Home screen.

The Editor live readout likewise avoids refreshing while a draft is changing.
It retains only a 72-column preview and character count, then refreshes after a
save or Enter action. Every editor on this page is local: none authors
`document` or `complete`. Synchronized documents continue through the existing
Material presentation until the explicit Phase 5 integration, while
completion and authoritative diagnostics remain Phase 6 work.

The catalog deliberately exercises the real application loop: one Action
increments Emacs state exactly once, and Choice commits the injected boolean
before requesting a refreshed document. Component rendering and presentation
state remain receiver-owned; application decisions remain in Emacs.

Every catalog body is wrapped in the invisible `jetpacs.scope` selection
boundary while its Glasspane chrome remains outside. The app composition root
installs the Phase 3 `text_input` and Phase 4 local-`editor` canonical
overrides for this app-only scope. Canonical nodes outside the boundary,
dialogs, and synchronized editors continue through Glasspane Material.

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
