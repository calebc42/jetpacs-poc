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

Device: Pixel Tablet (`tangorpro`), API 34, wireless ADB serial
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
(cd jetpacs/companion && ./gradlew testDebugUnitTest lintDebug assembleDebug)
(cd grove && ./test-elisp/run-tests.sh && ./gradlew testDebugUnitTest lintDebug assembleDebug)
```

Screenshot validation and API-34 connected tests are additional device gates;
they are not implied by a successful host build.

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
