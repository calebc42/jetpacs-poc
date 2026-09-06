# Jetpacs developer interfaces

Jetpacs has two developer products with different audiences and trust
boundaries. Both are source-backed development consumers; neither belongs in
the EBP or Jetpacs runtime dependency graph.

## Choose the interface by task

| Interface | Use it for | Evidence | Executes applet code |
|---|---|---|---:|
| `jetpacs-platform-tools` CLI or `jetpacs-platform` MCP | EBP, architecture, Kotlin/Elisp implementation, module, and exact Org-source questions | Workspace source, Gradle settings, EBP spec/contract, configured Org checkout | No |
| Static `jetpacs-applets` MCP | Public API discovery, applet inspection, manifests, validation, scaffolding, and package-skin planning | Reader-derived Jetpacs/package forms plus EBP contract | No |
| Trusted applet runtime | Live registry provenance, bounded canonical render comparison, and real non-sending sender gates | Static evidence plus one explicitly trusted loaded applet and its runtime context | Yes |

Start with the platform workbench for a platform or protocol change. Start with
the static applet server for applet code, including code not yet reviewed.
Cross into trusted runtime only when execution is necessary and the applet has
been explicitly trusted.

## Product identities

- `jetpacs-platform-tools` is the installed workbench and executable. Its MCP
  client key is `jetpacs-platform`; MCP is one frontend, not the product's
  identity.
- `jetpacs-applet-mcp` is the applet-authoring MCP server. Its client key is
  `jetpacs-applets`; `jetpacs-applet-tooling.el` is the protocol-independent
  static analysis engine behind it.
- `api` names runtime functions and contracts that code calls. `sdk` is
  reserved for a future stable distributable applet kit. `cli` describes the
  platform executable's command interface only.

## Evidence and authority

Both products derive answers from the current repository instead of carrying
parallel handwritten models:

- EBP vocabulary comes from the active `contract.json`; normative behavior
  still comes from `SPEC.md`.
- Kotlin modules come from current settings files, and implementation examples
  are bounded excerpts of real source.
- Public Elisp signatures and docstrings come from reader-backed definitions.
- Org and other package APIs come from explicitly configured, read-only source
  namespaces rather than from the model's memory or the running Emacs load
  path.

If the spec, contract, goldens, generated projections, or implementation
disagree, the tool reports the mismatch. It must not pick a convenient artifact
and silently turn it into authority.

## Static and trusted applet modes

Static mode canonicalizes paths, bounds reads and results, and never loads the
inspected applet. It exposes no file-writing, build, shell, network,
macroexpansion, or general evaluation operation. `JETPACS_REFERENCE_ROOTS`
maps stable namespaces to arbitrary package-source directories;
`JETPACS_ORG_SOURCE` is the configured Org instance exposed as `org/...`.
Those roots remain inspection-only and never enter trusted runtime
`load-path`.

The trusted launcher requires `JETPACS_TRUSTED_APPLET` to resolve to a
workspace `.el` file. Loading it is an explicit arbitrary-Elisp execution
boundary. The launcher adds bounded domain operations for runtime inventory
and render checking, not a general evaluator.

Static inspection is evidence, not a sandbox or proof. Trusted double-render
comparison is evidence for the reported source, registration, Emacs/Org state,
reconciled device state, negotiated profile/capabilities/limits, and window
class; it is not a claim of universal purity.

## One screen model

A screen builder returns ordinary plist/vector IR. The same value follows the
normal implementation path:

```text
built -> inspected or edited -> structurally analyzed -> sender-gated
      -> canonical JSON -> EBP -> Companion
```

`jetpacs-devtools-capture-spec` obtains the normal built value,
`jetpacs-shell--analyze-spec` reports its structure,
`jetpacs-node->canonical-json` provides stable bytes, and
`jetpacs-shell-push :spec` uses the usual sender path. The runtime checker
composes those seams; it does not introduce a second AST, serializer, or
friendly validator that can disagree with production.

For an existing package, keep that package as the domain engine and write a
narrow applet adapter for state and actions. The generic reader, manifest,
validator, inventory, and render checker apply to any configured package
source. A missing native primitive or receiver-local behavior belongs in the
EBP/Jetpacs/Companion layers, not in a package-specific tooling branch.

## Resources, tools, and prompts

- Resources provide compact stable context.
- Tools answer bounded questions from repository evidence.
- Prompts encode repeatable implementation and review workflows.

The platform CLI and MCP call the same workbench operations. The applet MCP
calls its standalone Elisp analysis engine. Protocol adapters own JSON-RPC/MCP
lifecycle only; repository discovery and domain behavior remain outside the
adapter.

## Governing guides

Read these in order for the task at hand:

1. [`../AGENTS.md`](../AGENTS.md) for workspace ownership, authority, routing,
   invariants, and verification.
2. [`../jetpacs-platform-tools/AGENTS.md`](../jetpacs-platform-tools/AGENTS.md)
   when changing the platform workbench.
3. [`../jetpacs-applet-mcp/AGENTS.md`](../jetpacs-applet-mcp/AGENTS.md) when
   maintaining applet tooling or using it to author an applet.
4. [`../jetpacs-applet-mcp/APPLET-GUIDE.md`](../jetpacs-applet-mcp/APPLET-GUIDE.md)
   and [`DETERMINISM.md`](../jetpacs-applet-mcp/DETERMINISM.md) for applet work.
5. [`../jetpacs-applet-mcp/PACKAGE-SKINS.md`](../jetpacs-applet-mcp/PACKAGE-SKINS.md)
   when adapting an existing Emacs package.

The user-facing commands, environment variables, MCP configuration, and test
commands live in each product's own README:

- [`../jetpacs-platform-tools/README.md`](../jetpacs-platform-tools/README.md)
- [`../jetpacs-applet-mcp/README.md`](../jetpacs-applet-mcp/README.md)

## Direction

The local stdio interfaces are intentionally narrow. Plausible next steps are
generated contract-to-builder coverage, structured validator findings, an
applet test-harness generator after the packaging contract settles, and
publication/versioning after a second checkout consumes the tools.

Streamable HTTP, remote access, autonomous file edits, and general Elisp
evaluation are not defaults. Each expands the trust boundary and needs a
specific use case and contract first.
