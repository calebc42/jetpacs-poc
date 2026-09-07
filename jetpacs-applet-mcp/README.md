# Jetpacs Applet MCP

`jetpacs-applet-mcp` is the Elisp-native applet-authoring server. Its default
process uses the Lisp reader without loading the applet. A separate, explicit
launcher can load one trusted applet to inspect its real registries and
renders.

Coding agents maintaining these tools or implementing an applet must follow
[`../AGENTS.md`](../AGENTS.md) and this directory's
[`AGENTS.md`](AGENTS.md). The latter contains separate, ordered workflows for
tooling maintenance and applet authoring.

It provides:

- the applet lifecycle and architecture guide;
- a package-agnostic guide and repeatable prompt for skinning existing Emacs
  packages;
- discovery of the real `jetpacs-*` and `ebp-*` APIs;
- static symbol signatures, docstrings, locations, and source;
- EBP node schemas joined to their actual Elisp builder APIs;
- applet owner/root/dependency inventory plus source-derived reconciliation of
  registered handlers, emitted remote actions, dynamic sites, platform
  providers, unresolved names, and statically reachable native builtin use;
- a deterministic semantic manifest containing source hashes, exact normalized
  registrations, signatures, docstrings, provenance, and its own digest;
- static validation of ownership, identifiers, emitted-action providers,
  lifecycle hygiene, and direct prompt or blocking calls in action dispatch;
- a lifecycle-complete applet scaffold generated from literal forms and
  reader-checked against those forms before it is returned.

The implementation boundary mirrors those responsibilities:

- `jetpacs-applet-tooling.el` owns source reading, introspection, manifests,
  validation, scaffolding, and bounded runtime checks;
- `jetpacs-applet-mcp.el` translates those domain operations to MCP; and
- `jetpacs-applet-mcp-runtime.el` is the only launcher that loads a trusted
  applet.

Read `jetpacs://applets/determinism` for the one-IR design: screen plists and
vectors are the value builders create, devtools inspect, sender gates analyze,
canonical JSON serializes, EBP carries, and the Companion renders.

## Trust modes

The default server is static and read-only. It has no `eval_elisp`, macro
expansion, file writing, process, shell, or network tool. It exposes the seven
named app checkouts through stable source namespaces (`ebp/`, `ebp.el/`,
`ebp-kmp/`, `ebp-compose/`, `ebp-org/`, `glasspane/`, `jetpacs-authoring/`). These checkout roots are distinct from
explicitly allowlisted, inspection-only package namespaces. Org is the
built-in reference example and appears as `org/`.

The trusted runtime launcher executes the applet named by
`JETPACS_TRUSTED_APPLET`. The target must be an `.el` file in the Jetpacs
workspace; no read-only reference namespace is an executable applet target.
Only that launcher advertises:

- `jetpacs_runtime_inventory`, sourced from live app/root/action/owner/schema
  registries and claim sites; and
- `jetpacs_check_render_determinism`, which builds twice, compares canonical
  bytes, reports context and structure, and invokes the real non-sending
  Jetpacs sender gates available in the current session.

Loading an applet is arbitrary Elisp execution. Runtime mode is for code you
trust; static inspection is not a sandbox or a trust verdict.
The launcher prefers a newer source file over a stale neighboring byte-code
file so live evidence follows the checkout being edited. The read-only
`JETPACS_ORG_SOURCE` tree is not added to `load-path`: it may come from a
different Emacs revision, while applet execution must use Org compatible with
the running Emacs unless the trusted workspace explicitly provides another
runtime dependency.

## Start it

```sh
JETPACS_WORKSPACE=/home/calebc42/workspace/jetpacs-poc \
JETPACS_REPOSITORIES_ROOT=/home/calebc42/workspace \
JETPACS_ORG_SOURCE=/home/calebc42/workspace/emacs/emacs/lisp/org \
  emacs -Q --batch -l /home/calebc42/workspace/jetpacs-poc/jetpacs-applet-mcp/jetpacs-applet-mcp.el
```

Example client configuration:

```json
{
  "mcpServers": {
    "jetpacs-applets": {
      "command": "emacs",
      "args": [
        "-Q",
        "--batch",
        "-l",
        "/home/calebc42/workspace/jetpacs-poc/jetpacs-applet-mcp/jetpacs-applet-mcp.el"
      ],
      "env": {
        "JETPACS_WORKSPACE": "/home/calebc42/workspace/jetpacs-poc",
        "JETPACS_REPOSITORIES_ROOT": "/home/calebc42/workspace",
        "JETPACS_ORG_SOURCE": "/home/calebc42/workspace/emacs/emacs/lisp/org"
      }
    }
  }
}
```

When the server lives in this checkout, it derives the flattened workspace
from its own path. With `JETPACS_REPOSITORIES_ROOT` unset it checks only the
workspace's immediate parent and grandparent for the exact seven checkout names;
it never scans all of `~/workspace`. It discovers an external Org checkout
under the repositories root for inspection only. The environment variables
remain the portable, explicit configuration.

To inspect arbitrary package implementations, set
`JETPACS_REFERENCE_ROOTS` to a JSON object. Namespaces are stable source
addresses, not load-path entries:

```sh
JETPACS_REFERENCE_ROOTS='{"magit":"/src/magit/lisp","denote":"/src/denote"}' \
  emacs -Q --batch \
  -l /home/calebc42/workspace/jetpacs-poc/jetpacs-applet-mcp/jetpacs-applet-mcp.el
```

This exposes paths such as `magit/magit-status.el` to static summary and exact
symbol lookup. It does not load those packages. Trusted execution must obtain
dependencies through the applet's actual runtime configuration.

## What the action inventory means

There is no Glasspane action allowlist and there is no fixed number of actions
the Companion must know. `jetpacs_inspect_applet` rereads current Elisp forms
and separates four facts:

- a **registered remote action** is a `jetpacs-defaction` handler in Emacs;
- an **emitted remote action** is a `jetpacs-action` (or surface-opening remote
  action) a screen can send;
- a **provider** is the applet or current Jetpacs platform source that
  registers that literal name; and
- a **native builtin** is one of the closed, profile-advertised actions in
  `contract.json`, such as `surface.open` or `dialog.submit`.

The validator rejects a statically visible literal emission with no applet or
platform provider. Dynamic names are listed with their exact expression and
source site because a static reader cannot prove their possible values.
Registered-but-not-emitted actions are evidence, not automatically dead code:
they may be compatibility aliases, external entry points, or actions created
through dynamic helpers. Trusted runtime inventory answers which handlers are
actually loaded.

`jetpacs_describe_actions` derives the remote descriptor fields and all native
builtins from the active EBP contract, then joins them to real Elisp
constructors. Glasspane appears only as a large integration test; its current
size is never copied into the tooling.

Manifest schema `jetpacs.applet-manifest/2` adds the lossless
`action-emissions` and `builtin-emissions` sections. The latter is conservative
source-derived reachability through direct constructors and platform wrappers,
not proof that every runtime branch emits the builtin. Registration records stay
under `actions`, so consumers can distinguish “handler exists” from “UI can
emit it” without interpreting prose. `analysis-inputs` hashes the active
contract and every platform source file used to derive builtin reachability,
so the manifest has no invisible generated-data dependency.

## Arbitrary Emacs-package skins

The MCP architecture is applet-generic. Treat an existing package as the
domain engine and the Jetpacs applet as a native, declarative skin:

1. Allowlist the package's exact source root and inspect the small API slice
   needed for the workflow.
2. Read package state in a pure screen builder and turn it into ordinary
   Jetpacs plist/vector data.
3. Register semantic remote actions that call the package's noninteractive
   APIs, durably conclude the effect, and defer presentation.
4. Reconcile emitted names, validate, test bound package states, and only then
   load trusted code for live registry/render evidence.

Remote action names are open-world, so a new package workflow needs no MCP or
Companion action case. Extensibility is intentionally bounded at the native
wire vocabulary: if a skin needs a UI primitive, descriptor field, builtin,
method, capability, or platform integration EBP cannot express, that is a
Jetpacs/EBP/Companion feature—not another applet verb. Packages whose useful
behavior is exposed only through interactive minibuffer UI may also need a
small noninteractive adapter before they can be skinned deterministically.

The complete lower-model workflow is in
[`PACKAGE-SKINS.md`](PACKAGE-SKINS.md). The MCP exposes the same guide as
`jetpacs://applets/package-skins` and provides the `skin_emacs_package` prompt
for a package-specific implementation run.

Start trusted runtime mode for one applet:

```sh
JETPACS_WORKSPACE=/home/calebc42/workspace/jetpacs-poc \
JETPACS_REPOSITORIES_ROOT=/home/calebc42/workspace \
JETPACS_ORG_SOURCE=/home/calebc42/workspace/emacs/emacs/lisp/org \
JETPACS_TRUSTED_APPLET=glasspane/glasspane.el \
  emacs -Q --batch \
  -l /home/calebc42/workspace/jetpacs-poc/jetpacs-applet-mcp/jetpacs-applet-mcp-runtime.el
```

## Suggested workflow

1. Read `jetpacs://applets/implementation-guide` for the layered coding-agent
   contract, then `jetpacs://applets/guide` and
   `jetpacs://applets/determinism` for the applet behavior contract.
2. Generate a scaffold with `jetpacs_applet_template`.
3. Use `jetpacs_describe_actions`, `jetpacs_describe_node`, and
   `jetpacs_describe_symbol` only for the actions, UI components, and package
   APIs the applet needs.
4. Inspect and hash the finished directory with `jetpacs_inspect_applet` and
   `jetpacs_applet_manifest`; keep the digest with diagnostic output. Small
   manifests are returned whole. A large manifest returns a section index;
   follow its `section` and `next-offset` fields to retrieve complete records
   without ever truncating an Elisp form or docstring.
5. Run `jetpacs_validate_applet`; require zero unresolved literal action names,
   fix errors, and assess warnings and dynamic emission sites.
6. For trusted code, launch runtime mode and run the inventory and render check.
7. Add offline ERT coverage, then a narrow device smoke test for the user flow.

The committed [`hello.el`](examples/hello/hello.el) is the same minimal shape
returned by the scaffold tool.

## Tests

```sh
emacs -Q --batch \
  -l test/jetpacs-applet-tooling-test.el \
  -l test/jetpacs-applet-mcp-test.el \
  -f ert-run-tests-batch-and-exit

emacs -Q --batch \
  -L /home/calebc42/workspace/ebp.el/lisp \
  -l test/jetpacs-applet-mcp-runtime-test.el \
  -f ert-run-tests-batch-and-exit
```

The runtime suite loads the real Jetpacs platform and the committed hello
applet in a fresh Emacs process; keep it separate from the static-mode suite.

Static source discovery skips hidden directories, build output, and symlinks
before descending. It sorts entries and rejects overflow of its shared
20,000-entry walk budget, 1,000-file index budget, or depth limit of 64; it
never silently presents a truncated index. Individual file-size bounds remain
in force. Per-file namespace resolution is shared by that file's records.

Components, automations and the catalog are workspace modules at
`jetpacs-components/`, `jetpacs-automations/`, and `jetpacs-component-catalog/`. Their Elisp sources are indexed inside the workspace;
stale sibling copies are excluded. The trusted launcher makes their fixed
module load paths available without requiring either module. Inspection-only
package roots remain excluded from runtime loading.
