# jetpacs-components

Jetpacs' optional Foundation design extensions. The repository owns the
format-2 `jetpacs.components` manifest and the experimental, format-2
`jetpacs.design` manifest, their generated Kotlin and Elisp projections, the
Compose renderers, and the public Elisp builders.

Both manifests also project typed member sets for the renderer
(`JetpacsComponentsMembers.kt` and `JetpacsDesignMembers.kt`, each with an
enum twin) through the same generator; `tools/check-projections.sh` checks
them together with the vocabularies. Each renderer dispatches through a
`ComposeComponentRegistry` of one component per node type
(`JetpacsComponentsRenderer.registry`, `JetpacsDesignRenderer.registry`), and
each component reads its node through its generated member set. Both
manifests type every member, and the Companion installs both typed schemas,
so a `jetpacs.*` node is admitted against the manifest's bounds (texts up to
4096 characters, up to 10,000 options or children, line limits 1 to 10,000,
section levels 1 to 6) before it is rendered; a swipe side is typed
`swipe-side`, which delegates to EBP's own swipe validator, and the tab
`variant` stays a bounded string rather than an enum because its names are
forward-compatible renderer policy: a newer sender's variant degrades to the
`scrollable` fallback on an older receiver instead of being rejected. The
golden corpus replays through the registry headless under Robolectric
(`JetpacsComponentsGoldenReplayTest`).

`jetpacs.design` is a bounded language of tokens, styles, state rules, and
motions. Its pure Kotlin compiler is shared by admission and rendering; no
AndroidX `Style` or executable Elisp crosses either public boundary. Loading
`jetpacs-design.el` installs deterministic builders, while
`jetpacs-design-material.el` demonstrates a filled button, card, and
two-option selector without adding a Material dependency to the renderer.

Profiles are ordinary, versioned Elisp data managed by
`jetpacs-design-profiles.el`. A profile can bind the closed semantic slots for
canonical Text, Action, Choice, Panel, Tabs, Section Navigator, Text Field,
and Editor to authored styles. Inside a compiled design scope every canonical
`text` node is rendered by the Foundation text override: its typography comes
from the `text.<style>` slot for its EBP style name, then from the nearest
enclosing `jetpacs.styled` or `jetpacs.pressable` program (resolved against
that face's live state), and finally from the node's own `color` and
`font_weight` members. The scope also selects Foundation presentation for canonical
`icon`, `button` (by variant), `chip`, `divider`, `section_header`, `menu`,
`switch`, `collapsible`, `month_grid`, and the closed single-select
`dropdown`, each with its own slots, and for the Foundation text field and
editor, so the `text-field.*` and `editor.*` bindings take effect without a
separate `jetpacs.scope`. An override declines members it cannot honor
(toggle buttons, collapsing FABs, connected groups, badged icons, avatar
chips, editable dropdowns), and those nodes keep their Material
presentation. Named glyphs come from the
`ComposeIconResolver` the composition root installs through `ebp-compose`.
Canonical icon buttons honor `tonal` with the theme's selected surface; removing
that variant restores the plain icon face on the next authored snapshot.

The `editor.syntax.<role>` slots restyle a synchronized editor's
`fontify.show` runs, one slot per EBP syntax role. A bound slot overlays the
role's palette style with the color, background, weight, `italic`,
`underline` and `strike_through` it authors. A heading run that carries a
`level` (EBP amendment #199) also stacks `editor.syntax.heading-N` on top.
The baseline binds every syntax slot to an empty style, so the palette
stands until an app binds one. The three text-decoration properties apply
wherever text properties do, so a `text.*` slot or a styled program can also
italicize, underline or strike through text.

`jetpacs.list_item` is the row primitive: leading, a flexible
overline/title/subtitle column, one trailing node, taps and both swipe
sides. It is flat unless a profile binds `list-item.container` to a surface
style, and it is an app-target node — `jetpacs-components-list-item` returns
the canonical card composition for any other target or receiver. Reveal
swipe is shared with the design-scoped `card` override and with Glasspane,
so an authored row travels identically whichever renderer draws it.

A scope may also re-declare the 13 EBP theme roles (`:theme-roles` on a
profile, `theme_roles` on the wire) with literal colors, tokens, or ambient
role references. The renderer publishes the resolved roles through
`ebp-compose`'s `LocalComposeThemeRoles`, and the Material dispatcher
re-derives its scheme from them for the subtree, so a profile with a fixed
palette colors the scaffold, top bar, and tab indicator too. A profile that
declares no roles leaves chrome on the Emacs theme. A nested scope may declare no tokens or styles and
rebind slots alone, which is how an applet switches one region to a different
prose face. Color properties may use literal values or one of EBP's 13
theme roles; role values resolve against the current receiver theme at render
time and therefore follow light/dark and Emacs theme changes without rewriting
the profile. Presets are immutable, user copies persist through Customize, and
only the selected compiled scope crosses the wire.

This extension is downstream of `ebp-kmp`, `ebp-compose`, and the Jetpacs
authoring surface. It is selected by the Jetpacs composition root but is not
part of the EBP protocol specification.

```sh
test/run-tests.sh
tools/check-projections.sh
./gradlew :renderer:jetpacs:testAndroidHostTest :renderer:jetpacs:compileKotlinJvm --console=plain
```

### Fonts on the JVM

The design model names the IBM Plex families. Android loads them from this
module's font resources. The JVM jar carries the same files on the classpath
(`font/ibm_plex_*.ttf`, with the OFL beside them), but turning a resource into
a font is desktop-only Compose API that this module cannot name, so a JVM host
installs the one platform call once, before its first composition:

```kotlin
JetpacsJvmFonts.loader = JetpacsJvmFonts.Loader { resource, weight, style ->
    androidx.compose.ui.text.platform.Font(resource, weight, style)
}
```

Without a loader the Plex families fall back to the platform's default sans,
serif and monospace families.

### Design-scoped rich text

Canonical `rich_text` now renders with Foundation `BasicText` within a
`jetpacs.design_scope`. It inherits the same typography and enclosing style
state as plain text, preserves every canonical span attribute, and routes link
actions through the current renderer context. This adds no wire vocabulary
and no Material dependency. The Orgzly applet is a downstream consumer; its
palettes and notebook policy stay in `../orgzly-native/`.

Design-scoped passive `chart` nodes now use Foundation Canvas with profile
colors for line, bar, area and sparkline plots. Ordinal spacing, explicit
vertical ranges and accessible summaries follow the canonical contract.
Charts with point actions retain the host implementation and its required
index/value injection seam. This adds no wire vocabulary or app dependency.

### Dense rows and TextView metrics

Four style properties and one chrome slot let an app reproduce a native
Android View design exactly (Orgzly Native's port of Orgzly Revived is the
first consumer):

- `intrinsic_height` sizes a styled or pressable child to its children's
  intrinsic height, so a sibling the parent row stretches (`align_self`
  `stretch`) spans the tallest one, as an outline's indent guides span a
  wrapped note. Foundation Styles sizes do not report intrinsic width, so fixed
  sizes inside such a row belong on universal `width`/`height` attributes.
- `center_vertically` centres a pressable's children in its height, as a
  Material button or list row centres its content.
- A pressable keeps its 48dp minimum interaction bound unless its style
  authors `width`/`min_width` (horizontal) or `height`/`min_height`
  (vertical), so a list of short rows can tile edge to edge.
- `word_ellipsis` cuts an overflowing `max_lines` text at the last word that
  fits, as a Toolbar title does, instead of mid-word.
- `paragraph_breaking` wraps with the balanced breaker and no hyphenation,
  an Android TextView's defaults, instead of Compose's greedy breaker.
- `line_height_centered` gives every line its full `line_height` with the
  text centred, as Material's type scale lays text out.
- `line_spacing_below` puts the extra height of a `line_height` below each
  line and drops it from the last, as a TextView's line spacing does, so a
  one-line text is no taller than its font.
- `popup_before_anchor` on `menu.container` ends the popup where its trigger
  starts, and an authored `min_width` may exceed the menu maximum, so a
  trigger beside a field can open a field-wide dropdown; `elevation` gives
  the popup the platform shadow a View popup casts.
- `jetpacs.glyph` draws an app's own vector icon (SVG path data fitted into
  a square); `jetpacs.inline_text` lays `text` children and `jetpacs.inline`
  placeholders out as one wrapping paragraph, each placeholder reserving the
  measured width of its `reserve_text` plus `reserve_extra`, as a Compose
  Text with inline content does; `elevation` on a styled or pressable face
  casts the platform shadow; `middle_ellipsis` cuts a too-long text in its
  middle; the `system-mono` font family is the platform monospace.
- Inside a design scope `date_button` and `time_button` draw as a button
  styled by `picker.container` and `picker.label` and open their pickers
  locally. Unlike `button.*`, those slots may author `min_height`, padding
  and `font_size`, and `max_lines` lets a label wrap.
- `chrome.bottom-bar` sets the band a scaffold's bottom bar sits on: its
  `background_color` and its `padding`, `padding_horizontal` and
  `padding_vertical`, so an app can draw its own navigation bar.
- `chrome.scrim` colors the content behind a modal drawer through its
  `background_color` (a View `DrawerLayout` dims to 60% black).

Rich text honors the RichSpan `scale` of EBP amendment #201 in both the
Foundation and Material renderers.

This directory is a module of `jetpacs-poc`; it needs no separate Git remote.
Run the commands above from this directory. External EBP dependencies remain
sibling repositories of `jetpacs-poc`; `JETPACS_REPOSITORIES_ROOT` can select
another checkout collection. Runtime feature and extension names are unchanged.
