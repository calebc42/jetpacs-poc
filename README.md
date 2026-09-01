# jetpacs-components

Jetpacs' optional Foundation design extensions. The repository owns the
unchanged format-1 `jetpacs.components` manifest and the experimental,
format-2 `jetpacs.design` manifest, their generated Kotlin and Elisp
projections, the Compose renderers, and the public Elisp builders.

`jetpacs.design` is a bounded language of tokens, styles, state rules, and
motions. Its pure Kotlin compiler is shared by admission and rendering; no
AndroidX `Style` or executable Elisp crosses either public boundary. Loading
`jetpacs-design.el` installs deterministic builders, while
`jetpacs-design-material.el` demonstrates a filled button, card, and
two-option selector without adding a Material dependency to the renderer.

This extension is downstream of `ebp-kmp`, `ebp-compose`, and the Jetpacs
authoring surface. It is selected by the Jetpacs composition root but is not
part of the EBP protocol specification.

```sh
test/run-tests.sh
./gradlew :renderer:jetpacs:testDebugUnitTest --console=plain
```
