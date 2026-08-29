# Jetpacs text-editing plan

Status: Phase 4 implemented; manual accessibility acceptance pending
Date: 2026-08-28

## Outcome and ownership

Deliver a Jetpacs-owned Foundation text-field and editor family that is
visually distinct from Glasspane's Material presentation while retaining the
existing EBP `text_input` and `editor` semantics, synchronization protocol,
durability rules, and Emacs ownership.

The owning tree is `llm-poc-3`. Toolkit-neutral models and behavior belong in
`:renderer:model` and `:renderer:compose`; Jetpacs presentation belongs in
`:renderer:jetpacs`; authenticated action, state, and editor traffic remains
owned by `:wire` and the application bridge. Elisp continues to author
declarative EBP rather than executable UI behavior.

## Invariants

- EBP remains implementation- and Jetpacs-neutral.
- The canonical wire nodes remain `text_input` and `editor`; no
  `jetpacs.text_input` or `jetpacs.editor` aliases are introduced.
- Emacs owns application state and application decisions.
- The Companion owns accepted presentation state, native editing state,
  durability, and synchronized-editor delivery records.
- Glasspane remains Material by default and its existing screenshots remain
  pixel-identical.
- Jetpacs editing has no Material dependency.
- A visual renderer never reimplements action admission, input reconciliation,
  editor session policy, or password persistence policy.
- Generated vocabulary is projected from its owning contract or extension
  manifest and is never edited as authority.

## Recommended architecture

Canonical EBP nodes feed shared editing controllers, then select a downstream
presentation:

```text
Canonical EBP text_input / editor
                 |
       shared editing controllers
  (:renderer:model + :renderer:compose)
                 |
       +---------+---------+
       |                   |
Glasspane Material 3   Jetpacs Foundation
outside Jetpacs scope  inside jetpacs.scope
```

Add an invisible downstream extension node named `jetpacs.scope`. It contains
ordinary EBP children and selects Jetpacs overrides for canonical nodes below
it. The scope carries no application state or accessibility semantics, never
merges or hides descendants, and degrades to its children on a receiver that
does not implement the extension. The nearest scope wins if scopes are nested.
The initial scoped registry deliberately accepts only the generated eight-node
EBP Core Node Set. The first visual implementation overrides `text_input`.
Optional canonical nodes, including `editor`, require a later explicit
registry expansion after the foundational core set has matured.

This keeps Glasspane's current renderer selected outside the scope and avoids
coupling design selection to `jetpacs.panel`, which would make standalone
fields and editors awkward.

## Phase 0: reconcile the governing contract

The normative `ebp/SPEC.md` defines a smaller `text_input` schema than
`ebp/contract.json`. The projection, generated vocabulary, public Elisp
builder, validator, and Material renderer currently also recognize these
members that are absent from the normative prose:

- `variant`
- `is_error`
- `supporting_text`
- `prefix` and `suffix`
- `leading_icon` and `trailing_icon`
- `max_length`
- `selection`
- `hide_keyboard_on_submit`
- `content_padding`
- `mask`
- `filter`

Phase 0 must:

1. Audit every use, validation rule, test, example, and retained-document risk
   for each member.
2. Classify each member as toolkit-neutral behavior/semantic content,
   Glasspane presentation, or obsolete draft residue.
3. Specify every retained neutral member normatively, including types,
   defaults, validation, reconciliation, Unicode units, byte accounting,
   password interactions, and action ordering.
4. Move renderer-specific members behind the Glasspane extension, with an
   explicit compatibility transition if existing documents use them.
5. Regenerate Kotlin and Elisp vocabulary from the corrected authority.
6. Keep protocol major 3; advance draft or contract metadata only when the EBP
   amendment policy requires it.
7. Add accepted and rejected goldens and run the EBP validator plus every
   affected cross-language conformance test.

Jetpacs will implement only fields that emerge from this process as normative
neutral behavior. Contract projection is not authority merely because current
source consumes it.

### Phase 0 outcome

The audit classified all thirteen members as toolkit-neutral editing
semantics, field structure, or presentation intent; none required migration to
a Glasspane-only extension. EBP amendment #180 now defines their exact
validation, reconciliation, Unicode-scalar, transformation-order, error, and
accessibility behavior. Contract format advanced from 8 to 9 while protocol
major remains 3.

The corrected authority is projected into generated Kotlin and Elisp
vocabulary. A shared 27-case golden is replayed by the Python reference
validator and Kotlin receiver tests, while Elisp authoring tests cover the
same accepted and rejected shapes. Glasspane's renderer now uses the common
scalar-safe input and mask rules, composes selection with content padding, and
projects maximum length and error precedence without changing pixels.

Phase 1 still owns the action-host extraction and typed one-shot action
outcome required for contract-correct `clear_on_submit`; Phase 0 deliberately
does not infer action admission from the current `Unit` callback.

## Phase 1: extract a neutral editing host

Split the Material-owned renderer bridge into:

- a toolkit-neutral action/state host;
- a toolkit-neutral editor host; and
- a small Material-only host for Material-specific facilities.

Move neutral input displays, draft epochs, editor mirrors, completion offers,
candidate documents, raw annotation ranges, and scalar/UTF-16 conversion
models out of `:renderer:material3`. Keep palette, typography, diagnostic
decoration, and popup presentation in each design renderer.

Add shared `TextInputController` and `EditorController` implementations to
`:renderer:compose`. They own state seeding, reconciliation, normalization,
host dispatch, remote mirror adoption, and lifecycle. Material and Jetpacs
become alternate views over the same behavior.

Action dispatch must expose a typed one-shot outcome. The current `Unit` seam
cannot implement `clear_on_submit` correctly because the value may clear only
after the engine reports the outcome that the EBP contract considers safe.
The renderer must not infer that conclusion from connectivity or remote timing.

This phase has no intentional visual change. Existing Glasspane screenshot
references are an acceptance gate.

### Phase 1 outcome

The renderer boundary is now toolkit-neutral. `:renderer:model` owns the
action/state and synchronized-editor hosts plus input displays, editor mirrors,
completion data, raw annotations, and scalar/UTF-16 conversion. The
Material-only host extends those interfaces solely with Glasspane presentation
facilities. `:renderer:compose` owns shared state-based `TextInputController`
and `EditorController` implementations, and the existing Material field and
editor renderers consume them.

Every handed-off action occurrence now receives one typed terminal outcome.
Durable queue commit and live `accepted` or `duplicate` replies are the only
safe-admission evidence; local builtins complete separately, and every local,
transport, storage, queue, or remote refusal is explicit. Callbacks cross the
Android bridge on the main thread exactly once. `clear_on_submit` waits for
safe admission and clears only when no newer edit has occurred.

Normal input still publishes accepted draft state before its paired action.
Password input uses unsaveable state, never emits `state.changed`, carries its
secret only in the owning occurrence's capture fields, blocks duplicate
submission, and erases on every terminal outcome or disposal. Dialog password
capture is also cleared from its volatile capture layer. Editor changes are
derived from the state API's change list, converted losslessly between UTF-16
and Unicode-scalar offsets, and remote mirror adoption cannot echo a local
delta. Local publication honors `publish_state`; local and synchronized
editors enforce their distinct `max_field_bytes` and `max_editor_bytes` JCS
budgets.

Focused wire, model, controller, renderer, and app tests cover the new seams;
device instrumentation drives the controllers through real state-based Compose
fields. All 11 existing Glasspane screenshot references validate unchanged.

## Phase 2: add scoped core-renderer overrides

Extend the Compose renderer registry with scoped overrides that are separate
from extension-node ownership:

- Extension-node ownership remains singular.
- Core overrides are keyed by an admitted design scope.
- An override does not change validation or advertise a second core node.
- It activates only inside `jetpacs.scope`.
- Core nodes outside the scope continue through Glasspane Material.
- Duplicate overrides for one scope and core node fail when the composition
  root is assembled.
- Scope activation cannot cross a surface or dialog boundary.
- Registrations are limited to the generated `CORE_NODE_SET`; optional EBP and
  downstream extension nodes fail composition-root validation.

Add `jetpacs.scope` to the `jetpacs.components` extension manifest and
regenerate its Kotlin and Elisp vocabulary. Implement app surfaces first;
advertise dialog support only after secure-field and dialog-lifecycle tests
pass.

Phase 2 installs the selection seam but no production core override. Existing
Jetpacs catalog bodies enter the scope to exercise the negotiated traversal
path without changing their pixels; Glasspane chrome remains outside.

## Phase 3: implement `JetpacsTextField`

Create a public Foundation component in `:renderer:jetpacs`, backed by the
shared text-input controller. Prefer Compose's state-based text APIs:
`TextFieldState` retains text, selection, and composition, while
`InputTransformation` applies restrictions atomically to keyboard, paste,
drop, autofill, and IME changes.

References:

- <https://developer.android.com/develop/ui/compose/text/user-input>
- <https://developer.android.com/develop/ui/compose/text/migrate-state-based>

Required behavior:

- authored, empty, locally dirty, acknowledged, reset, and restored states;
- single- and multi-line operation;
- exact U+000A removal for single-line input;
- minimum and maximum line bounds;
- keyboard type and IME action;
- label and hint behavior;
- enabled and disabled behavior;
- `state.changed` before the corresponding `on_change` action;
- `on_submit` value injection;
- clear only after the engine-authorized result;
- autofocus only for a new presentation identity; and
- any selection, mask, filter, maximum-length, supporting-text, or error
  behavior retained by Phase 0.

Normal text is restored through the accepted draft store, not Compose state
alone.

Use Foundation `BasicSecureTextField` for a separate password path. A password
is never seeded from authored JSON, saved, retained, logged, screenshotted, or
sent through `state.changed`. It is captured only by the owning submit
occurrence and erased on every contract-required terminal lifecycle event.
`max_field_bytes` applies while it exists in volatile memory.

The visual direction is compact and structural: a restrained work surface,
crisp focus boundary, explicit label and supporting/error line, and Jetpacs
typography and spacing rather than a recreation of a Material field.

### Phase 3 outcome

`:renderer:compose` now owns one toolkit-neutral text-input presentation and
binding seam. It parses the canonical `text_input` node, selects authored or
accepted draft state, retains selection and composition through the shared
controller, applies scalar-safe line, filter, mask, and maximum-length rules,
and dispatches every change or submit through the ordinary action host. The
same seam now drives Glasspane's Material field and the Jetpacs field, so
capture, admission, durable queuing, offline policy, dialog rebinding, and
typed terminal outcomes are not presentation responsibilities.

`:renderer:jetpacs` supplies the public state-based `JetpacsTextField`, a
Foundation `BasicTextField`/`BasicSecureTextField` implementation, independent
Jetpacs tokens and syntax colors, and outlined and filled Compose Styles.
Labels, support and error text, affixes, and code-native icons are
presentation-only descendants of one editable semantics owner. Normal fields
publish accepted draft state before `on_change`; submit injects the current
value and clears only after safe admission. Secure fields have unsaveable
state, never seed or publish a password, capture the secret only in the owning
submit occurrence, block duplicate submission, and erase on refusal,
completion, or disposal.

The application composition root installs a scoped override for canonical
`text_input` only. It activates beneath `jetpacs.scope` on app surfaces;
unscoped fields and dialogs continue through Glasspane Material, preserving
the Phase 2 boundary and avoiding an unproved secure-dialog lifetime. A
shared, uncapped syntax-role projection keeps parsing toolkit-neutral while
each renderer owns its palette.

The Jetpacs Components catalog now has a live **Text Field** page covering
normal state/action ordering, filled decorations, phone filtering and masking,
multiline Elisp syntax, error and disabled states, and secure capture. Catalog
handlers retain only non-secret counters and the secure value's scalar length.
Six new Jetpacs screenshot references cover compact and expanded widths, dark
theme, 1.5x text, deterministic focus with an empty secure field, and RTL; all
existing Jetpacs and Glasspane Material references remain unchanged.

The EBP validator, warning-as-error Elisp compilation, the full Elisp suite,
all affected Kotlin unit suites, the broad APK and screenshot gate, and the
connected controller, scoped-dispatch, and nine-case Jetpacs semantics suites
pass. The verified APK and 115-file Elisp tree were installed on the Pixel
Tablet through `tools/onboard-tablet.sh`; the reconnected live accessibility
tree exposes the Jetpacs Components **Text Field** page and its editable
controls. The developer subsequently completed and passed the prescribed
TalkBack, Switch Access, hardware-keyboard, pointer, touch, rotation, restart,
offline-refusal, ordinary-submit, and secure-field manual acceptance checks;
the audit records these separately as user-reported evidence.

## Phase 4: implement the local `JetpacsEditor`

Begin Phase 4 by explicitly expanding scoped override eligibility from the
Core Node Set to canonical EBP-owned optional nodes. Continue to reject every
downstream extension-owned node; `editor` remains the sole canonical semantic
node and no `jetpacs.editor` alias is introduced.

Support the complete local-editor tier before synchronized editing:

- multi-line editing and selection;
- `single_line`, `min_lines`, and `max_lines`;
- independent `read_only` and `enabled` states;
- `on_save` and software-IME `on_enter`;
- `publish_state` and local draft reconciliation;
- monospace and syntax presentation;
- a shared-scroll line-number gutter;
- chromeless mode;
- toolbar items and snippets;
- autofocus by presentation identity; and
- preservation across compatible snapshots.

Run an early prototype proving that active IME composition, syntax styling,
diagnostic decoration, caret layout, and remote text adoption can coexist with
the selected Foundation state API. If a current experimental API cannot
represent annotation styling safely, isolate a narrow adapter rather than
falling back to a Material component.

Line numbers are presentation-only and do not become separate accessibility
nodes.

### Phase 4 implementation outcome

The scoped registry now admits canonical optional EBP nodes from the generated
node schema while continuing to reject downstream extension-owned and invented
types. The application installs one conditional local `editor` override under
`jetpacs.scope`; synchronized editors with `document` continue through the
canonical Material renderer until Phase 5. Both presentations consume the same
shared editor binding for state, byte limits, focus identity, publication, and
occurrence-time action values.

`:renderer:jetpacs` now supplies a public Foundation `JetpacsEditor`, local
syntax projection, shared-scroll logical line-number gutter, chromeless and
read-only/disabled states, and an accessible toolbar with line operations,
menus, snippets, long-press alternatives, and a named free-text prompt.
Compose Styles control only editor, gutter, and toolbar visuals; no Style
animates or owns text, caret, selection, composition, or layout state.

The Jetpacs Components catalog has a live **Editor** page covering multiline
save, single-line software-IME Enter, publication readout, syntax, line
numbers, toolbar transformations, read-only, disabled, chromeless, and
autofocus behavior. It explicitly labels synchronization, completion, and
authoritative diagnostics as later tiers. Six new editor references cover
compact and expanded widths, dark theme, focus/read-only, 1.5x text, and RTL;
all previously tracked Jetpacs and Material references remain unchanged.

The EBP validator, deterministic 10,000-case cross-language replay,
warning-as-error Elisp compilation, full ERT suite, broad Kotlin/APK gate, both
screenshot validators, and all five connected suites pass. The final APK and
115-file managed Elisp tree are installed on the Pixel Tablet. Live checks
proved save and software-IME Enter dispatch once, toolbar menu and prompted
snippet insertion, disabled/read-only behavior, named editable semantics,
autofocus-once, and exact draft preservation across portrait recreation; all
temporarily changed device settings were restored. TalkBack, Switch Access,
hardware-keyboard, pointer, and touch acceptance remain human-observation
checks and must be recorded before Phase 5 starts.

## Phase 5: add synchronized editing

Connect the Jetpacs editor to the existing `editor.sync` module:

- fresh session opening when a synchronized editor becomes present in
  `READY`;
- immediate read-only transition outside `READY`;
- no offline drafts, deltas, saves, completion, or commands;
- lossless Unicode-scalar/UTF-16 conversion;
- monotonic sequence handling;
- exactly-once local delta emission;
- remote apply without a local echo;
- composition-atomic remote adoption;
- stale detection and explicit resynchronization;
- caret and selection throttling;
- closure on removal, tombstone, document change, identity change, and
  disconnect; and
- session-count and editor-byte limits.

Model opening, ready, composing, awaiting reconciliation, stale,
offline-read-only, and closed as explicit testable states.

## Phase 6: completion, annotations, and tooling

After synchronization is correct, add:

- bounded completion candidates;
- selection dispatch exactly once;
- lazy candidate documentation;
- fontification using fixed contract role names;
- diagnostics with severity, message, and accessible error descriptions;
- eldoc/status presentation;
- toolbar editor commands; and
- a client-side syntax fallback while authoritative annotations are pending.

Transport models remain neutral. Jetpacs and Glasspane resolve the same role
names through their independent palettes.

## Styles and theming boundary

Compose Styles is experimental and stays entirely in `:renderer:jetpacs`:

- Public components accept `style: Style = Style`.
- Styles own background, border, padding, color, focus, hover, error,
  disabled, and read-only visuals.
- Modifiers and controllers own semantics, focus, input, gestures, and
  behavior.
- Jetpacs supplies `LocalTextSelectionColors` rather than relying on
  `MaterialTheme`.
- Caret, selection, text layout, and diagnostic positions do not animate.
- Compilation and screenshots pin the repository's selected Compose version.

Styles never become part of EBP or the shared editing controller contract.

## Elisp and catalog work

Keep `jetpacs-text-input` and `jetpacs-editor` as the sole semantic builders.
Add a downstream `jetpacs-component-scope` helper that wraps canonical children
in `jetpacs.scope`; do not create a second set of text semantics.

Phase 3 adds a **Text editing** catalog category with a **Text Field** page.
Phase 4 adds **Editor** after optional-node override eligibility exists. Across
the two pages cover purpose, anatomy, empty/filled/focused/error/disabled/secure
states, local and synchronized editors, line numbers, syntax, diagnostics,
completion, toolbar, offline read-only behavior, canonical Elisp/EBP source,
and a live action/state readout.

## Accessibility requirements

- One editable semantics node per field or editor.
- Universal EBP semantics attach to the interaction-owning bounds.
- The label supplies the accessible name without duplicating hint/supporting
  text announcements.
- Error, disabled, read-only, and password state is exposed correctly.
- Completion candidates and toolbar items are individually labeled.
- Completion and toolbar descendants are not merged into the editor node.
- Default hardware text-editing commands remain available unless EBP
  explicitly defines an override.
- TalkBack and Switch Access can edit, submit, inspect an error, choose a
  completion, and leave the editor.

## Verification sequence

1. `cd ebp && python3 validate.py` and affected golden/conformance tests.
2. Focused Elisp constructor, profile, canonicalization, byte-budget,
   password, and editor-session ERT.
3. Warning-as-error Elisp byte compilation.
4. `test/run-tests.sh`.
5. Shared controller, renderer registry, and no-Material-boundary unit tests.
6. Existing wire editor, lifecycle, action, persistence, and reconciliation
   suites.
7. Jetpacs unit and instrumented tests.
8. All 11 existing Material screenshot validations without reference updates.
9. New Jetpacs references for compact/expanded, dark, 1.5x text, focus,
   error, disabled, read-only, secure, line numbers, diagnostics, completion,
   and RTL.
10. The broad Companion gate documented in `companion/TESTING.md`.
11. Tablet deployment through `tools/onboard-tablet.sh`, which refreshes both
    the APK and Elisp installation.
12. Connected and manual IME, paste, selection, rotation, restore, reconnect,
    offline, accessibility, hardware-keyboard, mouse, and touch checks.

## Definition of done

- `:renderer:jetpacs` has no Material dependency or import.
- Jetpacs and Glasspane share editing behavior but not presentation.
- Glasspane remains Material by default with pixel-identical references.
- No duplicate EBP node or editor synchronization protocol exists.
- State precedes its action and every occurrence dispatches once.
- Input clearing follows the engine-authorized outcome.
- Remote applies never echo.
- Unicode scalar offsets and active IME composition remain lossless.
- Synchronized editors are read-only outside `READY`.
- Passwords are never persisted, published, or logged.
- Accessibility exposes one correct editable control.
- Both components are exercised in the tablet catalog.

## Deferred work

- CRDT or collaborative multi-writer editing
- remote cursors and presence
- huge-document virtualization
- rich embedded content
- generalized command/keymap models
- cross-platform focus routing
