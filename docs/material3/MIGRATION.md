# Material 3 ownership migration

Jetpacs now owns the Material renderer, authoring library, and catalog in its
main repository. The imported source is the tracked working tree of the former
`glasspane-material3` repository at commit
`a1dc829b2c34eee5a4321639763e503ed01fb498`.

The import also preserves the existing uncommitted 18-line regression test
`jetpacs-m3-every-screen-builds-with-the-components-package`, which checks
catalog screens after loading the Foundation components package. Existing
Jetpacs working-tree edits and deletions are not part of this migration.
The former checkout is left untouched as an inactive provenance source;
active build, packaging, tests, and loading use Jetpacs-owned files exclusively.
No Git history is rewritten.

## Coordinated compatibility change

- Extension `glasspane.material3` becomes `jetpacs.material3`.
- Elisp feature and generated symbol prefix `glasspane-material3` become
  `jetpacs-material3`.
- Kotlin package `com.calebc42.glasspane.material3` becomes
  `com.calebc42.jetpacs.material3`.
- Gradle identity `:renderer:material3`, existing public `jetpacs-m3-*` and
  `jetpacs-material3-*` builders, `material3.*` nodes, and `m3catalog.*` actions
  remain stable. The Material schemas and target support are unchanged.

Update the Companion and managed Emacs sources together, then restart/reconnect
so the new extension profile is negotiated. There are no old-name aliases.
An old extension advertisement alone cannot satisfy a new app's requirement.
Onboarding replaces the managed distribution subtrees, removing stale library
paths. Manual installations must remove the retired API from their load path
and rebuild or omit old bytecode. No application ID or database schema changes,
receipt deletion, or persistent-data reset are required.

The source move does not redesign controls or make the current Companion
buildable without Material. Foundation and the style API gain no dependency on
the Material library.

## Baseline evidence

Before the import, the extension projection check passed, the catalog suite
passed 47 tests, the REPL suite passed 12 tests, and the Companion build's
`:renderer:material3:testDebugUnitTest` gate passed. Screenshot pixels are
preserved from the source checkout; compilation alone does not establish
visual or device behavior.

## Migration verification

The coordinated rename passes projection reproducibility, 47 catalog and
12 REPL ERT tests, Checkdoc, temporary warning-as-error compilation of the
Material authoring/catalog sources, and the per-component buttons gate.
The focused app, floor, widget, chrome, and icon suites pass 249 tests.
Foundation widgets/chrome also load with no Material API on the load path.
Android debug unit tests, instrumentation/screenshot-source compilation, and
APK assembly pass. The onboarding layout test passes; the final staged payload
contains each API/catalog entrypoint once and matches the integrated source.

Ten isolated renderer instrumentation tests pass on the connected Pixel Tablet
(Android 17), covering semantics, Foundation override dispatch, and chrome
Styles integration. All 11 inherited screenshot PNGs match newly rendered
images byte-for-byte after relocation to the new package path. The screenshot
suite still lacks the four `MaterialCatalogProjectionTabs` reference images;
no new reference pixels were accepted as part of this rename.

The trusted offline Glasspane builder is STABLE for compact/medium fallback
state without a negotiated Companion profile: two builds produce SHA-256
`1e97976620a0951e45238def713deff03b393fa707ee71435a56f02ed1c51737`,
8,455 bytes, 109 nodes, and depth 7. This establishes repeatability only within
that runtime context. The live paired Glasspane/catalog smoke flow was not run;
the paired Emacs installation was not updated.

Remaining broader gates are recorded rather than repaired in this migration:

- The aggregate Elisp suite stops at the existing warning-as-error failure
  in `emacs/jetpacs-org-render.el`: unknown `jetpacs-editor-org-save-policy`.
  That already-dirty file is unchanged from the migration baseline.
- The Glasspane suite has seven failures reproduced against its original
  source: agenda-formatters, detail-builders, hub-verb-inventory,
  no-cross-module-private-reads, reader-adapter-gate, reader-trees, and
  views-board (each with the `glasspane-test-` prefix).
- Glasspane static validation reports five unresolved providers in existing
  test fixtures and two package-floor warnings. Its full byte compilation
  encounters the existing unknown `glasspane-ui-clock-entry` declaration.
