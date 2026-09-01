# Jetpacs forms, determinism, and debugging

Jetpacs has one screen representation, not a source model plus a generated UI
model. A builder returns ordinary Elisp data: keyword plists, vectors, scalar
values, and named action descriptors. That datum is the intermediate
representation, the inspectable value, and the input to the wire serializer.

```text
applet forms
    │ native Elisp reader (static tools; never evaluates)
    ▼
registrations + exact source forms ──> hashed applet manifest

trusted builder + explicit current context
    ▼
node plist/vector datum
    ├──> shell structural analysis
    ├──> Jetpacs sender gates
    ├──> devtools Lisp inspector/edit/re-push loop
    └──> canonical JSON ──> EBP ──> native Companion rendering
```

There is deliberately no parallel AST or code generator between a widget
constructor and EBP. `jetpacs-devtools-capture-spec` obtains the same degraded
datum the normal shell build obtains. `jetpacs-shell--analyze-spec` walks that
datum once for node types, builtins, constrained features, identifiers,
duplicate identities, depth, node/child totals, and negotiated aggregate
counts. `jetpacs-node->canonical-json` recursively sorts object keys and emits
compact UTF-8 JSON for byte comparison with EBP goldens.

## What “deterministic” means

A screen is deterministic when the same explicit and implicit inputs produce
the same canonical EBP bytes. Relevant inputs include:

- loaded source and registrations;
- Emacs application state and any Org buffers/files read by the builder;
- current surface, navigation, and reconciled device state;
- the negotiated target profile, capabilities, limits, and window class; and
- any locale, theme, or user option the builder deliberately observes.

Time, randomness, network responses, file writes, prompts, process launches,
and registry mutation are not legitimate hidden builder inputs. Move those
effects into actions or background state producers; make the resulting state
the builder's visible input. Stable node IDs and keys must derive from stable
domain identity, never traversal order that can vary.

`jetpacs_check_render_determinism` builds a trusted live surface twice,
canonicalizes both results, and compares their SHA-256 digests and bytes. It
also reports a digest of the observable runtime context. Equality is strong
regression evidence for that exact moment; it is not a proof over future
state, time, or I/O. Test builders as ordinary pure functions with controlled
state to cover the rest of the input space.

## The bounds actually checked

Offline runtime inspection enforces every bound that depends only on the
datum: unique/valid identity, retained-variant rules, and the loaded sender's
fixed depth, node, child, and retained-variant ceilings. The report names the
exact values read from that runtime (currently depth 20, nodes 10,000,
children 10,000, and eight retained variants), so the checker does not carry a
second copy of the contract constants. It also reports the four document
aggregates (`max_rich_spans`, `max_table_cells`, `max_chart_points`, and
`max_canvas_ops`) but cannot judge negotiated ceilings without a Companion
welcome.

In a connected session the checker invokes the real, non-sending Jetpacs
sender gates against the live client:

1. advertised node types, builtins, and constraining features;
2. retained-variant rules;
3. surface capability;
4. amendment policy, including wake and synchronized-editor constraints;
5. fixed and negotiated structural, aggregate, editor, and frame limits; and
6. identifier validity plus global ID and sibling-key uniqueness.

The normal push remains authoritative because only it owns navigation/stale
snapshot handling, revision allocation, transport, and device acceptance.

## The debugging loop

The screen is data, so debugging stays in normal Emacs territory:

```elisp
(setq spec (jetpacs-devtools-capture-spec "app:notes"))
(pp spec)
(jetpacs-node->canonical-json spec)
(jetpacs-shell--analyze-spec spec)
```

`jetpacs-devtools-inspect` presents the fresh plist itself through Jetpacs.
Edit the form and pass it to `jetpacs-shell-push` with `:spec` to test a
hypothesis through the same gates and renderer. Builder/gate failures can be
retained—with bounded count and lifetime—by the explicit devtools flight
recorder. Payload retention remains off by default.

For source-level diagnosis, the static manifest records each file hash,
feature dependency, provided feature, owner, app/root/action registration,
remote action emission, native builtin constructor use, exact normalized form,
definition signature, docstring, and source location. Emacs 30's
`read-positioning-symbols` attaches those locations to the symbols while
reading; the applet tooling records call cons identity and then strips the
position wrappers, leaving ordinary forms. Identical quoted examples and
executable calls therefore cannot steal each other's provenance. The generator
record includes both the applet-tooling version and exact Emacs reader version.
Because builtin detection joins applet calls to platform constructors, the
manifest also hashes the active EBP contract and the exact platform files on
those constructor paths under `analysis-inputs`; derived evidence never has an
unrecorded source dependency. The manifest digest covers all of that data. A
diff therefore
answers both “what bytes changed?” and “what declared behavior changed?” If a
complete manifest exceeds the MCP result bound, the same call returns a
digest-bearing section index. Section pages repeat the digest, exact range,
and next offset and contain only whole records—canonical data is never cut in
the middle of an Elisp form or docstring. If the repeated digest changes
between calls, source changed during retrieval; restart at the new index.

## Packages are inspectable source, not model lore

`JETPACS_REFERENCE_ROOTS` maps arbitrary stable namespaces to exact package
source directories. The applet tooling exposes each as read-only
`NAMESPACE/...` paths, lazily indexes top-level definitions, and hydrates an
exact match through the native Elisp reader. It never guesses APIs from model
memory and never widens the executable applet boundary. This is the same
mechanism for Magit, Denote, Gnus, Eglot, or an application-specific package;
adding a package does not add a package-specific MCP implementation.

Org remains the automatically discovered example, not a special semantic
case.

Set `JETPACS_ORG_SOURCE` to the Emacs checkout's `lisp/org` directory. The
applet tooling exposes it under the separate read-only `org/` namespace, uses
the native Elisp reader to hydrate exact definitions and docstrings, and never
widens the applet workspace boundary. On this checkout the discovered source
is:

```text
/home/calebc42/workspace/emacs/emacs/lisp/org
```

Every reference root is for inspection, not runtime injection. The trusted
launcher does not add one to `load-path`, because an external checkout may
target a different Emacs or package revision; applet execution uses the
dependencies supplied by its actual trusted runtime graph.

That makes the actual Org implementation available when an applet depends on
`org-entry-get`, `org-element-context`, parsing, agenda, or other behavior.
Applets should call those real APIs; tooling should describe their current
definitions instead of carrying a handwritten paraphrase.

Static action reconciliation follows the same principle. It derives
registrations and emissions from current Elisp forms and native builtins from
the active contract plus platform constructors. It can prove that a literal
name has a source provider, expose an unresolved spelling, and locate a
dynamic expression; it cannot prove which branch a dynamic expression takes
or that a source provider was loaded. ERT and trusted runtime inventory close
those two bounds.

## Trust modes

The default MCP is static. It reads Elisp forms but does not load the applet,
macroexpand it, write files, start processes, or offer evaluation.

The trusted runtime launcher is intentionally separate. It requires
`JETPACS_TRUSTED_APPLET`, resolves that `.el` file inside the workspace, loads
the real Jetpacs runtime and applet, and only then advertises live inventory
and render-check tools. Loading an applet executes arbitrary Elisp; use that
mode only for code you trust.
