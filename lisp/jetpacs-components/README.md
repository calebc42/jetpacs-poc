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
(jetpacs-component-panel LABEL CHILDREN)
```

- `LABEL` is always a non-empty string.
- `ON-TAP` and `ON-CHANGE` are ordinary ActionDescriptors.
- Boolean values use `t` or `:json-false`; `CHECKED` is required.
- `CHILDREN` is a proper list of real typed node plists and is emitted as the
  node's JSON array without hiding or merging descendants.
- Choice publishes `state.changed` before dispatching `ON-CHANGE`, with the
  same next boolean supplied through the ordinary action value path.

Applications must declare `:requires-extensions '("jetpacs.components")`.
Existing receivers that do not advertise it keep the app outside its builders
and show the standard unavailable-app explanation.

The checked-in vocabulary is generated from
`renderer-extensions/jetpacs-components.json`. Run `test/run-tests.sh`, or use
the focused gate documented by the sibling catalog README, before changing the
public constructors.
