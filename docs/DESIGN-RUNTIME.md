# Elisp-authored design runtime

Status: implemented as an experimental, default-off Companion feature. The
receiver advertises it for app surfaces only when the user enables
**Experimental Elisp design runtime** and reconnects.

This document is an implementation map and a statement of durable design
decisions. The `jetpacs.design` extension remains Jetpacs-specific; it does not
add Jetpacs, Compose, Material, or Android concepts to EBP.

## Ownership map

| Concern | Owning source |
|---|---|
| Extension schema and fixed bounds | [`../jetpacs-components/renderer-extensions/jetpacs-design.json`](../jetpacs-components/renderer-extensions/jetpacs-design.json) |
| Elisp node and value builders | [`../jetpacs-components/lisp/jetpacs-components/jetpacs-design.el`](../jetpacs-components/lisp/jetpacs-components/jetpacs-design.el) |
| Profile validation, registry, persistence, and selection | [`../jetpacs-components/lisp/jetpacs-components/jetpacs-design-profiles.el`](../jetpacs-components/lisp/jetpacs-components/jetpacs-design-profiles.el) |
| Platform baseline profile and presentation function | [`../jetpacs-components/lisp/jetpacs-components/jetpacs-design-baseline.el`](../jetpacs-components/lisp/jetpacs-components/jetpacs-design-baseline.el) |
| Toolkit-neutral parsing and compilation | [`../jetpacs-components/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/DesignModel.kt`](../jetpacs-components/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/DesignModel.kt) |
| Foundation scope renderer and style adapter | [`../jetpacs-components/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsDesignRenderer.kt`](../jetpacs-components/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsDesignRenderer.kt), [`JetpacsDesignStyleAdapter.kt`](../jetpacs-components/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsDesignStyleAdapter.kt) |
| Canonical-node override registry | [`../jetpacs-components/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsDesignTextRenderer.kt`](../jetpacs-components/renderer/jetpacs/src/main/kotlin/com/calebc42/jetpacs/renderer/jetpacs/JetpacsDesignTextRenderer.kt) |
| Companion admission and renderer installation | [`../jetpacs/companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/CompanionRenderer.kt`](../jetpacs/companion/app/src/main/kotlin/com/calebc42/jetpacs/companion/CompanionRenderer.kt) |
| Host presentation and bounded scaffold traversal | [`../jetpacs/emacs/jetpacs-chrome.el`](../jetpacs/emacs/jetpacs-chrome.el), [`jetpacs-shell.el`](../jetpacs/emacs/jetpacs-shell.el) |
| Interactive profile authoring | [`../jetpacs-component-catalog/lisp/jetpacs-design-lab.el`](../jetpacs-component-catalog/lisp/jetpacs-design-lab.el) |

`jetpacs-design-vocabulary.el` and `JetpacsDesignVocabulary.kt` are generated
from the extension manifest. Change the manifest and run its documented
projection check; never edit either generated mirror as the source of a
vocabulary change.

## Data flow

```text
Emacs profile + ordinary screen IR
          │
          ▼
jetpacs.design_scope / styled / pressable nodes
          │  canonical JSON through the normal sender gates
          ▼
Companion admission + bounded design compiler
          │
          ├── Foundation extension and canonical-node renderers
          └── neutral theme/chrome values consumed by Material chrome
```

There is one screen model. Elisp builders return the same plist/vector IR that
the inspector, canonicalizer, sender, EBP session, and Companion consume. The
design runtime does not translate source into a parallel UI AST and never
sends executable Elisp.

## Durable invariants

- EBP stays implementation-neutral. The extension carries bounded declarative
  data and named semantic actions only.
- Emacs owns registered presets, user snapshots, the active profile ID, and
  application decisions. Android receives a compiled scope and owns native
  presentation state.
- The Companion installs extension ownership, typed semantic validation,
  profile advertisement, extension rendering, and canonical overrides as one
  configuration. It must not advertise a partial installation.
- The runtime is opt-in. With it disabled, cached design nodes remain
  structurally recognizable for safe child traversal, but the Companion does
  not claim, validate, or execute the extension.
- A profile may alter presentation, not node meaning, action order, state
  ownership, or accessibility obligations.
- A canonical override must honor the complete node shape it accepts. When a
  Foundation presentation cannot preserve a member or variant, it declines so
  the installed Material renderer continues to handle that node.
- The modifier supplied to a canonical override belongs on its outermost
  layout node. Parent data such as `weight` must reach the direct parent.
- Unordered maps are sorted before serialization, and the manifest/profile
  bounds are enforced at the layer that owns them.

## Profiles and selection

A versioned profile is inert data containing tokens, typography, styles,
motions, component-slot bindings, and optional EBP theme-role bindings.
Registered presets are immutable: repeating an identical registration is
idempotent, while changing an existing preset ID is an error. User profiles
are validated snapshots stored through Customize; the current implementation
bounds them to 16 and bounds a compiled profile to 256 KiB.

`jetpacs-design-active-profile-id` selects one global user choice. A nil value
lets an app use its registered default. Host screens use `jetpacs.baseline` as
their fallback through `jetpacs-design-present`; the presenter returns the
screen unchanged when the receiver has not advertised the extension or the
screen was already presented by its app.

The baseline deliberately uses theme roles instead of literal colors, system
type for ordinary content, Plex Mono for code and metadata, and binds every
closed component slot. A profile may publish all 13 EBP color roles into the
renderer scope. Material chrome consumes those resolved roles without making
the design extension a Material dependency; polarity, density, layout
direction, and syntax colors remain properties of the receiver theme.

## Rendering coverage and fallback

The source currently pins three extension nodes, 99 component-style slots,
and canonical overrides for 17 node types. Those numbers are a source-audited
snapshot, not an independent registry:

| Pin | Witness |
|---|---|
| Extension targets, schema, maps, and bounds | `renderer-extensions/jetpacs-design.json` |
| Slot mirror equality | `JetpacsDesignVocabularyTest.kt` |
| Exact canonical override set | `CompanionRendererTest.kt` |
| Baseline binds every slot | `test/jetpacs-design-test.el` |

The override set covers the high-value styled controls, text and editing
surfaces used by the host and component catalog. Shape-specific fallbacks are
intentional. Examples include the editable form of `dropdown`, button or icon
variants whose state/decoration the Foundation implementation cannot express,
and canonical nodes with no design override at all. Layout-only nodes continue
through the ordinary renderer unless styling requires an override.

The design scope also republishes neutral chrome styles for tabs, rails,
drawers, and snackbars. Host chrome remains attached when a screen is wrapped:
the shell finds the scaffold through a bounded chain of single-child wrappers,
applies drawer/dock/FAB/global injections there, and rebuilds the wrapper chain.
Back handling uses the same seam. Jetpacs foundation code recognizes wrapper
shape and does not name `jetpacs.design` or another downstream applet.

When `jetpacs.scope` appears inside an active design scope, the components
renderer preserves the enclosing design scope. Outside one, its original
component-scoped field/editor selection remains in effect.

## State and accessibility

Interactive overrides keep application state in the same place as their
canonical counterparts. For controlled inputs such as switches and closed
dropdowns, reconciled receiver state outranks the authored seed, and a user
change publishes `state.changed` before the semantic action. Presentation-only
state such as an open menu, a disclosure's expansion, or the viewed calendar
month stays local and is keyed by presentation identity.

Interactive controls are tested as one meaningful target with an authored
accessible name, including 48 dp touch targets where applicable. Gesture
alternatives such as reveal-swipe actions use the same typed action path and
inject only the direction the renderer is authorized to contribute.

## Design Lab

The Design Lab edits a validated process-local draft. Structured sections use
dropdowns for closed vocabularies, switches for booleans, suitable keyboards
for numeric values, and stable-path disclosures for existing entries. The
Source section is the structural editor: it can add or remove bounded tokens,
typography roles, styles, rules, motions, and bindings by reading one inert
form. It does not evaluate Lisp.

Edits are digest-addressed so a stale surface cannot mutate a newer draft.
Save As creates a user snapshot; Apply validates, persists, and selects an
existing user profile; immutable presets cannot be rewritten. Rejection text
occupies a stable node position so an error does not reset disclosure state.

## Known limitations

- The runtime remains experimental, default-off, and limited to app surfaces.
- Canonical coverage is deliberately partial. Unsupported shapes fall back to
  the ordinary renderer, so a scope can still contain Material-presented
  controls.
- The Design Lab's structured sections edit existing leaves. Adding, removing,
  or reordering profile structure still requires the inert Source editor, and
  the structured binding editor still represents ordered styles as
  comma-separated text.
- The shared [`Semantics.kt`](../ebp-poc/ebp-compose/renderer/model/src/main/kotlin/com/calebc42/ebp/renderer/model/Semantics.kt)
  projection currently exposes a bare icon identifier as an accessible name
  even when the icon is decorative. EBP Section 16.4 requires a derived name
  for interactive nodes; resolving the decorative-icon behavior belongs in
  `ebp-compose`, not in a host or applet workaround.
- A canonical card's accessibility swipe action still follows the generic
  [`EbpSemantics.kt`](../ebp-poc/ebp-compose/renderer/compose/src/main/kotlin/com/calebc42/ebp/renderer/compose/EbpSemantics.kt)
  action path without the renderer-injected `direction` argument that its
  finger gesture supplies. The Foundation list-item path injects the direction
  correctly. These paths must be reconciled before claiming gesture and
  accessibility-action equivalence for cards.
- The historical Grove source and screenshot fixture are absent from this
  workspace, so the old tablet results are not a current end-to-end parity
  gate.

## Verification and claims

Use the verification ladder in [`../AGENTS.md`](../AGENTS.md). The focused
witnesses for this runtime are the extension projection check, Elisp design
and Design Lab ERT suites, renderer model/unit/screenshot tests, Companion
installation tests, and the device semantics/presented-screen tests. A host
build does not establish rendering, accessibility, input reconciliation, or
gesture behavior; those claims require the corresponding device gate.

The dated Grove and UI-polish device records are retained as historical
evidence in [`HISTORY.md`](HISTORY.md); rerun the owning gates before making a
current render claim.
