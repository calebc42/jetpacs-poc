# Jetpacs Components implementation guide

Treat `renderer-extensions/jetpacs-components.json` as the extension authority.
Generated Kotlin and Elisp projections must move together and remain
byte-for-byte reproducible through `tools/check-projections.sh`.

- Keep core EBP and generic Compose behavior in their upstream repositories.
- Keep Jetpacs application policy out of reusable renderer primitives.
- Add focused Kotlin and Elisp coverage for every new component contract.
- Never hand-edit a generated vocabulary without changing its manifest.
