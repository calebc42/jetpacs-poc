# Jetpacs Material 3

Optional Material 3 rendering and Elisp authoring for Jetpacs Tier 1 apps.
Jetpacs and the Material 3 Catalog consume this shared library. The API
returns ordinary declarative screen IR; Emacs retains application decisions.

## Ownership and loading

Paths in this guide are relative to the Jetpacs repository root:

- `renderer-extensions/jetpacs-material3.json` owns extension identity, schemas,
  target support, and stateful rules; Kotlin and Elisp projections are generated.
- `companion/renderer/material3` implements the native renderer in the existing
  Companion Gradle build as `:renderer:material3`.
- `emacs/apps/jetpacs-material3` contains the optional public Elisp API.
- `emacs/apps/m3-catalog` contains the catalog and its entrypoint.
- `test/material3` owns catalog/REPL ERT; `lookup-tables/` beside this guide
  contains the reference tables and their generators.

After adding the API directory to `load-path`, applications explicitly load it:

```elisp
(require 'jetpacs-material3)
;; Include this keyword in the application's existing jetpacs-defapp form:
;; :requires-extensions '("jetpacs.material3")
```

`jetpacs.el` makes packaged app directories discoverable. Merely making the
library discoverable does not require it from Foundation. The live receiver
must advertise `jetpacs.material3` and each emitted namespaced node type.
The Material catalog retains its stable `jetpacs-m3-*` builders and
`m3catalog.*` actions, independently of the library's product name.

Foundation rendering remains in `ebp-compose` with no Material dependency.
The in-repository `jetpacs-components/` library supplies Foundation-based controls
and the optional `jetpacs.design` style API. Material and that design layer
remain peers. The current Companion bundles Material, including host utilities
and fallbacks; a Companion build without Material is a separate change.
No Material identifiers become normative EBP vocabulary.

## Verification

From the Jetpacs root:

```sh
tools/check-material3-projections.sh
test/material3/run-tests.sh
tools/m3-check.sh buttons
```

From `companion/`:

```sh
./gradlew :renderer:material3:testDebugUnitTest
./gradlew :renderer:material3:compileDebugAndroidTestKotlin :renderer:material3:compileDebugScreenshotTestKotlin
```

Use the existing `ebp-compose/tools/gen-renderer-extension-vocabulary.py`
generator with the manifest, the Kotlin package `com.calebc42.jetpacs.material3`,
and the two output paths recorded in the projection check script. Regenerate
both outputs together; never hand-edit vocabulary. Keep compilation output in
a temporary directory, away from source `.elc` files.

See [migration and compatibility](MIGRATION.md), the [catalog guide](../../emacs/apps/m3-catalog/README.md),
and [Companion testing](../../companion/TESTING.md) for device and screenshot gates.
