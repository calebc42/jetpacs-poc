# Jetpacs Components Elisp API

This library authors the optional, app-only `jetpacs.components` renderer
extension. It returns ordinary EBP plist/vector IR; it does not introduce a
catalog AST or send executable Elisp. Loading `jetpacs-components.el` registers
the generated schema and target map so the normal sender profile, member,
descriptor, size, and extension gates remain authoritative.

The first public builders are:

```elisp
(jetpacs-component-action LABEL ON-TAP :enabled BOOL)
(jetpacs-component-choice ID LABEL CHECKED ON-CHANGE :enabled BOOL)
(jetpacs-component-tab LABEL VALUE)
(jetpacs-component-tabs ID OPTIONS VALUE ON-CHANGE
                        :enabled BOOL :scrollable BOOL :pinned BOOL
                        :variant VARIANT)
(jetpacs-component-section LABEL VALUE LEVEL)
(jetpacs-component-section-navigator ID SECTIONS VALUE ON-CHANGE
                                     :enabled BOOL :pinned BOOL)
(jetpacs-component-panel LABEL CHILDREN)
(jetpacs-component-scope CHILDREN)
```

- `LABEL` is always a non-empty string.
- `ON-TAP` and `ON-CHANGE` are ordinary ActionDescriptors.
- Boolean values use `t` or `:json-false`; `CHECKED` is required.
- `CHILDREN` is a proper list of real typed node plists and is emitted as the
  node's JSON array without hiding or merging descendants.
- `jetpacs-component-scope` is an app-only, invisible renderer-selection
  boundary. It has no layout or accessibility bounds of its own; universal
  presentation attributes are therefore nonsensical on it. The installed
  Phase 3 receiver selects Jetpacs' Foundation presentation for canonical
  `text_input` descendants. Authors continue to use `jetpacs-text-input` from
  the foundational API; there is no duplicate Jetpacs text node or builder.
- Choice publishes `state.changed` before dispatching `ON-CHANGE`, with the
  same next boolean supplied through the ordinary action value path.
- Tabs is controlled: `OPTIONS` is a non-empty list of closed tab objects with
  unique non-empty string values, and `VALUE` must name one of them. The
  receiver injects the selected string into `ON-CHANGE`; the next authored
  document remains the authority for selection. `VARIANT` is `"fixed"`,
  `"scrollable"`, `"navigator"`, or `"adaptive"`. When present it derives
  the compatibility `scrollable` member (`false` for fixed, `true` otherwise),
  and a contradictory explicitly supplied `SCROLLABLE` is rejected. When
  `VARIANT` is absent, the legacy `SCROLLABLE` behavior is unchanged.
- A Section is a closed, untyped option object with a non-empty label and
  value plus an integer hierarchy `LEVEL` from 1 through 6. Section Navigator
  emits those objects under its `options` wire member, requires unique values,
  and is controlled by one selected `VALUE`. The selected value is injected
  into `ON-CHANGE`; command scrolling remains application-owned. Levels may
  skip numbers because they describe hierarchy rather than impose a tree.
- `PINNED` on Tabs or Section Navigator asks a directly containing
  `lazy_column` to keep the navigator visible while its other items scroll.
  True requires that direct parent; absent or false renders the component
  ordinarily under any otherwise valid parent.

Applications must declare `:requires-extensions '("jetpacs.components")`.
Existing receivers that do not advertise it keep the app outside its builders
and show the standard unavailable-app explanation.

The checked-in vocabulary is generated from
`renderer-extensions/jetpacs-components.json`. Run `test/run-tests.sh`, or use
the focused gate documented by the sibling catalog README, before changing the
public constructors.

## Experimental design runtime

`jetpacs-design.el` separately registers the format-2 `jetpacs.design`
extension. Its scope, styled, and pressable builders use a closed set of
bounded values and properties. Token, style, and motion maps are alists at the
call site and become lexically sorted JSON objects; local type mismatches,
token cycles, and unresolved local token or motion references signal before a
surface is sent.

```elisp
(jetpacs-design-scope TOKENS STYLES CHILDREN :motions MOTIONS)
(jetpacs-design-styled STYLE-NAMES CHILDREN)
(jetpacs-design-pressable STYLE-NAMES ON-TAP CHILDREN
                          :enabled BOOL :selected BOOL :toggled BOOL)
```

`jetpacs-design-material.el` is a small proof library with a filled button,
passive card, and controlled two-option selector. It remains ordinary Elisp
authoring over Foundation-backed renderer nodes; it does not expose or depend
on Android Material APIs.
