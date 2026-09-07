# Jetpacs Material 3 implementation guide

The manifest `renderer-extensions/jetpacs-material3.json`, relative to the
Jetpacs repository root, owns extension identity, schemas, target support,
and stateful rules. Regenerate both projections together with the upstream
`ebp-compose` generator; verify with `tools/check-material3-projections.sh`.

Keep generic renderer machinery in `ebp-compose`. Keep app policy in applets.
Material remains an optional app capability alongside Foundation components
and the design API. Do not introduce a Material dependency into Foundation.

Compile Elisp into a temporary directory. Run `test/material3/run-tests.sh`
and the `:renderer:material3` Gradle gates from `companion/` before handoff.
