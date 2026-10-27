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

`companion/settings.gradle.kts` composes these modules:

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
| `:renderer:compose-testing` | Test-support library (test and androidTest configurations only): recording render context and editor host, the headless component test controller, the golden-corpus loader; sourced from `ebp-compose` |
| `:renderer:material3-testing` | Test-support library over the Material renderer: the recording Material host and the headless Material test controller; owned locally in `companion/renderer/material3-testing` |
| `:renderer:glance` | Restricted generic home-screen-widget renderer and profile |
| `:renderer:jetpacs` | Foundation-only `jetpacs.components` and optional `jetpacs.design` implementations; sourced from the in-repository `jetpacs-components/` module |
| `:renderer:material3` | Jetpacs Material 3 implementation and optional extension; owned locally in `companion/renderer/material3` |
| `:host` | The platform-neutral Companion host both composition roots share: the loopback listener and engine wiring (`DeviceBridge`), the process-wide projections (`CompanionStores`), the composition root below a platform's own (`CompanionProcess`), the renderer installation over platform targets (`CompanionRenderer`), and the Nav 3 window composition; a platform implements `CompanionHostPlatform` and `HostUiPlatform` |
| `:app` | Android composition root over `:host`: the Keystore bootstrap, the Android halves of the two seams, platform adapters, onboarding and exact installed renderer selection; its `bundled` flavor source set adds the engine process, supervisor and provisioner of [the bundled engine](#bundled-engine) |
| `:desktop` | Compose for Desktop composition root over `:host`: the data directory, keychain credentials, the generated pairing and the JDK/AWT halves of the two seams ([`PLAN-desktop-companion.md`](PLAN-desktop-companion.md)) |

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

## Bundled engine

`companion/app` builds two product flavors of one codebase
([`PLAN-bundled-emacs.md`](PLAN-bundled-emacs.md)). `standalone` is the
Companion above, paired with an Emacs the user installs. `bundled`
(application ID suffix `.bundled`) carries GNU Emacs 31.1 inside the APK as
an **engine**: the sibling `jetpacs-emacs-engine` repository builds the
unmodified, signature-verified release tarball with Emacs's own build system
into an AAR that `companion/gradle/engine.lock` pins by identity and SHA-256.
No Emacs source tree carries a change made for Jetpacs.

The engine runs in its own process, `:emacs`, under a private `specialUse`
foreground service (`JetpacsEngineService`) that starts Emacs the way
upstream's non-interactive entry does: `EmacsNative.setEmacsParams` with no
`EmacsService`, then `initEmacs` with `--fg-daemon`. Nothing in that process
opens Room, the Keystore or the bridge; `JetpacsApplication` returns before
its composition root when it finds itself there. A third component beside
the two EBP endpoints, the **engine supervisor** of SPEC amendment #209
(`EngineProvisioner`, `EngineSupervisor`, `EngineWake` in the bundled source
set), owns the host's initialization files and lifecycle the way a user's
init and a service manager would on a desktop: it provisions the engine's
HOME under the app's private files (the managed kit, `init.el`,
`install.conf`, the private vault and the Emacs endpoint's 0600 copy of a
generated pairing), starts, stops, restarts and keeps the process alive on
product policy, lets Emacs's own loadup build the first-start dump with the
kit preloaded through `site-lisp/site-init.el`, and performs the SPEC §5.8
bound-service wake. The engine's foreground service is the app's only one:
while it runs it binds a private, inert service in the Companion's process,
which keeps that process, and the listener that lives as long as it does, at
the engine's importance, so background running shows one notification and,
switched off, none. Emacs then dials the Companion over the SPEC §5.3 Unix
socket (`LocalSocketTransport`, same UID only) and authenticates like any
other endpoint; the Companion never writes Elisp and never passes code,
arguments or files through a start or wake signal. Every seam between the
flavors is one `EngineFlavor` object per source set, so `:host` and the main
source set stay flavor-blind.

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

Compose dispatch is a registry of components, one object per node type
(`ComposeNodeComponent`, assembled in a `ComposeComponentRegistry`). Each
component declares the generated member set it reads through
(`:renderer:model` `vocabulary/generated`, derived from `contract.json` and the
extension manifests) and presents the accepted JSON node directly; there is no
parallel tree. The Material renderer resolves a node in this order: the
advertised-type check for the target, universal attributes and semantics,
the owning extension renderer, a design-scoped canonical override that
applies, then the canonical Material component from `MaterialComponents`, and
finally the SPEC §16.2 children fallback. `NodeSupportPinTest` pins the
registry's node types to the advertised app profile.
The `jetpacs.components` and `jetpacs.design` extension renderers are
registries of the same shape (`JetpacsComponentsRenderer.registry`,
`JetpacsDesignRenderer.registry`), pinned to their manifests by
`JetpacsComponentRegistriesTest`.
The Glance widget translation is a registry of the same shape over
`:renderer:model`'s toolkit-neutral `NodeComponentRegistry` (`GlanceComponents`);
its contribution advertises exactly the registry's node types, so the
advertised and the implemented widget profile cannot split.

A surface may carry a data document beside its tree (EBP §13.8, amendment
#194, capability `data.model`). Emacs authors the document (`:data-builder`
on `jetpacs-shell-define-root`), binds node members to pointers into it
(`jetpacs-ref`), and patches it with `surface.data` through
`jetpacs-shell-data-patch`; the Companion engine materializes the bindings
before validation, so the renderers, widgets, tiles and the input store see
an ordinary document and need no knowledge of pointers. Emacs alone writes
the document; a Companion-local write-back for bound inputs is a deferred
exploration. `emacs/jetpacs-data.el` is the Elisp reference materializer,
replayed against `ebp/goldens/data.golden` like the engine's. On the app
side `jetpacs-chrome-define-root` takes the `:data-builder` (one document
per surface, across the stack) and `jetpacs-app-defer-patch` is the
handler's D2 patch seam beside `jetpacs-app-defer-refresh`; bindings move
values, so a handler patches when the tree's shape is unchanged and
refreshes when it is not. Grove's planning canvas is the pilot: 24
bindings, an 835-byte document, and a picker tap costs one 965-byte
`surface.data` frame where it re-sent the 5.2 KB screen with the rest of
the stack. Measured end to end on a Galaxy S21 Ultra served by a desktop
Emacs over an adb port forward: a quick-pick tap sends one 866-byte
`surface.data` frame where the same stack's snapshot is 18.9 KB, and the
scrolled list keeps its position to the pixel across the patch, a direct
patch and a full re-push alike.

Amendment #195 adds templates over bound arrays (EBP §13.8.5, feature
`data.template`). A node's `template` — one Node, written with
`(jetpacs-template "/rows" ROW)` where a container takes its children —
is expanded by the engine once per element of the array its
`bind.children` reaches; inside the template a `jetpacs-ref` without a
leading `/` resolves from the item, the template node binds `key`
(`jetpacs-with-attrs … :key (jetpacs-ref "id")`), and nothing inside may
carry an `id`, input state, an editor, a `variant_host` or
`capture_fields`. `Materializer.materializeWithItems` also records each
expanded item root under its `IdentityPath` (key, else id, else JSON
position, each with the type), the renderer carries that grammar as
`RenderCtx.sourcePath` beside its own retained-presentation path and
reports it on every `RendererActionRequest`, and the engine injects the
innermost item's key as `args.item` at dispatch (EBP §14.3). Templates are
admitted in app snapshots only; a Companion without the feature gets the
expansion from Emacs, with `item` written into each row's descriptors by
`jetpacs-data-materialize`. The shell remembers which surfaces were pushed
with their document and re-pushes rather than patches the others. Orgzly's
outline is the pilot (orgzly-native `30d5d98`): one row template over
`/rows`, a 40-note page measured at 5,849 bytes of screen plus a 10,940-byte
document against 34,180 bytes of literal rows, 23 nodes against 530, and a
fold is one `surface.data` patch of `/rows`.

Amendment #196 adds an `insert` DataOp (EBP §13.8.4, feature `data.insert`)
so a list can be patched element by element. The shell keeps the document
it last sent each surface — set by a push, advanced by every patch,
reset by the whole push a refused patch triggers — and
`jetpacs-shell-data-sync` (handler twin `jetpacs-app-defer-sync`) diffs an
array against it by a key member (`jetpacs-data-array-ops`: removals,
inserts over the longest common subsequence of keys, member sets) and sends
whichever is smaller, the diff or one `set` of the array. Orgzly's fold went
from an 11 KB set of `/rows` to 204 bytes (1.1 KB when rows flow in from the
next page).

Amendment #197 adds device locale advice (EBP §20.1.2): the Companion
reports `{tag, hour_cycle, time_zone}` as the welcome's `locale` mirror and
the `locale.changed` notification, on the `window.changed` model.
`AppCapabilities.deviceLocale` reads the platform, `DeviceBridge` seeds every
new session and re-reports on the locale, time-zone and time broadcasts, and
the engine emits only on change. ebp.el keeps the advice beside the window
geometry; `jetpacs-dates-today` and `jetpacs-dates-format-clock` take it as
explicit arguments so Grove's planning "today" and time labels follow the
phone's zone and clock cycle. The Companion formats nothing: it reports facts
and Emacs authors the strings.

Amendment #205 adds the device colors (EBP §20.1.3) on the same model: the
Companion reports `{polarity, light, dark}` — the system appearance, and the
device's scheme at each polarity as a role-to-`#rrggbb` map — as the welcome's
`colors` mirror and the `colors.changed` notification. `schemeRoles`
(renderer/material3) projects a Material 3 `ColorScheme` as the thirteen EBP
roles followed by Material's own roles in snake_case (`primary_container`,
`on_surface_variant`, `surface_container_low` …), which the spec treats as
extended roles: reported values, not EBP names. `AndroidHostColors` reads the
wallpaper-derived schemes on S and later (the baseline schemes below) and
re-reports on every configuration change and wallpaper broadcast; the desktop
reports the baseline schemes once. ebp.el keeps the object beside the window
geometry and locale; `jetpacs-colors`, `jetpacs-device-dark-p` and
`jetpacs-device-color` read it, and the chrome re-pushes every surface on a
change, as it does for a size-class flip. Orgzly Native derives its Revived
palette from it (the fixed measured palettes stay as the fallback), so a new
wallpaper recolors Orgzly Native as it recolors Revived.

Amendment #198 adds conditional nodes (EBP §13.8.6, feature `data.visible`).
The universal attribute `visible_when` is a flat AND of at most eight
predicates `{path, op, value?}` over the data document; `Materializer`
evaluates it for each element of a `children` array before that element's
bindings and omits a hidden subtree from the materialized children, so a
`surface.data` patch shows or hides a node at an ordinary revision. Identity
positions count materialized siblings, which is why an array holding a
conditional node must be keyed throughout; nothing beneath a conditional node
may carry an `id`, input state, an editor or a `variant_host`, so §13.6 drafts
are untouched. The attribute is admitted in app snapshots only and shares the
template's admission checks in `SurfaceStore`; a dialog document rejects it.
On the Emacs side `jetpacs-when` builds predicates, `jetpacs-with-attrs
:visible_when` places them, `jetpacs-data-visible-p` is the reference
evaluator replayed against `data.golden`, and the shell sends conditionals
only to a target advertising `data.visible`, otherwise dropping the hidden
nodes before pushing. Pilots: Grove's three feedback cards are keyed
conditional siblings reading `/planning/feedback_kind`, so a feedback-kind
change is a 319–711-byte `surface.data` frame where the 6.3 KB screen and
its stack went out before; Orgzly's row template hides an empty state,
priority, tag or planning line with relative predicates. The renderers are
unchanged: a hidden node never reaches one.

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
