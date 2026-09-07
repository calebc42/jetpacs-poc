# POC 3 architecture

Jetpacs is a spec-driven Android/Emacs system built on EBP 3. It combines an
Emacs-owned application layer with an Android Companion that persists accepted
presentation state, renders it natively, and durably returns typed events.

The normative cross-platform authority is
[`../../ebp/SPEC.md`](../../ebp/SPEC.md). This document owns
Jetpacs-specific dependency, persistence, rendering, navigation, and runtime
boundaries. [`PLAN-poc3-rebuild.md`](PLAN-poc3-rebuild.md) records current
status and completion gates; [`PLAN-room3-rebuild.md`](PLAN-room3-rebuild.md)
details the implemented durable store.

## System invariants

- EBP is implementation- and Jetpacs-neutral. It carries declarative data and
  named semantic actions, never executable Elisp or another host language.
- Emacs owns application state, application actions, and application policy.
- The Companion owns native presentation, negotiated Android integration,
  accepted presentation state, and its outgoing durable delivery records.
- The receiver is a renderer, not a second application implementation. It does
  not compile an app catalog or interpret app-specific action names in Kotlin.
- Dependency direction is EBP → Jetpacs platform → applets. Foundation code
  does not name a downstream applet.
- Builders return the real plist/vector SurfaceSpec IR. There is no parallel
  app, widget, tile, or automation UI AST.
- Builders are deterministic readers of explicit state. Effects occur in
  actions or background state producers.
- An `accepted` event is a durability conclusion. Jetpacs commits its outbox
  before sending; Emacs commits its independent receipt and recoverable work
  before returning `accepted`.
- Every emitted feature, node, builtin, identifier, aggregate, and byte count
  stays inside the negotiated target profile and fixed limits.

Room, Navigation 3, Compose, Material 3, Glance, and Android services are
Jetpacs implementation choices. None becomes an EBP requirement.

## Repository boundary

The standalone repository is a workspace composition root. Protocol and
renderer foundations, the Foundation design extensions, and applets have
independent owners in neighboring repositories. Jetpacs owns its optional
Material 3 implementation as an internal module. The complete graph and physical paths are
in [`REPOSITORY-BOUNDARIES.md`](REPOSITORY-BOUNDARIES.md).

The Elisp naming rule mirrors that graph:

> `ebp-` names code that depends only on Emacs and EBP; `jetpacs-` names
> product behavior that depends on the Jetpacs application boundary.

Canonical `ebp-` libraries now live in `../../../ebp.el` and
`../../../ebp-org`. Jetpacs' aggregate test runner checks their isolated
load closure and checks that Jetpacs foundation source does not acquire a
downstream application dependency.

## Companion module graph

`companion/settings.gradle.kts` currently composes 14 modules:

| Module | Responsibility and boundary |
|---|---|
| `:ebp-kmp` | Storage-neutral durable records, transaction SPI, reducers, and rollback-capable reference store; sourced from `ebp-kmp` |
| `:wire` | Transport, framing, generated EBP vocabulary, validation, sessions, triggers, and protocol handling; sourced from `ebp-kmp` |
| `:core:model` | Jetpacs read models and identifiers |
| `:core:database` | Room 3 entities, DAOs, database builder, migrations, and exported schemas |
| `:core:ebp-store` | Jetpacs' Room-backed implementation of the EBP durable-store SPI |
| `:core:data` | Read-only Room projections for product UI consumers |
| `:core:navigation` | Closed serializable Navigation 3 destination keys and policy |
| `:core:testing` | Shared Jetpacs read-model fakes and fixtures |
| `:renderer:model` | Toolkit-neutral contribution/profile registry, raw EBP readers, semantics, action/editor hosts, and editing models; sourced from `ebp-compose` |
| `:renderer:compose` | Compose Foundation reference rendering and shared input/editor controllers; sourced from `ebp-compose`, with no Material dependency |
| `:renderer:glance` | Restricted generic home-screen-widget renderer and profile |
| `:renderer:jetpacs` | Foundation-only `jetpacs.components` and optional `jetpacs.design` implementations; sourced from the in-repository `jetpacs-components/` module |
| `:renderer:material3` | Jetpacs Material 3 implementation and optional extension; owned locally in `companion/renderer/material3` |
| `:app` | Android composition root, Room/Nav shell, transport, platform adapters, and exact installed renderer selection |

The app may compose these modules; it does not transfer their authority. In
particular, `:wire` cannot depend on Room, Android, Navigation, Compose, or a
downstream renderer, and `:renderer:compose` cannot depend on Material.

## Runtime data flow

Accepted presentation follows one path:

```text
Emacs app state
  -> deterministic SurfaceSpec builder
  -> ebp.el session and EBP frame
  -> :wire validation/session handling
  -> :ebp-kmp reducer inside its transaction SPI
  -> :core:ebp-store Room transaction
  -> committed Room state
  -> selected target profile and native renderer
```

A durable action crosses two independent failure domains:

```text
Android gesture
  -> typed ActionDescriptor and state flush
  -> bounded Companion actor
  -> Room outbox commit
  -> EBP delivery/replay
  -> Emacs SQLite receipt + recoverable work commit
  -> accepted response
  -> Companion removes the resolved outbox record
```

A socket write, Android platform call, or Compose callback never occurs inside
a Room transaction. Platform work is represented durably where required and
is reconciled after commit.

## Durable state

The production Companion opens one `jetpacs.db` Room database. The current
schema is version 2 and is the only production store for accepted EBP state and
the Companion outbox. Records derived from Emacs are partitioned by pairing.
The database includes surfaces and drafts, queue state and issued event IDs,
reminders and receipts, trigger registrations/runtime, themes, platform
effects, widget bindings/tokens, pairing runtime, and crash-resumable
revocation state.

Legacy file stores and prototype databases are not imported, dual-written, or
used as a fallback. Emacs remains the authored-state authority and can re-push
desired state into a clean Companion store.

Session-only editor state, dialog/pie-menu state, navigation stacks, focus,
scroll positions, animations, and Emacs-side durable receipts do not enter
Room. See [`PLAN-room3-rebuild.md`](PLAN-room3-rebuild.md) for the schema and
transaction matrix.

## Renderer and profile ownership

EBP 3 advertises positive profiles per surface target. Extension identifiers
and namespaced node types are owned by downstream manifests; EBP transports
them opaquely. Admission requires both the node type and its owning extension.
The app derives welcome profiles from the same installed contributions used by
Compose dispatch, preventing an advertised/implemented split.

The normal app profile composes core Foundation rendering, Jetpacs Material
3, and `jetpacs.components`. Dialogs receive core plus Material; notification,
widget, and tile targets have their own narrower profiles. The optional
`jetpacs.design` runtime is default-off, app-only, and installed atomically as
typed schema, semantic validator, profile contribution, Compose extension,
and canonical-node overrides.

`jetpacs.scope` selects the Jetpacs field/editor presentation without changing
validation. When enabled, `jetpacs.design_scope` can style its subtree and
reselect the canonical implementations of `text`, `card`, `icon`,
`icon_button`, `badge`, `empty_state`, `button`, `chip`, `divider`,
`section_header`, `menu`, `switch`, `collapsible`, `month_grid`, `dropdown`,
`text_input`, and `editor`. An override may decline a node it cannot faithfully
render, returning control to the normal dispatcher.

Chrome and snackbar composition traverse only the bounded, recognized
single-child presentation-wrapper shape to find a scaffold. Foundation code
does not name the downstream wrapper. Dialogs do not inherit either Jetpacs
scope. See [`EBP3-RENDERER-MIGRATION.md`](EBP3-RENDERER-MIGRATION.md).

## Navigation and chrome

Navigation 3 owns receiver destinations such as setup, catalog, selected
surface, and settings. Keys contain identifiers only. EBP owns a SurfaceSpec's
multi-view state, revisions, and `view.switch`; no spec or Room entity is put
in a Nav key. The precise back precedence and process-restoration rules live in
[`NAV3-EBP-BOUNDARY.md`](NAV3-EBP-BOUNDARY.md).

Elisp chrome represents each bounded per-surface screen stack as one EBP
`multi_view`. It composes the host drawer, adaptive bottom bar/navigation rail,
application FAB, and shell globals into each eligible scaffold while preserving
authored-slot precedence and isolating a broken seam. The functional contract
is [`CHROME-VOCABULARY.md`](CHROME-VOCABULARY.md).

## Text editing

The generic Section 19 synchronization engine lives in the `ebp.el` repository
and uses Emacs 30.1's public `track-changes.el` API. Jetpacs owns buffer
selection, reader/editor presentation, toolbar policy, and product UX adapters.
Compose's shared state-based controllers translate actual IME, paste, and
accessibility edits into bounded scalar-index splices; they do not diff whole
strings after every edit.

Editor sessions, shadows, sequence numbers, carets, completion results,
diagnostics, and fontification are session state. They are intentionally absent
from Room and the offline action queue. Full text is reserved for open and
explicit resynchronization; incremental traffic uses ordered edits.

## Optional Android platform breadth

`:wire` owns the closed capability schemas, trigger validation/runtime, state
predicates, context-less action validation, and `offline.wake` grant checks.
`:app` owns current Android permission checks, exact Intent/package allowlists,
OS observation, platform effects, shortcut ingress, fixed tile services, and
encrypted storage. Android sources produce typed state or occurrences; they do
not decide application policy.

The current capability, trigger, state, and tile map is in
[`REVIVAL-EXECUTION.md`](REVIVAL-EXECUTION.md). Android trust transitions are
summarized in [`SECURITY.md`](SECURITY.md). Generic home-screen widgets are a
separate target described by [`GLANCE-WIDGETS.md`](GLANCE-WIDGETS.md).

## Verification ownership

Use the narrowest owning test first:

- validate EBP in `../../../ebp` before relying on protocol behavior;
- run the owning EBP Kotlin/Elisp or renderer-extension suite for an upstream
  change;
- run `test/run-tests.sh` for Jetpacs Elisp and cross-repository integration;
- use [`../companion/TESTING.md`](../companion/TESTING.md) for Room, wire,
  renderer, screenshot, lint, assembly, and device commands; and
- use real-device evidence whenever behavior crosses Android lifecycle,
  platform permission, native presentation, process death, or input boundaries.

Compilation alone is not evidence for persistence, reconnect, offline replay,
native rendering, accessibility, or gesture behavior.
