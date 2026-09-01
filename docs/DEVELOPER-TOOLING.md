# Jetpacs developer tooling

Jetpacs now has two deliberately different developer interfaces.

| Product | Audience | Source of truth | Primary job |
|---|---|---|---|
| `jetpacs-platform-tools` | Platform and EBP contributors | Kotlin + Elisp source, Org source, Gradle settings, EBP spec/contract | Explain and navigate the platform without loading it |
| `jetpacs-applet-mcp` | Applet authors | Public Jetpacs and allowlisted package Elisp APIs + EBP contract + applet forms | Discover, scaffold/skin, manifest, inspect, validate, and optionally exercise trusted applets |

This is a scope boundary, not duplicated branding. Platform changes frequently
cross protocol, storage, renderer, and Emacs endpoint layers. Applet work
should not need any of those internals; it needs a precise view of the public
node/action/surface/app contract.

## Names describe scope before transport

- `jetpacs-platform-tools` is the product and installed executable. Its MCP
  client key is `jetpacs-platform`; MCP is one interface to the workbench, not
  the workbench's identity.
- `jetpacs-applet-mcp` is specifically an MCP server for applet authors. Its
  client key is `jetpacs-applets`, and `jetpacs-applet-tooling.el` is the
  protocol-independent Elisp analysis engine behind it.
- `api` remains reserved for the runtime functions and EBP contracts that code
  actually calls. `sdk` remains available for a future stable, distributable
  applet development kit. `cli` describes the platform executable's command
  interface, not the whole product.

## Implementation guides for coding agents

The guides are layered so an agent receives global boundaries before local
recipes:

1. [`../AGENTS.md`](../AGENTS.md) identifies repository ownership, artifact
   authority, task routing, cross-layer invariants, and the verification
   ladder for the whole workspace.
2. [`../jetpacs-platform-tools/AGENTS.md`](../jetpacs-platform-tools/AGENTS.md)
   covers the platform CLI/workbench/MCP implementation.
3. [`../jetpacs-applet-mcp/AGENTS.md`](../jetpacs-applet-mcp/AGENTS.md) covers
   the Elisp analysis engine, applet MCP, trusted launcher, and the complete
   applet-authoring sequence.

An agent reads the root guide and then the nearest directory guide before
editing. These files are operational checklists; the EBP spec and current
source remain the behavioral authorities they identify.

## Design decisions

1. **Repository-backed, not lore-backed.** API signatures and examples come
   from current source. EBP vocabulary comes from `contract.json`. Curated
   architecture text points back to the local decision documents.
2. **Safe default, explicit execution boundary.** Static mode canonicalizes
   paths, bounds reads/results, and exposes no file-writing, build, shell,
   network, macroexpansion, or arbitrary evaluation tool. A separate launcher
   requires an allowlisted workspace `.el` path before it loads a trusted
   applet; runtime-only tools are absent from the default server.
3. **Resources for context, tools for questions, prompts for workflows.** A
   client can attach a compact stable overview, ask a narrow contract/source
   question, or begin a repeatable implementation/review workflow without
   injecting the entire repository.
4. **One modern MCP lifecycle.** The servers use newline-delimited JSON-RPC on
   stdio, preserve string or numeric request IDs, require initialization,
   return protocol errors for protocol failures, and return `isError` tool
   results for recoverable tool-input failures. They advertise static lists as
   static.
5. **No fake architecture templates.** The prototype `ebp_handler` referenced
   packages and interfaces that do not exist. The platform tools now return
   bounded excerpts of real endpoint, durable-store, renderer, action, applet,
   and navigation patterns.
6. **No ambient Emacs evaluator.** The prototype applet server exposed
   `eval_elisp` and `macroexpand` while loading almost none of Jetpacs. The new
   static server reads the real tree and removes those execution paths. The
   trusted launcher exposes two bounded domain operations—registry inventory
   and render checking—not a general evaluator.
7. **Documentation is derived behavior.** Public forms carry docstrings;
   actions carry schemas and docs; MCP tools carry closed input schemas and
   trust annotations. The canonical applet manifest hashes file bytes, exact
   registrations and remote/builtin emission evidence, signatures, docs,
   provenance, and every contract/platform input used to derive that evidence.
   Reader-native positioned symbols tie executable calls to cons identity
   before becoming ordinary forms, so quoted examples cannot distort source
   locations. Large manifests page whole digest-bearing records rather than
   truncating Lisp. Scaffold text is read back and compared with its literal
   source forms before return.
8. **Package source is generic and separate.** `JETPACS_REFERENCE_ROOTS` maps
   stable namespaces to arbitrary package source trees. They are lazily
   indexed, read-only, non-overlapping with the workspace, and absent from
   runtime `load-path`. `JETPACS_ORG_SOURCE`/`org/…` is the discovered
   compatibility instance, not a package-specific MCP architecture.
9. **Actions are reconciled, not counted.** Remote names are open-world. The
   inspector rereads current registrations and emission sites, resolves
   literal names to applet/platform providers, exposes dynamic expressions,
   and derives the closed native builtin vocabulary from `contract.json` and
   platform Elisp. Glasspane is integration coverage, never an allowlist or
   fixed count.

## Elisp is the screen model

Jetpacs does not translate applet source into a second UI AST. A screen builder
returns keyword plists and vectors. That same value is:

```text
built ──> inspected/edited ──> structurally analyzed ──> sender-gated
      └──────────────────────> canonical JSON ──> EBP ──> Companion
```

This makes introspection operational instead of decorative.
`jetpacs-devtools-capture-spec` obtains the normal shell build;
`jetpacs-shell--analyze-spec` reports the complete structural facts;
`jetpacs-node->canonical-json` makes byte comparisons deterministic; and
`jetpacs-shell-push :spec` sends an edited datum through the usual path. The
MCP runtime checker composes those existing seams rather than defining a
parallel representation.

Determinism is always stated with its envelope: source and registrations,
Emacs/Org state, reconciled device state, profile/capabilities/limits, and
window class. Two equal builds are evidence for those inputs. Connected checks
invoke the real non-sending profile, capability, amendment, size, variant, and
identity gates; the normal push and Companion acceptance remain authoritative.
See [`DETERMINISM.md`](../jetpacs-applet-mcp/DETERMINISM.md).

For an existing package, keep the package as domain engine and put the
state/action translation in a narrow applet adapter. The same generic reader,
manifest, validator, runtime inventory, and render checker apply to Magit,
Denote, Gnus, Eglot, Org, or another package. Read
[`PACKAGE-SKINS.md`](../jetpacs-applet-mcp/PACKAGE-SKINS.md). A new package
workflow normally adds only applet Elisp; a missing native UI or receiver-local
behavior belongs in the EBP/Jetpacs/Companion contract.

## Trust modes

| Mode | Loads applet code | Available evidence | Intended use |
|---|---:|---|---|
| Static MCP | No | Reader-derived API/docs, exact forms, manifest, validator | Unknown or not-yet-reviewed source |
| Trusted runtime MCP | Yes | Static evidence plus live registries, provenance, canonical renders, real sender gates | Applet code the developer explicitly trusts |

## Relationship to EBP

Neither tool belongs in the EBP or Jetpacs runtime dependency graph. They are
development consumers:

```text
EBP spec + contract ─┬─> endpoints and conformance suites
                     ├─> Jetpacs platform ──> live registries/node data/gates
                     ├─> jetpacs-platform-tools (cross-language inspection)
Package source refs ─┤
Applet forms ────────└─> jetpacs-applet-mcp (static manifest or trusted runtime)
```

If a tool discovers a mismatch among the spec, contract, golden fixtures, or
implementations, the mismatch is reported. Tooling must not silently promote a
derived artifact into a new authority.

## Evolution

The first stable surface is intentionally local and stdio-only. Logical next
steps, once repeated work proves the need, are:

- generated contract-to-builder coverage rather than additional handwritten
  tables;
- structured validator findings alongside the backward-compatible text result;
- applet test-harness generation after the applet packaging contract settles;
- extraction of the active spec-driven implementation from the POC snapshot,
  after which discovery should prefer top-level `ebp/`, `emacs/`, and
  `companion/` automatically; and
- publication/versioning only after a second checkout consumes the tools.

Streamable HTTP, remote access, autonomous file edits, and general Elisp
evaluation are not roadmap defaults. Each would expand the trust boundary and
needs a concrete use case first. Trusted applet execution stays a separate
local launcher rather than becoming the default MCP mode.
