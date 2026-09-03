# Grove design fidelity program

Date: 2026-09-02
Goal: re-create the Grove Android app's presentation entirely from Elisp on
Jetpacs, adding Kotlin and Elisp primitives to Jetpacs where the design
runtime cannot yet express what Grove needs. Grove is the stress test for
expressivity, accessibility, and configurability; the Android app remains
the visual and workflow acceptance reference.

Builds on the Elisp design runtime experiment
(`ELISP-DESIGN-RUNTIME-EXPERIMENT.md`) and on the uncommitted design-profile
slice found in `jetpacs-components`, `jetpacs`, and `grove` at the start of
this program (theme-role colors, component style slots, profile registry,
Design Lab, `jetpacs-list-item`). That slice was green when this program
began (5 component and 35 Grove ERT tests) and is preserved.

## What Grove's look actually is

From `grove/app/src/main/java/com/rrajath/grove/ui/`:

- A 30-token palette per theme (`GroveColors`): four surfaces, three inks,
  two lines, accent plus accent-ink plus a translucent accent-soft, five
  status colors with translucent soft variants, and eight Org syntax colors.
  Eighteen themes, all literal; no follow-system option.
- Three IBM Plex families with fixed roles: Sans for chrome and lists, Serif
  for read-mode prose (16 sp at 1.65 em), Mono for the editor (13.5 sp at
  1.85 em), timestamps, tags, and file names. The effective scale is about
  25 hardcoded sizes between 9 and 30 sp; `groveTypography` is barely used.
- Flat, borderless rows: monogram tiles, pills, square keyword chips, hairline
  group headers, an indent rail for folder trees, near-zero elevation.
- Custom chrome: a 56 dp top bar, a pill "+ Capture" FAB, a hand-drawn
  segmented control and agenda tabs, custom switches and checkboxes, a
  Canvas brand mark, disclosure triangles, and a reveal-first swipe row with
  its own physics.
- A single-month planning calendar with both dates on one grid.

## Where the design runtime stood

The `jetpacs.design` extension could express tokens, styles, six states,
motions, four bundled Plex families, and 36 component slots, but inside a
design scope every canonical `text`, `icon`, `card`, `chip`, and container
was still drawn by the Material renderer with Material typography. A profile
could therefore not change the font of a single ordinary text node, and the
Grove profile had dropped the app's palette in favor of the 13 theme roles.
Grove's Elisp screens converted exactly two node kinds (labeled buttons and
the agenda range row) to semantic components.

## Slice 1 (this change): design-native text and the Grove palettes

Kotlin, `jetpacs-components/renderer/jetpacs`:

- `JetpacsDesignTextRenderer` is a canonical override for `text` under the
  `jetpacs.design` scope. Typography resolves in three layers: the profile's
  `text.<style>` slot for the node's EBP style name (falling back to new
  private Jetpacs text defaults), the nearest enclosing `jetpacs.styled` or
  `jetpacs.pressable` program resolved against that face's live state, then
  the node's own `color` and `font_weight`. `max_lines`, `selectable`, and
  `syntax` are honored with Foundation text and the local syntax projection.
- The design renderer now renders its whole subtree through the owned scope,
  and design-scoped selections of the Foundation text field and editor make
  the `text-field.*` and `editor.*` slots effective. A malformed scope still
  falls back to unscoped children.
- Six new component slots: `text.body`, `text.title`, `text.headline`,
  `text.caption`, `text.label`, `text.mono` (manifest, regenerated Kotlin and
  Elisp projections, schema hash updated, binding limit 42).
- Fixed a latent bug: literal `#RRGGBB` and `#AARRGGBB` design colors were
  packed with Compose's raw `ULong` constructor and would have crashed at
  render for any profile using literal colors. Theme-role colors were never
  affected, which is why the experiment did not hit it.

Elisp, `jetpacs-components`:

- `jetpacs-design-scope` emits literal empty `tokens` and `styles` maps so a
  nested scope can rebind slots alone.

Elisp, `grove`:

- Three presets: `grove.default` (theme roles), `grove.light`, and
  `grove.dark` (the app's palettes verbatim, soft fills with real alpha,
  syntax colors). All share one type scale aligned to the app's effective
  sizes, bind all 42 slots, and are about 12 KB compiled.
- The document screen nests a scope that rebinds only `text.body` to the
  Serif reading face.

- Grove's labeled buttons and its Capture FAB project onto intrinsic-width
  `jetpacs.pressable` faces (a filled pill for emphasized variants, a
  bordered one for tonal, outlined, and text variants) whose label takes the
  profile's label typography. The Foundation Action is a full-row control by
  contract and had turned the Folders toggle and the FAB into full-width
  bars on the tablet. Under a profile that does not declare Grove's face
  styles the buttons stay Material, so a portable active profile never
  emits a style reference the receiver cannot resolve.
- `fill_width: false` in a design style is now a safe no-op. Foundation
  Styles have no "unset width": the previous NaN fraction collapsed the box
  to zero width, and an unspecified Dp keeps an inherited fill (both
  observed on the tablet).

Companion: the enabled installation registers the design-scoped overrides;
the disabled installation is unchanged.

Tooling: `jetpacs/tools/onboard-tablet.sh` now stages Grove into
`emacs/apps/grove` like the Companion payload task, and reads `ebp.el` and
`ebp-org` from `ebp-poc/` (its previous sibling paths no longer exist).

### Verification record for slice 1

- `jetpacs-components`: projections check, 6 ERT tests, Checkdoc, and
  warning-as-error byte-compile; `:renderer:jetpacs:testDebugUnitTest` 44
  tests including the new pure text-resolution test and the slot test.
- `grove`: 38 ERT tests, Checkdoc, byte-compile.
- `jetpacs/companion`: `CompanionRendererTest` 5 tests and `assembleDebug`.
- Device (Pixel Tablet, API 37): `JetpacsComponentsSemanticsTest`, 30 of 30
  instrumentation tests, including the new text-override typography test
  (a `text.title` slot at 48 sp measures at least 56 dp tall while an
  unbound body text stays shorter) and the scoped-children dispatch test.
- Tablet test drive (Termux-HOME layout, `/sdcard` Vault, design runtime
  on): the changed tree and Companion were installed with
  `tools/onboard-tablet.sh`; Grove's Notebooks, Outline, Document, and
  Agenda screens rendered with IBM Plex through the text override, and the
  button projection was corrected after the first pass. Screenshots are in
  the session scratch directory, not the repository.
- Not run: Companion `lintDebug` and the screenshot suites.

### What the tablet drive showed against the app's screenshots

Compared with `grove/screenshots/GroveLight/*.png`:

- Typography now matches in family and role: screen titles, row titles,
  captions, and the Folders and Capture pills render in IBM Plex Sans at
  Grove's sizes, and Design Lab switches between the three presets live.
- Under the Grove Light preset, design-owned surfaces take the app's
  palette (cream pill surface, paper-line border, brown ink), while every
  Material-owned surface keeps the Emacs theme: page background stays
  white instead of `#F3EDE1`, the "Notebooks" section header and the
  Agenda tab indicator stay theme purple instead of accent brown, and rows
  are outlined Material cards rather than flat rows with monogram tiles.
  This is gap 1 (chrome colors) and gap 2 (design-native rows) below.
- The Preface card's Org keywords take the receiver's syntax palette, not
  the preset's `color.syn-*` tokens; wiring those tokens into the design
  text override's syntax projection is a small follow-up.
- Read mode showed only the folded heading line for the starter inbox, so
  the Serif rebinding could not be judged on device; that is the existing
  fold behavior of the reader over a `#+STARTUP: overview` file, not a
  design-runtime issue.

## Slice 2: canonical controls in Foundation, and the catalog as test bench

Kotlin, `jetpacs-components/renderer/jetpacs` (`JetpacsScopedCanonicalRenderers.kt`):

- Design-scoped overrides for `icon`, `button`, `chip`, `divider`, and
  `section_header`, each keeping the canonical members and semantics and
  changing only Compose selection. Base visuals come from the private
  Jetpacs theme, the profile's slots layer on top, and an authored `color`
  wins last. A member the Foundation presentation cannot honor makes the
  override decline to Material: toggle buttons, collapsing FABs, connected
  group positions, badged icons, and avatar chips.
- Eleven new slots: `button.filled/tonal/elevated/outlined/text`,
  `button.label`, `chip.container`, `chip.label`, `divider.line`,
  `section-header.container`, `section-header.title` (53 total).
- `ebp-compose` gains `ComposeIconResolver` and `LocalComposeIconResolver`:
  the composition root installs one glyph resolver (the Companion passes
  Glasspane's `IconMap`), so the Material-free module draws the same named
  icons without depending on the Material icon artifact.

Elisp:

- The Material 3 catalog's inspection module gains a fifth projection,
  "Design", at every scope. It presents the unchanged Preview screen inside
  the active design profile (or an empty scope), so each of the 279
  examples becomes a live check of which Jetpacs primitives exist; an
  example that still looks Material is a gap. Without the runtime the
  projection shows Preview with a note.
- Grove binds the new slots (pill buttons, chips on the raised surface,
  hairlines in the paper line color, accent section titles) and drops its
  private pressable projection: buttons stay canonical and the receiver's
  button override renders them.

## Slice 3: chrome colors follow the profile

- `jetpacs.design_scope` gains an optional `theme_roles` map (closed to the
  13 EBP roles; values are a literal color, a token, or an ambient role
  reference). The compiler merges it through nested scopes like the other
  maps and includes it in the canonical cache key.
- `ebp-compose` gains `ComposeThemeRoles` and `LocalComposeThemeRoles`, the
  neutral hand-off between renderers. The design renderer resolves a scope's
  roles over the ambient ones and publishes them, re-deriving its private
  Foundation theme at the same time.
- Glasspane's scoped-child dispatch wraps the subtree in `ScopedThemeRoles`,
  which re-derives the Material scheme, the extended success/warning pair,
  and the receiver style tokens from the published roles with the same
  derivation the pushed Emacs theme uses. Scaffold background, top bar, tab
  indicator, cards, sheets, and every other Material node under the scope
  then follow the profile; polarity, density, direction, and syntax colors
  stay ambient.
- Profiles carry an optional `:theme-roles` alist (older snapshots without
  it remain valid). Grove Light and Grove Dark re-declare all 13 roles
  through their own `color.*` tokens; `grove.default` declares none, so it
  stays palette-neutral.

### Verification record for slice 3

- `jetpacs-components`: projections, 7 ERT tests, Checkdoc, byte-compile;
  renderer unit tests (12 design-model tests including theme-role
  resolution, inheritance, and rejection); 35 of 35 semantics device tests
  on the Pixel Tablet including the scope theme-roles test.
- `glasspane-material3`: 7 theme-model unit tests including the shared
  derivation check; 46 plus 12 catalog ERT tests.
- `grove`: 38 ERT tests, Checkdoc, byte-compile; `jetpacs-component-catalog`
  suites.
- Companion renderer configuration tests and debug APK, installed.
- Tablet: Grove Notebooks under Grove Light now paints the `#F3EDE1` page
  background, the `#FBF8F1` top bar and card surface, and a brown section
  title; the top bar and rows were white and purple before this slice.

## Slice 4: host chrome survives a presented screen

A regression the first three slices introduced and this slice closes.
`jetpacs-chrome` composes the navigation drawer, the adaptive dock/rail,
the app FAB and the shell globals (M-x) into a **scaffold**. An applet that
presents its screen inside a design scope returns a wrapper at the root, so
every one of those four injections silently skipped, and the receiver's
`chromeBackDescriptor` — which also requires a scaffold root — handed the
system back gesture to Nav3 instead of the screen's own arrow. Grove with
the design runtime on therefore had no drawer, no rail, no capture FAB, no
M-x, and a back gesture that left the app.

- `jetpacs-chrome--scaffold-apply` composes into the scaffold a screen
  presents, descending at most `jetpacs-chrome-presentation-depth` (4)
  single-child wrappers and rebuilding the chain by copy. The foundation
  names no downstream node type: a wrapper is recognized by shape, so
  `jetpacs.design_scope` needs no mention in foundation code.
- All four injection sites go through it, keeping authored-wins intact.
- Glasspane's `chromeBackDescriptor` descends the same way under the same
  bound, so a presented screen keeps the system back gesture.

No applet changed: Grove and the Material 3 catalog's Design projection
both regained host chrome by construction.

## Slice 5: design-native list rows with reveal swipe

Grove's rows were Material cards: bordered, elevated, drawn by Glasspane. The
Android app's rows are flat, and their signature interaction is the
reveal-first swipe, which was reachable only through the Material `card` and
`collapsible` renderers.

Shared (`ebp-compose`):

- `RevealSwipeCellWidth` is public, so the gesture's geometry and every drawn
  strip read one definition instead of two constants and a test asserting
  they match.
- `ComposeNodeRenderContext.swipeAction(descriptor, direction)` replaces the
  ad-hoc injected-arguments path. It is typed to `RevealSwipeDirection`
  rather than a free-form object on purpose: `direction` is the one argument
  a renderer may contribute, and a general channel would let any renderer
  forge arguments its author never authorised. It has no default, so a
  context that cannot reach the host's injecting path fails to compile
  rather than silently dropping the argument.

New in `jetpacs-components`:

- `jetpacs.list_item`, an app-target node: `title` required, with
  `overline`, `subtitle`, their max-lines, `leading`, one `trailing` node,
  taps, both swipe sides, `selected` and `enabled`. Text slots are strings so
  the renderer has the accessible name without inspecting a child, and the
  typography comes from the profile. Reusing the canonical `swipe_start` /
  `swipe_end` member names means the node inherits the wire's swipe
  validation and its `direction` conflict rule with no `ebp-kmp` change.
- `JetpacsSwipeRow`, the shared reveal-swipe presentation, and a
  design-scoped `card` override, so cards an applet already authors go flat
  too. Nine new slots (`card.*`, `list-item.*`, `swipe.*`), 53 -> 62.
- `jetpacs-components-list-item` picks the node or the canonical
  `jetpacs-list-item` composition. It gates on the manifest's own target list
  first and the live profile second, because the node carries no children:
  an unadvertised emission would render a blank row, and offline the profile
  question answers optimistically. Widget rows pass `:target :widget` and
  take the canonical arm.

Grove: four of five row sites migrated (saved views, notebooks, agenda,
outline heading); the document heading card stays a `card` and goes flat
through the override. The literal presets bind a flat row face.

Corrections made during review: the injection API was narrowed as above; the
`trailing` member became a single node, matching how `FIELD_TYPES` already
types every `trailing` and keeping the engine's editor scan able to descend
it; the `list-item.separator` slot was dropped rather than ship a binding no
renderer reads.

### Verification record for slice 5

- Renderer unit tests, including the swipe wire reading (legacy, rich,
  commit bound, dropped actions, four-action cap) and a new assertion that
  every slot wire name matches the manifest enum, not just the count.
- 37 of 37 semantics device tests on the Pixel Tablet, including the row's
  single click target, its merged accessible name, and its swipe actions
  reachable without the gesture and carrying their direction.
- Elisp suites: `jetpacs-components` 9, `grove` 39, catalog 46 plus 12,
  component catalog 16/10/76/4, and the full Jetpacs foundation gate.
- The boundary test now asserts the invariant that is true and load-bearing:
  the design model never becomes Compose, one file translates a computed
  style into a `Style`, and raw design values are converted in three named
  files. Its previous form filtered by filename and so asserted nothing about
  any newly added file.
- Tablet: Notebooks and the outline render flat; a right swipe on an outline
  heading reveals four Foundation-drawn cells; host chrome is unchanged. With
  the design runtime OFF the chrome returns to Material while rows still
  render through the new node on Foundation defaults, which is the mixed
  configuration nothing else covers.

## Slice 6: growing the Foundation set in Grove's order

The order is measured, not guessed. Counting Grove's Elisp for canonical nodes
still drawn only by Material:

| Builder | Uses in Grove |
|---|---|
| `jetpacs-menu` (+ items, groups) | 28 |
| `jetpacs-empty-state` | 15 |
| `jetpacs-icon-button` | 10 |
| `jetpacs-lazy-column` | 8 |
| `jetpacs-flow-row` | 8 |
| `jetpacs-badge` | 5 |

Layout-only nodes were skipped deliberately. `lazy_column`, `flow_row` and
`surface` carry no Material styling, so a Foundation implementation would look
identical and buy only portability, not fidelity. The value is in nodes whose
appearance IS Material.

This slice took the three styled nodes on nearly every Grove screen:
`empty_state`, `icon_button` and `badge`, with six slots
(`icon-button.container`, `badge.container`, `badge.label`,
`empty-state.container`, `empty-state.title`, `empty-state.caption`), 62 -> 68.

Each declines what it cannot honor: a toggle or badged `icon_button` keeps
Material's checked container and badge anchor, and a `badge` decorating
children keeps Material's anchored `BadgedBox`. An empty state's action reuses
the design button renderer rather than restating it.

Foundation coverage of the canonical vocabulary is now 16 of 49 node types,
plus the seven `jetpacs.*` components.

### Verification record for slice 6

- Renderer unit tests; 39 of 39 semantics device tests on the Pixel Tablet,
  including the icon button's single full-size target and its two decline
  cases, and the empty state's action being its only target.
- Elisp suites across components, Grove, both catalogs, and the foundation.
- Tablet: the Agenda empty state renders in Plex with the preset's muted
  caption and a Foundation action; top-bar icon buttons are Foundation-drawn.
- The slot-name mirror test earned its keep immediately: it caught the enum
  and the manifest listing the same slots in a different order. Since binding
  order carries no meaning, it now compares sorted names and pins the count
  separately.

### Styles API conformance

Audited against the AndroidX Compose Styles guidance. The prerequisites and
the theme wiring were already conformant: `compileSdk` 37, Foundation 1.12.0
through Compose BOM 2026.08.00, the experimental opt-in declared at the module
boundary, a `ComponentStyles` object exposed as a plain static
`JetpacsTheme.styles` rather than a composition local, and a `StyleScope`
extension reading theme tokens through `currentValue`.

One real deviation was fixed. The new row bundled its presentation into the
node renderer, while the other Jetpacs components are public composables that
accept `style: Style = Style`. `JetpacsListItem` is now such a composable —
`style` plus per-slot overline, title and subtitle styles, layered after the
theme and the active profile — and `RenderListItem` is the thin mapper that
reads the wire, wraps the row in the reveal swipe, and supplies the semantics
an extension node does not inherit. The canonical overrides (`card`,
`icon_button`, `badge`, `empty_state`) stay renderers by intent: they present
protocol nodes and have no caller to accept a `Style` from.

Screenshot coverage was added for the row per the guidance's final step:
compact, dark and 1.5x font scale, covering a plain row, leading and trailing
slots, an overline with a truncating two-line subtitle, and the selected and
disabled faces.

## Slice 7: a Foundation `menu`

The slice-6 table counts builder calls, not menus. `jetpacs-menu` at 28 is
**28 calls across exactly three menus**: one menu, five groups and fifteen
items for the outline kebab, one plus four for notebooks, one plus one for
views. Three renderer shapes — but they sit on every row of Outline,
Notebooks and Views, which is why this was still the largest visible gap.

`menu` is one canonical node, not a composition: it owns its own anchor (the
`icon` member, defaulting to `more_vert`) as well as the popup, and its items
and groups are plain wire records rather than child nodes. So this slice is a
design-scoped canonical override and no new component node — unlike the list
row, there was nothing missing from the contract to add.

The override honors the whole node and never declines: flat `items`, `groups`,
the checkable item form (`checked`, `checked_icon`), `supporting_text`,
`trailing_icon`, `initial_scroll`, and a `footer` node. Declining part of the
vocabulary would have left the catalog's grouped Menus page on Material while
Grove's menus moved to Foundation, which hides exactly the regressions the
catalog exists to catch.

Six slots, 68 -> 74: `menu.trigger`, `menu.container`, `menu.item`,
`menu.item-label`, `menu.item-supporting`, `menu.group-label`. A group's
hairline deliberately reuses `divider.line` rather than taking a seventh slot,
so one binding governs every rule in a design. Grove needed no new styles at
all — `grove.popup`, `grove.interactive` and `typography.label-large` already
existed, and `label-large` is Plex Sans 15/600, exactly what the Android app's
own `DropdownMenuItem`s render at.

The popup reuses what `ControlledNavigator` already solved in the same module:
the anchored position provider, the `PopupProperties` set that delegates back
press and outside tap to the popup window, the focus-restore idiom, and the
anchor-bounds measurement that bounds the popup to the larger of the space
above and below. Content is a scrolling `Column` rather than a `LazyColumn`,
because headings, rules, rows and a footer are heterogeneous and
`initial_scroll: "end"` maps directly onto a scroll to `maxValue`.

Two things improve on the Material renderer rather than merely matching it:

- **`supporting_text` now survives on an ordinary item.** Material computes it
  and then drops it, because the expressive overload that has a supporting
  slot is selected by `checked`, not by `supporting_text`. Jetpacs' own Files
  locations menu has been silently losing its path lines; under the design
  runtime it does not.
- **The accessible name lands on the control.** Material puts the host
  modifier on a wrapper `Box` around an unlabelled `IconButton`; here it goes
  on the trigger itself, so name, role and target are one node. Separately,
  Grove's three menus now author a name, because the fallback chain ends at
  the icon identifier and an un-annotated menu announces "more_vert".

### Verification record for slice 7

- 60 renderer unit tests, 9 of them new and covering the wire reading the
  Material renderer has never had a test for: the checkable form selected by
  presence rather than value, a label-less item dropped, an item without
  `on_tap` drawn but inert, an empty group not drawn as a heading over
  nothing, and `supporting_text` on an ordinary item.
- 45 of 45 semantics device tests on the Pixel Tablet, 4 new: the trigger as
  one 48 dp `DropdownList` target reporting Collapsed then Expanded, an
  ordinary item dispatching once and closing, a disabled item announcing
  itself and dispatching nothing, and a checkable item reporting its state
  and leaving the popup open.
- Screenshot references for the grouped popup and the rich-item popup in
  compact, dark and 1.5x font scale.
- 78 Elisp suites across ebp.el, ebp-org, authoring, both catalogs,
  components, automations, Grove and the foundation. Grove is 40; the
  foundation gained `chk "93"`, the grouped/footer/checkable golden that had
  no authoring test at all.
- The Companion configuration test proves the registration is admissible:
  a design scope requires every target that admits it to advertise every node
  type in it, and `menu` is advertised by the app and dialog profiles.
- Tablet: Grove's notebook kebab opens on the Grove popup surface with Plex
  Sans SemiBold labels and resolved glyphs; the outline kebab shows all five
  groups with muted headings over hairlines, bounded below the anchor and
  scrolling to Delete, with "Paste under" dimmed and inert because the
  clipboard is empty. Back press dismisses and returns focus to the trigger.
  The drawer, rail and FAB are unchanged.
- The scope gate was confirmed with both renderers live in one session rather
  than by toggling the runtime: the Material 3 catalog's own top-bar overflow
  is a `menu` outside any design scope, and it still draws Material's square,
  borderless popup while Grove's draws the profile's.

Two defects in my own work were caught by the gates rather than by reading.
The group heading set `heading()` on a wrapper that did not merge its text, so
the heading was announced as an unrelated marker; the device test found it.
And the opening-focus selection wrote Compose state during composition, which
would have left the first row unfocused after the recomposition it triggered;
that is now decided from the authored order instead.

## Slice 8: amendment #187, `menu` made true

Slice 7 recorded three findings as out of scope. They are closed here, as one
amendment.

`SPEC.md` §17.4 described the format-6 menu -- `items` required, a MenuItem
`{label, on_tap, icon, enabled}` -- while the contract, both generated
vocabularies, the reference validator and the Elisp authoring layer had all
moved on. `groups`, `footer`, `initial_scroll`, `supporting_text`,
`trailing_icon`, `checked` and `checked_icon` had shipped with no spec text
and no amendment at all. The numbering gap explains why: **amendments
#153-#167 are missing from `SPEC-CHANGES.md` entirely**. That is where the M3
tier-1 package's prose was drafted
(`jetpacs/docs/DRAFT-amendments-156-167-m3-tier1.md`) and never landed. Only
the menu portion is landed here; the other eleven amendments in that package
remain undocumented, and that is now the largest known spec-sync debt in the
tree.

Two corrections travel with the sync:

- **`trailing_text` ships.** Draft #167 specified the trailing pair and only
  half of it landed, so `menus/MenuSample`'s "F11" shortcut had been silently
  dropped since it was written -- a loss the module's own docstring recorded.
  The pair is mutually exclusive because a MenuItem has one trailing slot, and
  it is two typed members rather than one overloaded string because
  `IconMap.get` cannot report a miss and would draw "F11" as a help glyph with
  no diagnostic anywhere. Both renderers draw it, and the catalog sample no
  longer records the loss.
- **MenuItem `on_tap` is enforced.** §17.4 has required it since format 6, but
  `SpecValidator` checked only `label`, so a dispatchless row validated,
  rendered, and did nothing when tapped.

### Verification record for slice 8

- `validate.py` gains a `check_menu_items` arm; 101 widget golden lines
  validate, one appended (index 106) carrying `trailing_text`,
  `supporting_text`, `trailing_icon` and `initial_scroll` together.
- `SpecValidatorCompletenessTest` gains the first `menu` coverage in the
  tree: both new rules, on the flat form and inside a group.
- The Elisp builder rejects the trailing pair together and authors index 106
  byte-identically (`chk "106"`).
- Renderer unit and screenshot gates, 45 of 45 device tests, 78 Elisp suites,
  the wire and Material unit suites, and the Companion build: all green.

The `on_tap` enforcement is restricting, but only against senders already
violating a MUST: no golden, fixture, or in-tree app carried a dispatchless
item, and the existing 100 golden lines passed the new arm unchanged.

## Slice 9: a Foundation `switch`

Grove has three switches, all on its settings screen, all the same shape:
`id`, `checked`, `label`, `on_change`. The Android app draws a 40 x 23 pill
-- raised surface off, accent on -- with an 18 dp surface thumb that slides.

A design-scoped `switch` override, honoring every member including
`thumb_icon`, so it never declines. The row is the one toggle target, so the
label is part of what a screen reader announces and a tap anywhere on the
row flips it; Material's row is two targets, the label and the control.

State is the host's, mirrored from the choice component and the Material
renderer alike: the live value is seeded from the store at the node's epoch
and falls back to the authored `checked`, and a flip publishes
`state.changed` before `on_change`, in that order. A device test asserts the
order and that a stored value outranks the authored one.

Three slots, 74 -> 77: `switch.track`, `switch.thumb`, `switch.label`. The
thumb's travel is measured rather than assumed, so Grove sizes the track and
thumb through the slot (`width`, `height`, `corner_radius` are design
properties) and the thumb still rests inset from each end. Two new Grove
styles carry the app's pill; the renderer's default metrics never learn them.

### Verification record for slice 9

- 61 renderer unit tests; 48 of 48 device tests, 3 new: the row as one
  48 dp `Switch` target publishing `state:true` then `action`, a disabled
  row dispatching nothing, and a stored value outranking `checked`.
- Screenshot references for off, on, on-with-glyph and disabled, in compact,
  dark and 1.5x font scale.
- Grove 40 of 40, including the assertion that every slot is bound.
- Companion configuration test with `switch` in the pinned override set.
- Tablet: Grove's settings screen draws its three switches as the app's own
  pill, primary on and raised surface off, sized through the slot. Tapping
  the LABEL side of "Show drawers initially" flipped it -- Emacs received
  the change, set the variable, and rebuilt the screen checked -- and a
  second tap restored it.

## Slice 10: a Foundation `collapsible`

Grove has two: the outline heading, whose header is the swipeable list row
itself, and the document's Properties drawer. The app discloses a heading
from a small muted triangle that turns to point down.

A design-scoped `collapsible` override honoring every member -- `collapsed`,
`on_long_tap`, both swipe sides -- so it never declines. Three things about
this node are unlike the others and are worth recording.

The host hands a collapsible a modifier WITHOUT the semantics projection,
because the header owns it with the live expanded state; the override
applies `ebpSemantics` on the header row itself, so a screen reader hears one
disclosure row with an expanded/collapsed state rather than a chevron and
some text.

Expansion is Companion-local presentation state. `collapsed` seeds only a new
presentation identity and the user's state survives later snapshots at the
same path (SPEC 17.3), which is `rememberSaveable(context.path)` and nothing
more.

The chevron toggles and the header content keeps its own targets: an inner
click consumes before the header row sees it, so Grove's outline heading
still opens on tap and expands from its chevron, exactly as the app does.
The expanded header carries the `toggled` design state, so a profile can give
an open heading its own face.

Three slots, 77 -> 80: `collapsible.header`, `collapsible.chevron`,
`collapsible.body`. Grove binds the chevron muted and indents the body 20 dp.

### Verification record for slice 10

- 50 of 50 device tests, 2 new: seeded collapsed composes no children, the
  header toggles them in and out and dispatches nothing doing so; a long
  press dispatches `on_long_tap` without toggling.
- Screenshot references for collapsed and expanded, in compact, dark and
  1.5x font scale.
- Grove 40 of 40, 78 Elisp suites, the Companion pin with `collapsible`.
- Tablet: in the outline, the one heading with children draws the muted
  triangle; tapping it turns the triangle down and reveals the two child
  headings indented 20 dp with their badges and kebabs intact, and tapping
  the heading text still opens the document instead of toggling. That
  document's "Properties (1)" drawer is the second collapsible, seeded
  collapsed.

## Remaining gaps, in order of leverage

1. Chrome colors. Done in slice 3 through scope theme roles. Shapes and
   typography of Material chrome (top bar title font, tab label font) still
   come from Material; a `chrome.*` slot family would be the next step.
2. Design-native rows. Done in slice 5. Still open: a design-scoped
   `collapsible` override (chevron, expanded state, header swipe), and the
   fact that an accessibility-run swipe on a canonical `card` still carries
   no `direction` — the shared semantics projection dispatches through
   `onAction` with no injected argument, so a screen-reader run and a finger
   swipe send different occurrences there. The new node does this correctly,
   which makes the canonical gap easier to see.
3. Icons. Done in slice 2 through the shared icon resolver; `icon_button`
   followed in slice 6. Badged and toggling icon buttons still fall through
   to Material by design.
4. `menu`. Done in slice 7. Next: `switch`, `collapsible`, and the planning
   calendar's `month_grid`.
5. Grove component kit in Elisp: pill, keyword chip, monogram tile, group
   header with hairline, overdue card, segmented control, agenda tabs, indent
   rail. Most compose from `styled`, `pressable`, `row`, `column`, and
   `spacer` once slices 1 and 2 exist; the segmented control should become a
   `jetpacs.tabs` variant rather than a Grove-private node.
5. Read mode. With `text.body` rebound to Serif the Org renderer's output
   already reads like the app; headline sizes by relative depth, keyword
   pills, tag pills, and Canvas checkboxes still need styled projections.
6. Planning calendar. `month_grid` exists with day styles; the both-dates
   band, legend, shorthand box, and repeater sentence are Elisp composition
   plus `text_input`.
7. Accessibility. Grove's own app announces most icons as raw glyphs and has
   no custom accessibility actions for swipes; Jetpacs already derives names,
   roles, and swipe actions. Keep that advantage: every Grove component must
   carry an accessible name and a 48 dp target.
8. Configurability. Design Lab already lets a user copy and edit any preset.
   The remaining app settings that change appearance (monogram tiles on
   rows, flat versus tree, agenda density) stay Customize options.
