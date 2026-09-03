# jetpacs-component-catalog

Downstream Jetpacs app for browsing, inspecting, and authoring the
`jetpacs.components` extension. It consumes `jetpacs-components`,
`jetpacs-authoring`, `ebp.el`, and the public Jetpacs host surface.

The package also registers Jetpacs Design Lab as a separate app. Design Lab
edits bounded `jetpacs.design` profiles through visual scalar controls or an
inert, closed source form; it previews all seven semantic primitives without
styling its own authoring controls. Presets are opened read-only, Save As
creates a complete user snapshot, and Apply persists the active profile through
Customize. The app requires `jetpacs.components`, but remains reachable while
the experimental design runtime is disabled.

```sh
test/run-tests.sh
```
