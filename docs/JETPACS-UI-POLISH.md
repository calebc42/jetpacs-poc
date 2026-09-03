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

## Slice 5: Glasspane

- **Rows.** Seven card-shaped rows became `jetpacs-chrome-row` (the list
  item under the profile): contextual note, mention (with its "Link it"
  button trailing), stale note, recent clock task, saved view (delete
  trailing), saved search (edit and delete trailing), and the journal's
  carried-over task (Today and Pick trailing). The rich item cards on the
  agenda, tasks and detail screens stay cards: their headline is rich text
  with a priority badge, their body carries chip rows and date rows, and
  they swipe. A list item's title is a string.
- **Colors.** Twenty hex literals became theme roles: overdue, deadline and
  archive in `error`; priority in `warning`; done and cycle in `success`;
  the selected calendar day and tag spans in `primary`/`on_primary`; the
  neutral scheduled glyph and unranked priority in `outline`. Glasspane now
  authors no hex at all, so the profile and the Companion's own palette
  decide every color.
- **Menus.** Both menus carry `semantics.name`: "Move to column" on a board
  card, "Heading actions" in the reader.
- **Sections.** Already section headers throughout; the remaining title-
  styled texts are dialog titles, which is right.
- **Back arrow beside the rail.** A Tier-1 destination (Projects, Areas,
  Resources, Archive, Review) is a peer of the Agenda root, reached from
  the rail beside it, yet it was pushed with a back arrow. The destination
  opener now hands those builders a nil back; drills keep theirs. The
  system gesture at a destination therefore falls to the platform default,
  the way top-level destinations behave in Material navigation. That
  first exposed a chrome rule: the drawer hung on the stack root only, so
  a destination without its arrow lost the hamburger too. The chrome now
  hangs the drawer on every screen that draws no back arrow (it inspects
  the presented scaffold's top bar, the same spine the Companion reads
  for the system gesture), so the five main views carry the hamburger and
  drills carry the arrow: one slot, one of the two.
- **Capture FAB.** Its glyph is `note_add`, not `add`: the FAB rides the
  Files guest that Resources opens, where a plain plus beside Files' own
  "+" read as the same action.


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

## Slice 6: the catalog's Visual editor on Foundation controls

Landed 2026-09-03 as jetpacs-component-catalog ac67a48. The component
catalog and the Design Lab already sat inside the presentation seam, so
every node type the Foundation renderer draws was Foundation-drawn there
with no code change. What was left to Material was what the Visual
projection itself emitted: a tri-state dropdown (unset / true / false) for
every boolean member, and a Jetpacs panel around every compound member.
Both now use canonical nodes from the Grove program's set.

- **Booleans are switches** on the existing native boolean edit action.
  A switch is two-state, so an absent member is seeded from its effective
  default (`enabled` means true when absent; everything else false) and
  an optional member keeps the "use the default" icon button beside it,
  the same shape optional strings already had. A presence-only flag
  (Text's `selectable`) is a switch whose off position removes the member,
  through a new closed `injected-presence` codec. The boolean edit handler
  honors that one codec and collapses any other claim on the action to
  `injected-boolean`, so a control cannot smuggle an arbitrary codec
  through the boolean path.
- **Compound members are disclosures.** Padding, corner, border, Semantics
  and its nested objects, action descriptors, selection, toolbar, and a
  toolbar item's long press are `collapsible` nodes seeded open only when
  authored, with a header caption reading Authored or Default so a folded
  editor still says which members are customized. Root and fixed-child
  sections stay panels: they are the content, not a member of it.
- **Closed enums stay dropdowns.** There is no Foundation dropdown yet, so
  the eleven enum controls (variants, keyboards, offline policy, toolbar
  operations, Tabs presentation, controlled values) keep Material's. The
  next slice adds the node; the Design Lab restructuring then builds on it.

### Verification record for slice 6

- Elisp: the four catalog suites, 109 tests, three new: switches across a
  specimen with an absent `enabled`, the presence codec end to end through
  the edit document and the boolean handler, and the disclosures' seeding,
  captions, unique catalog-owned ids and surviving panels.
- Tablet, Text Field Visual under the baseline: `single_line` drew as a
  switch on with its remove button; a tap flipped it, Emacs rebuilt the
  page, and the Lisp projection read `:single_line :json-false`.
  `enabled` drew on though absent; `scroll_here` drew off. "Directional
  padding" drew folded with its chevron and "Default" caption; a tap
  opened it on "Configure Directional padding". Offline policy and Filter
  drew as Material dropdowns, as expected.
- Correction, found in slice 7: those switches and disclosures were
  Material's, not Foundation's. The catalog body sits inside
  `jetpacs.scope`, and an extension's scoped children take that
  extension's id as their design scope, replacing the enclosing
  `jetpacs.design`, so no `jetpacs.design` override applied there. The
  node choice in this slice stands (the editor now emits the canonical
  nodes the Foundation set draws); slice 7 makes the scope nesting honor
  them.
- The tablet's catalog tree had no stale `.elc`; the desktop checkout has
  three from an interactive session on Aug 29 (gitignored, untouched).
  The test runner sets `load-prefer-newer`, so they do not shadow the
  suites, but a bare `emacs -l` does load them.

## Slice 7: a Foundation `dropdown`

The catalog's Visual editor and the Design Lab pick from closed enums
everywhere: variants, keyboards, offline policies, toolbar operations, Tabs
presentations, controlled values, easing names, font weights. Every one of
them was Material's exposed dropdown. This slice adds the node to the
Foundation set, 16 -> 17 of 49, so the Design Lab restructuring that follows
can build on it.

`dropdown` has two forms. The closed single-select form (`id`, `options`,
`value`, `label`, `hint`, `on_change`, `enabled`) is what both editors use
and is what the override draws: a field showing the selected option's label
(or the hint, muted, when nothing is selected) with a chevron, and a popup
as wide as the field listing the options, opening on the selected
row, bounded to the larger of the space above and below, dismissing on back
press and outside tap, and handing focus back to the field. The field is
the one target and carries the host's name and role (`DropdownList`), with
an Expanded / Collapsed state; the label above it is decoration. The
editable form (`editable`, and with it `report_caret`) is a completion text
field rather than a select, and declines to Material the way an avatar chip
does.

State is the host's, mirrored from the switch: the live value is seeded
from the store at the node's epoch and falls back to the authored `value`,
and a pick closes the popup, then publishes `state.changed` before
`on_change`. What it publishes is the option value's text. An `EnumOption`
value may be a string, number or boolean; the Material renderer publishes
its text, and an app must receive the same thing whichever renderer drew
the control, so the Foundation one does too, and a unit test pins that a
numeric or boolean option value goes out as its text.

Five slots, 94 -> 99: `dropdown.field`, `dropdown.text` (the selected
label in the field and each row's label), `dropdown.label`,
`dropdown.popup`, `dropdown.item`. The Elisp slot list, the manifest and
the Kotlin enum are the three mirrors, and the manifest's binding bound
moved with them. The baseline binds them to `base.field`, `typography.body`,
`typography.label`, `base.popup` and `base.interactive`; Grove to its
`grove.field`, `grove.popup` and `grove.interactive`, so both
every-slot-bound tests stay green. The navigator's chevron became
`internal` so the field could reuse it rather than draw a second one.

### Finding: `jetpacs.scope` inside a design scope dropped every design override

The first tablet walk with the new Companion showed the catalog's Offline
policy and Filter controls still on Material. The switches beside them
had looked right in slice 6, so the override registration was suspected
first; the Companion pin test and the registry said it was there. The
cause is in the Material dispatcher: an extension's scoped children are
rendered with the extension's own id as their design scope
(`inDesignScope(extensionId)` in the `MaterialExtensionRenderContext`),
which replaces rather than nests. The catalog wraps every body in
`jetpacs.scope` to select the Foundation text field and editor, so under
the presented `jetpacs.design_scope` its children were looked up in the
`jetpacs.components` scope, which owns only those two nodes. Every other
Foundation override, including slice 6's switch and collapsible, fell
through to Material there.

The fix is in the components renderer, not the dispatcher. Inside an
active design scope (`LocalDesignScope` is set) the boundary is redundant:
the design scope registers delegating overrides for the text field and
editor and everything else besides. So `jetpacs.scope` now renders its
children through the ordinary child path when a design scope is active,
keeping the enclosing scope, and through its scoped path otherwise, exactly
as before. A device test pins each branch. No Elisp changed, and the
Design Lab preview, which also nests `jetpacs.scope` inside the draft
profile's scope, gains the same behavior.


### Verification record for slice 7

Landed 2026-09-03 as jetpacs-components e1bb4cf (the node), 5afcd0d (the
scope fix) and bee68ad (popup width); grove 45d090b; jetpacs 2ec3740 (the
Companion pin).

- 75 renderer unit tests, 4 new. 59 of 59 device tests on the Pixel
  Tablet, 5 new: the field as one 48 dp `DropdownList` target showing the
  selected label, opening its options, and a pick closing the popup before
  publishing `state:tonal` then the action; a disabled field opening
  nothing; a stored value outranking the authored one; the hint without a
  value; and `jetpacs.scope` under a design scope rendering its children
  through the enclosing scope.
- Screenshot references for the closed states (value, hint, disabled) in
  compact, dark and 1.5x, and the open popup in compact and dark; the 52
  existing references unchanged.
- Elisp: components 10 of 10 with the slot count at 99 and the baseline
  binding every slot; Grove 40 of 40 with every slot bound; the 79 jetpacs
  suites (1841 tests); the four catalog suites (109).
- Companion: the renderer installation test pins `dropdown` in the design
  override set.
- Tablet, Text Field Visual under the baseline, after the scope fix: the
  Reset and Arm buttons drew as Foundation outlined buttons for the first
  time, the switches as the Foundation pill, and Offline policy as the
  Foundation field with the baseline's label typography (the first walk
  drew the label in Plex Mono because the tablet's Elisp had not been
  pushed and no binding covered the new slot). The popup opened on the
  selected row; picking Queue closed it, Emacs rebuilt the page with the
  queue policy's Dedupe and TTL fields, and the Lisp projection read
  `:when_offline "queue" :ttl_s 3600`. The first popup spanned the window
  and clamped to its left edge, which is the width fix above; after it the
  popup matched the field.

Open after this slice: the Design Lab restructuring on these controls
(collapsible per entry with a value caption, dropdowns for easing,
text-align and weight, numeric keyboards on number fields), and the
catalog's eleven enum controls, which now draw as Foundation dropdowns
with no Elisp change.
