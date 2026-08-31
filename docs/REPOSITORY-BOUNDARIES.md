# Repository boundaries

This document is the extraction manifest for the POC 3 monolith.  A
directory is a repository boundary only when it has an independent authority,
dependency direction, release surface, or downstream application lifecycle.
Small implementation modules which share Jetpacs ownership and release cadence
remain modules rather than becoming repositories.

## Dependency graph

```text
ebp                         normative protocol, contract, and conformance data
|-- ebp.el                  canonical Emacs endpoint implementation
|   `-- ebp-org             reusable Org engine/integration
`-- ebp-kmp                 canonical Kotlin endpoint implementation
    `-- ebp-compose         neutral renderer model + Foundation reference UI

Jetpacs                     product policy, Room/Nav/platform adapters, app shell
|-- consumes ebp.el, ebp-org, ebp-kmp, and ebp-compose
|-- composes glasspane-material3 and jetpacs-components
|-- hosts downstream Elisp apps through its public app/surface APIs
`-- packages selected downstream applets without owning their source

glasspane-material3         downstream Material renderer extension + M3 catalog
jetpacs-components          Jetpacs-owned Foundation design extension
jetpacs-authoring           shared, inert Elisp authoring/inspection support
|-- jetpacs-automations     downstream applet and automation runtime
`-- jetpacs-component-catalog
glasspane                   downstream personal-information applet
`-- glasspane-ef            optional Modus-family theme provider module
```

Consumption flows downward through the diagram: implementations consume the
specification, Jetpacs consumes the implementations, and applets consume
Jetpacs.  No upper layer may acquire a reverse dependency on a lower one.  In
particular:

- `ebp`, `ebp.el`, and `ebp-kmp` may not depend on or define Jetpacs,
  Glasspane, Room, Navigation, Android policy, or a design system.
- `ebp-compose` may depend on `ebp-kmp` and Compose Foundation/UI, but not
  Material, Jetpacs policy, or a downstream extension.
- renderer-extension manifests and their generated Kotlin/Elisp projections
  belong to the extension owner.  EBP treats extension identifiers as opaque.
- the Jetpacs application is the composition root which selects installed
  renderers and applets; that selection does not transfer ownership to Jetpacs.

## Physical repositories

| Repository | Canonical workspace path | Source extracted from `llm-poc-3` |
|---|---|---|
| `ebp` | `../ebp-poc/ebp` | POC specification, contract, goldens, and conformance tools |
| `ebp.el` | `../ebp-poc/ebp.el` | `emacs/ebp.el`, `ebp-sync.el`, `ebp-complete.el`, `ebp-store.el`, `ebp-sqlite.el`, `ebp-path.el`, and their pure ERT suites |
| `ebp-org` | `../ebp-poc/ebp-org` | `emacs/ebp-org.el` and `test/ebp-org-test.el` |
| `ebp-kmp` | `../ebp-poc/ebp-kmp` | `companion/ebp-kmp`, `companion/wire`, their tests, and the Kotlin vocabulary generator |
| `ebp-compose` | `../ebp-poc/ebp-compose` | `companion/renderer/model`, `companion/renderer/compose`, and renderer-extension projection tooling |
| `glasspane` | `../glasspane` | the already extracted applet, its EF Themes provider module, remaining legacy tests, and plans |
| `glasspane-material3` | `../glasspane-material3` | `companion/renderer/material3`, `emacs/apps/glasspane-material3`, the M3 catalog, its manifest/golden, tests, and lookup tooling |
| `jetpacs-components` | `../jetpacs-components` | `companion/renderer/jetpacs`, `emacs/apps/jetpacs-components`, and the `jetpacs.components` manifest/golden |
| `jetpacs-authoring` | `../jetpacs-authoring` | shared `jetpacs-authoring.el`, `jetpacs-elisp-source.el`, and `jetpacs-catalog-inspection.el` |
| `jetpacs-automations` | `../jetpacs-automations` | automation model, runtime, applet, and tests |
| `jetpacs-component-catalog` | `../jetpacs-component-catalog` | component authoring/catalog applet and tests |

`jetpacs-platform-tools` and `jetpacs-applet-mcp` are already physically
separate workspace projects and remain workspace-only developer tooling.

## What remains in Jetpacs

The following are module seams, not repository seams:

- `companion/core/*`: Jetpacs read models, Room database/store, repositories,
  Navigation keys, and shared product tests;
- `companion/app`: Android composition root and platform adapters;
- the general `emacs/jetpacs-*.el` surface, shell, navigation, files, reader,
  editor, Org presentation adapters, app host, and device integration;
- the renderer selection/composition policy which consumes extension-owned
  generated projections;
- `device/`, `org/`, onboarding, packaging, and product-specific goldens; and
- historical audits and cross-repository architecture plans.  History may
  describe several owners without becoming source authority for any of them.

Ad-hoc patch scripts, rejected patches, editor backups, bytecode, and build
outputs are working-tree artifacts, not repository candidates.  Extraction
must preserve them without promoting them to source authority.

## Workspace consumption

During the local multi-repository transition, Gradle project directories and
Emacs load paths resolve the canonical neighboring checkouts.  Standalone
upstream builds use the same relative workspace layout.  Publishing or remote
checkout coordinates may replace those local paths later without changing the
ownership graph.

Until those repositories have remotes and release coordinates, hosted CI must
check out the same workspace graph before running Jetpacs.  The local sibling
paths are deliberately explicit; the monolith is no longer a fallback source
of extracted code.

Each extracted repository retains the relevant committed history and then
overlays the current working tree.  Existing modifications therefore remain
modifications in their new owner rather than being silently committed or
discarded.
