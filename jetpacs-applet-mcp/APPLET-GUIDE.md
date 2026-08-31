# Building a Jetpacs applet

A Jetpacs applet is an Emacs application presented through native Jetpacs UI.
Emacs owns application state and decisions. The Companion owns native
presentation, accepted presentation state, platform integration, and durable
delivery. EBP carries declarative node trees and named semantic actions; it
never carries Elisp to evaluate.

An applet may be a new application or a native skin over an existing Emacs
package. In the latter case, the package remains the domain engine: reuse its
state model, noninteractive functions, hooks, persistence, and invariants.
The skin translates that state into Jetpacs data and translates named device
actions back into package API calls. Do not fork or reimplement package logic
merely to make it renderable.

For that workflow, read [`PACKAGE-SKINS.md`](PACKAGE-SKINS.md) after this
guide; it gives the package adapter, source-inspection, extension-decision, and
test sequence lower models should follow.

Coding agents should use the ordered applet-authoring workflow and completion
checklist in [`AGENTS.md`](AGENTS.md) alongside this behavioral guide.

## The lifecycle

A complete applet has one stable owner and symmetric registration:

1. Require only the Jetpacs layers it uses.
2. Build screens with `jetpacs-*` node constructors.
3. Inside `with-jetpacs-owner`, register a chrome root and every semantic
   action.
4. Register app identity with `jetpacs-defapp`; the first claimed surface is
   its home.
5. Provide an idempotent public `*-register` function and a matching
   `*-unregister` function for live reload, disable, and uninstall.

Use a non-reserved owner such as `weather` or `notes.team`. The `jetpacs.`
prefix belongs to platform surfaces. Wire identifiers contain 1–128 ASCII
characters, start with a letter or digit, and otherwise use letters, digits,
`.`, `_`, `-`, `:`, or `/`. Action names must also contain a dot, such as
`weather.refresh`.

## Screen builders are pure

A screen builder reads Emacs state and returns a typed node tree. It must not
write files, prompt, start processes, mutate registries, or push another
surface. Jetpacs can call a builder during reconnect, capability changes,
window-class changes, or refresh, so hidden effects become duplicate work and
ordering bugs.

Use `jetpacs-chrome-screen` for an ordinary app screen, then compose typed
builders such as `jetpacs-column`, `jetpacs-text`, `jetpacs-button`, and
`jetpacs-lazy-column`. Query `jetpacs_describe_node` before using an unfamiliar
node: it joins the current EBP schema to the real builder signature and
docstring.

For a package skin, bind or snapshot every package state input the builder
reads. If a convenient package query mutates caches, prompts, switches
buffers, or starts work, move it into an action/background producer and have
the builder read the resulting explicit state. An interactive command is not
automatically a suitable handler API; prefer its underlying noninteractive
function or write a narrow adapter with explicit arguments.

The returned plist/vector tree is the UI intermediate representation. There
is no second generated model: the same datum is pretty-printed by the
inspector, walked by the shell, checked by the sender gates, serialized to
canonical JSON, carried by EBP, and rendered natively. This is the practical
payoff of Elisp homoiconicity: inspect a screen with
`jetpacs-devtools-capture-spec`, edit the data, and re-push it through
`jetpacs-shell-push :spec` using the normal path.

Every emitted node type and optional feature must be advertised for its target.
Use the Jetpacs capability helpers; do not assume the phone, widget, dialog,
and notification profiles have the same vocabulary. Respect negotiated byte,
node, depth, field, and module limits.

## Determinism is scoped to explicit inputs

The same source, Emacs application state, reconciled device state, negotiated
profile/capabilities/limits, and window class should produce byte-identical
canonical output. A builder must not hide time, randomness, I/O, prompting,
process launch, registry mutation, or file writes inside that calculation.
Actions and background producers perform effects; builders read their
resulting state.

Use stable domain identity for node IDs and sibling keys. Do not derive them
from hash iteration or unstable traversal order. Keep unordered source data
sorted before rendering.

For trusted code, `jetpacs_check_render_determinism` builds a surface twice,
compares canonical SHA-256 hashes, reports the structural walk and context,
and runs the real non-sending Jetpacs gates. Equal builds prove repeatability
only for that exact state; ordinary ERT cases should bind each meaningful
state explicitly. Read `jetpacs://applets/determinism` for the complete bound
and trust contract.

## Actions are a durability boundary

`jetpacs-defaction` handlers receive `(ARGS PARAMS)` and return exactly one of
`accepted`, `stale`, or `rejected`.

Remote action names are open-world. The Companion does not contain a switch
statement for `weather.refresh`, `magit.stage`, or every future applet verb;
it dispatches the name generically to Emacs. Native builtins are different:
they form the closed, profile-advertised vocabulary in `contract.json` and run
locally on the Companion. Call `jetpacs_describe_actions` for the current
descriptor fields, builtin set, and real Elisp constructors.

Every registration should carry a literal `:doc` that explains the semantic
effect to agents and action editors, plus a literal `:args` schema when it
accepts named arguments. Handler docstrings document implementation details;
action metadata documents the wire-visible contract. The validator checks
both layers.

- `accepted` means the effect is already complete or durably committed.
- `stale` and `rejected` are permanent conclusions for that event.
- Use `jetpacs-retry-later` when durable work cannot be accepted yet.

Handlers run inside JSON-RPC dispatch. They must return promptly and must not
call `read-string`, `completing-read`, `yes-or-no-p`, `read-file-name`, or
similar prompts directly. Use `jetpacs-flow-continue` for interaction after
the response. Use `jetpacs-app-defer-refresh` for ordinary post-action
presentation. Never return `accepted` and then schedule the durable effect on
a timer: a crash would lose work the Companion was told it could delete.

An action is owner-scoped by default. Keep that protection. `:any-surface` is
for a deliberately global verb, not a workaround for a missing surface claim.

Use `jetpacs_inspect_applet` to keep the action inventory honest. It reads
current forms and reports these independently:

- `jetpacs-defaction` registrations (available Emacs handlers);
- literal and dynamic `jetpacs-action` emission sites (what UI can send);
- literal emissions resolved to this applet or current Jetpacs platform
  source, and unresolved names; and
- statically reachable native builtin sites (including platform wrapper call
  paths) and the complete contract vocabulary.

`jetpacs_validate_applet` rejects an emitted literal remote name with no known
provider. A dynamic site is not guessed: inspect its expression and cover its
finite values in ERT. A provider found in platform source is still only static
evidence; trusted runtime inventory establishes that the needed module was
actually loaded. Registered-but-not-statically-emitted actions may be valid
external entry points or compatibility aliases, so they are reported rather
than automatically rejected.

## State and navigation

Stable node IDs preserve device drafts and state across renders. Mint IDs from
arbitrary names with `jetpacs-wire-id`; do not send raw buffer names or paths
as identifiers. Read reconciled device state through the Jetpacs state API and
register explicit state-change handlers when immediate synchronization is
required.

App destinations are places, not commands. Register destinations with
`jetpacs-defapp`; keep one app's navigation and actions under the same owner.
Use chrome push/reset APIs for app-owned screens and defer presentation out of
action dispatch.

## Packaging and teardown

A packaged app entry names its feature, app ID, register function, and
unregister function. Loading source and enabling the app are separate acts.
Registration should be idempotent so evaluation replaces handlers and roots in
place. Unregistration removes actions, app identity, chrome roots, hooks,
timers, and app-owned mutable state.

Treat side-loaded Elisp as code, because it is code. Installation consent is
not a sandbox. The MCP server's static inspection does not make an untrusted
applet safe to load.

Before loading, `jetpacs_applet_manifest` can document the applet entirely as
data: source hashes, features, owners, apps, roots, registered actions, emitted
remote actions and builtins, exact forms, definition
signatures/docstrings/locations, and a digest covering the whole semantic
record. Commit useful public docstrings because they become tooling output
rather than decaying prose elsewhere.

When that record exceeds the bounded MCP result size, the manifest call
returns a section index. Request the named sections from offset zero and
follow each `next-offset`; every page repeats the full digest and contains
whole records, never truncated Lisp. Restart from the index if that repeated
digest changes while paging, because the source changed between calls.

## Package APIs come from package source

Do not make a lower model recall a package API from training data. Point the
applet tooling at the exact source version with `JETPACS_REFERENCE_ROOTS`, a
JSON object from stable namespace to directory. Then use that namespace as a
`jetpacs_list_api` category, `jetpacs_describe_symbol` for exact definitions,
or `jetpacs_summarize_elisp` on `NAMESPACE/file.el`. Every reference root is
read-only, non-overlapping with the workspace, and absent from trusted
runtime `load-path`; inspection and execution remain separate authorities.

Org is the preconfigured example. `JETPACS_ORG_SOURCE` exposes the exact Emacs
Org checkout as `org/`, so an Org-backed applet can inspect `org-entry-get`,
`org-element-context`, `ob-*`, and `ox-*` definitions without prefix guesses.
Magit, Denote, Gnus, Eglot, or an application-specific package uses the same
generic mechanism rather than requiring a new MCP tool or architecture.

The package-source mechanism makes semantic adapters extensible, not the wire
vocabulary infinite. If the desired skin cannot be represented with the
current nodes, members, descriptors, builtins, methods, or capabilities, add
that primitive to EBP/Jetpacs/Companion and its contract-derived tooling. Do
not encode a missing native concept as a pile of app-specific action names.

## Tests

Start with offline ERT tests:

- builder output and edge/empty/error states;
- action status, durability, and idempotence;
- every finite dynamic action emission and zero unresolved literal emissions;
- package adapter behavior against representative bound package state;
- owner and surface scope rejection;
- registration/unregistration symmetry;
- capability fallback and negotiated limit behavior; and
- prompt deferral outside dispatch.

Then add a narrow device smoke test for the actual gesture, native rendering,
state round trip, reconnect, and offline behavior. Android compilation alone
does not prove an applet flow.

The MCP validator is a fast static gate, not a proof of semantics. It catches
direct hazards and incomplete lifecycle structure; ERT, EBP conformance tests,
and device smoke tests establish behavior.
