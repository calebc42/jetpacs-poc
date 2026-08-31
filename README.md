# jetpacs-components

Jetpacs' optional Foundation design extension. The repository owns the
`jetpacs.components` manifest, its generated Kotlin and Elisp projections,
the Compose renderer, and the public Elisp builders.

This extension is downstream of `ebp-kmp`, `ebp-compose`, and the Jetpacs
authoring surface. It is selected by the Jetpacs composition root but is not
part of the EBP protocol specification.

```sh
test/run-tests.sh
./gradlew :renderer:jetpacs:testDebugUnitTest --console=plain
```
