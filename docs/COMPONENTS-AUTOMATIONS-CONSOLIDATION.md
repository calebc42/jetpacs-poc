# Components and automations consolidation verification

Completed locally on 2026-09-06. The two modules are ordinary directories at
`jetpacs-components/` and `jetpacs-automations/` within `jetpacs-poc`.
The current 13-repository GitHub layout and history recovery instructions are
in [LOCAL-REPOSITORY-LAYOUT.md](LOCAL-REPOSITORY-LAYOUT.md).

## Boundaries and preservation

- Foundation components remain independent of Material 3. Automations remains
  an optional downstream applet using public platform/authoring APIs.
- Public Elisp features, Kotlin packages, Gradle project identities, renderer
  extensions and packaged runtime paths are unchanged.
- Companion source mapping, onboarding assets, main/module test entrypoints,
  Grove/Orgzly/Harp/catalog consumers and developer-tool discovery use the new
  module paths. External EBP and authoring repositories remain siblings.
- Both source trees were moved intact, including ignored working files. All
  152 components and 13 automations tracked/untracked source files matched
  their pre-move hashes. Subsequent module edits affect only README, Gradle
  settings and test/projection script paths; runtime source is byte-identical.
- Every original POC ref and every affected repository HEAD is unchanged.
  Both imported histories have verified portable bundles and namespaced archive
  refs. Original Git databases/indexes, source snapshots and working patches
  remain in the recovery backup documented in the layout guide.
- Archive refs are preserved history, not merged branch ancestry. A normal
  branch push will not publish them; explicitly push those refs or retain the
  bundles when publishing. No commits, merges, remote changes or pushes were
  performed.

## Verification

- Component vocabulary projections reproduce without edits; component ERT
  (12 tests) and temporary warning-as-error byte compilation pass.
- Automation ERT: 29 tests pass across all three suites.
- Material 3 ERT: 59 tests pass.
- Grove: 41 tests; Orgzly: 80; Harp: 22; catalog: 115. All pass, including
  each runner's configured documentation and compilation checks.
- Companion `testDebugUnitTest assembleDebug`: passes, including 80 Foundation
  component and 66 application unit tests. The components directory's own
  `./gradlew :renderer:jetpacs:testDebugUnitTest` also passes.
- Onboarding and package-vc installation fixtures pass. All seven component
  and three automation Elisp files in the built onboarding payload match the
  relocated source bytes.
- Platform tools `clean test installDist`: all 16 tests pass with installed
  JDK 21. Its Gradle 8.5 wrapper fails under the default JDK 25; using JDK 21
  resolves that environment mismatch. Installed doctor and actual MCP
  initialization/tool listing pass.
- Applet static/MCP suite: 44/45 pass. The single failure remains the existing
  `jetpacs-applet-tooling-test-glasspane-documentation-contract-is-complete`
  fixture failure. New Elisp and Kotlin tests prove module discovery excludes
  stale sibling copies and escaped paths.
- Trusted tooling ERT: 3/3 pass. Checkdoc and temporary warning-as-error
  compilation pass. Real static and trusted launcher handshakes pass; an
  isolated synthetic workspace loads both modules without Material and exposes
  exactly two additional bounded runtime tools.
- Harp static validation passes with zero errors/warnings. Its synthetic,
  offline compact/medium render remains 7,007 bytes, 32 nodes, depth 7, with
  SHA-256 `7753a0d1e00a967d5d3e4ec6a8db85649c7cbab3d3a41fd1723e850702fb16db`,
  identical to pre-consolidation verification. No negotiated device profile;
  this verifies only that fixed fixture context and the real offline gates.
- Aggregate `test/run-tests.sh` runs the relocated suites, packaging and
  delineation guards, then reaches the previously recorded warning-as-error
  failure at `emacs/jetpacs-org-render.el:1420`:
  `jetpacs-editor-org-save-policy` is not known to be defined.
- `git diff --check` passes. No source-directory bytecode was created or
  modified. No device deployment was needed for this source-path change.

Verification logs and the source/hash audit are retained in the migration
backup's `verification/` directory (also `/tmp/consolidation-*.log` during this
session).
