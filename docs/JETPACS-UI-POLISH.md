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

Files (uncommitted at the time of writing):

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

## Remaining slices

2. Rows: `jetpacs-chrome-row` becomes a `jetpacs-components-list-item`;
   name every menu; describe every icon.
3. Chrome polish: drawer alignment, the dead navigation-bar conditional,
   the stale rail, Glasspane's back arrow on the rail, Theme copy that leaks
   `jetpacs-theme-mode` and "(SPEC 18.4)", the duplicate + and FAB, empty
   states and section headers.
4. `chrome.*` slots (Kotlin): top bar title and tab label typography and
   shapes, so Material chrome takes the profile's type.
5. Glasspane: rows through the list item, hex colors to theme roles,
   sections, menus.

Open items noticed on the tablet, not yet addressed:

- The drawer's collapsible header shows a focus ring on open. The Foundation
  focused rule fires on programmatic focus; it should be gated to keyboard
  input mode.
- An empty chromeless editor draws nothing under the baseline: no
  outline, no placeholder, so the REPL input is an invisible strip above the
  divider until it has text. A placeholder or a faint surface belongs in
  slice 3.
- The tablet's active profile was left at `grove.light` from Design Lab
  testing, so the host wore Grove's palette until reset with
  `(jetpacs-design-set-active-profile nil t)`.
