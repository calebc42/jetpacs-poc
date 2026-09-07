# Jetpacs widget-builder milestone

Status: implemented. `emacs/jetpacs-widgets.el` and its test retain the JW-0
citation for the foundation that introduced contract-backed node builders.

The live builder layer:

- consumes generated EBP vocabulary and owner-registered extension schemas;
- returns the actual plist/vector SurfaceSpec IR;
- validates identifiers, closed members, enums, bounds, actions, semantics,
  target profiles, and document-wide IDs/keys;
- performs canonical JSON and byte accounting through shared helpers; and
- provides app, dialog, notification, widget, and tile constructors without a
  parallel AST.

Current authority is EBP's spec/contract plus `emacs/jetpacs-widgets.el` and
`test/jetpacs-widgets-test.el`. Renderer-specific builders and generated
vocabulary live in their owning sibling repositories.

Original plan:

```sh
git show 6251e457aaadba8d2daf9ead3d305a7676412212:docs/PLAN-jetpacs-widgets.md
```
