# Elisp design runtime experiment manifest

Date: 2026-09-01  
Checkpoint tag: `checkpoint/pre-elisp-design-runtime-2026-09-01`  
Required commit trailer: `Experiment: elisp-design-runtime`

## Checkpoint

| Repository | Branch | Checkpoint SHA |
|---|---|---|
| `jetpacs-poc` | `slop-fork/main` | `7cdda1e03efc05e067397c39c10c81ef16f32c9a` |
| `ebp` | `slop-fork/main` | `a5268e9a3418a9b933ba1257b6663be0c8ca2b46` |
| `ebp.el` | `main` | `22ace12725f63effa43bc65c21b9fd242802b99c` |
| `ebp-kmp` | `main` | `858d7ad3fc0ee60b5e9d12cddf698660e5b447e7` |
| `ebp-compose` | `main` | `7b0e020395494afcb1a9b8f25fd5e3eff303cd0b` |
| `jetpacs-components` | `main` | `76974f7a4711550440acf65ad6c30c82cc0fe474` |
| `glasspane-material3` | `main` | `08e7aa98de0b2674cb2c8f5a5f8de9052ddcb42d` |
| `jetpacs` | `slop-fork/main` | `1059a7a26d39b1c69735b0f5a71d664af5cb19f2` |
| `grove` | `main` | `9d8dbf75e7e2a2da352508a2b1b2e902ebc67ae0` |

All repositories were clean when tagged. The experiment does not amend EBP
`SPEC.md`, `contract.json`, Room schemas, or the existing
`jetpacs.components` and `glasspane.material3` extension manifests.

## Toolchain and limits

- Android Gradle Plugin: 9.2.1
- Kotlin: 2.3.21
- Compose BOM: 2026.08.00
- kotlinx.serialization: 1.11.0
- kotlinx.coroutines: 1.11.0
- Android compile/target SDK: 37
- Design configuration limit: 256 KiB
- Surface frame limit: 4 MiB
- Compiled-scope cache: 32 process-local entries
- Maximum-scope compilation p95 target: 16 ms

The renderer uses the experimental AndroidX Foundation Styles API and opts in
at the renderer module boundary. AndroidX `Style` is not a public Kotlin or
Elisp API of the design extension.

## Pre-change device baseline

Device: Pixel Tablet (`tangorpro`), API 37, wireless ADB serial
`192.168.1.181:33425`. Installed Companion: `0.1.0-w4`.

Five process-cold `am start -W` samples, preserving Room and app data, were
1554, 1534, 1530, 1534, and 1527 ms (`TotalTime`; median 1534 ms). The tablet
was behind its keyguard, so these samples are process-start measurements and
must not be represented as fully drawn cold-display measurements. Agenda
scrolling, slow/frozen-frame, and screenshot baselines were not measurable
before implementation because the keyguard could not be dismissed without
user authentication. Post-change device comparisons must record that missing
pre-change visual/scroll baseline explicitly.

## Verification commands

Run from each owning repository, narrow gates before broad gates:

```sh
(cd ebp-poc/ebp && python3 validate.py)
(cd ebp-poc/ebp-kmp && ./gradlew test)
(cd ebp-poc/ebp.el && ./test/run-tests.sh)
(cd ebp-poc/ebp-compose && ./gradlew test && ./gradlew connectedCheck)
(cd jetpacs-components && ./tools/check-projections.sh && ./test/run-tests.sh && ./gradlew test)
(cd glasspane-material3 && ./tools/check-projections.sh && ./test/run-tests.sh && ./gradlew test)
(cd jetpacs && ./test/run-tests.sh)
(cd jetpacs/companion && ./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest)
(cd grove && ./test-elisp/run-tests.sh && ./gradlew testDebugUnitTest lintDebug assembleDebug)
```

Screenshot validation and API-34 connected tests are additional device gates;
they are not implied by a successful host build.

## Implemented commits

| Repository | Commit | Purpose |
|---|---|---|
| jetpacs-poc | 08cf67b3aa1776eca56874410880af1032f56e04 | Checkpoint manifest |
| ebp-kmp | 43d241c77d883cab5ce2db82fd0442e0fc40d43d | Typed extension validation |
| ebp-kmp | 0701d78f186a27d3f0b4b1667f7a9f6b7c427495 | Live app admission profiles |
| ebp-compose | 6ba448ed0b15cfce64db00d29f53fa9f94e21556 | Closed format-2 projections |
| jetpacs-components | f6e549a3cf1cc66e80f25ec2a6d7d072e513c5c9 | Pure design model and authoring |
| jetpacs-components | 1f122304f100dd6c72392b4bac5f489b35fba4b8 | Foundation design renderer |
| jetpacs-components | d14eb3741b2be16c5bc1fafd8af0b4058897e111 | Bounded compiler performance |
| jetpacs | 46c81f2c9c8a4730ab27e3e5485a39fe0871e078 | Live Companion opt-in |
| jetpacs | 783b7cbdaef1e1dec4c9bd49dffa795b2195a4e9 | Offline cached-surface repair route |
| grove | 7fccae9bb76e8c572eb09047e3445f896face1d8 | Advertised design presentation |
| grove | 54cdb77df3e29534dcd4a505effe51f184f75996 | Configuration-aware lint fix |

The checkpoint-only ebp, ebp.el, and glasspane-material3 repositories have no
experiment source commits. EBP SPEC.md, contract.json, Room schemas, and the
existing format-1 extension manifests remain unchanged.

## Final verification record

The following gates passed on 2026-09-01:

- EBP validation; format-1, format-2, components, and Material projection
  checks; component, Material, Grove, ebp.el, and full Jetpacs ERT suites.
- Full ebp-kmp, ebp-compose, jetpacs-components, and glasspane-material3
  Gradle test suites.
- Companion testDebugUnitTest, lintDebug, and assembleDebug: 406 tasks.
- Grove ERT plus testDebugUnitTest, lintDebug, and assembleDebug.
- Grove connectedDebugAndroidTest on the Pixel Tablet: 57 of 57 tests.
- The Companion device-global experiment-preference recreation test on the
  Pixel Tablet.
- The Companion offline-recovery banner instrumentation test on the Pixel
  Tablet. Live layout inspection also verified cached offline surface ->
  Settings -> Set up or repair Jetpacs -> repair wizard, without clearing app
  data.
- Four focused Foundation renderer device tests covering one accessible
  pressable/action path, authored disabled/selected/toggled state, child
  modifier style application, and malformed-cache passive fallback.

The target-tablet compiler benchmark uses 20 warmups and 40 measured samples.
The maximum-collection scope was 64,345 bytes: cold compile p50 was
11.441447 ms, p95 was 13.93038 ms, cache-hit p50 was 2.808878 ms, and
cache-hit p95 was 4.35669 ms. A dense 249,945-byte near-wire-limit scope had a
cache-hit p95 of 7.162842 ms. Both measured p95 values satisfy the 16 ms
budget. The benchmark is committed as an instrumentation regression test.

Post-change process-cold Companion TotalTime samples were 1393, 1424, 1446,
1390, and 1510 ms (median 1424 ms). Against the 1534 ms checkpoint median,
that is about 7.2 percent faster and therefore within the maximum 15 percent
regression budget. The checkpoint samples were behind the keyguard, so this
comparison remains a process-start comparison rather than a fully-drawn
benchmark.

Before the connected-test runner removed its debug installation, the newly
installed Companion displayed the cached Room-backed Grove/REPL surface while
Emacs was not foregrounded. This verifies process-death cache restoration
without a Room migration. The runner's normal uninstall removed the debug
package afterward. Reinstallation and local-Emacs pairing succeeded, including
recreating the disposable Documents/jetpacs-installer staging cache.

## Exceptions and non-comparable gates

- The required API-34 managed-device APK and test APK compiled, but
  pixel2Api34Setup could not start its x86_64 emulator: this host has no
  /dev/kvm and the emulator requires hardware acceleration. The physical
  Pixel Tablet is API 37, so it cannot substitute for the exact-API assertion.
- Jetpacs screenshot validation ran 33 cases: 24 passed and 9 failed. Six
  failures are the intentionally absent navigation/popup candidates documented
  by TESTING.md. The other three are existing Tabs reference drift; Tabs and
  its dependencies were untouched by this experiment.
- Material screenshot validation ran 15 cases. Its 11 existing references
  remain under the stale com/calebc42/ebp/companion/ui package path while the
  tests render under com/calebc42/glasspane/material3/ui; the four catalog
  candidates are intentionally absent. No Material source changed, and no
  reference was overwritten merely to make validation green.
- There was no authenticated pre-change Agenda scroll or screenshot baseline,
  so a percentage-point scrolling comparison is not valid. After test
  teardown, the installed Termux-side Grove copy reported screen-home build
  errors while republishing, leaving no valid Agenda surface for a post-change
  gfxinfo sample. Slow/frozen-frame regression and direct GroveLight visual
  comparison therefore remain unmeasured.
- Dark mode, font scaling, compact/expanded screenshots, hover/focus, and
  disabled states remain covered by deterministic screenshot sources and
  renderer tests, but the outstanding screenshot-reference issues above
  prevent claiming a complete manual visual gate.
- Instrumentation proves local hover/focus/press state does not dispatch an
  action and activation dispatches exactly once. No packet capture was taken,
  so absence of socket traffic is established at the action boundary rather
  than by network observation.

## Rollback

Revert experiment commits in dependency-reverse order, within each repository
newest first:

1. `grove`
2. `jetpacs`
3. `jetpacs-components`
4. `ebp-compose`
5. `ebp-kmp`
6. `jetpacs-poc` manifest commits

`glasspane-material3`, `ebp.el`, and `ebp` are checkpoint-only unless a later
audit records an experiment commit in them. After reverting, verify every HEAD
against the table above or restore exactly from the annotated checkpoint tag.
Do not roll Room back with destructive migration or data clearing; this
experiment deliberately introduces no Room schema change.

## Exact reverse revert order

Run each repository's reverts newest first, in this dependency-reverse order:

1. grove: 54cdb77df3e29534dcd4a505effe51f184f75996, then
   7fccae9bb76e8c572eb09047e3445f896face1d8.
2. jetpacs: 783b7cbdaef1e1dec4c9bd49dffa795b2195a4e9, then
   46c81f2c9c8a4730ab27e3e5485a39fe0871e078.
3. jetpacs-components: d14eb3741b2be16c5bc1fafd8af0b4058897e111,
   then 1f122304f100dd6c72392b4bac5f489b35fba4b8, then
   f6e549a3cf1cc66e80f25ec2a6d7d072e513c5c9.
4. ebp-compose: 6ba448ed0b15cfce64db00d29f53fa9f94e21556.
5. ebp-kmp: 0701d78f186a27d3f0b4b1667f7a9f6b7c427495, then
   43d241c77d883cab5ce2db82fd0442e0fc40d43d.
6. jetpacs-poc: first revert the final audit commit containing this section
   while it is HEAD, then 08cf67b3aa1776eca56874410880af1032f56e04.

No reverts are required in ebp, ebp.el, or glasspane-material3. After the
reverts, every repository should resolve to its annotated checkpoint tag.
