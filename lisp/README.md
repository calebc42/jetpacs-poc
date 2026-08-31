# Jetpacs Components catalog

**Jetpacs Components** is the separate Elisp-authored reference app for
Jetpacs' own emerging design language. It coexists with the **Glasspane
Material 3 Catalog** and does not rename, replace, or add entries to that
Material inventory.

The catalog demonstrates five real `jetpacs.components` nodes plus scoped
presentations of two canonical EBP controls:

- **Action** — one full-width command target using the ordinary action path;
- **Choice** — one full-row checkbox target with Emacs-owned boolean state;
- **Tabs** — a controlled, string-valued tab strip with fixed, scrollable,
  navigator, adaptive, legacy-compatible, and lazy-list-pinned presentations,
  native tab semantics, and an injected selection action;
- **Section Navigator** — a pinned hierarchical table of contents with levels
  1–6, Emacs-owned selection, and command-driven scrolling to one direct keyed
  heading;
- **Panel** — labeled containment whose heading and descendants remain
  independently accessible;
- **Text Field** — canonical `text_input` behavior presented by the
  Foundation-only `JetpacsTextField` while inside `jetpacs.scope`;
- **Editor** — the complete local `editor` tier plus one real synchronized
  `editor`, including multiline and single-line input, save/enter actions,
  published local drafts, syntax, logical line numbers, toolbar snippets and
  line operations, autofocus, read-only, disabled, chromeless states,
  completion, lazy candidate documentation, fontification, diagnostics,
  eldoc, and synchronized editor commands.

Home remains a concise seven-entry index and now exposes the same four-mode
language as an inspection-first playground for the catalog program itself:

- **Preview** is the current live component index;
- **Visual** reveals the live root structure and drill-down path through the
  component registry, entries, actions, and detail routes;
- **Lisp** presents a read-only, inert, deterministic program manifest derived
  from that structure; and
- **Source** assembles the curated exact authored forms that implement the
  registry, Home builder, navigation, projection dispatch, and app
  registration.

Root inspection is deliberately separate from component authoring: it neither
mutates nor replaces any component draft, and it is not an arbitrary visual
program editor. Each detail offers four synchronized projections around one
process-local, versioned component specimen:

- **Preview** keeps the full reference page and renders the live specimen;
- **Visual** edits the specimen through schema-driven controls for its
  component properties, universal attributes, Semantics, actions, and Editor
  toolbar;
- **Lisp** edits the same specimen as one canonical, EBP-shaped Lisp data form;
- **Source** preserves the read-only exact authored Preview builder and its
  collapsed canonical EBP snapshot.

The four projection controls are themselves fixed Jetpacs Tabs rather than a
row of button-shaped filters. Each strip is a direct `lazy_column` child with
pinning enabled, so Preview/Visual/Lisp/Source remains available while its
catalog body scrolls. As with Material-style sticky headers, a later pinned
peer pushes the earlier strip off; this is visible when an editable navigator
specimen is also pinned. The selected projection and draft are remembered
independently per component through navigation and transport reconnects, until
catalog unregister or process exit. Visual and Lisp share one normalized
document: a successful edit updates both, while invalid Lisp remains available for
correction and the live specimen keeps the last valid document. Lisp input is
parsed as exactly one bounded inert form with reader evaluation disabled; it
is never evaluated.
**Reset** restores the authored default, and copy actions export canonical
Lisp, authored source, or canonical EBP. Drafts have no persistence or edit/undo
history; the separate redacted action trace is bounded to the current session.

Panel deliberately retains a fixed Text child followed by a Jetpacs Action
child. This catalog is not a general EBP tree composer; arbitrary-tree editing
remains a separate exploratory spike.

Tabs Visual editing includes one atomic **Presentation** choice. It stores
either a closed fixed/scrollable/navigator/adaptive variant or legacy state;
compiled renderer IR derives the compatible `scrollable` boolean without
putting that shadow member into a variant-authored Lisp document. Tabs and
Section Navigator both expose a real **Pin while scrolling** boolean toggle.
Their editable roots stay direct lazy-list items, separate from labels and
safety controls, so enabling the option produces valid live sticky specimens
in every editable projection.

Section Navigator Visual editing keeps every option as an ordered
**Label / Value / Level** row. Rename and deletion update the controlled value
atomically, and Level accepts only integers 1–6. The fixed reference fixture
is a command-driven table of contents: selecting an option commits its value
in Emacs, rebuilds the page, and marks exactly one direct, stable-key heading
with `scroll_here`. In application code, `:pinned t` has the same direct-child
requirement for either navigator; omit it or author `:pinned :json-false` for
ordinary placement under another valid parent.

Source and canonical EBP displays are each bounded to 20,000 characters and
mark truncation explicitly. When authored source cannot be recovered, Source
labels the loaded-function representation or unavailable source honestly.
Canonical EBP failure is isolated so the other projections remain usable.

The app declares `:requires-extensions '("jetpacs.components")`, owns the
`jpcatalog` surface, and registers only bounded `jpcatalog.*` actions. Text
changes use the ordinary state-before-action path without refreshing the
active IME session. Non-secret submits retain only their demonstration value.
The secure demonstration sends its value solely through `capture_fields`,
records only its scalar length, and destroys the volatile Elisp string in
place. `M-x jetpacs-component-catalog` returns to its Home screen without
discarding remembered per-component projection or authoring state.

Authored specimen actions are trace-only by default. Trace history is bounded
and retains only redacted metadata and value shapes, never raw arguments or
captured values. **Arm real actions** requires confirmation and current
compatibility; only dispatchable drop-policy remote actions and advertised,
context-valid builtins can run. Queue and wake policies always remain
trace-only. Editing, reset, or reconnect disarms the specimen, and any current
compatibility loss forces its projection back to trace-only.

The local Editor readout likewise avoids refreshing while a draft is changing.
It retains only a 72-column preview and character count, then refreshes after a
save or Enter action. The synchronized example authors the canonical
`document` member and binds it through the real `ebp-sync` module to a
process-volatile in-memory Emacs Lisp buffer. It enables the production
completion, font-lock, Flymake, eldoc, and editor-command riders while keeping
Eglot disabled so the deterministic fixture never starts an external language
server. Its catalog CAPF supplies bounded candidates, fixed kinds, and a lazy
documentation buffer alongside the mode's ordinary tooling. It creates no
offline draft or durable file. The buffer and documentation survive transport
reconnects only within the process and are detached and destroyed when the
catalog unregisters.

The editable Editor specimen can also declare `document`. The catalog owns a
separate process-volatile buffer for that exact `(document, editor-id)` pair,
rejects collisions rather than detaching another owner, and attaches it only
when the current Companion admits synchronized app editors. Its authored
`value` is the new-session seed; explicitly editing that value updates the
owned live buffer through the real synchronization path.

The catalog deliberately exercises the real application loop: one Action
increments Emacs state exactly once, Choice commits the injected boolean,
Tabs commits the injected option string, and Section Navigator commits a
destination and issues a keyed scroll command before requesting a refreshed
document. Native interaction state remains
receiver-owned; catalog projection and authoring state, plus application
decisions, remain in Emacs.

Every catalog body is wrapped in the invisible `jetpacs.scope` selection
boundary while its Glasspane chrome remains outside. The app composition root
installs the Phase 3 `text_input` and Phase 4–6 `editor` canonical overrides
for this app-only scope. Canonical nodes outside the boundary and dialogs
continue through Glasspane Material.

Run its focused authoring gates from the repository root:

```sh
test/run-tests.sh
```

Jetpacs' aggregate `test/run-tests.sh` additionally checks the generated core
authoring vocabulary. Android unit, screenshot, and device-semantics commands
are maintained in the Jetpacs repository's `companion/TESTING.md`.
