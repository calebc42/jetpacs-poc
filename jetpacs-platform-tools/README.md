# Jetpacs Platform Tools

`jetpacs-platform-tools` is the read-only developer workbench for the Jetpacs
platform. It turns the large proof-of-concept tree into a small, source-backed
interface for humans and coding agents. It does not become a runtime dependency
of Jetpacs or EBP.

Coding agents changing this product must follow
[`../AGENTS.md`](../AGENTS.md) and this directory's
[`AGENTS.md`](AGENTS.md). Together they define repository authority, read-only
and determinism invariants, change recipes, and required verification.

The platform tools deliberately answer questions from the repository instead
of keeping a second handwritten model of it:

- workspace and Gradle-module discovery;
- token-efficient Kotlin and Elisp source outlines;
- bounded symbol search across both implementation languages;
- focused JSON Pointer and text queries over the active `contract.json`;
- architectural boundary explanations with canonical local references; and
- excerpts of real implementation patterns rather than invented templates.

The Kotlin executable supplies both the human-facing command line and the MCP
stdio server. Applet-specific native-reader introspection, manifests,
validation, scaffolding, and trusted render checks belong to
[`jetpacs-applet-mcp`](../jetpacs-applet-mcp/README.md), whose
`jetpacs-applet-tooling.el` library implements those operations.

Workspace reads are canonicalized and size-bounded. The Emacs Org checkout can
be allowlisted as a second, non-overlapping read-only root and is exposed only
as `org/…`; it does not broaden workspace traversal. The seven EBP, applet, and
component repositories are separate explicitly allowlisted sibling roots,
addressed as `ebp/`, `ebp.el/`, `ebp-kmp/`, `ebp-compose/`, `ebp-org/`,
`glasspane/` and `jetpacs-authoring/`. The platform server
has no write, shell, build, network, or arbitrary-evaluation tool.

## Command line

Build and verify it:

```sh
./gradlew test installDist
```

The installed executable is `build/install/jetpacs-platform-tools/bin/jetpacs-platform-tools`.

```sh
jetpacs-platform-tools --root /path/to/jetpacs-poc doctor
jetpacs-platform-tools --root /path/to/jetpacs-poc overview
jetpacs-platform-tools --root /path/to/jetpacs-poc \
  --repositories-root /path/to/workspace summarize emacs/jetpacs-apps.el
jetpacs-platform-tools --root /path/to/jetpacs-poc \
  --org-source /path/to/emacs/lisp/org summarize org/org.el
jetpacs-platform-tools --root /path/to/jetpacs-poc contract /methods/surface.update
```

With no command, or with `serve`, it starts an MCP stdio server. The Jetpacs
root is selected in this order: `--root`, `JETPACS_WORKSPACE`, then the
nearest discovered checkout from the current directory. Named sibling
repositories are selected by `--repositories-root`,
`JETPACS_REPOSITORIES_ROOT`, then a bounded search of the workspace parent and
grandparent for the fixed repository names above. The default search never
scans an arbitrary parent tree. Org source is selected by `--org-source`,
`JETPACS_ORG_SOURCE`, then this checkout layout's
`../../emacs/emacs/lisp/org` when present.

## MCP surface

Tools:

- `jetpacs_project_overview`
- `jetpacs_source_summary`
- `jetpacs_find_symbol`
- `jetpacs_contract_query`
- `jetpacs_architecture`
- `jetpacs_find_examples`
- `jetpacs_doctor`

The server also exposes compact project/architecture/contract resources and
`jetpacs://project/implementation-guide`, which returns the root and local
`AGENTS.md` contracts in application order. Prompts cover implementing an EBP
change or reviewing an architecture boundary.
`jetpacs_find_symbol` searches only the external checkout with `kind: "org"`,
or combines it with the selected language scope when `includeOrg` is true.
`jetpacs_source_summary` accepts an explicit `org/FILE.el` path.

Example client configuration:

```json
{
  "mcpServers": {
    "jetpacs-platform": {
      "command": "/path/to/jetpacs-platform-tools/build/install/jetpacs-platform-tools/bin/jetpacs-platform-tools",
      "args": [
        "--root", "/path/to/jetpacs-poc",
        "--repositories-root", "/path/to/workspace",
        "--org-source", "/path/to/emacs/lisp/org"
      ]
    }
  }
}
```

The stdio server negotiates MCP `2025-11-25` and the compatible
`2025-06-18`, `2025-03-26`, and `2024-11-05` revisions. Protocol traffic is
the only stdout output; diagnostics use stderr.

## Product boundary

Use these tools while changing EBP, the Companion, the renderer, persistence,
or the Jetpacs foundation. Use
[`jetpacs-applet-mcp`](../jetpacs-applet-mcp/README.md) while authoring an
applet. The split mirrors the repository itself: platform work needs both
Kotlin and Elisp context, while applets need a precise, safe view of the public
Elisp surface.

Components, automations and the catalog are workspace modules at
`jetpacs-components/`, `jetpacs-automations/`, and `jetpacs-component-catalog/`. Their Elisp sources are indexed inside the workspace;
stale sibling copies are excluded. The separate applet tooling owns trusted runtime loading; this platform
workbench remains read-only.
