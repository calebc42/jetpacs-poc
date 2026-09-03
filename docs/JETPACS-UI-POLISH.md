# Jetpacs and Glasspane UI polish

Date started: 2026-09-02. Follows the Grove program in
`GROVE-DESIGN-FIDELITY.md`, which grew the Material-free Foundation renderer
to 16 canonical node types under the `jetpacs.design` scope. This program
turns that renderer on the host itself: every `jetpacs-chrome-screen` in
every app wears one platform design profile, and the chrome around it is
tidied to the same standard.

## Decisions

- **Baseline look: "Quiet Material, tidied."** One platform profile,
  `jetpacs.baseline` (label "Jetpacs"), system font family with Plex Mono
  for code and metadata, theme roles only (no hex), 8/12/20 dp radii,
  16/8/16 spacing. It is registered at load by
  `jetpacs-components/lisp/jetpacs-components/jetpacs-design-baseline.el`.
- **Glasspane: "Wear the baseline, own the rest."** Glasspane screens are
  presented through the same seam and keep only content-level styling.
- **Presentation is a host seam, not an app duty.** `jetpacs-chrome-screen`
  wraps its scaffold through `jetpacs-chrome-present-function` exactly once;
  an app that already wraps its screen is respected, and a presenter that
  signals falls back to the bare scaffold. The baseline installs itself as
  the presenter when nothing else has.
- **Profile choice stays Emacs-side.** The Companion has one global toggle
  ("Experimental Elisp design runtime"); the active profile is the
  `jetpacs-design-active-profile-id` Customize variable, nil meaning the
  applet default, which for host screens is the baseline.

## Slice 1: baseline profile and the presentation seam

Landed 2026-09-03 as jetpacs-components d33750b, jetpacs f163528,
jetpacs-component-catalog 5c0c041. Files:

- `jetpacs-components/lisp/jetpacs-components/jetpacs-design-baseline.el`
  (new): the profile, `jetpacs-design-present`, and
  `jetpacs-design-baseline-register`.
- `jetpacs/emacs/jetpacs-chrome.el`: `jetpacs-chrome-present-function`,
  `jetpacs-chrome--present`; the local scaffold walker became an alias of
  `jetpacs-shell-scaffold-apply`.
- `jetpacs/emacs/jetpacs-shell.el`: `jetpacs-shell-presentation-depth` (4)
  and `jetpacs-shell-scaffold-apply`, so the snackbar injection reaches a
  scaffold through up to four single-child wrappers.
- `jetpacs/emacs/jetpacs-init.el`: requires the baseline after the catalog.
- `jetpacs-component-catalog/lisp/jetpacs-design-lab.el`: the Design Lab's
  "baseline" preset is now the platform baseline and is never unregistered.
- `jetpacs-components/renderer/jetpacs/.../JetpacsEditorRenderer.kt`: the
  fix below.

### Finding: the hub body vanished under any design scope

The first tablet walkthrough showed the hub's Elisp REPL as a blank body
under the presented scope while every other host screen rendered.
Reproduced in `jetpacs/companion/app/src/androidTest/.../PresentedScreenInstrumentedTest.kt`,
which renders the exact presented tree (`jetpacs.design_scope` >
`scaffold` > `column[empty_state weight 1, divider, row[editor weight 1,
icon_button]]`) through the Companion's real installation: the empty state
measured to zero height, the editor spanned the whole body, and the Eval
button had zero width.

Cause: the Foundation editor override put the incoming `modifier` on its
inner `BasicTextField` instead of its root `Column`. A row or column
`weight` is layout parent data read by the direct parent; on the field, the
override's own column read the row's weight as a column weight and the
field claimed every remaining pixel. Because Compose measures unweighted
children before weighted ones, the body column then had nothing left for
the weighted empty state. Material's editor already puts `m` on its root
column. The override now does the same, and its four device tests locate
the editable field inside the tagged root rather than expecting the tag on
the field.

Rule for overrides, recorded here so the next one does not repeat it: the
`modifier` an override receives is the parent's, and it goes on the
override's outermost layout node. Every other override in
`JetpacsScopedCanonicalRenderers.kt` already follows it.

### Verification record for slice 1

- Elisp: 78 suites green across jetpacs, jetpacs-components,
  jetpacs-component-catalog, and Grove.
- `jetpacs-components` `:renderer:jetpacs` unit, screenshot validation,
  and device suites: 176 tests, 0 failures, on the Pixel Tablet.
- `jetpacs/companion` `PresentedScreenInstrumentedTest`: 1 test, green.
- Tablet: hub REPL renders under the presented scope after the fix.

## Slice 2: rows, menu names, icon names

- `jetpacs-chrome-row` delegates to `jetpacs-components-list-item`: the
  flat `jetpacs.list_item` node wherever the receiver advertises it (the
  Companion always does), styled by the profile's `list-item.*` slots, and
  the canonical card composition elsewhere. Every host list (Files, Apps,
  Settings, drawer entries, project and package rows) changes with it.
- Both host menus carry `semantics.name` ("Switch location" on Files,
  "Heading actions" on an Org heading). `menu` has no name member of its
  own; SPEC 16.4 derives the name from `semantics.name` first, and without
  it the trigger announced as its icon identifier. The Material trigger
  now puts the host modifier on the `IconButton` itself, so the name lands
  on the control rather than on a box around an unlabelled button; the
  Foundation trigger already did.
- Icon buttons were already described; four labels were not names ("back",
  "whole buffer", "imenu", "command palette") and became "Back", "Show
  whole buffer", "Outline", "Command palette". The Files search field
  announced as `text_input` (a hint is not a name) and now carries
  `semantics.name` "Search contents".
- Verified on the tablet with a `uiautomator` dump of the Files screen:
  every row announces as its title, the location menu as "Switch location".
- The reference profile the tests run against advertises
  `jetpacs.list_item`, so the chrome row tests pin the list item and a
  separate test pins the card fallback with advertisement stubbed out. The
  org-render golden regenerated with exactly one change: the menu name.

## Slice 3: chrome polish

- **Focus ring on open.** Opening the drawer hands focus to its first row,
  and the profile's `focused` rule drew a ring on it. Every Foundation
  control now attaches its focus interactions through
  `Modifier.jetpacsFocusable` (`JetpacsFocus.kt`), which feeds the style
  state only while `LocalInputModeManager` reports keyboard input. The node
  stays focusable for accessibility and keyboard travel; the ring appears on
  the first key press and never on a tap. Twelve call sites, one rule.
- **Drawer anatomy.** The app nests' headers were `row[icon, title]` with no
  gap and top alignment; they now share the plain rows' anatomy (icon, 12 dp,
  centered title, subtitle where one exists). The leading chevron still
  offsets a nest's icon from a plain row's by its own width; that placement
  is the collapsible renderer's, and Grove's outline relies on it being
  leading, so it stays.
- **Dead conditional.** The bottom bar's label color was
  `(if selected "on_surface" "on_surface")`. M3 mutes the unselected label
  with `on_surface_variant`, a role the wire does not carry, so the label is
  `on_surface` unconditionally with the reason recorded beside it.
- **Theme copy.** The Theme screen rendered the color-scheme option under
  its variable name with its docstring's spec reference as the caption. The
  row is labelled "Color scheme", and `jetpacs-settings--doc-line` drops a
  parenthesised `(SPEC n.n)` note from any option's caption: that note is
  for the developer reading the source.
- **Empty states.** Files used the `info` glyph for every state; "Can't open
  folder", "No search", "Search failed" and "Nothing being edited" now use
  `folder_off`, `search`, `error` and `edit_off`, all in the icon table the
  lint test checks.

Not done in this slice: the "stale rail" note from the audit did not
reproduce on the tablet (the rail selects Eval on the hub, Files on Files,
nothing on host screens, which is right); Glasspane's back arrow beside the
rail and its capture FAB next to Files' "+" are Glasspane authoring and move
to slice 5.

## Slice 4: `chrome.*` slots

The top bar title turned out to need nothing: the host authors it as a
`text` inside the `top_bar` row, so under a scope the Foundation text
override already draws it from `text.title`. What Material still styled on
its own was the tab strip, the navigation rail, the drawer sheet and the
snackbar.

- **Seam.** ebp-compose gains `ComposeChromeStyles` and
  `LocalComposeChromeStyles` (`ChromeStyles.kt`), the same shape as the
  theme-roles seam: a neutral resolved value the design scope provides and
  the toolkit reads where it draws chrome, keeping its own default for every
  null field. A text style carries only the properties the profile set, so
  Material merges it over its own base style.
- **Slots.** Six, 88 → 94: `chrome.tab-label` (typography; its `selected`
  rule's `content_color` is the selected label color), `chrome.tab-indicator`
  (`background_color`), `chrome.rail-label`, `chrome.rail-indicator`
  (`background_color`), `chrome.drawer` (`background_color`,
  `corner_radius`), `chrome.snackbar` (`background_color`, `content_color`,
  `corner_radius`). The six mirrors moved together and the vocabularies
  regenerated. `CompiledDesignScope.composeChromeStyles` in the style
  adapter resolves them; `RenderScope` provides the local in both of its
  branches, falling back to an enclosing scope's value when none of the six
  is bound.
- **Consumers.** `RenderTabs` (label style, selected color, indicator color
  on both primary and secondary rows), `RenderNavigationRail` (label style,
  indicator color), the modal and permanent drawer sheets (container, shape),
  both snackbar overloads (container, content, shape).
- **Bindings.** Baseline: label typography for tabs and rail, the indicator
  style for the tab indicator, the surface for the drawer, and a new
  `base.toast` (inverted surface) for the snackbar. The rail indicator is
  bound to `base.toolkit`, an empty style: the wire carries no container
  roles, and a pill painted from `secondary` was a saturated block where
  Material's `secondaryContainer` is a pale one, so that one stays the
  toolkit's. An empty style is now legal: a plain plist with no properties
  member in the profile (so profiles still print, re-read and compare
  `equal`), and the scope builder supplies the wire's required `{}`. Grove
  binds the same six from its own styles, which its every-slot test
  requires.
- **Tests.** The Foundation device suite checks a scope resolves bound chrome
  slots and leaves unbound ones null; a new Material device test checks tab
  and rail labels take the seam's font size and keep Material's `titleSmall`
  without it.

### Adversarial review of slice 4

Checked and held: nested scopes merge their parent's component bindings, so
an inner scope that binds one chrome slot recomputes the whole value from
the merged bindings rather than dropping the outer scope's fields, and the
`?: parentChrome` fallback only fires when no chrome slot is bound anywhere.
The tab colors are computed inside the tab row's composition, so the
no-seam default (the row's own content color, as Tab's defaults take) is
unchanged. The empty-style scope compiled on device: the Foundation send
button proves the scope was live when the rail indicator showed Material's
default.

Found and fixed: the scrollable tab rows ignored the indicator slot while
the fixed rows honored it; the wide navigation rail item ignored the rail
indicator while the narrow item honored it; an unselected tab label ignored
the label style's own color. All three now match their siblings.

Recorded, not changed: a profile that binds `chrome.snackbar` with a
background and no `content_color` keeps Material's inverse content color,
which may not contrast with the chosen background; the baseline binds both
and a profile author must too. No slot table exists in the docs to go
stale; the experiment manifest records checkpoint-era commits only.

## Remaining slices

5. Glasspane: rows through the list item, hex colors to theme roles,
   sections, menus.

Open items noticed on the tablet, not yet addressed:

- Every bare `icon` node announces its identifier ("folder" ten times on
  the Files screen, "chevron right" on every drill row). The shared
  projection in ebp-compose `Semantics.kt` exposes a name whenever a node
  has an icon identifier, and `SemanticsProjectionTest` pins it. SPEC 16.4
  requires names for interactive nodes only; a bare icon with no authored
  `content_description` should be decorative and silent. This is an
  ebp-compose decision, not a host one, so it is recorded here for a
  separate change.

- Resolved in slice 3: the focus ring on the drawer's first row on open.
- Resolved 2026-09-03: the REPL authored its editor `chromeless`, which
  Material ignored and Foundation honored, leaving an invisible input strip.
  The flag is dropped; the editor wears the `editor.surface` outline. The
  dialog prompt editor is still chromeless but dialogs are not presented
  through the scope, so Material keeps drawing its outline there.
- The tablet's active profile was left at `grove.light` from Design Lab
  testing, so the host wore Grove's palette until reset with
  `(jetpacs-design-set-active-profile nil t)`.
