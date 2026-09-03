# jetpacs-components

Jetpacs' optional Foundation design extensions. The repository owns the
unchanged format-1 `jetpacs.components` manifest and the experimental,
format-2 `jetpacs.design` manifest, their generated Kotlin and Elisp
projections, the Compose renderers, and the public Elisp builders.

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
`icon`, `button` (by variant), `chip`, `divider`, and `section_header`, each
with its own slots, and for the Foundation text field and editor, so the
`text-field.*` and `editor.*` bindings take effect without a separate
`jetpacs.scope`. An override declines members it cannot honor (toggle
buttons, collapsing FABs, connected groups, badged icons, avatar chips), and
those nodes keep their Material presentation. Named glyphs come from the
`ComposeIconResolver` the composition root installs through `ebp-compose`.

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
./gradlew :renderer:jetpacs:testDebugUnitTest --console=plain
```
