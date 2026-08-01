# PLAN — RF-2c: hoist `:wire` to commonMain (corrected H-ladder, H1–H8 + EXIT)

Expansion of PLAN-rf2-kmp-migration.md §3.1–3.5. First execution step: commit this plan
into the repo as `docs/PLAN-rf2c-hoist.md` (house precedent), including the runbook
corrections below.

## Context

RF-2b removed org.json; RF-2c makes `:wire`'s platform-freedom compiler-enforced by
hoisting everything pure into `commonMain`, leaving jvmMain = exactly the platform seam.
RF-2c unblocks RF-2.6 (headless host) and through it RF-1c/RF-3/RF-4c. The runbook's
Phase 3 predates C4 and line-verified exploration found **four hard defects** in it as
written, plus corrections — this plan supersedes §3.2/§3.3 where they conflict:

- **Defect A**: `WireLimits` + the frame-taxonomy exceptions (`FrameClose`/
  `FrameIncomplete`/`WireParseError`/`InvalidRequest`) live in the H3 file but are used
  by H1/H2 files → extract both to commonMain at H1 (with `ContentInvalid` from
  SpecValidator, freeing later ordering).
- **Defect B**: runbook H5 (trigger stack) depends on H6 files → trigger stack moves
  AFTER validators/stores. `TriggerValidator` has ZERO java.time (pure move). There are
  ZERO `DateTimeException` catches anywhere — that runbook clause is a no-op.
- **Defect C**: `SpecValidator:688` (common-bound) calls `ImageGuards` (pinned jvmMain)
  → split: common `ImageGuards` keeps the pure string policy (`isValidImageUrl` etc.);
  new jvmMain `object NetGuards` keeps InetAddress + `parseDataImage`. End-state jvmMain
  is **7 files**, not 6.
- **Defect D**: measure==emit breaks if `encodeFrame`'s UTF-8 flip (H3) and the five
  emit-coupled byte tapes flip in different commits (lone surrogate: `toByteArray` '?'
  = 1 byte vs `encodeToByteArray` U+FFFD = 3). One common `String.utf8Size()` helper;
  all six sites flip in the H3 commit. `jcsUtf8Bytes` is IMMUNE (hand-rolled tally
  already counts 3 — converges).
- **AtomicReference must NOT be demoted** (runbook H5 wrong): `TriggerFiringService:58`'s
  `detach` CAS is load-bearing (documented lost-update race). Port via an `AtomicRef`
  expect/actual (Kotlin 2.1.10 common stdlib has no atomic).
- **`catch (StackOverflowError)`** (`CompanionEngine:2446`, the R1 SPEC 7.1 funnel) has
  no common declaration → `catchingStackOverflow` expect.
- **TextUnits needs SIX helpers**, not three: + `charCountCompat`, `codePointsOf`,
  `stringFromCodePoints` (for `EditorSession.diff`'s `codePoints().toArray()` +
  `String(int[],int,int)`).
- **`Math.floor` ×9** (SpecValidator ×8, Substitution ×1) and `Character.*` ×4 are
  java.lang default-imports — invisible to the purity grep; explicit checklist items.
- **Lock ground truth**: 59 `@Synchronized` (CE 24, DQ 16, ReminderStore 8, TriggerStore
  6, TFS 5) + 1 `synchronized(this)` block (TFS:141-150, whose LD-16 callback at :150 is
  deliberately OUTSIDE the lock — must stay outside). 15+ reentrancy chains ⇒ the JVM
  `WireLock` actual MUST be reentrant (`synchronized(monitor)` is). `SurfaceStore` has
  ZERO locks by design (engine-monitor-protected) — do not add one.
- **kotlinx-datetime 0.6.2 is the only possible pin** on Kotlin 2.1.10 (0.7.x needs
  stdlib `kotlin.time.Instant` ≥2.1.20). linuxX64 klib published (needed for purity part
  2); `IllegalTimeZoneException` confirmed. The LIBRARY-LEDGER row already exists —
  amend its Verification cell, don't add one.
- Purity part-1 grep passes vacuously on a missing dir (exit 2 → `!` → 0) — use the
  `purity()` function below. jvmTest sees commonMain internals (same jvm compilation) —
  **zero test edits from moves**.

## Ratified decisions (this session)

1. **Model split**: Opus fleets execute the file moves and WireLock sweeps under
   zero-discretion sheets (H1, H5, H6.a/b/c, H7, H8); **Fable does H2, H3 (incl. the
   pre-rewrite pin test), H4**, plus all gates, count verification, purity runs,
   straggler/green recovery, and the EXIT sequence — per the standing division.
2. **dispatchDialogAction (`CompanionEngine:319`) missing lock: FIX in H8, flagged** —
   it lacks the engine lock its siblings hold (pre-existing race); the reentrant
   WireLock makes the wrap safe (it calls locked `sendRequest`). Count assertion
   becomes 24→25; deviation recorded in the commit body; DialogTest + P0 dialog pin
   guard it.
3. writeJsonAtomic dedup: YES — new jvmMain `JsonFileIo.kt` (own file: not
   Platform.jvm.kt, which is the actuals seam; not inside one File impl).
4. jvmTest keeps `java.time.Instant.parse` fixtures (jvmTest stays JVM); only the
   `ZoneId→TimeZone` constructor args change (~9 lines, 3 files).

## Environment / branch

Worktree `$WT = jetpacs/.claude/worktrees/rf2b-c5` (currently `rf-2b` @ `470e0fb`,
clean; `companion/local.properties` survives branch switches — verified).

```bash
cd $WT && git switch -c rf-2c
cd companion && ./gradlew :wire:jvmTest    # baseline 39 suites / 353 tests green
```

Gradle from `$WT/companion`. All greps `-a`. CI fires only on the eventual PR
(`rf-2c` ≠ `slop-fork/**`). Gate cadence: **G-wire + purity + goldens after every H
step**; **G-app at H4, H5 (it edits :app), H8, EXIT**; G-spec/G-elisp at EXIT.

## The ladder

### H1 (Opus) — commonMain exists: 7 pure files + two extraction files + build seam

- Create `wire/src/commonMain/kotlin/com/calebc42/ebp/wire/`; move
  `api(libs.kotlinx.serialization.json)` from jvmMain.dependencies to a new
  `commonMain.dependencies` block (delete from jvmMain — no duplicate).
- `git mv` to commonMain, verbatim: `JsonAccess.kt`, `JsonEquality.kt`, `Envelope.kt`,
  `Vocabulary.kt`, `MethodRegistry.kt`, `ToolbarEdits.kt`, `Session.kt`.
- NEW `commonMain/.../WireLimits.kt`: verbatim relocation of FrameCodec.kt:13-45 —
  `object WireLimits` (all 10 consts + their comments) AND the four taxonomy classes
  `FrameClose`/`FrameIncomplete`/`WireParseError`/`InvalidRequest`; delete those lines
  from FrameCodec.kt (same package — zero import churn).
- NEW `commonMain/.../ContentInvalid.kt`: the one class from SpecValidator.kt:19-20
  (`class ContentInvalid(val path: String, val reason: String) :
  Exception("$reason at $path")`); delete from SpecValidator.kt.
- Commit `RF-2c(H1): commonMain exists — 7 pure files hoisted; WireLimits+taxonomy and
  ContentInvalid extracted`. Checklist: renames shown as renames (`--numstat -M`);
  serialization dep appears once; donors still compile.

### H2 (Fable) — TextUnits + EbpJson + EditorSession + oracle property test

- NEW `commonMain/.../TextUnits.kt`: `internal fun String.utf8Size(): Int =
  encodeToByteArray().size` (the single Defect-D tape) + six codepoint helpers with
  java.lang-exact semantics INCLUDING lone surrogates (codePointAt returns the
  surrogate value; charCount 1; codePointsOf emits it; stringFromCodePoints
  re-materializes it): `charCountCompat`, `codePointAtCompat`, `codePointCountCompat`
  (throws IndexOutOfBounds on bad range), `offsetByCodePointsCompat` (throws on
  overrun; both callers pre-clamp), `codePointsOf(String): IntArray`,
  `stringFromCodePoints(IntArray, offset, count)` (full designs in planning record —
  hand-rolled surrogate math, no java.lang).
- Move `EbpJson.kt` (edits: `Character.isHigh/LowSurrogate` statics :212/:219/:224 →
  Char extensions; nothing else) and `EditorSession.kt` (edits: :35/:40/:65/:98/:126
  codePointCount → compat; :41 offsetByCodePoints → compat; :156 codePointAt → compat;
  :167 `Character.charCount` → `charCountCompat`; :182-183 `codePoints().toArray()` →
  `codePointsOf`; :190 `String(n, pre, …)` → `stringFromCodePoints`).
- NEW jvmTest `TextUnitsTest.kt`: property tests vs the java.lang oracles
  (`codePointAt/codePointCount/offsetByCodePoints/Character.charCount/
  codePoints().toArray()/String(int[],int,int)`) over ASCII/BMP/astral/lone-surrogate
  corpora (fixed list incl. `"\uD800"`, `"\uDC00"`, `"\uDC00\uD800"`, interleaved) + a
  seeded fuzz set; plus `utf8SizeDivergesFromJvmTapeOnlyOnLoneSurrogates` (documents
  Defect D: `"\uD800".utf8Size()==3` vs `toByteArray(UTF_8).size==1`).
- Commit `RF-2c(H2): TextUnits + EbpJson + EditorSession common — six codepoint helpers
  oracle-pinned`. Checklist: lone-surrogate corpus entries present; EbpJson
  unpaired-surrogate rejection tests green; no `Character.`/`Math.` outside comments.

### H3 (Fable) — FrameCodec: pin, then ByteArray rewrite + measure==emit, one commit

**H3a** (separate commit, against OLD code): add
`WireConformanceTest.compactionShiftsPartialFrameAfter64KiBConsumed` — a ~70KB frame A
+ partial frame B in one feed (A completes, offset>65_536, shift runs with B's prefix
pending), then B's remainder; assert both decode and B's content is intact.
`compact()`'s self-overlap branch currently has ZERO coverage.
Commit `test(wire): pin FrameDecoder compaction self-overlap past 64 KiB before the
ByteArray rewrite`.

**H3b**: `git mv FrameCodec.kt` → commonMain; ~14 lines across 6 functions:
- Header decode (:92-93) — **hand-rolled total byte→char map**
  (`CharArray(n){(buffer[offset+it].toInt() and 0xFF).toChar()}.concatToString()`),
  NEVER `decodeToString` (UTF-8 would corrupt ≥0x80 header bytes into U+FFFD and shift
  the error taxonomy; no fixture has non-ASCII headers — the harness can't catch it).
- :116/:126 `System.arraycopy` → `copyInto` (self-overlap form at :126; H3a is proof).
- Strict body decode (:181-188) → `body.decodeToString(throwOnInvalidSequence = true)`,
  same `catch (_: CharacterCodingException)` (kotlin.text, same name).
- :200 `toByteArray(UTF_8)` → `encodeToByteArray()`; :204 ASCII header likewise.
- Preserve exactly: both distinct MAX_HEADER_OCTETS gates (:86 partial, :90 complete),
  `scanned = maxOf(offset, size - 3)` watermark, deliver-then-throw ordering,
  `finish()`, buffer growth/compaction structure.
- **Same commit — Defect D**: `CompanionEngine:2481 utf8Len` body → `utf8Size()`;
  `DurableQueue:142-143`, `ContextlessEvents:79,:172`, `TriggerFiringService:179` →
  `.utf8Size()`. (SurfaceStore/SpecValidator identifier gates flip at their own hoists —
  not emit-coupled.) `jcsUtf8Bytes` untouched.
- Commit `RF-2c(H3): FrameCodec to ByteArray/commonMain; measure==emit — the five byte
  tapes flip in this commit`. Checklist: `decodeToString` appears exactly once
  (parseBody); all tape sites + encodeFrame in ONE diff; `ByteBuffer` gone module-wide;
  17 fixtures ×3 chunkings + H3a + byteGate pin green.

### H4 (Fable) — the platform seam: Platform.kt (seven declarations) + Auth

- NEW `commonMain/.../Platform.kt` (all seven, even though WireLock/AtomicRef/
  catchingStackOverflow are first consumed later — one stable seam):
  `secureRandomBytes(n)`, `hmacSha256(key: ByteArray, msg: ByteArray)`,
  `constantTimeEquals(a: ByteArray, b: ByteArray)`, `base64UrlDecode(text)`,
  `expect class WireLock() { fun <T> withLock(block: () -> T): T }` (KDoc: MUST be
  reentrant — 15+ chains), `expect class AtomicRef<T>(initial: T)`
  (get/set/compareAndSet; KDoc: TFS detach CAS load-bearing, no @Volatile),
  `expect fun <T> catchingStackOverflow(block: () -> T): T?`.
- NEW `jvmMain/.../Platform.jvm.kt`: SecureRandom singleton; Mac/SecretKeySpec HMAC;
  `MessageDigest.isEqual`; `Base64.getUrlDecoder()`; WireLock =
  `synchronized(monitor)`; AtomicRef wraps `j.u.c.a.AtomicReference` (wrapper, not
  typealias); catchingStackOverflow catches `StackOverflowError` → null.
- Move `Auth.kt` → commonMain: delete 5 imports + the SecureRandom field; :33 →
  `base64UrlDecode`; :39 → `secureRandomBytes(16).toHex()`; private helper renamed
  `hmacText(key, message) = hmacSha256(key, message.encodeToByteArray())` (avoids the
  member/expect same-name subtlety; 2 call sites :44/:49); public
  `constantTimeEquals(String?, String?)` survives as a delegating wrapper (overload-
  legal vs the ByteArray expect); verify* → byte-wrapped; :116 `"%02x".format` →
  `(it.toInt() and 0xff).toString(16).padStart(2, '0')` (Byte sign handled).
- **US_ASCII→UTF-8 invariant, recorded in the commit body**: all legal inputs are
  hex/ASCII by the NONCE/PROOF regex gates; for illegal non-ASCII input the elisp peer
  already encodes UTF-8, so the change ALIGNS cross-peer behavior. KAT
  (`katProofsReproduceExactly`) pins the vectors byte-exact.
- Gates: G-wire + **G-app**. Commit `RF-2c(H4): the platform seam — seven expects in
  Platform.kt; Auth common, KAT pins the vectors`.

### H5 (Opus) — validator tier + ImageGuards split

- Split (Defect C): move `ImageGuards.kt` → commonMain keeping ONLY
  `SUPPORTED_MEDIA_TYPES`/`redirectAllowed`/`isHttps`/`isStrictBase64`/
  `isValidImageUrl` (verbatim). NEW jvmMain **`NetGuards.kt`** (`object NetGuards` —
  not an expect pair, so no `.jvm.kt` suffix): `isBlockedAddress`/`isBlockedV4`/
  `isV4Mapped`/`isNat64`/`firstAllowedAddress`/`DataImage`/`parseDataImage` (its two
  cross-refs qualify as `ImageGuards.SUPPORTED_MEDIA_TYPES`/`ImageGuards.isStrictBase64`).
  `SpecValidator:688` needs zero edits. Forced edits: `:app/render/ImageLoader.kt`
  (:66 parseDataImage, :92 firstAllowedAddress → `NetGuards.`; isHttps ×3 unchanged;
  add import) and `ImageGuardsTest.kt` (14 call lines → `NetGuards.`), completeness by
  grep-count not eye.
- Moves + edits: `Substitution.kt` (:119 `Math.floor` → `kotlin.math.floor`),
  `CapabilityCatalog.kt` (verbatim), `TriggerValidator.kt` (verbatim pure move),
  `SpecValidator.kt` (`Math.floor` ×8 → floor — **invisible to the purity grep,
  checklist item**; identifier gates :298/:315/:475/:505 → `.utf8Size()`).
- Gates: G-wire + **G-app**. Commit `RF-2c(H5): validator tier common; ImageGuards
  split — common string policy / jvm NetGuards`.

### H6 (Opus, three sub-commits) — stores, datetime, 30 locks

- **H6.a** — four backing splits: each file stays commonMain with data classes +
  interface + Memory impl; File impl carved to new jvmMain `FileQueueStore.kt`/
  `FileSurfaceBacking.kt`/`FileReminderBacking.kt`/`FileTriggerBacking.kt` (verbatim,
  incl. their private helpers `reqBoolean`/`optLongOrNull` + doc comments). NEW jvmMain
  `JsonFileIo.kt`: `internal fun writeJsonAtomic(target: File, root: JsonObject)`
  factored from the SurfaceBacking:171-188 template; four duplicate blocks deleted.
  PersistenceCompatTest (the 9-pin upgrade gate) is the on-disk-format pin.
- **H6.b** — catalog `kotlinx-datetime = "0.6.2"` + library entry;
  `commonMain.dependencies { api(libs.kotlinx.datetime) }`; LIBRARY-LEDGER
  kotlinx-datetime row: AMEND Verification cell (re-verified 2026-07-31: last 0.6.x;
  0.7.x excluded by Kotlin 2.1.10 pin; linuxX64 klib published;
  IllegalTimeZoneException confirmed; correction — zero DateTimeException catches
  exist, the retarget clause was a no-op). Move `DurableQueue.kt` → commonMain: clock
  default → `{ Clock.System.now().toEpochMilliseconds() }` (kotlinx.datetime.Clock;
  signature/`var` unchanged, zero call-site churn); `@Volatile` →
  `kotlin.concurrent.Volatile`; **WireLock sweep 16**; two flagged micro-fixes (dead
  `val now` :118 deleted; :153 double `clock()` hoisted to a local).
- **H6.c** — `ReminderStore` (sweep 8), `TriggerStore` (sweep 6), `SurfaceStore`
  (**zero locks — assert 0 before AND after, do not add**; :161 → `.utf8Size()`).
- Gates per sub-commit: G-wire; step end: purity, goldens.

### H7 (Opus) — trigger stack (Defect-B order: after its deps)

- `ContextlessEvents.kt` → commonMain verbatim (LiveSession defined here; zero locks).
- `TriggerRuntime.kt` → commonMain: imports → kotlinx.datetime; :31 `zone: () ->
  TimeZone`; :372-373 `Instant.fromEpochMilliseconds(now()).toLocalDateTime(zone())`
  + `.time`; :393-394 `ldt.date.minus(1, DateTimeUnit.DAY).dayOfWeek` (day-wrap: pinned
  by the :168-172 wrapped-window fixtures); `WEEK`/`DAY_KEY` DayOfWeek constants
  verbatim (JVM typealias). Exception drift (`LocalTime.parse` →
  IllegalArgumentException) recorded, corrupt-store-only, no catch exists.
- `TriggerFiringService.kt` → commonMain: :41 → `TimeZone.currentSystemDefault()`;
  :58 → `AtomicRef<LiveSession?>(null)` (**CAS kept — never @Volatile**); `@Volatile`
  ×3 → kotlin.concurrent; **sweep 5 + the :141-150 block → withLock with the LD-16
  callback at :150 kept OUTSIDE** (withLock count after: 6).
- jvmTest (~9 lines): TriggerRuntimeTest/TriggerScheduleTest/OnFireTest `ZoneId.of("UTC")`
  → `TimeZone.of("UTC")` (+ imports); java `Instant.parse` fixtures KEPT.
- Gates: G-wire (SPEC 21.7 harness: TriggerRuntime/Schedule/OnFire/FiringService
  suites), purity, goldens. Commit `RF-2c(H7): trigger stack common — kotlinx civil
  time (zone supplier, day-wrap), AtomicRef CAS kept, 6 locks`.

### H8 (Opus sweep, Fable verification) — CompanionEngine

- `git mv CompanionEngine.kt` → commonMain (imports already pure).
- **WireLock sweep 24 → 25**: the 24 `@Synchronized` sites + the ratified
  dispatchDialogAction fix (:319 wrapped, flagged paragraph in the commit body;
  DialogTest + P0 dialog pin guard).
- `serializeReply` → `catchingStackOverflow { try { wireSerialize(msg) } catch (_:
  Exception) { null } }` (semantics identical to the dual-catch).
- Codepoint swaps: :1569/:1784/:1893 → `codePointCountCompat`; :1897/:1898 →
  `offsetByCodePointsCompat`.
- Gates: G-wire + **G-app**, purity, goldens. Commit `RF-2c(H8): CompanionEngine
  common — 25 locks (dispatchDialogAction's missing lock closed, flagged),
  catchingStackOverflow seam, codepoint compat`.
- Checklist: counts 24→25 exactly; every interior `return` became `return@withLock`
  (non-inline lambda = compile-enforced; eyeball the nested-dispatch paths anyway);
  `StackOverflowError` named only in Platform files.

## Shared procedures

**WireLock sweep, per file (zero-discretion, in every Opus sheet)**:
(1) `before=$(grep -ac '@Synchronized' F.kt)` asserted vs ground truth;
(2) add `private val lock = WireLock()`;
(3) per function: drop the annotation, wrap the ENTIRE body in `lock.withLock { … }`,
every `return e` → `return@withLock e`;
(4) after: `@Synchronized` count 0 AND `withLock` count == before (+1 for TFS's block /
CE's ratified fix);
(5) only TFS has a `synchronized(){}` block; its LD-16 callback stays outside;
(6) lock ORDER unchanged by construction (one WireLock per object, same sites).
Wrapping less than the whole body is the one silent failure mode — forbidden.

**purity() (after every step)**:
```bash
purity() {
  local d="companion/wire/src/commonMain"
  [ -d "$d" ] || { echo "FAIL: commonMain missing"; return 1; }
  grep -ranE '(^import (java|javax|android)\.)|[^.[:alnum:]](java|javax|android)\.[a-z]' "$d" \
    && { echo FAIL; return 1; }
  echo "-- review list (java.lang default-import blind spot) --"
  grep -ranE '[^.[:alnum:]](System|Thread|Math|Character|Integer|Runtime)\.' "$d" || true
}
```

## EXIT

1. From clean: `./gradlew clean :wire:jvmTest :app:testDebugUnitTest :app:assembleDebug`;
   `python3 ebp/validate.py`; `test/run-tests.sh`; `git status --short ebp/` empty (I1).
2. End-state jvmMain == exactly 7 files: `Platform.jvm.kt`, `NetGuards.kt`,
   `JsonFileIo.kt`, `FileQueueStore.kt`, `FileSurfaceBacking.kt`,
   `FileReminderBacking.kt`, `FileTriggerBacking.kt`.
3. Purity part 1 final; module lock audit: `@Synchronized` 0; withLock total 61
   (59 + TFS block + the ratified fix).
4. **Purity part 2 (scratch, never committed)**: add `linuxX64()` after `jvm()` + a
   throwaway `wire/src/linuxX64Main/.../Platform.linuxX64.kt` with `TODO()` bodies for
   ALL SEVEN actuals; `./gradlew --stop` first (WSL2 memory);
   `:wire:compileCommonMainKotlinMetadata` as fast pre-flight, then
   `:wire:compileKotlinLinuxX64` (cold: ~535MB into `~/.konan`, 15–25 min; disk verified
   858G free); revert both edits; `git status` clean.
5. Ledgers: PLAN-rf2 §0 STATE rows for H1–H8+EXIT (hashes, counts, deviations incl. the
   H1 extractions and the dispatchDialogAction fix); LIBRARY-LEDGER amendment present;
   dated correction notes appended at §3.2/§3.3: (i) the DateTimeException clause was a
   no-op; (ii) the AtomicReference demotion was wrong (CAS load-bearing).
6. Final commit `RF-2c: exit gate met — commonMain proven pure under linuxX64; jvmMain
   is 7 platform files`; push/PR (CI) + device smoke are the user's, per the runbook's
   final-verification section (reuse the C5 smoke drivers + tap procedure from memory).

## Verification (end-to-end)

Per-step: G-wire (353 + TextUnits/H3a additions) + purity + goldens; G-app at
H4/H5/H8/EXIT. The acceptance harnesses by risk: 17 fixtures ×3 chunkings + H3a + the
byteGate pin (FrameCodec); KAT vectors (Auth); the 23 P0 pins (stores/persistence);
SPEC 21.7 window fixtures (datetime); count-discipline + reentrant actual (locks);
linuxX64 frontend (purity). If any pin goes red, the C5 adjudication protocol applies
(test-only vs production-fix commits, never mixed).

## Critical files

- `companion/wire/src/jvmMain/.../` → commonMain: 21 of 28 files move; FrameCodec/
  Auth/EbpJson/EditorSession/TriggerRuntime/TriggerFiringService/DurableQueue carry
  edits; 4 backing files split; ImageGuards splits.
- NEW: commonMain `WireLimits.kt`, `ContentInvalid.kt`, `TextUnits.kt`, `Platform.kt`,
  `ImageGuards.kt`(common half); jvmMain `Platform.jvm.kt`, `NetGuards.kt`,
  `JsonFileIo.kt`, 4 `File*.kt`; jvmTest `TextUnitsTest.kt` + the H3a compaction test.
- `companion/wire/build.gradle.kts`, `companion/gradle/libs.versions.toml`,
  `docs/LIBRARY-LEDGER.md`, `docs/PLAN-rf2-kmp-migration.md` (§0 + correction notes).
- `:app`: `render/ImageLoader.kt` (2 renames + import) — the only :app source change.
- jvmTest: 3 trigger test files (~9 lines ZoneId→TimeZone), `ImageGuardsTest.kt`
  (14 renames).
