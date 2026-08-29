# Companion testing

Run commands below from `llm-poc-3/companion`. Gradle needs the configured JDK
and Android SDK; it writes only normal Gradle/build outputs plus screenshot
references when the explicit update task is used.

## Focused component gates

```sh
./gradlew :renderer:model:testDebugUnitTest
./gradlew :wire:jvmTest --tests '*SemanticsTest'
./gradlew :renderer:compose:testDebugUnitTest
./gradlew :renderer:compose:compileDebugAndroidTestKotlin
./gradlew :renderer:jetpacs:testDebugUnitTest
./gradlew :renderer:jetpacs:compileDebugAndroidTestKotlin
./gradlew :renderer:jetpacs:compileDebugScreenshotTestKotlin
./gradlew :renderer:material3:compileDebugAndroidTestKotlin
./gradlew :renderer:material3:compileDebugScreenshotTestKotlin
```

The model suite pins registry-derived profiles, semantic name/default/state
projection, and the no-Material dependency boundary for EBP, wire, core,
renderer model, and the Foundation renderer. `SemanticsTest` consumes the
shared EBP semantic golden witnesses and proves builtin, feature, capture, and
unknown-member admission behavior. The Compose controller tests pin input
normalization, state-before-action ordering, typed safe-admission clearing,
secret erasure, `publish_state`, distinct JCS field/editor byte budgets,
Unicode-scalar editor splices, and no-echo remote adoption. They also pin the
synchronized opening/READY/composing/stale/offline/closed lifecycle,
composition-atomic adoption, typed edit outcomes, and retained Unicode
text/selection seeding across a fresh session.
Their device test drives the same controllers through real state-based Compose
fields and IME semantics. The Material renderer's Android tests cover
the shared Compose projection as well as receiver-owned component roles and
single-click-target behavior. The Jetpacs module pins generated extension
admission, private theme derivation, singular Compose dispatch, state-before-
action ordering, local Editor projection, toolbar transforms, logical-line
gutter indexing, and the no-Material dependency boundary. Experimental Compose
Styles opt-in exists only in the two downstream design renderer modules, never
in EBP, `:renderer:model`, or `:renderer:compose`.

## Screenshot references

```sh
./gradlew :renderer:jetpacs:validateDebugScreenshotTest
./gradlew :renderer:material3:validateDebugScreenshotTest
```

The Jetpacs gallery covers compact and expanded widths, dark mode, 1.5× font
scale, focus/hover, disabled controls, selected state, and nested content. Its
text-field gallery adds outlined, filled, error, disabled, syntax, empty secure,
focused, and RTL states. The six text-field references join the five original
component references. Six local-editor references add compact/expanded widths,
dark mode, 1.5× text, focus/read-only, syntax with logical line numbers,
toolbar/chromeless presentation, and RTL gutter placement. One synchronized
reference adds the compact offline and stale status rows. All 18 references
live under `renderer/jetpacs/src/screenshotTestDebug/reference/`.

The deterministic Material gallery covers a 3×3 window matrix (400/610/900 dp by
400/500/1000 dp), a dark 610×500 configuration, and 1.5× font scale at
400×500. Its 11 references live under
`renderer/material3/src/screenshotTestDebug/reference/`. The gallery uses
fixed strings, no clock, network, device state, animation clock, or EBP
session.

Update references only after reviewing the rendered change:

```sh
./gradlew :renderer:jetpacs:updateDebugScreenshotTest
./gradlew :renderer:material3:updateDebugScreenshotTest
```

Reference updates are evidence of an intentional visual change, not a way to
make a validation failure disappear.

## Device semantics

With one authorized device connected:

```sh
./gradlew :renderer:compose:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.renderer.compose.EditingControllersInstrumentedTest

./gradlew :renderer:jetpacs:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.renderer.jetpacs.JetpacsComponentsSemanticsTest

./gradlew :renderer:material3:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.ebp.companion.ui.JetpacsComponentsSemanticsTest

./gradlew :renderer:material3:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.renderer.compose.EbpSemanticsTest

./gradlew :renderer:material3:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.ebp.companion.render.ScopedCoreOverrideDispatchTest
```

The shared editing class enters normalized text through a real
`BasicTextField` and edits an astral Unicode selection through a real editor;
it proves state-before-action ordering and exactly one scalar splice. The
Jetpacs class checks Action, Choice, Panel, Text Field, and local or
synchronized Editor
semantics, enabled/checked/error/maximum-length state, one-control/one-target behavior,
descendant preservation, autofocus, normalized text entry, exact ordinary
action dispatch after `state.changed`, safe-admission clearing, volatile
password capture/erasure, Editor save/value dispatch, toolbar edits, read-only
inertness, offline text retention, synchronized action refusal, and a single
editable Editor owner. The first Material class checks
its receiver-owned catalog and single-choice components. A third case mounts a
Material `OutlinedTextField` beside the custom Styles host;
that is the device regression for keeping Material3's binary ABI compatible
with the Compose 1.12 Styles API. The EBP class asserts heading, pane title,
live region, error, state description, collection/item, traversal, determinate
and indeterminate progress, and custom actions in the semantics tree. Its
Foundation button dispatches one custom action through the ordinary action sink
exactly once and retains one click target.
`ScopedCoreOverrideDispatchTest` proves nearest-scope selection, canonical
Material fallback outside scope, extension-node ownership inside scope,
universal semantics on the interaction-owning overridden node, the invisible
scope boundary, and app-to-dialog admission isolation.

These tests do not replace manual TalkBack/Switch Access checks on the target
tablet. Verify pane announcements, heading navigation, collection position,
expanded state, and the existing labeled swipe actions; execute one custom
action and confirm Emacs handles its ordinary action once. Restore every
accessibility setting afterward, then confirm reconnect and ordinary touch
interaction. For Text Field, also exercise IME submit, paste normalization,
selection, rotation/restore, hardware keyboard, pointer focus, error
announcement, and one secure submit whose secret never appears in the catalog
readout. For Editor, also exercise multiline selection, save, single-line
Enter, syntax, line-number scroll alignment, toolbar snippets/line operations,
read-only selection, disabled state, chromeless presentation, autofocus-once,
rotation/restore, and preservation across a compatible repaint. Keyboard/focus,
font-scale, and touch-target checks remain part of the same device envelope.

Phase 4 manual acceptance passed on the Pixel Tablet on 2026-08-28. The user
reported successful TalkBack, Switch Access, hardware-keyboard, pointer, touch,
and Editor-specific checks above; the device was left reconnected with ordinary
touch interaction healthy.

For the Phase 5 live gate, open **Jetpacs Components → Editor → Synchronized /
Live** and verify the in-memory buffer, not a local draft:

1. Type ordinary and astral text, move the caret, select in both directions,
   and save once; confirm Emacs receives each edit once and the save count
   increases once.
2. Start an IME composition while changing the same buffer from Emacs; confirm
   the composition is not split and the winning value/selection appears after
   reconciliation without a duplicate delta.
3. Disconnect Emacs. Confirm the displayed value remains, the offline status
   appears immediately, and text, Save, Enter, completion, and toolbar actions
   are inert with no draft or queued action.
4. Reconnect without repushing the catalog. Confirm a fresh session opens from
   the visible value and selection. Then reconnect with an explicit newer
   surface snapshot and confirm that snapshot wins.
5. Rotate during READY and offline states, remove/re-add the editor page, and
   restart the Companion; confirm session identity closes/reopens correctly
   and process death does not pretend the volatile buffer was persisted.

The Phase 5 automated, screenshot, and five connected suites passed on the
Pixel Tablet on 2026-08-28. The reviewed APK and 115-file managed Elisp tree
were redeployed, Emacs and the Companion reconnected, and the live synchronized
fixture was left visible in READY. The user subsequently reported that all five
interaction checks above passed. That report is the manual acceptance evidence;
it is not inferred from instrumentation. Phase 5 is complete.

`espresso-core` is pinned directly to the stable
AndroidX Test 1.7/3.7 release line because Compose UI Test 1.12's older
transitive Espresso cannot initialize on Android 17/API 37.

## Broad gate

```sh
./gradlew :wire:jvmTest \
  :renderer:model:testDebugUnitTest \
  :renderer:compose:testDebugUnitTest \
  :renderer:compose:compileDebugAndroidTestKotlin \
  :renderer:jetpacs:testDebugUnitTest \
  :renderer:material3:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :renderer:jetpacs:validateDebugScreenshotTest \
  :renderer:material3:validateDebugScreenshotTest
```

Compilation and screenshots prove deterministic code and pixels. Behavior that
crosses persistence, reconnect, offline delivery, Android navigation, IME, or
accessibility-service boundaries still needs its focused device flow.

## Tablet deployment

After the broad and connected gates pass, return to the `llm-poc-3` root and
refresh both the Companion APK and managed Elisp tree through the repository's
one-command path:

```sh
cd ..
tools/onboard-tablet.sh --vault shared --emacs-home emacs --skip-pylsp SERIAL
```

Install mode builds `:app:assembleDebug`, reinstalls it with app data
preserved, provisions the selected Emacs home, and prints the installed package
version and managed-tree audit. Pass the current serial from `adb devices -l`;
wireless ADB ports do not remain stable across reconnects.
