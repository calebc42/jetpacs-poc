# Companion testing

Run commands below from `llm-poc-3/companion`. Gradle needs the configured JDK
and Android SDK; it writes only normal Gradle/build outputs plus screenshot
references when the explicit update task is used.

## Focused component gates

```sh
./gradlew :renderer:model:testDebugUnitTest
./gradlew :wire:jvmTest --tests '*SemanticsTest'
./gradlew :renderer:material3:compileDebugAndroidTestKotlin
./gradlew :renderer:material3:compileDebugScreenshotTestKotlin
```

The model suite pins registry-derived profiles, semantic name/default/state
projection, and the no-Material dependency boundary for EBP, wire, core,
renderer model, and the Foundation renderer. `SemanticsTest` consumes the
shared EBP semantic golden witnesses and proves builtin, feature, capture, and
unknown-member admission behavior. The Material renderer's Android tests cover
the shared Compose projection as well as receiver-owned component roles and
single-click-target behavior. Experimental Compose Styles opt-in exists only
in `:renderer:material3`.

## Screenshot references

```sh
./gradlew :renderer:material3:validateDebugScreenshotTest
```

The deterministic gallery covers a 3×3 window matrix (400/610/900 dp by
400/500/1000 dp), a dark 610×500 configuration, and 1.5× font scale at
400×500. Its 11 references live under
`renderer/material3/src/screenshotTestDebug/reference/`. The gallery uses
fixed strings, no clock, network, device state, animation clock, or EBP
session.

Update references only after reviewing the rendered change:

```sh
./gradlew :renderer:material3:updateDebugScreenshotTest
```

Reference updates are evidence of an intentional visual change, not a way to
make a validation failure disappear.

## Device semantics

With one authorized device connected:

```sh
./gradlew :renderer:material3:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.ebp.companion.ui.JetpacsComponentsSemanticsTest

./gradlew :renderer:material3:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.calebc42.jetpacs.renderer.compose.EbpSemanticsTest
```

The first class checks receiver-owned catalog and single-choice components. A
third case mounts a Material `OutlinedTextField` beside the custom Styles host;
that is the device regression for keeping Material3's binary ABI compatible
with the Compose 1.12 Styles API. The EBP class asserts heading, pane title,
live region, error, state description, collection/item, traversal, determinate
and indeterminate progress, and custom actions in the semantics tree. Its
Foundation button dispatches one custom action through the ordinary action sink
exactly once and retains one click target.

These tests do not replace manual TalkBack/Switch Access checks on the target
tablet. Verify pane announcements, heading navigation, collection position,
expanded state, and the existing labeled swipe actions; execute one custom
action and confirm Emacs handles its ordinary action once. Restore every
accessibility setting afterward, then confirm reconnect and ordinary touch
interaction. Keyboard/focus, font-scale, and touch-target checks remain part
of the same device envelope. `espresso-core` is pinned directly to the stable
AndroidX Test 1.7/3.7 release line because Compose UI Test 1.12's older
transitive Espresso cannot initialize on Android 17/API 37.

## Broad gate

```sh
./gradlew :wire:jvmTest \
  :renderer:model:testDebugUnitTest \
  :renderer:material3:testDebugUnitTest \
  :app:testDebugUnitTest \
  :app:assembleDebug \
  :renderer:material3:validateDebugScreenshotTest
```

Compilation and screenshots prove deterministic code and pixels. Behavior that
crosses persistence, reconnect, offline delivery, Android navigation, IME, or
accessibility-service boundaries still needs its focused device flow.
