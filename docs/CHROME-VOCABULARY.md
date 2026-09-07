# Chrome vocabulary and functional contracts

This document names the application chrome that Elisp authors and records how
`jetpacs-chrome.el` composes it into EBP scaffolds. The names follow Android
and Material terminology; the behavioral rules are Jetpacs policy.

## Vocabulary

| User-facing element | Canonical term | EBP representation | Purpose |
|---|---|---|---|
| Side sheet opened from the leading menu affordance | Navigation drawer | `scaffold.drawer` | App-level destinations |
| Titled bar at the top | Top app bar | `scaffold.top_bar` | Identity, back/menu affordance, screen actions, shell globals |
| Compact bottom destinations | Navigation bar form | `scaffold.bottom_bar` | Three to five persistent places |
| Medium/expanded start-edge destinations | Navigation rail | `scaffold.rail` with `navigation_rail` | The same places adapted to window class |
| Contextual action strip | Toolbar | `scaffold.floating_toolbar` or an authored docked bar | Mode/selection actions |
| One prominent creation action | FAB | `scaffold.fab` | Primary create/add action |
| Anchor-scoped action list | Menu; overflow menu in a top bar | `menu` | Secondary actions |

A destination changes place; an action changes state. Drawers, navigation bars,
and rails contain destinations. Toolbars, FABs, and menus contain actions.

## Placement contracts

### Navigation drawer

The drawer contains app-level destinations, not document mutations. Chrome
injects the host drawer into the stack-bottom scaffold only: a root can show
the hamburger, while a drilled screen shows back. A guest screen cannot inherit
the host drawer. A screen-authored drawer wins.

Drawer builders must use verbs valid on every surface where they appear. A
builder error or malformed result removes the drawer only; it does not fail the
surface.

### Top app bar

The top app bar carries the title, a leading back or menu affordance, a small
set of screen actions, and optional shell globals. Contextual selection actions
belong in a toolbar. Secondary actions belong in an overflow menu.

`jetpacs-chrome-screen` builds a small styled top bar by default. Authored
actions are retained, and global actions are de-duplicated by their exact
action name.

### Navigation bar and rail

Author shared destinations as data through
`jetpacs-chrome-dock-items-function`. On compact windows, chrome creates the
icon/label/selection composition in the scaffold's bottom bar. On medium and
expanded windows it authors a `navigation_rail` into the start-edge rail slot,
which the Material renderer presents with NavigationRail/WideNavigationRail.
The same items therefore persist across screens and adapt without a second
destination list.

`jetpacs-chrome-dock-function` is the raw-node override. Its node always stays
in `bottom_bar` and outranks data items. Any screen-authored dock slot wins over
host injection. A malformed dock costs only the dock.

### Toolbar, FAB, and menu

A floating toolbar holds mode- or selection-contextual actions; a screen may
instead author a bottom-docked action strip. It should not duplicate navigation
or top-bar globals.

A FAB represents one primary creation action. The application default is
resolved for the owner of each screen, so a guest cannot inherit its host's
create action. A screen-authored FAB wins. Shell globals can be placed in the
FAB slot by user policy; one item becomes one button and multiple items use the
installed design-layer FAB menu or a safe fallback.

A menu is scoped to its anchor. In a top app bar it is the overflow menu; on a
row or card it contains that item's actions. Destructive entries carry an
explicit confirmation descriptor.

## Composition precedence

Chrome uses deterministic authored-wins rules:

1. A screen's explicit scaffold slot wins.
2. An application-owner default FAB fills an empty FAB slot.
3. Host drawer and dock fill eligible empty slots.
4. Data-form shell globals supersede the legacy finished-node global seam so
   placement can be re-authored.
5. Globals configured for FAB fall back to the top app bar when the screen's
   FAB is already occupied or the live profile cannot admit their FAB form.
6. The optional downstream presentation wrapper is applied once to a bare
   scaffold; an already presented screen keeps its own wrapper.

Chrome and the shell descend only a fixed number of recognized single-child
wrappers to reach a scaffold. Wrapper recognition is structural, keeping the
foundation free of downstream extension names. Node IDs injected into multiple
`multi_view` views are namespaced so document-wide uniqueness remains true.

Every optional seam is failure-isolated: a signal or malformed node/data value
removes that contribution, not the app surface.

## Screen stacks and back

Each surface has a bounded Elisp screen stack represented as one EBP
`multi_view`; the default maximum is three rendered screens with the root
pinned. A push or reset names `current_view`. Ordinary background refreshes omit
it, so they cannot pull the user away from the visible view.

`jetpacs-chrome-reset-screens` accepts optional `no-push` to truncate to the
registered root without sending a frame. A destination handler can immediately
push its peer screen, preserving root Back navigation while sending only the
destination frame. The caller owns that follow-up presentation; the reset stays
committed even if the subsequent screen push fails.

The authored back arrow dispatches the exact `view.switch` builtin for the view
below. When the Companion reports the switch, Elisp truncates its corresponding
stack without registering a competing `view.switched` action. System back uses
the same visible authored descriptor before falling through to Navigation 3;
see [`NAV3-EBP-BOUNDARY.md`](NAV3-EBP-BOUNDARY.md).

A failed pushed screen is replaced by an isolated error view when the profile
allows it, and the previous stack remains recoverable. Budget charging, node
identity, profile admission, and teardown are enforced for each composed view.

## App integration poles

`jetpacs-defapp` supports two explicit policies:

- `build-within` is the default. The app contributes destinations, may choose
  which core destinations remain in its primary dock, can relocate suppressed
  core destinations into its drawer, inherits shell globals, and can push
  owner-scoped guest screens.
- `standalone` withdraws host dock/global presentation for the app's surfaces
  and lets the app author its chrome. It still uses the same back contract,
  profile/byte budgets, action routing, error isolation, and command parity.

Both policies share one Android activity and EBP session. “Standalone” is a
presentation policy, not a new process, task, identity, or trust domain.

## Current source and tests

- `../emacs/jetpacs-chrome.el` owns stacks and scaffold composition.
- `../emacs/jetpacs-apps.el` owns app registration and build-within/standalone
  policy.
- `../emacs/jetpacs-widgets.el` owns the contract-backed constructors.
- `../test/jetpacs-chrome-test.el` and `../test/jetpacs-apps-test.el` pin
  precedence, adaptation, failure isolation, guest ownership, back, budgets,
  teardown, and profile behavior.

Future vocabulary growth starts at the EBP contract or the owning renderer
manifest. A dated lookup table is not a source of truth.
