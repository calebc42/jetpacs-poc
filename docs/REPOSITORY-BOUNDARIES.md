# Repository boundaries

Jetpacs is one repository in a multi-repository workspace. A boundary exists
where authority, dependency direction, release surface, or downstream app
lifecycle differs. Internal modules that share Jetpacs ownership and release
cadence remain modules.

Each direct repository has its own Git status and local instructions. A status
from the Jetpacs repository does not describe sibling dirtiness.

## Dependency graph

```text
ebp                         normative protocol, contract, and conformance data
|-- ebp.el                  Emacs endpoint and independent SQLite durability
|   `-- ebp-org             reusable Org protocol engine
`-- ebp-kmp                 Kotlin durable model, reducers, wire/session code
    `-- ebp-compose         neutral renderer model + Foundation reference UI
          |
          v
Jetpacs                     Room/Nav/platform adapters and Elisp app shell
|-- owns Foundation jetpacs-components and optional jetpacs-material3
|-- contains optional jetpacs-automations and jetpacs-component-catalog using public host APIs
|-- consumes jetpacs-authoring helpers where selected
`-- hosts independently owned Elisp applets through public APIs
          |
          +-- glasspane
          +-- grove-native
          +-- orgzly-native
          `-- harp-native
```

Consumption flows downward. No upper layer acquires a reverse dependency on a
lower layer:

- EBP specifications and endpoint libraries do not name Jetpacs, Room,
  Navigation, Android policy, Compose design systems, or applets.
- `ebp-compose` may use the EBP Kotlin model and Compose Foundation/UI, but not
  Material, Jetpacs policy, or a downstream renderer extension.
- Renderer-extension manifests and their generated Kotlin/Elisp projections
  belong to the owning module or repository. EBP treats their identifiers as opaque.
- Jetpacs is the Android composition root and Elisp host; selecting a renderer
  or applet does not transfer ownership into Jetpacs.
- Applets may depend on public Jetpacs APIs. Jetpacs foundation source does not
  name an applet or encode its domain policy.

## Physical repositories and modules

Paths below are relative to the Jetpacs repository root.

| Repository | Workspace path | Owns |
|---|---|---|
| `ebp` | `../ebp` | Normative spec, contract projection, goldens, validator, conformance data |
| `ebp.el` | `../ebp.el` | Emacs session/wire endpoint, sync/completion, durable store, path helpers, ERT |
| `ebp-org` | `../ebp-org` | Reusable Org protocol engine and tests |
| `ebp-kmp` | `../ebp-kmp` | `:ebp-kmp`, `:wire`, generators, and Kotlin tests |
| `ebp-compose` | `../ebp-compose` | `:renderer:model`, `:renderer:compose`, and generic extension projection tooling |
| Jetpacs module | `jetpacs-components` | Foundation design renderer, `jetpacs.components` and `jetpacs.design` manifests/projections, builders/tests |
| `jetpacs-authoring` | `../jetpacs-authoring` | Shared inert Elisp authoring and source-inspection support |
| Jetpacs module | `jetpacs-automations` | Automation model/runtime, applet, and tests |
| Jetpacs module | `jetpacs-component-catalog` | Component catalog/design-lab applet and tests |
| `glasspane` | `../glasspane` | Personal-information applet, its optional EF theme provider, and tests |
| `orgzly-native` | `../orgzly-native` | Restored Orgzly Elisp applet, Foundation style profiles and tests |
| `grove-native` | `../grove-native` | Grove Jetpacs Elisp applet, design presets, workflow guide and tests |
| Jetpacs module | `jetpacs-platform-tools` | Read-only platform workbench, CLI, and MCP server |
| Jetpacs module | `jetpacs-applet-mcp` | Static applet analysis and explicitly trusted launcher tooling |

The developer tools are tracked modules of `jetpacs-poc`, not independent
repositories or runtime dependencies. The POC repositories all live
directly under `~/workspace/`; `~/workspace/jetpacs/` is the separate hand rewrite.
Set `JETPACS_REPOSITORIES_ROOT` to use a different collection directory.
The Material 3 library remains optional and lives inside `jetpacs-poc`.

## What Jetpacs owns

The following stay in this repository:

- `companion/core/*`: product read models, Room database/store, data
  projections, Navigation keys, and shared product tests;
- `companion/renderer/glance`: the Jetpacs home-screen widget renderer;
- `companion/renderer/material3`, `emacs/apps/jetpacs-material3`, and
  `emacs/apps/m3-catalog`: optional Material rendering, authoring, and catalog;
- `renderer-extensions/jetpacs-material3.json`: the Material extension authority;
- `companion/app`: Android composition root and platform adapters;
- `emacs/jetpacs-*.el`: surface builders, shell, chrome, navigation, files,
  reader/editor hosts, Org presentation adapters, app host, and device policy;
- composition policy that selects extension-owned generated projections;
- `device/`, `org/`, onboarding, product-specific fixtures, and packaging; and
- integration tests that prove the selected sibling repositories work together.

The Android namespace and application ID are
`com.calebc42.jetpacs.companion`. Consumed protocol code remains under
`com.calebc42.ebp.*`. The old `com.calebc42.ebp.companion` package is a
different POC-era application and is not upgrade-compatible.

## Workspace composition

`companion/settings.gradle.kts` maps stable Gradle project paths to the owning
local modules and sibling repositories. This preserves type-safe accessors
while keeping each source under its owner. The Elisp aggregate runner similarly points at sibling
source/test roots and runs their owner suites before Jetpacs integration.

A standalone or hosted build must check out the same graph or replace local
paths with published coordinates without changing dependency direction. The
Jetpacs monolith is not a fallback copy of extracted code.

Generated projections are checked in to their owner and regenerated there.
Build outputs, `.elc` files, editor backups, rejected patches, and historical
working-tree copies are not source boundaries merely because they are nearby.

Grove's independent Android implementation remains in `../grove/`. Its Jetpacs
implementation and relevant Git history were extracted into `../grove-native/`;
runtime `grove-*` features and the `grove` app identity remain unchanged.

`../harp-native` restores the archived Harp Personal Health Record applet as an
independent sibling. Its Org schema adapter and health workflows remain Elisp;
the reusable chart implementation belongs to `jetpacs-components`. Harp is an
optional, inactive packaged entry. See `../harp-native/docs/RESTORATION.md` for
verification results and remaining migration work.
