# CLAUDE-REBUILD-ROADMAP — the hidden tutoring roadmap

**What this is.** Claude's answer key and process map for the clean-room,
from-scratch rebuild of EBP + jetpacs + glasspane, hand-written entirely by
Caleb with Claude tutoring Socratically. Caleb has chosen not to read this
document — it lives inside the sealed `llm-poc/` tree precisely so the same
wall that seals the PoC seals the answer key. It is not secret; it is
spoiler-protected. Future Claude sessions tutor **from this document alone**,
without re-reading the PoC (consult PoC sources only as last resort, and log
it in §6).

**Created:** 2026-07-21, from a dedicated planning session (3 explorer agents
over the three PoCs + 3 design agents). Chapter provenance: §1 = clean-room
process plan; §2 = curriculum plan; §3 = technical build map. All three were
merged here with unified labels (see §0.5).

---

## §0 STATE — read this first, update every session

```
CURRENT PHASE/UNIT: L0, late — Lesson 1 CLOSED 2026-07-20; now in the
  minimum-viable-spec stretch (the "paper twin of rungs 1-4" finish line
  Caleb defined: (A) Terminology+Conventions complete, (B) 3-sentence
  precedence clause, (C) Framing from his LSP gap-list, (D) vertical slice:
  session-open + surface.update + event.action + ~3 node types + direction
  table, (E) open-questions section). Manifesto v1-complete.
NOTE: rewrite-notes.org's "deliverables for next session" list (durability
  3-claims + spec §1 draft) is OLDER than this state — the persistent-memory
  file jetpacs-manifesto-spec-rewrite.md carries the fresher, much more
  detailed Lesson-1-closure + spec-draft-3 record. Trust memory > notes tail
  here; reconcile with Caleb at session start.
OUTSTANDING (from the draft-3 review, all previously delivered to Caleb):
  - Terminology: definitions became doctrine magnets — strip laws from
    definitions ("definitions define, they don't legislate"); keep the
    "interpreter for several deliberately weak languages" sentence as
    adjacent informative characterization; typo "interpretor"
  - Conventions: batch decision still owed (silence = allowed; decide);
    precedence clause owed
  - Doctrine: prohibitions with right defendants (skip-whole's defendant is
    the SENDER/Emacs); parked autonomy-ceiling laws stay parked
  - Framing: expand the single actor-less Content-Length sentence using his
    LSP gap-list dimensions, actor-first
  - Manifesto pre-publication: "the users actions" typo; AOSP URL + Emacs
    manual citations; reconcile "formally-specified structured-input" vs
    key-lists YAGNI
NEXT SESSION MODE: REVIEW current SPEC.org against the draft-3 findings,
  then drive toward rung 1 (first frame) as the next light — sections 1-5
  standing is the entry gate.
LIGHTS LIT: none (pre-L1)
LAST UPDATED: 2026-07-21 (roadmap created + STATE corrected same day)
```

Staleness tripwire: if LAST UPDATED is >7 days older than the mtime of
`/home/calebc42/pkb/projects/jetpacs/rewrite-notes.org`, reconcile with the
ledger before tutoring — Caleb may have advanced solo.

**Cold-start read order for any new session:**
1. This file, §0 then §0.5. 2. `rewrite-notes.org` (tail first — Caleb's live
position). 3. `ebp/DECISIONS.org`, `ebp/SPEC.org` (and AMENDMENTS.org once it
exists). 4. Only as needed: the §3 oracle for the current unit; then
`poc-audit/*` (pkb worktree `jetpacs-manifesto-spec-a70c9d`); PoC source only
as last resort — always logged in §6.

---

## §0.5 Master key — how to read this document

**Document map:**
- **§1 Process constitution** — seal rules, conveyance taxonomy, archaeology
  table, pseudocode rules P1–P5, session modes, repo/branch/CI governance,
  FSF posture, risk mitigations. The constitution every session operates under.
- **§2 Curriculum** — the full lesson sequence with per-unit challenge
  questions, deliverables, lights, traps, and motivation machinery.
- **§3 Technical build map** — the phase table with entry/exit gates, the
  unified decisions register D1–D34 (the answer key), architecture
  corrections, testing strategy, scope targets, risk register.
- **§4 Consolidated trap index** — one-line index of every known landmine.
- **§5 Session log · §6 Consultation log · §7 Retained-phrasings ledger** —
  append-only operational records.

**Canonical phase scheme** (used for gating; defined fully in §3):
`L0–L5` = the six-rung "Road to Hello World" ladder (construction).
`G1–G5` = growth rings. `U` = 1.0/upstream prep (continuous, concentrated at
the end). The curriculum chapter (§2) keeps its finer session-level unit
labels; this crosswalk binds them:

| Canonical phase | §2 curriculum units | One-line exit gate |
|---|---|---|
| **L0** Ground truth *(current)* | 0.1 Lesson-1 close-out, 0.2 envelope argument | §1 draft survives review; echo kata works; S2/S3 settled on kata evidence |
| **K-track** (parallel, Kotlin from zero) | K0 (runs beside L1); K1 (before L4); K2 (inside G1, before B8); K3 (inside G3); K4 (inside G4) | each toy runs on Caleb's phone |
| **L1** First frame + first conversation (rungs 1–2) | R1, R2 | hand-played handshake completes; pre-auth fail-closed ERT green |
| **L2** First pixel (rung 3) | R3 | elisp text on phone; unknown-node/key degrade tests pass |
| **L3** First round trip (rung 4) | R4 | counter round trip; unknown action → log+drop |
| **L4** First resurrection (rung 5) | R5 | the kill matrix passes |
| **L5** First offline tap (rung 6) | R6 | offline taps replay; **tag v0.1 — hello world finished** |
| **G1** Usable shell/platform | B1 contract+CI, B2 widgets wave 1, B3 state/forms, B4 shell/apps, B5 dialogs/prompts, B7 overload, K2, B8 companion-becomes-app, C1 Tier-0 buffer, C2 tablist, C3 M-x palette, C4 satellite-lite | core-load-test green (org-free, app-free); additive gate (a); metrics visible |
| **G2** Glasspane MVP (may run ‖ G3) | GP0–GP6 | daily dogfood; tier-boundary gate green |
| **G3** Device half | K3, E1 capabilities, E2 triggers, E3 screen-off surfaces | safety-inversion vectors green both sides; reboot re-arm |
| **G4** Editor sync | ED1 on-paper, K4, ED2 elisp, ED3 Kotlin | resync invariant + edit.apply race tests green |
| **G5** Breadth + polish | elective pool, glasspane ring 2, :app shell/onboarding | no spec-ahead debt at any tag |
| **U** 1.0 / upstream | F1 conformance, F2 versioning, F3 prose, F4 FSF prep | tagged 1.0 a stranger could implement from |

Ordering nuances the crosswalk encodes: §2 sequences C-phase (renderers)
before the editor and glasspane; §3 allows G2 (glasspane) to interleave with
G3 and pulls dogfooding earlier. Resolution: **§3's coarse gates govern; §2's
unit order inside a phase is flexible (the motivation valve)** — glasspane MVP
may start as soon as its G1 prerequisites (B-units + C1/C2) exist; the editor
phase always waits for boringly-solid transport.

**Decision numbering** (one register, three source vocabularies):
- `Dnn` (D1–D34) = the unified decisions register in §3 — the only
  authoritative numbering.
- `Sn` (S1–S10) = the 10-item syllabus in Caleb's rewrite-notes.org, as source
  labels. §2 uses these. Crosswalk: S1→D1, S2→D2, S3→D3, S4→D9, S5→D17,
  S6→D18, S7→D19, S8→D20, S9→D21, S10→D22.
- `§16.n` / `§14.n` = the PoC SPEC-2 reserved decisions / spec-ahead
  divergences: §16.1→D5, §16.2→D8, §16.3→D7, §16.4→D14, §16.5→D15, §16.6→D6,
  §16.7→D16; §14.1→D14, §14.2→D10, §14.3→D11, §14.4→D12, §14.5→D13.
- §2's editor units are ED1/ED2/ED3 and glasspane units GP0–GP6 (renamed from
  the design drafts to avoid colliding with D-numbers and G-phases).
- Note: D11 (`input_state`) is *decided/spec'd* at L5 as part of the replay
  state machine; its full forms treatment is implemented at G1 unit B3.

**The two standing prohibitions, stated once more:** the PoC is sealed to
Caleb (Claude conveys requirements, shapes-as-facts, traps — never PoC prose,
code, or golden lines); Claude never writes jetpacs/ebp/glasspane code, spec
prose, or goldens — generic examples and P1–P5-compliant pseudocode are the
ceiling. When in doubt, §1 governs.

---

# §1 Process constitution (clean-room, repos, governance, operations)

> Chapter-local §-references below (e.g. §1.5, §4.1) refer to sections *within this chapter*, except where they name the roadmap's top-level §0–§7 structure explicitly.

# Clean-Room Process & Repo/Governance Plan for the Jetpacs Rebuild

This is the process-machinery chapter for the hidden roadmap document. It defines HOW the rebuild runs — provenance, repos, CI, session operations — so any future Claude session can tutor defensibly and smoothly without re-reading the PoC. Curriculum content (what to teach when) comes from the other perspectives; this chapter is the constitution they operate under.

**Verified ground truth this plan builds on** (from live repo inspection, 2026-07-21):

- `ebp` repo: `main` = hand rebuild (SPEC.org 144-line skeleton, DECISIONS.org 2 entries, no CI yet); `llm-poc/` is a **git worktree mounted inside main's tree, checked out to branch `slop-fork/main`**, and `main`'s `.gitignore` contains `llm-poc/`. PoC CI (`validate.yml`) runs only on `slop-fork/**`.
- `jetpacs` repo: **already converted to the same pattern** — `main` tracks only `.gitignore`, `README.org`, `docs/TODO.org`; `llm-poc/` worktree pinned at `slop-fork/main` (commit `caf426a`); the PoC's vendored `ebp` submodule inside `llm-poc/` points at `18ad0a4` (ebp slop line). Backup branch `backup/slop-fork-main-pre-ndb` exists.
- `glasspane` repo: **NOT converted** — `main` is still the full PoC (bundle, tests, `ci.yml` with ERT + bundle-freshness jobs), submodule `jetpacs` with relative URL `../jetpacs.git`, backup branch `backup/glasspane-main-pre-ndb` exists.
- Teaching apparatus (`poc-audit/`, `spec-kit/`, org-neoroam) lives in a **pkb-repo worktree** at `/home/calebc42/pkb/projects/jetpacs/.claude/worktrees/jetpacs-manifesto-spec-a70c9d/` on branch `jetpacs-manifesto-spec`. `rewrite-notes.org` and `spec-example.org` sit loose at `/home/calebc42/pkb/projects/jetpacs/` (untracked by pkb `main`).

---

## 1. Provenance discipline

### 1.1 The clean-room model, named

Adopt the classic two-team structure explicitly, in writing:

- **Specification team (tainted): Claude.** Has read the PoC. Produces requirements, wire-shape facts, trap warnings, challenge questions, adversarial reviews. Never produces jetpacs/ebp/glasspane code, spec prose, or golden files.
- **Implementation team (clean): Caleb.** Never reads the PoC. Types every line of code, every normative sentence, every golden.
- **The wall is documented**, not just observed: PROVENANCE.md per repo (§1.5), a consultation log in the hidden roadmap (§4.1), and CI-enforced authorship gates (§1.5). A clean-room's defensibility is the paper trail of the wall, and this arrangement generates that trail as a side effect of normal operation.

### 1.2 What Claude may convey — the conveyance taxonomy

Five classes, from always-allowed to never:

1. **Platform facts** (Emacs/Android/JSON-RPC public APIs, RFCs, jsonrpc.el internals): unrestricted. These are the world, not the PoC.
2. **Behavioral requirements** ("the welcome must carry a per-surface revision snapshot, because otherwise a restarted Emacs pushes stale revisions that get silently rejected"): unrestricted. This is the core tutoring currency.
3. **Trap warnings** (jsonrpc.el fails open; code 32000 is a sentinel; lenient unknown-key parsing inverts a battery trigger's meaning): unrestricted, encouraged.
4. **Wire-shape facts** (method names, field names, envelope grammar, e.g. "the PoC's surface.update carried `{surface, revision, spec, stale_after_s?}`"): **allowed, with conditions** — see the ruling below.
5. **PoC expression** (spec prose sentences, code in any language, golden file lines pasted verbatim, identifier schemes internal to implementations): **never conveyed to Caleb**.

**The wire-shape ruling: YES, conveying shapes is permitted.** Justification, three-legged:

- Shapes are functional interface facts — methods of operation, not expression. A field named `revision` on a message named `surface.update` is the idea, and ideas are exactly what a clean-room spec team exists to transmit.
- The rebuild's shapes are **re-decided, not transcribed**: every shape arrives as a precedent to challenge inside the syllabus (SPEC-2 §16's seven reserved decisions, the 10-lesson ledger), and Caleb's SPEC.org tables are the new authoritative source. The rebuild already deviates deliberately (brand-free namespaces, ebp2: HMAC tag, renumbered error codes, resolved ttl_s/stale_after_s) — the deviation record itself is evidence of independent authorship.
- A prohibition would be incoherent: behavior at the wire IS shape. You cannot teach "the companion must reject non-newer revisions" without naming a revision field.

**Conditions on class 4:** shapes are conveyed as requirement sentences, org-table rows, or freshly-composed illustrative frames (Claude writes a new example frame; never pastes a golden line or a spec snippet). And the spec **prose** describing a shape is always Caleb's own — Claude gives him a checklist of what the section must accomplish (the spec-kit [PROMPT] idiom already does this), never draft paragraphs.

**One bounded exception to class 5:** single-sentence PoC maxims Caleb has already adopted in rewrite-notes.org as named invariants ("wrong state can only ever cost a feature, never a wrong edit"; "the wire declares UI one way and named actions the other; never code"). These may be retained verbatim **only if enumerated** in `ebp/PROVENANCE.md` under "Retained phrasings" (expected total: under ten sentences). A logged retention is defensible; an unacknowledged one is a landmine. Cap: one sentence each, no paragraphs, ever.

### 1.3 Goldens: performed, not copied

- **PoC goldens are never copied into rebuild repos.** The ladder already makes this natural: rung 1–2 goldens are captured from Caleb's own netcat/jsonrpc.el sessions; later goldens are captured from his own implementations.
- **Claude may consult PoC goldens privately as a checking oracle**: after Caleb produces a frame, Claude compares against PoC behavior and, where they diverge, raises the divergence *as a question* ("what should happen when the revision equals the cached one — does your frame's shape let the companion tell?") — never as "the PoC did X, change yours to match." Divergences that survive challenge are correct-by-decision and get a DECISIONS/AMENDMENTS entry.
- Consequence written into the roadmap: byte-compatibility with the PoC wire is a **non-goal**. The rebuild is a new protocol line; where shapes coincide it is because Caleb's spec decided so.

### 1.4 The archaeology exemption — enumerated, not vibes

"Archaeology" = artifacts that carry no application logic and are dictated by toolchains. These may be copied from `llm-poc/` directly (by Claude in SCRIBE mode or by Caleb without it counting as "reading the PoC"). The list is **closed**; anything not on it goes through the normal tutoring channel:

| Copyable (archaeology) | Not copyable (application structure) |
|---|---|
| `gradlew`, `gradlew.bat`, `gradle/` wrapper, version catalogs | `AndroidManifest.xml` (encodes services/receivers/intents = architecture; conveyed as class-2/4 requirements instead) |
| `settings.gradle.kts`, `build.gradle.kts` **dependency/plugin blocks** | Any `.kt`, `.el`, `.py` file |
| `.gitignore`, `.gitattributes`, editorconfig | `deploy.sh` / `deploy.ps1` (small, has logic — rewritten) |
| CI YAML **skeletons** (checkout/setup-emacs/setup-java steps, concurrency stanzas — the glasspane `ci.yml` step structure is the reference) | CI **check logic** (validators, grep tripwires — Caleb writes these; they're conformance artifacts) |
| `gradle.properties`, `local.properties` handling | `build-bundle.el` / `build-contract.el` (they encode the dependency graph = design) |

Every archaeology copy gets a line in that repo's PROVENANCE.md: file, source path, date, "toolchain plumbing, no application logic."

### 1.5 Provenance documentation machinery

Create in each rebuild repo (Caleb types these too, from a checklist of what they must say):

- **`PROVENANCE.md`** (repo root, on `main`): (a) statement of the clean-room arrangement — the PoC exists on `slop-fork/main`, is sealed to the author, and behavior was conveyed by an LLM specification intermediary as requirements/reviews only; (b) the archaeology table (§1.4) as instantiated for this repo; (c) retained-phrasings list (ebp only); (d) what a sign-off means here.
- **DCO-style sign-off:** Caleb commits normative/source changes with `Signed-off-by: Caleb Christensen <calebchr42@gmail.com>`, defined in PROVENANCE.md as "this is my own work, typed by me, not copied from the sealed branch."
- **CI provenance gate** (all three repos, on `main` pushes/PRs): fail if any commit touching source/normative paths (everything except `docs/CLAUDE-*`, `PROVENANCE.md` archaeology entries) has author ≠ Caleb **or** contains a `Co-Authored-By: Claude` trailer. This mechanically catches the single worst accident (Claude committing derived code) at the last line of defense.
- **Consultation log** (hidden roadmap §, see 4.1): every time a Claude session opens a PoC source file post-roadmap, one appended line: date, file, fact-class extracted (1–5), where it surfaced (question/review/roadmap patch). The goal state is that this log stays nearly empty because the roadmap suffices.

### 1.6 FSF assignment — process posture (not legal advice)

The roadmap should carry these as **process steps with the standing instruction to confirm everything with the FSF copyright clerk (assign@gnu.org) and, where relevant, emacs-devel** — Claude must never present these as legal conclusions:

1. **Start early.** Request the future-assignment questionnaire (`request-assign.future` for Emacs) around **Rung 4** (first round trip — the point the elisp becomes real), because processing takes weeks-to-months and must be done before the first upstream patch. GNU ELPA submissions require the same Emacs papers as core.
2. **Papers are per-package.** The elisp targets the **Emacs** assignment. The spec prose is a **separate work** — a freestanding standard is not a GNU package; its licensing (e.g., GFDL or another documentation license) is Caleb's decision, recorded in `ebp/DECISIONS.org`, and is *not* covered by Emacs code papers. Keeping spec-prose copyright separate from code copyright is another reason the prose must be 100% Caleb's expression (§1.2 class 5).
3. **Employer disclaimer:** the questionnaire asks about employment; if any employer could claim the work, a signed disclaimer is part of the papers. Flag this as a to-do for Caleb to answer honestly on the form.
4. **LLM disclosure posture:** emacs-devel has active norms about LLM involvement. The defensible, honest statement this process is engineered to support: *"Every line was written by me. An LLM that had read a discarded generated prototype acted as tutor and specification reviewer; it provided requirements, questions, and critique — no code, no prose. The full process is documented in PROVENANCE.md."* The seal, the sign-offs, the consultation log, and the CI gates exist so that sentence is checkably true.

---

## 2. Repo & branch layout

### 2.1 Codify the canonical pattern (already live in ebp and jetpacs)

One repo per artifact; inside each repo:

- `main` — the hand rebuild. Only Caleb-authored commits on source/normative paths.
- `slop-fork/main` — the frozen PoC. **Freeze rule (new, explicit):** post-seal, `slop-fork/main` accepts only (a) `meta:` commits touching `docs/CLAUDE-*` files (the hidden roadmap and its siblings), and (b) `life-support:` commits (§2.4). Nothing else, ever.
- `llm-poc/` — a git worktree of `slop-fork/main` mounted inside `main`'s tree, with `llm-poc/` in `main`'s `.gitignore`. This makes the seal boundary a **single directory name**, greppable, and means `rg`/project.el searches from repo root skip it by default (they honor `.gitignore` — note in the roadmap: Caleb should search with `rg`/project.el, never bare `grep -r`).
- `backup/*` branches: leave untouched.

**Do not create fresh repos.** Same-repo branches keep the pin auditable in one `git log`, keep remotes/CI wiring intact, and the worktree mount is what makes the mechanical guards (§5.2) simple.

### 2.2 Glasspane conversion (one SCRIBE session, do it soon — the seal should be uniform before Caleb's attention ever wanders there)

Checklist (repo plumbing, not jetpacs code — Claude may drive, Caleb approves):

1. `git -C glasspane push origin main` (ensure current PoC state is safe upstream).
2. `git branch slop-fork/main main && git push origin slop-fork/main`.
3. On `main`: remove all PoC content; seed with `README.org` (Caleb writes ~5 lines mirroring jetpacs/README.org's framing: "clean-room hand rebuild; PoC on slop-fork/main is the north star; pending core + spec"), `.gitignore` containing `llm-poc/`, `docs/TODO.org`.
4. `git worktree add llm-poc slop-fork/main`.
5. Move `ci.yml`: the existing ERT/bundle-freshness workflow belongs to the PoC — keep it on `slop-fork/main` (retarget its `branches:` to `slop-fork/**`); `main` gets a stub CI that grows per §3.
6. Submodule note: the `jetpacs` submodule (url `../jetpacs.git`) rides along on `slop-fork/main` pinned at its PoC commit — that commit lives on jetpacs' slop line, which is kept forever, so the pin stays resolvable. The rebuild `main` re-adds the submodule **later** (when Tier-1 work starts) pointing at jetpacs `main`.
7. The many stale `claude/*` branches: leave them; optionally list them in glasspane's PROVENANCE.md as pre-seal exhaust.

### 2.3 Submodule pointer map (rebuild line vs slop line)

| Consumer | Submodule | Points at | Bump discipline |
|---|---|---|---|
| `jetpacs@main` | `ebp/` (add at Rung 3, when the Android app exists) | `ebp@main` | Two-commit, one-direction flow: amend ebp (with AMENDMENTS entry) → bump pin in jetpacs, never the reverse |
| `glasspane@main` | `jetpacs/` (add when Tier-1 starts) | `jetpacs@main` | Same: core first, app follows |
| `jetpacs@slop-fork/main` | `ebp/` | `18ad0a4` (ebp slop line) | **Frozen forever** |
| `glasspane@slop-fork/main` | `jetpacs/` | PoC commit | **Frozen forever** |

### 2.4 The running PoC APK during the rebuild

Caleb daily-drives the PoC (it is his PKM). Rules:

- The installed APK and on-device bundles are **artifacts, not code** — nothing about the seal requires touching the phone. They keep running untouched.
- **Life-support:** if the PoC breaks in daily use, *Claude* fixes it (Claude is already tainted; Caleb does not read the diff), committed on the relevant `slop-fork/main` as `life-support: <what>`, built and deployed from the `llm-poc/` worktree. If a life-support fix changes behavior the roadmap describes, patch the roadmap in the same session and log it.
- **Coexistence:** the rebuild companion installs alongside, not over: distinct `applicationId` (recommend `com.calebc42.jetpacs.dev` via an applicationIdSuffix — gradle-level, archaeology), distinct launcher label ("EBP Dev"), and a **different loopback port for the dev profile — recommend 8766** (the PoC owns 8765 on the phone). Frame the port as what it really is spec-wise: a transport-profile parameter Caleb decides; 8766-during-coexistence is the deployment fact.
- **Cutover milestone** (post Rung 6 + Tier-1 parity for Caleb's actual daily workflows): switch the rebuild to the final port/id, uninstall the PoC APK, and `slop-fork/*` becomes pure archive. Until then, kill-tests and offline tests of the rebuild happen against the dev install without endangering the daily driver.

### 2.5 Naming and de-branding — decided once, day one

The PoC needed amendments #10/#11/#29 to scrub the brand and still left residue. The rebuild's rule: **spec and upstream-bound code are brand-free from their first line; brands live only in downstream shells.**

| Artifact | Name rule |
|---|---|
| Protocol / spec / repo `ebp` | "EBP / Emacs Bridge Protocol". No "jetpacs", no "glasspane", anywhere. Enforced by CI grep from the first commit (§3.2). |
| HMAC domain tag | Versioned, brand-free, decided by Caleb in the handshake lesson — the obvious candidate is `ebp2:`; what matters is it is *his* amendment-logged decision, never `jetpacs1:`. |
| Wire namespaces | No `jetpacs.*` action/method namespace reservation; companion-generic names (`companion.settings.open` pattern). |
| Elisp | **Brand-free `ebp-` prefix** for everything upstream-bound (`ebp.el`, `ebp-surfaces.el`, …). This is the FSF-endgame artifact; naming it `jetpacs-*` would force a mass rename at the worst moment. It lives in `jetpacs/emacs/` for now (matches the ladder and keeps one repo per half); extraction to its own repo/ELPA tarball is a named future milestone, cheap because the prefix is already right. |
| Kotlin | `:jetpacs` library / `:app` shell naming may keep the brand — the Compose companion is downstream forever. Package `com.calebc42.jetpacs`. |
| Tier-1 app | "glasspane" stays — downstream app brand, registers no wire vocabulary. |

---

## 3. Artifact governance from day one

### 3.1 One authoring surface, one generation direction

Already latent in SPEC.org's PROMPT blocks; make it constitutional:

- **SPEC.org org tables** (direction table, per-method param tables, node catalog, error registry) are the single authoring surface for shapes.
- **`contract.json` is generated** from those tables by a small extractor **Caleb writes** (org-table parsing in python-stdlib or batch elisp — it is conformance tooling, i.e. his code, and a good early exercise). CI regenerates and fails on diff. This resolves the PoC's three-sources-of-truth rot and #30's Option B in one move: prose owns behavior, tables own shapes, contract is a projection, **mismatch = build failure**.
- **Goldens are witnesses**, captured from performed sessions (§1.3), validated against the contract by Caleb's validator (stdlib-only, per the PoC's one good constraint: nothing in ebp requires an implementation to exist).
- **Precedence clause** (goes in SPEC.org Conventions, per the scaffold's prompt d): tables > prose > contract > goldens; any disagreement is a bug with a mandated direction of fix.

### 3.2 CI job lists per repo, with introduction rung (so nothing is retrofit debt)

**ebp `main`** (`.github/workflows/ci.yml`, created at Rung 1 with jobs 1–3, grown as marked):

1. `validate` — Caleb's validator: goldens vs contract, **both directions**, requests-have-results, notifications-don't. *(Rung 1; companion-direction coverage from Rung 2)*
2. `debrand-grep` — fail on `jetpacs|glasspane|jetpack` tokens in SPEC.org/contract/goldens. *(Rung 1)*
3. `provenance` — author + trailer gate per §1.5. *(Rung 1)*
4. `amendment-gate` — if the diff touches `SPEC.org|contract.json|goldens/`, it must also touch `AMENDMENTS.org`, else fail. "No entry, no amendment" as a machine rule. *(strict from Rung 2; create `AMENDMENTS.org` at Rung 1 with the GDRP-style column set: # / date / sections / change / fixtures / ratified)*
5. `contract-gen-diff` — regenerate contract.json from SPEC.org tables, fail on drift. *(Rung 3–4, when the method/node tables exist)*
6. `landmine-grep` — fail if error registry contains `32000`, `-1`, or −32899..−32800. *(Rung 4, with the error registry)*
7. `golden-coverage` — floor counts per direction + per-method witness check (every registered method has ≥1 golden). *(Rung 4 onward, ratchet the floors)*

**jetpacs `main`:**

1. `provenance` *(first code commit)*
2. `elisp` — byte-compile clean + ERT. *(Rung 1)*
3. `wire-conformance` — elisp constructors' output byte-matches ebp goldens; Kotlin `WireGoldenConformanceTest` analog. *(Rung 3)*
4. `core-load-test` analog — foundation loads with no app and **no org**; grep tripwire for `require 'org` outside the sanctioned boundary (the rebuild's boundary decision: org lives in glasspane, not core — so the grep allowlist starts *empty*). *(the moment a second module exists)*
5. `opinion-grep` — no org TODO-keyword regexes / agenda strings / app opinion in the `:jetpacs` Kotlin library (the PoC's four leaked pieces become the grep's seed patterns). *(when the Kotlin library module is created)*
6. `submodule-pin` — ebp pin must be an ancestor of ebp `main`; pin bumps must be solo commits referencing the ebp amendment #. *(Rung 3)*
7. `kotlin` — unit tests + lint. *(Rung 3)*
8. `bundle-staleness` — regenerate bundle, `git diff --exit-code`. *(only when/if the bundle build exists; the glasspane ci.yml stanza is the archaeology reference)*

**glasspane `main`** (all introduced when Tier-1 work starts): `provenance`; headless ERT vs the submodule core; `bundle-staleness`; `pack-json-snapshot` (regen + assert); `tier-boundary-grep` (app defines no wire node types, no transport code); `submodule-pin`.

### 3.3 Amendment discipline

- `ebp/DECISIONS.org` = architectural decisions (the 10-lesson syllabus outputs land here, one entry each — it already has 2).
- `ebp/AMENDMENTS.org` = the append-only change ledger for normative artifacts, enforced by CI job 4. jetpacs/glasspane get lightweight `CHANGELOG.org` + the pin-bump rule instead — the spec repo is where amendment law bites.

### 3.4 What a "release" is (one-person governance)

A release is: **an annotated git tag on a commit that (a) is CI-green and (b) is at least one full day old** (the cooling-off day is the reviewer this project doesn't have). Tag names: `ebp` → `spec-vX.Y[-draftN]`; jetpacs/glasspane → `vX.Y`. The tag date goes into the AMENDMENTS.org `ratified` column for every row it covers. Consumers pin tags/submodule SHAs, never branches. Version-number budget (anti-sprawl rule from the PoC's four-numbers-one-burned lesson): the rebuild carries at most three (protocol_version, spec_version, contract_format) and DECISIONS.org must define what each pins before a second one exists.

---

## 4. Session operations

### 4.1 The hidden roadmap document

- **Path:** `/home/calebc42/pkb/projects/jetpacs/jetpacs/llm-poc/docs/CLAUDE-REBUILD-ROADMAP.md` — inside the sealed tree, so the same honor-system wall that keeps Caleb out of PoC code hides the answer key; and inside the *companion* PoC's docs dir because that repo is the hub (largest doc corpus, and its slop line already vendors the ebp pin).
- **Commit policy:** committed on jetpacs `slop-fork/main` with `meta(roadmap):` prefix — permitted by the §2.1 freeze rule; pushed so it survives machine loss. (It will be publicly visible on GitHub; so is the sealed PoC. The wall is Caleb's discipline plus §5.2's guards, not secrecy.)
- **Required structure** (this chapter defines the skeleton; other perspectives fill §§2–4):
  - `§0 STATE` — current lesson, current rung, outstanding Caleb deliverables, next-session mode, last-updated date. **The first thing any session reads.**
  - `§1 Process constitution` — this plan, distilled: seal rules, conveyance taxonomy, pseudocode line, archaeology table, session modes, checklists.
  - `§2 Curriculum map` — lessons × rungs (other perspectives).
  - `§3 Answer key / oracles` — per-rung PoC-derived facts, expected shapes, model answers, kill-test scripts.
  - `§4 Trap list` — jsonrpc.el landmines, Android gotchas, the PoC's discovered-by-failure designs.
  - `§5 Session log` — append-only, one line per session (date, mode, what advanced).
  - `§6 Consultation log` — append-only (§1.5).
  - `§7 Retained-phrasings ledger` — mirrors ebp/PROVENANCE.md's list.

### 4.2 Visible vs hidden split

- **`rewrite-notes.org` (Caleb's, visible):** everything Caleb has *already derived* — settled decisions, his ladder position, assigned deliverables, adversarial-review verdicts he has absorbed. Caleb owns the text; Claude may propose lines during a session but the ledger is his learning artifact.
- **Hidden roadmap (Claude's):** everything Caleb *hasn't discovered yet* — the answer key, the question banks, upcoming traps, the full process map.
- **Transfer rule:** content moves from hidden → visible only through tutoring output (questions, reviews, requirement statements), never by pasting roadmap sections.

### 4.3 Session modes and the end-of-session checklist

Three declared modes (a session states its mode; mixed sessions state transitions):

- **TUTOR** — Socratic. Touches no repo files except (optionally) appending assignments Caleb dictates into rewrite-notes.org at his direction.
- **REVIEW** — adversarial review of Caleb's drafts (spec sections, code, goldens). Output is findings and challenge questions; still writes no code.
- **SCRIBE** — machinery only: roadmap updates, glasspane conversion, life-support, archaeology copies, CI plumbing. Never touches rebuild source/normative files.

**End-of-session checklist (every session, any mode):**
1. Update roadmap §0 STATE; append §5 session-log line.
2. Append §6 consultation-log entries if any PoC file was opened.
3. Confirm Caleb's deliverables are recorded in rewrite-notes.org.
4. Commit roadmap (`meta(roadmap):` on jetpacs `slop-fork/main`).
5. Sanity sweep: `git status` across the three repos — no Claude-authored modifications on any `main` source path.

### 4.4 Cold-start recovery protocol (read order)

1. `jetpacs/llm-poc/docs/CLAUDE-REBUILD-ROADMAP.md` — §0 STATE, then §1 constitution.
2. `/home/calebc42/pkb/projects/jetpacs/rewrite-notes.org` — tail first (Caleb's live position and drafted answers).
3. The active repo's `DECISIONS.org` / `AMENDMENTS.org` / `SPEC.org` (ebp) or `README.org`.
4. Only as needed: roadmap §3 oracle for the current rung; then `poc-audit/*` in the `jetpacs-manifesto-spec-a70c9d` worktree; **PoC source only as last resort, always logged in §6.**
- Staleness tripwire: if §0's last-updated date is >7 days older than rewrite-notes.org's mtime, reconcile before tutoring (Caleb may have advanced solo).

### 4.5 Memory indexing

- Create `CLAUDE.md` at `/home/calebc42/pkb/projects/jetpacs/` (covers sessions launched from the hub dir) and in each rebuild repo root — **short and Caleb-visible** (rules only, no answers): the seal ("never open llm-poc/ with Caleb; it is sealed to him"), the never-write-code rule, the roadmap path, the read order, the session modes. ~10 lines each.
- Optional hard guard: per-repo `.claude/settings.json` permission **deny** rules for `Edit`/`Write` on source globs (`emacs/**`, `app/**`, `jetpacs/src/**`, `*.el`, `*.kt`) with docs paths allowed — belt to the CI gate's suspenders. Flag for Caleb's consent (it's his config).

---

## 5. Risks specific to this arrangement

### 5.1 Claude pastes derived code

Defense in depth, in order: (1) CLAUDE.md standing prohibition in every repo; (2) session-mode discipline (only SCRIBE writes files at all, and never source paths); (3) optional permission-deny rules (§4.5); (4) the CI provenance gate (§1.5) as the backstop that makes an accident *visible and revertible* rather than silent. Plus the transcript-side rule: Claude never puts runnable elisp/Kotlin for jetpacs logic in a message at all (§5.3), so there is nothing *to* paste.

### 5.2 Caleb accidentally opens PoC files

The PoCs stay where they are (moving them breaks worktrees, pins, and life-support builds — **explicitly rejected**). Guards that break nothing:

- **Emacs guard (primary — Caleb lives in Emacs):** a ~4-line `find-file-hook` in Caleb's personal init: if `buffer-file-name` matches `/llm-poc/` → `read-only-mode`, red `header-line-format` "SEALED PoC — ask Claude instead." This is Caleb's own generic config (Claude may sketch it under the generic-example rule; Caleb types it). More robust than committed `.dir-locals.el` (no unsafe-local-variable prompts, works across all three repos at once).
- **Shell guard:** a `PROMPT_COMMAND`/precmd snippet printing a red `⛔ SEALED` banner when `$PWD` contains `llm-poc`.
- **Search hygiene:** already structurally handled — `llm-poc/` is gitignored, so `rg` and project.el skip it; roadmap notes the habit "search with rg/project.el, never bare `grep -r` from repo root."
- The APK, the slop-fork branches, and the worktree mounts are untouched by all of the above.

### 5.3 Pseudocode drifting into dictation — the operational line

"Anything short of the answer" is defined by five rules:

- **P1 Notation:** pseudocode for jetpacs logic is numbered prose steps or diagrams — never syntactically valid elisp/Kotlin, never a fenced block tagged with a real language.
- **P2 Names:** Claude never invents the identifiers Caleb will type. Wire names (Caleb's own spec facts) and public platform API names are fair game; internal function/variable names are his.
- **P3 Granularity ceiling:** one step per responsibility; if a step would transliterate to a single line of code, it is too fine — collapse it. Control-flow outline, not statement sequence.
- **P4 Transcription test (send-time check):** "could a non-programmer produce working code by token-for-token transliteration of this message?" If yes, it is dictation — raise the altitude or switch domains.
- **P5 Escalation path when Caleb is stuck**, in order: sharper question → trap warning → parallel-domain worked example (the GDRP trick — full code is fine in a *fictional* protocol/app) → P3-ceiling pseudocode → name the exact Emacs/Android manual node to read. **There is no step 6.**

### 5.4 Remaining risks

| Risk | Mitigation |
|---|---|
| Roadmap goes stale; future sessions tutor from wrong state | End-of-session checklist item 1 + the §4.4 staleness tripwire |
| Life-support fix silently diverges PoC from the roadmap's description | Same-session roadmap patch rule (§2.4) + consultation log |
| `poc-audit/`/`spec-kit` live only in a prunable `.claude/worktrees` checkout of an unpushed pkb branch | SCRIBE task, early: push `jetpacs-manifesto-spec` to the pkb remote (or merge to pkb main); record the worktree path in roadmap §1 |
| `rewrite-notes.org` / `spec-example.org` are untracked loose files (the ledger is the tutoring spine and has no backup) | SCRIBE task, early: get them under version control (commit into pkb, or move into the ebp repo's docs/ — Caleb's call), and out of Emacs-lockfile limbo |
| Port/id collision bricks the daily-driver PKM | §2.4 coexistence rules (8766 + `.dev` id) until the cutover milestone |
| FSF papers arrive late and block upstreaming | §1.6 step 1 — initiate at Rung 4, track as a roadmap milestone |
| Brand leakage requiring a #10/#11/#29-style scrub | §2.5 day-one naming table + `debrand-grep` CI from the first ebp commit |

---

## 6. Build order for this machinery (what SCRIBE does first)

1. Write `CLAUDE-REBUILD-ROADMAP.md` (skeleton per §4.1; this plan distilled into §1; STATE block seeded from rewrite-notes.org's Lesson-1 position) → commit `meta(roadmap):` on jetpacs `slop-fork/main`.
2. Safety commits: push `jetpacs-manifesto-spec` pkb branch; version-control rewrite-notes.org/spec-example.org.
3. Glasspane conversion (§2.2).
4. `CLAUDE.md` files (§4.5) + propose the two Caleb-side guards (§5.2) as his next-session five-minute task.
5. PROVENANCE.md checklists queued as Caleb deliverables; ebp CI jobs 1–3 + `AMENDMENTS.org` land with Rung 1 (which is also the current curriculum position — Lesson 1's spec-§1 deliverable feeds directly into it).

### Critical Files for Implementation

- /home/calebc42/pkb/projects/jetpacs/jetpacs/llm-poc/docs/CLAUDE-REBUILD-ROADMAP.md (the deliverable — to be created on jetpacs `slop-fork/main`)
- /home/calebc42/pkb/projects/jetpacs/rewrite-notes.org (Caleb's visible ledger; the hidden doc's counterpart and staleness reference)
- /home/calebc42/pkb/projects/jetpacs/ebp/SPEC.org (the single authoring surface all shape-governance hangs on)
- /home/calebc42/pkb/projects/jetpacs/ebp/DECISIONS.org (decision/amendment discipline seed; AMENDMENTS.org is created beside it)
- /home/calebc42/pkb/projects/jetpacs/glasspane/.github/workflows/ci.yml (the CI-skeleton archaeology reference, and the file the glasspane conversion relocates to slop-fork)

---

# §2 Curriculum (lesson sequence, session loop, pedagogy)

> Unit labels are §2-local (0.x, K*, R*, B*, C*, ED*, E*, F*, GP*); the canonical phase crosswalk is §0.5. Bare `Sn` = the 10-item syllabus source labels — crosswalk to the authoritative D-register in §0.5.

# JETPACS REBUILD — CURRICULUM ARCHITECTURE (Hidden Roadmap, Pedagogy Core)

**Audience:** future Claude tutoring sessions only. Caleb never reads this document. Suggested storage: the PoC docs dir (`/home/calebc42/pkb/projects/jetpacs/jetpacs/docs/`), which is inside the sealed zone.

**What this is:** the complete lesson sequence from the current position (mid-Lesson-1, manifesto partially drafted) through spec 1.0, elisp foundation, Kotlin companion, and glasspane. It extends — never replaces — the two structures Caleb already owns: the **10-decision syllabus** (S1–S10, rewrite-notes.org) and the **6-rung Hello-World ladder** (R1–R6). Everything beyond rung 6 is the "growth phase" this document designs.

---

## 0. Standing rules (apply to every session, non-negotiable)

1. **The PoC is sealed.** Never name PoC files, quote PoC code, or say "the PoC did X" as an argument. Every sealed lesson is delivered as a *scenario challenge*: "What happens if an old companion receives a trigger with a gate field it doesn't understand?" — Caleb must re-derive the failure; Claude confirms and lets him record it. The `poc-audit/` docs and `rewrite-notes.org` are the only PoC-derived artifacts Caleb may read.
2. **Claude never writes jetpacs/ebp/glasspane code.** Generic examples and pseudocode of jetpacs logic are allowed; stopping point is anything Caleb could paste. Build plumbing (gradle files, CI YAML, run-tests.sh shape) is "archaeology, not copying" — Claude may walk him through the PoC's wiring verbatim.
3. **Clean-room means Caleb's design wins.** When Caleb's draft diverges from the PoC and is sound, prefer his. Escalate only when a divergence walks into a *documented PoC failure* — and escalate as a scenario question, not a correction.
4. **Spec never more than one rung ahead of something visible.** If two consecutive sessions produced only prose, the next deliverable must turn a light on.
5. **Every MUST is one conformance item.** Adversarial review of any spec draft always includes the extraction test: "read me the checklist a stranger derives from this section."
6. **Every kept-verbatim PoC invariant must be re-derived, never dictated.** The four non-negotiables (forward compat; fail-closed auth; revision monotonicity; never-code) each have a scheduled unit where Caleb argues his way to them.
7. **Ledger discipline.** Caleb writes all entries in `rewrite-notes.org` and `ebp/DECISIONS.org` himself (format: number, date, decision, rejected alternative, justification). Claude reviews wording, never authors. Claude's only writable artifact is this roadmap's PROGRESS block.

---

## 1. The session loop protocol

**Before every session Claude checks (in order):**
1. This roadmap's PROGRESS block → current unit, assigned deliverable.
2. `git -C` logs/diffs since last session in: `ebp/`, `jetpacs-site/`, the rebuild code repos, plus `rewrite-notes.org` tail and `ebp/DECISIONS.org`.
3. Whether the assigned deliverable exists. If missing → run an *unblock session* (smaller Socratic step, re-cut the deliverable in half; never guilt).
4. The current unit's **trap** entry below — pre-load the scenario question that teaches it.
5. Whether the "one rung ahead" rule is in danger (two prose sessions in a row → force a light).

**Session shape (60–90 min equivalent):**
1. **Adversarial review** of the deliverable. Find at least one real flaw; classify it (factual / normative-language / design / testability). Point and question; Caleb fixes live or as homework. Never rewrite his text.
2. **Retire decisions.** If the deliverable settles a syllabus item or a §16 reserved decision, Caleb writes the DECISIONS.org entry now, in-session.
3. **Open the next material Socratically** — the unit's challenge questions below, before any explanation.
4. **Teach concepts** (generic examples / pseudocode only).
5. **Assign the next deliverable** — sized to fit before next session; state its "light" explicitly.
6. **Bookkeeping:** Caleb updates the ledger state line; Claude updates the PROGRESS block here.

**Cadence assumption for sizing:** 1 session = one tutoring exchange + 2–6 hours of Caleb's solo work. Estimates below assume ~2 sessions/week. Total program: **~85–115 sessions ≈ 10–14 months.** Say this to Caleb honestly if asked; the ladder exists precisely because the end is far.

---

## 2. Decision spine — where every open decision gets retired

| Decision | Retired in |
|---|---|
| S1 who-is-the-server (1a–1d) | Unit 0.1 |
| S2 rent-or-own envelope | R1–R2 |
| S3 jsonrpc.el-or-justify | R1 (experimental evidence) |
| S4 snapshots + monotonic revisions (+ keys policy) | R5 (policy), B2 (:key normative) |
| S5 never-code + escape-hatch ledger | R4 (principle); hatches added at B4 (builtins), B5 (M-x/prompts), ED-phase (edit.command), E2 (intent.start, on_fire); one-place enumeration at F1 |
| S6 offline queue/replay state machine | R6 |
| S7 editor shadow-sync | Phase ED |
| S8 companion autonomy ceiling / non-Turing line | E2 |
| S9 additive-growth law / unknown-key taxonomy | drafted pre-R3 (spec §7), crowned at E2, closed F2 |
| S10 versioning + governance bound to CI | seeded B1, closed F2 |
| §16-1 domain-tag retag (`ebp1:`→?) | R2 |
| §16-2 rpc.cancel vs dialog.dismiss | B5 |
| §16-3 error-code numbering (1200/1400 ranges) | R4 first codes; full registry B1 |
| §16-4 edit.* method promotion timing | S1 (recommend: promote from day one — kills hand-rolled correlation) |
| §16-5 contract shape | B1 (hand-owned, Option-B inheritance re-derived) |
| §16-6 fixed vs negotiated frame cap | R1 provisional (fixed 4 MiB), finalized F2 |
| §16-7 WebSocket profile | F2 (reserved table row only) |
| §14-div ttl_s vs stale_after_s | R6 (design-clean: `ttl_s` = queue expiry ONLY; staleness field named fresh) |
| §14-div input_state | B3 |
| §14-div box-model attrs | B2 (small subset now, rest reserved) |
| §14-div enabled/can_disable | B3 (recommend: reserve) |
| §14-div edit.* promotion | Phase ED |

---

## 3. PHASE 0 — Resume point (2–3 sessions)

### Unit 0.1 — Lesson 1 close-out *(current; deliverables already assigned)*
- **Prereq state:** roles Emacs/Companion provisionally settled; durability thesis drafted at foot of rewrite-notes.org.
- **Deliverables (already assigned — review these first):** (a) durability paragraph as three separate claims; (b) spec §1 Conformance & Terminology first draft (two role names, one system-defining sentence, RFC 8174 boilerplate).
- **Adversarial review angles:** do the three claims cover the *screen-off* case (the logged gap)? Does §1 avoid bind/dial language (that's profile material)? Does every noun in the system-defining sentence resolve to a Terminology entry?
- **Also this unit:** manifesto Foundation fixes (mouse/Engelbart date, Emacs-22 TCP, MobileOrg just-so story), and promote the loopback co-location fact into a sentence.
- **Decisions retired:** S1 complete → DECISIONS.org entries 3+ (roles defined by function + who-sends-hello, never by dial direction; durability = Companion's disk; the durable-artifact list named for later versioning).
- **Light:** SPEC.org §Conventions + §Terminology stand without [PROMPT] blocks.
- **Trap:** role names flip referents mid-document (the PoC's client/companion drift). Grep his drafts for "client"/"server" outside the per-RPC JSON-RPC sense.

### Unit 0.2 — Envelope argument (S2/S3 on paper)
- **Challenge questions:** "What do you get for free by renting JSON-RPC 2.0? What do you give up? Where do your three deltas (self-describing kind, direction, string codes) live without breaking conformance — wire fields, or contract metadata?" (Answer to converge on: direction is contract metadata, not a spoofable `from` field — the logged spec/manifesto drift.)
- **Deliverables:** Doctrine section draft (Emacs computes / Companion renders+reports, as convictable prohibitions — GDRP form); Transport Profiles table skeleton (android-loopback published; desktop-uds, ws-loopback reserved).
- **Light:** none yet — which is why R1 must start immediately after. Do not let this unit grow.

---

## 4. PHASE A — The ladder, R1–R6 (+ Kotlin K-track from zero) (~16–20 sessions)

The K-track runs **in parallel** with R1–R2 (spec-heavy weeks get a code day; this is the primary motivation-protection move of the phase).

### K0 — Kotlin/Android from zero (neutral toys, no jetpacs logic) (5–7 sessions, parallel)
- **K0a Kotlin language** (JVM scratch files): val/var, null safety, data classes, lambdas, collections, coroutines first contact. Toy: word-frequency counter CLI.
- **K0b Activity + Compose basics:** composables, `remember`/state, recomposition, Modifier, layout (Column/Row/Box — note the happy accident: Compose's own layout trio mirrors the future node vocabulary; let Caleb notice, don't tell). Toy: dice-roller/tip-calculator-class app.
- **K0c Sockets + coroutines:** a "loopback parrot" — JVM echo server, then the same on Android with a foreground Activity and a background coroutine reading a socket. Teaches: threads vs coroutines, Dispatchers, why UI work can't happen on the socket thread.
- **Light:** his own APK, on his phone, echoing what he types. Screenshot ritual #0.
- **Trap to seed early:** Compose has **no error boundary** — one throwing composable kills the app. Have him crash the toy on purpose.

### R1 — First frame (2 sessions)
- **Prereq:** §Conventions/§Terminology/§Doctrine/§Transport-Profiles/§Framing drafted (fill-order 1–5).
- **Concepts:** Content-Length framing (bytes not chars), jsonrpc.el as rented envelope, golden files as witnesses, "eyeball debugging."
- **Opening challenges:** "Before you open a socket: who binds, who dials, on the android-loopback profile — and which of those facts is protocol-invariant?" "Your framing section says byte-accurate: prove it with a multibyte character."
- **Deliverables:** Emacs dials `nc -l 8765` via jsonrpc.el and sends hello; the observed bytes saved as **golden #1**; batch-prohibition sentence; frame-cap provisional decision (fixed 4 MiB, actor-first sender+receiver laws); DECISIONS entry for S3 (rent jsonrpc.el — the purely-additive test case #1, with evidence from this hour).
- **Light:** the Content-Length header in netcat, byte-for-byte as §Framing says.
- **Trap:** jsonrpc.el's default dispatchers are `#'ignore` — **fail-open**. Scenario: "You haven't authenticated yet and I send you `surface.update`. What does your Emacs do right now? Prove it." ("Fail-closed is written in your dispatcher or it is not real" — he must discover the library gives him nothing here.)

### R2 — First conversation: he plays the companion (2–3 sessions)
- **Prereq:** R1 done; §Session Lifecycle drafted (fill-order 6).
- **Concepts:** hello/welcome treaty contents (minimal now: version, granted, node_types, surfaces map, queued count — it grows additively later); mutual HMAC over a never-sent token; session state machine; reconnect.
- **Opening challenges:** "A rogue app squats port 8765. What stops Emacs trusting it?" (→ server proof, fail-closed both directions.) "Does your spec permit a dev profile with auth stubbed? Decide in writing — drifting into 'auth later' is the only wrong answer."
- **Deliverables:** hand-written reply frames (welcome.txt etc.) catted into netcat → **the companion-direction goldens the PoC never had**; §Lifecycle normative (pre-handshake: refuse requests with a not-authenticated error, drop notifications, *by explicit signal*); HMAC domain-tag decision (§16-1 — his own tag, e.g. `ebp2:`; note brand-leakage lesson: pick unbranded names *now*, scrubbing later costs breaking amendments).
- **Light:** Emacs completes a handshake with a human.
- **Trap:** welcome bloat — everything wants to ride the welcome eventually. Establish now: each welcome key needs its own spec sentence.

### R3 — First pixel (2–3 sessions; PoC proxy ≈ 300 Kotlin lines)
- **Prereq:** K0 complete; §Versioning & Compatibility (fill-order 7) drafted **before** any node vocabulary exists — the growth rules precede the things that grow. Minimal node section: `column`, `text`, `button`; `surface.update` + revision rule stated; Methods direction table begun.
- **Opening challenges (for §7):** "A companion meets a node type it doesn't know — crash, blank, or something better? What about a container vs a leaf?" "A key it doesn't know?" (→ unknown-container-renders-children, unknown-key-ignored — but plant the seed: "is ignoring *always* safe?" — full answer deferred to E2.)
- **Deliverables:** the minimal Android app — plain Activity, ServerSocket coroutine, framing codec, handshake verification, 3-node tree walker. No service, no Room, no cache. Gradle wiring cribbed from PoC (archaeology exemption — walk him through it openly).
- **Light:** text typed in elisp appears on his phone. **Screenshot ritual #1** — this is the one people frame.
- **Trap:** hardcoded fill-the-width defaults in composables (the PoC's "one true bug" class). Scenario at review: "Put two nodes in a row and give one weight. Does the other disappear?"

### R4 — First round trip: THE hello world (2 sessions; PoC proxy: 74-line app)
- **Prereq:** R3; Methods §: `event.action`, action-registry rule, `on_tap`, `args.value`; first two error codes (his numbering — §16-3 partially retired; teach the reserved-range craft move from GDRP).
- **Opening challenges:** "The wire carries a name. Who decides what the name does? What happens when no handler is registered — and why is 'log and drop' safer than an error here?" "Why must a *name*, never an elisp symbol to funcall, cross?"
- **Deliverables:** elisp action registry (`defaction`-analog) + counter handler; Kotlin tap→frame path; the counter app — the spec's permanent worked example.
- **Light:** tap phone → elisp increments → screen updates. Every manifesto core claim now demonstrated. **Screenshot ritual #2.**
- **Decisions retired:** S5 principle (never-code; escape-hatch ledger opened with zero entries — each future hatch must check in here).
- **Trap:** error-code landmines when the registry grows: **never 32000** (jsonrpc.el "no error" sentinel — transmutes error into result), **-1 taken** ("Server died"), avoid LSP's -32899..-32800. Also the outbound `:data`-stripping seam: Emacs-signalled errors lose `:data` — note it now, solve it in B1 when the error registry formalizes.

### K1 — Persistence toy (Room + versioned formats) (2 sessions, before R5)
- Toy: "jar of notes" app — Room entity, DAO, one deliberate schema change with a migration, plus a "version the format from day one" ritual. JUnit first contact.

### R5 — First resurrection (2–3 sessions; PoC proxy ≈ 430 Kotlin + part of surfaces.el 785)
- **Prereq:** R4, K1; §Surfaces revision/cache semantics made normative.
- **Opening challenges:** "Emacs restarts and its revision counter starts at 1 again. The companion holds revision 40. What happens to every push? Now design the fix using only things already in the welcome." (→ persisted counter + welcome revision-floor absorption.) "Why is `surface.update` a notification, not a request?" (→ the revision guard makes pushes idempotent and self-acknowledging.)
- **Deliverables:** companion persists last surface per id, renders from cache at cold start; strictly-newer rejection; Emacs persisted revision counter + floor absorption; reconnect with backoff; **kill-test scripts** (kill -9 Emacs; force-stop app; delete revision file) — these become permanent conformance checks.
- **Light:** kill -9 Emacs — the phone doesn't flinch. Force-stop the companion — it comes back showing the same screen before any connection exists.
- **Decisions retired:** S4 (snapshots as the contract; keys-from-day-one *policy* written; one reserved sentence for a future delta channel).
- **Trap:** unversioned disk formats (the PoC dragged legacy queue columns forever). Every persisted artifact (surface cache now; queue next; trigger table later; pairing token) gets a format version field at birth.

### R6 — First offline tap (2–3 sessions; PoC proxy ≈ queue parts of 435 Kotlin + surfaces.el)
- **Prereq:** R5; §Offline Queue & Replay drafted **as a state machine** with every early-exit defined (he designs it; the PoC discovered it by failure).
- **Opening challenges:** "Order these after reconnect: replay queued taps, push current surfaces, absorb the welcome. Wrong orders exist — find the one where a replayed handler reads state that doesn't include what the user saw." "Three offline taps of 'delete' — replay all three?" (→ dedupe) "A queued 'snooze 10 min' replayed 9 hours later?" (→ ttl) "What can 'wake' mean on a modern phone?" (→ ask the human; silent app starts are banned).
- **Deliverables:** Room queue (shape-preserving `kind`+`payload` schema at DB v1 — no legacy columns), `when_offline` queue/drop/wake, `queued_at` stamping, replay + drain summary as the replay request's response.
- **Light:** Emacs dead, tap the counter three times, restart Emacs — the count jumps by three. **Hello world is finished.** Mark it: every organ exists in miniature; everything after is growth, not construction.
- **Decisions retired:** S6; ttl_s naming (§14-div: `ttl_s` means queue expiry ONLY; the surface-staleness field, when it arrives in B-phase, gets a distinct name from birth).
- **Trap:** storing *fields* instead of the whole frame — the replay-disguise bug (a replayed state-change coming back dressed as an action). Scenario-quiz it before he designs the schema.

**End-of-phase ritual:** re-read the manifesto Foundation essay together against the six lights. Begin "The Handshake" essay now — *after* performing one (informative prose written last, describing the spec he wrote).

---

## 5. PHASE B — Growth I: from toy to platform (~16–22 sessions)

Order within B is flexible (motivation valve): B1 must precede B2; B8 needs K2; otherwise swappable.

### B1 — Contract, goldens, validator, CI (2–3 sessions; PoC proxy: contract.json 1039 + validate.py 189 + workflow 15)
- **Prereq:** R6.
- **Concepts:** one machine-readable source of truth; hand-owned contract (re-derive Option B: "should the spec repo require a reference implementation to build?"); goldens as witnesses **in both directions**; error registry formalized (numbering finalized, remedy-rides-the-error, the `:data` seam solved with the documented subclass override); artifact-precedence clause.
- **Deliverables:** his contract.json (small — only what exists: ~6 node types, ~10 methods, his error codes); his stdlib-only validator (he writes the Python; it's ebp code); CI wiring (archaeology exemption); the "**no entry, no amendment**" rule + amendment log table adopted.
- **Light:** CI goes red when he adds a golden the contract doesn't know; green when the amendment lands. The governance *runs*.
- **Decisions retired:** §16-3 (numbering), §16-5 (contract shape); S10 seeded.
- **Trap:** validator theater — the PoC's checked key-presence, one direction, no responses/errors/handshake vectors. His conformance corpus must cover companion-emitted frames, responses, errors, and handshake vectors from the start.

### B2 — Widget catalog, wave 1 + lint (3–4 sessions; PoC proxy: widgets.el 1436 + lint 924 + Sdui nodes ≈ 1600, but wave-1 subset ≈ a third of that)
- **Scope:** grow 3 → ~15 node types: `row`, `box`, `spacer`, `divider`, `card`, `lazy_column` (with **normative `:key`**), `icon`, `icon_button`, `chip`, `text_input`, `checkbox`, `switch`, plus a small box-model subset (`pad`, `weight`, `bg` — rest reserved).
- **Method:** each type = spec entry (uniform per-node template, LSP discipline) + contract row + golden line + elisp constructor + composable + renderer test. Assembly-line rhythm; Caleb picks the order after the first two.
- **Opening challenges:** "A lazy list reorders. Where does the phone's scroll position, cursor, and ripple animation go?" (→ stable identity, reconciliation-by-position failure — he bakes `:key` into the *first* list spec, retiring the PoC's three-release retrofit). "Who proves a schema key is actually rendered?"
- **Light:** a settings-panel-looking demo screen using all wave-1 types.
- **Trap:** renderer rot — keys on the wire silently ignored. Rule: no schema key without a renderer test that fails when it's dropped. Lint vocabulary derives from contract.json at load — one mirror, CI-gated (the ~6-drifting-mirrors lesson).

### B3 — State, forms, input_state (2 sessions)
- **Opening challenge (the crown scenario):** "Offline: user types into three fields, then taps Save. Your queue replays the tap. What did the handler read?" — he must discover the silent draft loss, then design where the drafts live (→ latest-value snapshot riding the welcome, delivered *before* the client can push).
- **Deliverables:** state store + debounced state-change frames + **flush-before-dispatch** law; input_state in the welcome; enabled/can_disable decision (recommend reserve).
- **Light:** kill Emacs mid-form, restart, the handler saves what the user actually typed.
- **Trap:** flush-before-dispatch under load — connect it forward to B7.

### B4 — Shell, apps, navigation (3 sessions; PoC proxy: shell 853 + apps 378, target ≈ 600)
- **Concepts:** view registry, multi-view surfaces + `view.switch` (first companion **builtin** → S5 escape-hatch ledger entry #1, with the test "the user, not the wire, chooses"), scaffold/tabs/top-bar chrome node, minimal `defapp` + launcher.
- **Opening challenge:** "Two overlay views both claim to be active after a reconnect. Design routing so that can't be ambiguous." (→ explicit route stack, params-merge — redesigning the PoC's two documented shell limitations without naming them.)
- **Light:** two registered apps, a launcher grid, tab switching that survives reconnect.
- **Trap:** navigation heuristics leaking into Kotlin (thin-client leak class). Back-stack behavior must be spec'd or forbidden.

### B5 — Dialogs, toasts, prompt bridge (2–3 sessions; PoC proxy: minibuffer.el 923 → target ≈ 350)
- **Opening challenges:** "y-or-n-p blocks Emacs. The phone user walks away. What must never happen?" (→ degrade-don't-hang; a cancelled prompt beats a frozen phone). "A dialog is up and Emacs changes its mind — dismiss method, or something the envelope already gives you?" (→ §16-2: cancel-the-outstanding-request; retire it).
- **Scope cut (explicit):** cover four blocking primitives (y-or-n-p, yes-or-no-p, completing-read, read-string) as a *spec-level tested contract* — NOT the PoC's 734-line 20-builtin advice net. The covered set is enumerated in the spec; everything else degrades.
- **Light:** `M-x find-file` in desktop Emacs pops a completing-read dialog on the phone. (This also lands the bridged M-x escape hatch → S5 ledger entry.)
- **Trap:** advice that isn't inert outside handlers (purely-additive invariant (c) — write the test).

### B7 — Overload & conflation (2 sessions)
- **Opening challenge:** "Emacs pushes 60 surface updates a second and the phone renders 5. Walk each traffic class: which frames may be replaced by newer ones, which must never be, which must be dropped, and what happens when the buffer is full?" (He derives the four classes + no-unbounded-buffering; then: "you already have a free lag gauge on the wire — find it" → revision_seen.)
- **Deliverables:** spec overload section by traffic class; sender-side coalescing in elisp; inbound conflation in Kotlin.
- **Light:** a stress demo — spam pushes, phone stays live, counter of coalesced frames visible.
- **Trap:** the PoC retrofitted conflation and contorted it to keep tests seeing synchronous sends — design the send path async-first with a test seam (public test-reset/flush seam, not let-bound `--` internals — the "tests binding privates IS the API bug report" lesson).

### K2 — Services toy (2 sessions, before B8): foreground service, notification channel, lifecycle, process death. Toy: metronome/step-counter FGS.

### B8 — The companion becomes an app (2–3 sessions; PoC proxy: BridgeService 115 + Runtime 147 + ActionReceiver 149 + NotificationRenderer part)
- **Deliverables:** foreground service owning the listener; runtime resurrection from a tap (trampoline); the wake notification (S6's "ask the human" made real); minimal notification rendering (`notification:*` surface namespace).
- **Light:** phone reboots; the app is listening again before Emacs notices; a notification button fires an action with the app cold.
- **Trap:** Android will not let one app silently start another; notification trampolines are banned on modern targets — the four-layer liveness model (resident Emacs / flat on_fire / queue / wake notification) gets written into the spec's informative architecture note.

---

## 6. PHASE C — Renderers & Emacs-side surface (~9–12 sessions)

Deliberate subset. Elective pool for the rest (see §10).

### C1 — Tier-0 buffer renderer (3–4 sessions; PoC proxy: buffer.el 758 + rich_text/span vocabulary)
- **Concepts:** text properties, faces, overlays; buffer → span tree; tappable regions; `rich_text` node + span vocabulary enters the catalog (an amendment — the governance loop's first real workout).
- **Opening challenge:** "Any buffer, zero per-package code — what's the minimum the wire needs: characters, faces, or intentions?"
- **Light:** *any* Emacs buffer readable on the phone — the manifesto's thesis on screen. **Screenshot ritual.**
- **Trap:** pushing on every buffer change → ghost pushes and battery (the measurable-costs lesson: build the frame-counter devtool *first*, ~50 lines, so cost is visible from day one).

### C2 — tabulated-list renderer (1–2 sessions; PoC proxy 204) — the small worked example proving "one renderer per declarative framework." Light: `M-x list-processes` as a native phone table.

### C3 — Command palette + bridged M-x (2 sessions; PoC proxy: keymap.el 719 → target ≈ 350, pie deferred)
- The M-x escape hatch formally lands (S5 ledger): the handler runs Emacs's own command dispatch, prompts bridged via B5. Light: fuzzy-search M-x from the phone.

### C4 — Satellite subset: emacs-ui + files-lite (2–3 sessions; PoC proxy: 646 + 897 → target ≈ 600 combined)
- Buffers list / messages / eval scratch; file browse + open (search deferred). These are the daily-driver chrome and the substrate Phase ED needs (a way to open a file to edit).
- **Trap:** eval-on-phone is NOT an escape-hatch violation — but make him argue why (the user typed the code; Emacs runs it; the wire carried text the user chose — revisit the S5 test).

---

## 7. PHASE ED — Editor sync (S7) (~10–13 sessions). The hardest correctness surface; scheduled late deliberately.

### ED1 — The sub-protocol on paper (2 sessions)
- **Opening challenges:** "Phone and Emacs both hold the text. Emacs's buffer changes underneath (a hook you don't have, a peer, a `revert-buffer`). Merge, or something humbler?" (→ resync-not-merge; the iron invariant *wrong state can only cost a feature, never a wrong edit* — he must produce this sentence's meaning himself, keep it verbatim once he does). "Offsets: bytes, UTF-16 units, or code points — who converts?" "Completion needs an answer correlated to a question — invent nothing; what does the envelope already give you?" (→ §16-4: promote edit legs to first-class requests from day one).
- **Deliverables:** editor spec section: seq-numbered deltas with expected-length, resync flow as request/response, edit-apply acceptance conditions (seq exactly-one-past + text-match + no IME composition; a race loses the command's result, never corrupts text), **written non-goal: concurrent desktop+phone editing** (S7's disqualification clause).
- **Decisions retired:** S7, §16-4, §14-div-5.

### K4 — Text-field deep dive toy (1–2 sessions): TextFieldValue, selection, IME composition, diff-to-splice on a neutral notepad toy.

### ED2 — elisp side (2–3 sessions; PoC proxy: sync.el 923 + complete.el 247 → target ≈ 700): shadow buffers (never touch disk), delta application, length-check → resync, capf bridge, diagnostics push (flymake), eldoc. **Deliberately no after-change hook** — he must defend laziness at review.

### ED3 — Kotlin side (3–4 sessions; PoC proxy: EditorSync 553 + editor node ≈ 400): editor node, UTF-16→code-point conversion, delta emission, completion UI, edit.apply guard.
- **Light:** type on the phone; flymake squiggles and capf completions appear live from desktop Emacs. Kill the connection mid-word; reconnect; the buffer is intact.
- **Conformance ritual:** adversarial kill-tests + a delta-fuzz script; EditorApply-style golden tests.
- **Deferred:** eglot opt-in, DWIM edit.command (escape hatch — spec sentence reserved in the S5 ledger, implementation elective), client-side syntax highlighting (thin-client: rely on Emacs fontify pushes; the PoC's 415-line highlighter with its hardcoded org regex is a leak, not a feature).

---

## 8. PHASE E — Device autonomy (S8/S9) (~11–15 sessions)

### K3 — Receivers/alarms/permissions toy (2 sessions): BroadcastReceiver, AlarmManager (+ reboot loss), runtime permissions. Toy: battery-diary app.

### E1 — Capabilities (2–3 sessions; PoC proxy: device.el 401 + DeviceCapabilities 723 → target subset ≈ 400)
- Subset catalog (~6: vibrate, tts.speak, settings.open, clipboard.read, flashlight, share.send) — the catalog is additive; a subset is fully conforming. Typed errors with remedy (cap-permission carries the permission + settings deep-link). Welcome device object (caps/perms/state_types).
- **Trap:** the confirm-gate class of bug — policy fields are companion-consumed, never echoed; a handler must resolve policy from *its own* state, never from the delivered frame. Scenario-quiz it.

### E2 — Triggers & the autonomy ceiling (4–5 sessions; PoC proxy: triggers.el 296 + TriggerHost 876 + StateSampler 336 → target ≈ 700)
- **Opening challenges (the phase's centerpiece, in order):** (1) "The phone must act while Emacs is dead. Write the rule language." — let him overbuild, then attack his own design: "your if/else needs an else-if; your loop needs a bound; where does it end?" (→ the non-Turing line: flat AND-ed state gates, flat on_fire lists, no conditionals/loops; *logic lives in Emacs*). (2) The **safety-inversion scenario**: "Old companion, new Emacs sends a trigger gated 'only below 20% battery.' The companion ignores the unknown gate key. What did you just ship?" (→ 'notify below 20%' became 'notify always' — constraining keys need catalog negotiation + **skip the whole thing, never strip-and-push**; S9 crowned). (3) "Name the field that fires and the action that arrives — now check your two names against every name already on the wire" (the one-letter cause/effect and the state.changed collision — naming as engineering).
- **Deliverables:** trigger spec (replace-set semantics, reboot re-arm, throttle, fail-closed when-gate, closed on_fire vocabulary, single-pass placeholders); subset trigger types: time, power, battery.level, screen, boot, manual (calendar/sms/call/network family deferred); persisted trigger table (versioned, format v1); on_fire and intent.start entries in the S5 ledger; a **mechanical tripwire** (CI grep/conformance table) for opinion leaking into the Kotlin library — doctrine wasn't enough for the PoC.
- **Light:** plug the phone in → Emacs (alive) logs it; Emacs dead → a local notification fires from a flat on_fire list; reboot → still armed.
- **Decisions retired:** S8, S9.

### E3 — Screen-off surfaces (2–3 sessions; PoC proxy: NotificationRenderer 240 + WidgetSlots 169 + Reminders 162)
- Notification surfaces full (chronometer, inline reply), ONE generic home-screen widget slot (customN pattern — never a domain-specific widget in the library), reminders replace-set.
- **Light:** a home-screen widget rendering a cached surface with Emacs dead — the manifesto's screen-off case, performed. The durability argument from Unit 0.1 is now fully demonstrated; note the closure explicitly to Caleb.

---

## 9. PHASE F — 1.0: conformance, governance, freeze (~6–8 sessions)

- **F1 — Conformance clause + escape-hatch enumeration:** per-MUST checklist extracted (if a MUST can't become a checklist item, rewrite it); minimal-conformance floor named (GDRP move); golden coverage audit (both directions, responses, errors, handshake vectors); the S5 hatch ledger (M-x, prompts, edit.command, intent.start, on_fire, builtins) lands in ONE spec section with the "user, not the wire, chooses" test applied to each.
- **F2 — Versioning finalization (S10 closed):** what a release is; what a consumer pins; artifact precedence on disagreement; minimized version-number count (challenge: "the PoC ended with four numbers and burned one — how few can you defend?"); amendment discipline = CI green + a cooling-off day; §16-6 frame cap finalized; §16-7 WebSocket = reserved row ("added when a second companion exists"); superseded lines are git *tags*, never sibling files.
- **F3 — Prose completion:** Security Considerations; manifesto "The Handshake" + "Second Draft"; the spec's informative Overview written *last*; Open Questions section published with the spec.
- **F4 — FSF/upstream prep:** copyright-assignment paperwork walk-through; provenance statement (the sealed-PoC discipline is the evidence — say so in writing); checkdoc/package-lint hygiene; ELPA packaging structure; decision on core-vs-ELPA target.
- **Light:** a tagged 1.0 of spec+contract+goldens that a stranger could implement from — verified by the ultimate test: *Caleb's own Kotlin companion was built only from it.*

---

## 10. PHASE GP — Glasspane (Tier 1) (~13–18 sessions)

Prereq: Phase C minimum + B-phase complete. Can interleave with E/F (glasspane needs no triggers).

- **GP0 — The tier contract (1–2):** Tier-1 = registrations only (views/sources/actions), zero transport/renderer/wire code; core-load-test-style gate (core loads with no app, no org leak); org primitives live *here*, never in core (the PoC's 3,500-line "sanctioned exception" is not reproduced). Binding layer: add a minimal `defsource`/`:spec` to core now, additively — and re-affirm the rejected template-DSL decision (challenge: "your board view needs a loop — JSON or elisp? Who is rich, server or client?").
- **GP1 — Org data layer + defsource (2–3; PoC proxy: glasspane-org 514 + source 96):** org-ql → canonicalized rows with a ref. Leverage org-neoroam explicitly — this is his home turf; let him lead.
- **GP2 — Journal first (1–2; PoC proxy 282):** datetree capture + today surface. **Motivation move: this makes jetpacs Caleb's daily driver as early as possible.** Light: capture a journal entry from his phone at the store.
- **GP3 — Saved views: list → board → calendar (3–4; PoC proxy 629 + month_grid node 254):** month_grid (and, if wanted, a board layout) enter the node catalog by amendment — proving 1.0's additive-growth machinery on a real need. Light: his actual tasks as a kanban board.
- **GP4 — Notes/vulpea (2–3; PoC proxy 484+84):** wikilinks, backlinks; unlinked mentions deferred.
- **GP5 — Capture templates + search (1–2; PoC proxy 226+215).**
- **GP6 — Test harness from day one (woven through GP1–GP5, not a unit):** headless ERT, node-tree + lint + JSON goldens with send stubbed; bundle-byte-compiles-standalone gate; generated pack manifest + snapshot test (never hand-maintained). Bundle/pack build scripts are small elisp Caleb writes (~130 lines).
- **Electives:** SRS (762), interactive org tables/babel/sparse/clock (~1,200), one non-org skin (magit pie, 80 — cheap and keeps the tier boundary honest; recommend), ef-themes control, gallery. **Dropped:** demo tour (897), packages self-provisioning, ghostel.

---

## 11. What is NOT rebuilt, and why (the honest 40%)

**Never (documented non-goals — record each as a DECISIONS/ledger entry when its unit passes by):**
v1 NDJSON envelope; ping/pong; JSON-RPC batch; the wire template DSL; CRDT/collaboration on the wire; jetpacs-cloud/KMP plans; the org-agenda Kotlin widget family + client-side org syntax highlighting (leaked opinion, not features); app-store (286); onboarding wizard (826) and demo generators (1160 + 897) — replaced by a README and manual pairing; theme-picker/modus (437); the 20-builtin prompt-advice net (covered-set contract instead).

**Deferred electives (post-1.0 pool — also the motivation escape valve: when Caleb stalls on a scheduled unit, an elective may be swapped in without breaking any dependency):**
Tier-0.5 renderers comint/results/transient/hypertext/sections (~2,100); RadialMenu pie (570) + pie frames; chart/canvas/tabs-node/reorderable_list/slider/date-time pickers; satellite screens package-browser/customize/tools/project/sql/hosts (~1,600); with-editor bridge (197); eglot/edit.command; trigger families calendar/sms/call/network/airplane/package; capabilities beyond the subset; devtools beyond the frame counter; WebSocket profile.

**Resulting honest scope:** elisp ≈ 8–10k of the PoC's 23.4k; Kotlin ≈ 6–8k of 16k; glasspane ≈ 3.5–4.5k of 13k. The system is *complete and conforming* at that size — the spec's conformance floor (F1) is written so that this subset IS a conforming implementation, which is the pedagogical point: the catalogs are additive by design.

---

## 12. Motivation-protection moves (named, for every future session to reuse)

1. **Interleave prose and code** — never two spec-only sessions consecutively (K-track exists partly for this).
2. **Every unit ends in a light** — stated at assignment time, demoed at review time.
3. **Screenshot rituals** at R3, R4, R5, R6, C1, ED3, E3, GP2, GP3 — an artifact trail of firsts.
4. **Nothing is throwaway** — every hand-typed frame becomes a golden; every kill-test a conformance check; the counter the spec's worked example. Say this out loud when assigning grunt work.
5. **Daily-driver early** — journal (GP2) can be pulled forward the moment Phase C lands if Caleb's energy flags; using your own tool is the strongest motivator available.
6. **Effort honesty** — quote the PoC line-count proxy when sizing a unit; celebrate cumulative hand-written totals at phase boundaries.
7. **Pre-agreed drop list** — cutting scope is executing the plan, not failing it (§11 is the contract).
8. **Elective swap rule** — stalled ≠ stopped; swap in an elective, return within two sessions.
9. **Unblock sessions** — missing deliverable → halve it, never scold.
10. **The FSF brick line** (from the Lesson-1 dialogue, reuse verbatim): every golden file is a brick in the road to the app-store listing that says "Emacs" with no Jetpacs branding anywhere.

---

## 13. PROGRESS block

*(Superseded at assembly time: the live STATE block is §0 at the top of this roadmap — update THAT, never this section.)*

---

### Critical Files for Implementation
- /home/calebc42/pkb/projects/jetpacs/rewrite-notes.org (the ledger this curriculum extends; syllabus + ladder spine)
- /home/calebc42/pkb/projects/jetpacs/ebp/SPEC.org (the fill-order skeleton every spec deliverable lands in)
- /home/calebc42/pkb/projects/jetpacs/ebp/DECISIONS.org (where every retired decision is recorded)
- /home/calebc42/pkb/projects/jetpacs/jetpacs-site/_root/index.org (manifesto draft; Units 0.1, R2-follow-up, F3)
- /home/calebc42/pkb/projects/jetpacs/spec-example.org (GDRP worked example; the craft-form reference cited throughout Phases 0–B)

---

# §3 Technical build map + decisions register (the answer key)

> `D1–D34` in this chapter is the authoritative decision numbering for the entire roadmap.

# EBP / jetpacs / glasspane Rebuild — Definitive Technical Build Map (Architect / Verification Layer)

**Purpose of this section of the hidden roadmap.** This is the dependency-ordered build map + answer key for the entire multi-month rebuild, hardened with verification gates, so future Claude sessions can tutor without re-reading the sealed PoC. Proposed home for the assembled roadmap document: `/home/calebc42/pkb/projects/jetpacs/jetpacs/llm-poc/docs/REBUILD-ROADMAP.md` (inside `llm-poc/`, which is sealed to Caleb by the standing rule — he never opens that tree).

**Governing constraints this map encodes:** the rebuild reproduces the **v2 (JSON-RPC 2.0) line only**; the spec never gets more than one rung ahead of a visible demo (the ladder IS the spec's vertical slice); Caleb types every line of elisp AND Kotlin; Claude supplies specs/requirements/challenge-questions/pseudocode, never writable jetpacs code; build plumbing (gradle, CI YAML) is archaeology and exempt. The map RESUMES the existing groundwork: Lesson 1 in progress, `ebp/SPEC.org` fill-order skeleton, manifesto Foundation essay done.

---

## 1. Phase Map

Phases **L0–L5** are the six-rung "Road to Hello World" ladder (construction — every architectural organ exists in miniature at the end). Phases **G1–G5** are growth rings (everything after is growth, not construction). PoC line counts are effort proxies only, not targets — the rebuild should come in smaller.

| Phase | Spec sections required FIRST (SPEC.org fill-order) | Built (PoC lines as effort proxy) | Conformance artifacts produced | Exit gate |
|---|---|---|---|---|
| **L0 Ground truth** (in progress — RESUME, do not restart) | Conventions (1), Terminology (2), Doctrine (3) — Lesson 1 deliverables: 3-claim durability paragraph + §1 Conformance & Terminology draft | Manifesto: Envelope finished, Handshake written, 3 factual fixes, screen-off case, loopback co-location promoted. jsonrpc.el katas: manual read → echo kata → hand-typed Content-Length frame | DECISIONS.org grows to ~6 entries (D1–D5 below); zero code artifacts yet by design | §1 draft survives adversarial review; echo kata works between two Emacsen; D2/D3 settled with written evidence from the kata, not vibes |
| **L1 First frame + first conversation** (rungs 1–2) | Transport Profiles (4), Framing (5), Session Lifecycle (6) incl. pre-handshake fail-closed state machine; error-registry seed (auth errors) | `ebp.el` transport module begins (PoC `jetpacs.el` 867): rented jsonrpc.el, hand-rolled fail-closed dispatchers, HMAC pairing, reconnect backoff | **First goldens, BOTH directions**: netcat-captured hello/auth frames + hand-crafted companion reply files (welcome.txt etc.) = the handshake-vector corpus the PoC never had; include bad-MAC and pre-auth-refusal negative vectors. `validate` script v0 (stdlib-only). Direction-table org table seeded (contract generated from it) | Rung-2 hand-played handshake completes; ERT (stubbed transport): pre-auth request → not-authenticated, pre-auth notification → dropped, missing/wrong server_proof → connection dropped |
| **L2 First pixel** (rung 3) | Versioning & Compatibility (7) — growth rules BEFORE any vocabulary; Methods template (8); node vocab **v0 = column/text/button** + the `*` row (`:key` present from day one); `surface.update`/`surface.remove` + revision rule stated | elisp: widgets v0 + lint-from-contract v0 + surfaces sender v0 (PoC widgets 1436 / lint 924 / surfaces 785 — build ~15% of each). Kotlin: `FrameCodec`+`Envelope`+`Auth` (147+102+80) as **pure JVM code first**, then minimal Activity + socket + 3-type tree walker — no service, no Room, no cache | widgets.golden v0; JVM unit tests (`FrameCodec`/`Auth`) against the **same** golden files the ERT suite uses; `SDUI_NODE_TYPES` generated from contract from day one | Text typed in elisp appears on the phone. Degrade test: send a 4th node type → container renders children / leaf renders nothing, never crashes. Unknown-key-ignored test passes on both sides |
| **L3 First round trip** (rung 4) | Semantic-action boundary: `event.action`, `state.changed`, action-registry rule (names never code), `args.value`, flush-before-dispatch; first typed error codes; escape hatches enumerated in ONE section | elisp: `defaction` dispatch + state store; Kotlin: on_tap wiring + live-path ActionReceiver (149, live half only). The counter app (~74 lines, PoC `jetpacs-hello.el`) | Companion-direction `event.action` goldens; counter app becomes the spec's permanent worked example (GDRP-style) | Tap → elisp handler runs → screen updates. ERT: unknown action name → log+drop, never funcall. Golden: `state.changed` flush precedes `event.action` |
| **L4 First resurrection** (rung 5) | Revision monotonicity normative; welcome carries revision snapshot; **durable-artifact clause naming every disk format + its version field** (surface cache, revision file, queue, pairing token); staleness semantics (D10 resolved) | elisp: revision persistence + welcome-floor absorption + reconnect. Kotlin: **Room DB v1 clean schema** (surface + queue tables designed together now, queue unused until L5), SurfaceStore/Manager (163+107) | Kill-test script (permanent conformance artifact); versioned-format fixtures | **The kill matrix**: `kill -9` Emacs → phone doesn't flinch; force-stop companion → returns rendering from cache pre-connection; restart Emacs → reconnect, re-push, zero stale-revision rejections; delete revision file → welcome floor recovers it; replayed non-newer update → provable no-op |
| **L5 First offline tap** (rung 6) | Offline queue: `when_offline` (queue/drop/wake), dedupe, `ttl_s`, `queued_at`; **replay as an explicit state machine with every early-exit defined** (absorb welcome incl. `input_state` → push surfaces → `queue.replay` → drain summary → re-push); wake = ask-the-human | Kotlin: shape-preserving queue (kind+payload), offline ActionReceiver policies, BridgeService FGS (115), EmacsWaker (80), cold-start resurrection. elisp: `queue.replay` + ordered absorption | Offline-scenario script; queue-format fixture; drain-summary golden | Emacs dead → 3 taps → restart → count jumps by 3. Dedupe collapses repeats; ttl expires stale intent; replay-order ERT proves handlers read coherent state. **Tag spec+both implementations v0.1 — hello world is finished; minimal conforming core exists** |
| **G1 Usable shell** | Vocabulary **v1** (~18 types, §5 below); dialogs/toasts/theme; **overload traffic classes** (conflation designed in the spec, not retrofitted); devtools counters NAMED in spec | elisp: config/commands/async (333+78+225), **devtools (187) EARLY**, widgets/lint/surfaces to v1, conflation, shell (853), apps (378), minibuffer bridge scoped (923→aim smaller), theme (454), Tier-0 buffer renderer (758). Kotlin: SduiRenderer + content/input/scaffold nodes (~960+1223+438+334, staged), InboundPipeline (91), NotificationRenderer basic (240), ThemeBridge, dialog-as-request | Golden coverage for every v1 node, both directions; renderer schema-coverage test (every key in node_schema consulted or absent — the anti-rot gate); burst test: 100 rapid surface.updates coalesce to latest per conflation key, ordered legs untouched | Shell with launcher + 2 views renders; core-load-test analog green (core loads with no app, **no org anywhere**); purely-additive gate (a): loaded-but-no-session leaves stock Emacs unchanged; metrics visible (frames/bytes/pushes per minute) |
| **G2 Tier-1 pattern + glasspane MVP** (may run parallel with G3 — elisp-only) | Binding layer (`defsource` + `:spec`), pack manifest format; NO template DSL (D26) | glasspane repo: **test harness FIRST** (headless ERT + stubbed send, the 18-suite pattern from suite #1), tier gate, org source (514+96), journal (282), saved views list+board (629), capture (226); bundle+pack build (97+28+61); one non-org skin (magit pie, 80) | Bundle byte-compiles standalone; pack-JSON regen-and-assert-snapshot; per-view render-to-JSON goldens | First real dogfood on device; tier gate: glasspane contains zero transport/renderer/node-type code; core still loads org-free |
| **G3 Device half** | Capabilities subset catalog; triggers: `when` gate (fail-closed AND-only), `on_fire` (flat, non-Turing), replace-set, **constraining-key negotiation** (`when` only if all predicate types ∈ announced `state_types`, else skip WHOLE registration) | elisp: device (401) + triggers (296) subsets. Kotlin: DeviceCapabilities subset (~8 of 18 caps), TriggerHost (876→subset), BootReceiver (43), Reminders (162), StateSampler subset (336→) | Trigger goldens incl. the **safety-inversion negative vector** (registration with unknown predicate type); CI grep tripwire for opinion-in-Kotlin | Safety-inversion test green on BOTH sides (client skips whole registration; companion never arms ungated); reboot re-arm test; on-device battery checklist run once (TESTING-ON-DEVICE analog) |
| **G4 Editor sync** (hardest correctness surface — deliberately last of the core) | Editor sub-protocol with edit.* as first-class methods (D14): code-point offsets, seq+expected-length, resync-not-merge, edit.apply guard triple, written non-goal: concurrent desktop+phone editing | elisp: sync (923) + complete (247). Kotlin: EditorSync (553) + state holders | edit.* goldens both directions; UTF-16↔code-point conversion fixtures with astral-plane chars | Length-mismatch → exactly one resync → reseed (missing feature, never wrong edit); edit.apply race test: stale seq / text mismatch / IME composition each refuse the apply |
| **G5 Breadth + polish** | Remaining catalogs additive per the growth law | Satellite subset (emacs-ui 646, files 897 only), customN widget/tile slots (169+123), RadialMenu (570), :app shell + Onboarding (675+826), build-bundle/build-contract (118+185), glasspane ring 2 (notes 484, SRS 762, tables/ui, ef), demo LAST (1160) | Full golden sweep; bundle CI staleness gate | Everything in the published spec implemented on both sides — **no §14-style spec-ahead ledger permitted at any tag** |
| **U Upstream prep** (continuous) | Conformance clause; security considerations; informative overview written LAST | FSF paperwork, ELPA packaging, brand audit | Per-MUST conformance checklist (each MUST = one testable item) | Every MUST maps to a golden or test; zero jetpacs branding in ebp artifacts |

**Sequencing rules the tutor must enforce:** (1) never open a phase whose spec sections don't exist — the spec is the ratchet; (2) never let the spec run more than one rung ahead of a demo; (3) G2 may interleave with G3 (glasspane is pure elisp; device work is pure Kotlin — good for pacing a beginner across both languages); (4) G4 requires L1 transport to be boringly solid — if reconnect is still flaky, editor sync will manufacture unfalsifiable bugs.

---

## 2. Decisions Register (answer key — Caleb must reach his own; these are Claude's defensible recommendations)

Sources merged: 10-item syllabus (S1–S10), SPEC-2 §16 seven reserved owner decisions (§16.n), SPEC-2 §14 five spec-ahead divergences (§14.n), and every drop/redesign item from the three explorer reports.

| # | Decision (source) | What the PoC did | The trap | Retired in | Recommended answer |
|---|---|---|---|---|---|
| D1 | Roles & two names forever (S1) | Drifted client/companion/emacs; wrote an untangling paragraph | "Client" flips referents; roles defined by dial direction break the future WS profile | L0 (settled provisionally — ratify) | **Emacs / Companion**; role = who sends hello, never who binds/dials; "liveness best-effort on every profile; durability only ever the Companion's disk" |
| D2 | Rent or own the envelope (S2) | v1 owned (NDJSON), v2 rented JSON-RPC 2.0 | Renting imports jsonrpc.el's fail-open default dispatchers | L1 | **Rent JSON-RPC 2.0**; the three v1 deltas (kind/direction/string-codes) live in the contract's direction table + `data.kind`, not on the wire |
| D3 | jsonrpc.el or justify (S3, purely-additive test #1) | Hand-rolled ~450 lines on raw make-network-process + pure-elisp HMAC | Loudest purely-additive violation; HMAC had a real justification (Android gnutls gap) never written down | L0–L1 | **Use jsonrpc.el**, decided by kata evidence; hand-roll ONLY the two dispatchers (fail-closed by explicit signal) + the outbound `:data` re-attach seam (subclass `jsonrpc-convert-to-endpoint`, not a fork); write the elisp-HMAC decision record |
| D4 | Auth day one vs dev-mode stub (rung-2 forced) | HMAC from the start | Drifting into "auth later" without the spec saying so | L1 | **Auth from day one** — it's ~20 lines/side and the katas cover it; a stubbed dev profile only as a written MAY, fail-closed everywhere else |
| D5 | HMAC domain tag (§16.1) | `jetpacs1:` → `ebp1:` breaking retag mid-draft | Reusing a tag across incompatible lines | L1 | Bind tag to protocol_version: **`ebp2:client:` / `ebp2:server:`** — one definition, bumps with the wire |
| D6 | Frame cap fixed vs negotiated (§16.6) | Fixed 4 MiB, enforced both halves | Negotiation machinery before any need | L1 | **Fixed 4 MiB constant** + one reserved sentence for future hello negotiation; keep byte-exact skip + 1400-on-log.error + connection survives |
| D7 | Error-code numbering (§16.3) | Invented 1000/1100/1200/1300/1400 ranges | jsonrpc.el sentinels: 32000 transmutes error→result; -1 taken; LSP squats -32899..-32800 | L1 seed, L3 grow | Renumber freely but **partition the space in a day-one table** (JSON-RPC craft move); normatively ban 32000, -1, -32899..-32800; remedy rides the error (`data.kind`, `retry_ms` pattern) |
| D8 | rpc.cancel vs dialog.dismiss alias (§16.2) | v2 dissolved dismiss into cancelling the dialog.show request | Semantic aliases re-grow hand-rolled correlation | G1 | **rpc.cancel only**; dialog.show is a request; id-correlation is the point of renting the envelope |
| D9 | Snapshots + monotonic revisions + reserved delta channel (S4) | Yes, but `:key` reconciliation retrofitted over 3 releases | Position-keyed lists attach focus/cursor to the wrong row after reorder | L2/L4 | **Keep snapshots as the contract**; `:key` in the `*` row from the first list spec; one reserved sentence for a future delta channel; frame cap is the honest ceiling |
| D10 | `ttl_s` vs `stale_after_s` (§14.2, S-naming) | Renamed in prose, never on the wire — a documented prose/wire gap | Two meanings of `ttl_s` (staleness vs queue expiry) collide | L4 | **`stale_after_s`** (from disconnect, persisted across process death) for surfaces; `ttl_s` ONLY for queue expiry — on the wire from day one; the rebuild has no legacy wire to appease |
| D11 | `input_state` in welcome (§14.3) | Discovered by the multi-field-form failure: replay-only-actions silently lost offline drafts | Skipping it re-imports a known data-loss bug | L5 | **Design in at L5** — it is part of the replay state machine (latest-value-only, delivered IN welcome so the client structurally holds it before pushing) |
| D12 | Universal box-model attrs (§14.4) | Documented, never in node_schema/renderer | "If it's only in the schema it doesn't exist" | G1+ | Minimal `*` row at v1 (`key`, `pad`, `weight`, …); each attr enters node_schema only the day the renderer consults it (schema-coverage test enforces) |
| D13 | `enabled`/`can_disable` (§14.5) | Admitted, unimplemented | It's a CONSTRAINING key — lenient ignoring over-acts | G1+ deferred | Defer; when shipped, follow the taxonomy exactly: welcome-announced `can_disable` + sender skip-don't-emit |
| D14 | edit.* method promotion timing (§16.4, §14.1) | Prose promoted, contract never followed | Hand-rolled request_id inside event.action payloads | G4 | **Promoted methods from the start** (edit.complete a request; resync a request whose response IS the reseed); give methods the same registered-handler allowlist discipline as actions |
| D15 | Contract shape & authorship (§16.5, #30) | Flipped to hand-owned contract (Option B); still 3 drifting truths + 6 vocabulary mirrors | Hand-copies are where specs rot (Wayland lesson) | L1 | **Org tables in SPEC.org are the single authoring surface; contract.json GENERATED from them; goldens are witnesses; validator CI-diffs all three.** One generator direction, stated precedence clause, stdlib-only validator |
| D16 | WebSocket profile (§16.7) | Parked in slop-docs | Speccing transports with no implementation | L1 (row only) | Reserved row in the Transport Profiles table; no laws until a browser companion exists |
| D17 | Never-code boundary + escape hatches (S5) | Best-argued section, but hatches (M-x bridge, edit.command, intent.start, on_fire) justified piecemeal | Unenumerated hatches multiply | L3 | One normative section enumerating ALL hatches with the single test: **the user, not the wire, chooses** |
| D18 | Offline queue/replay + wake semantics (S6) | Ordering discovered by failure; v1 queue stored fields so replayed state.changed came back disguised as event.action | Implicit state machines; shape-mutating storage; silent app starts are banned on Android | L5 | Explicit state machine, every early-exit defined; **shape-preserving kind+payload storage**; wake ≡ post the notification and ask the human |
| D19 | Editor doctrine (S7) | Shadow buffers, no after-change hook, resync-not-merge | Eager correctness tempts merge semantics | G4 | Keep verbatim: "wrong state can only cost a feature, never a wrong edit"; write the non-goal down: **no concurrent desktop+phone editing** |
| D20 | Companion autonomy ceiling (S8) | Non-Turing on_fire/when held in doctrine, but org opinion leaked into Kotlin 4+ times anyway | Doctrine without a tripwire fails | G3 | Keep the non-Turing line (no OR/negation/nesting in `when`; no conditionals/loops in `on_fire`); add the **mechanical tripwire**: conformance table + CI greps over `:jetpacs` for app strings |
| D21 | Additive-growth law (S9) | Cosmetic/QoS/constraining taxonomy + skip-the-whole-thing, after the "notify below 20%" → "notify always" inversion | Lenient parsing turns growth into safety inversions | L2 (before vocab grows) | Keep verbatim, stated **per object class**; the constraining-key rule indicts the SENDER |
| D22 | Versioning & governance (S10) | FOUR numbers, one burned; colliding amendment numbers; a no-entry amendment | Version sprawl; governance theater (blank reviewer signatures) | L0 + continuous | **Three numbers**: protocol_version, spec_version, contract_format (reference API version = the ELPA package version, informational); "no entry, no amendment" + CI-green + a cooling-off day as the ratification mechanism |
| D23 | v1 artifacts (foundation drops) | NDJSON envelope, ping/pong, batch ambiguity | Carrying two generations | L1 | Drop NDJSON entirely; drop ping/pong (no written semantics); **prohibit batch arrays** with an actor-first MUST NOT |
| D24 | Disk formats (companion drops) | Room v4 with legacy v1 columns + 3 migrations; unversioned surface cache | Migration baggage forever | L4 | **Room DB v1 clean** (shape-preserving queue schema from day one); every persisted format (cache, revision file, queue, token) carries a version field, named in the spec's durable-artifact clause |
| D25 | Org-free core for real (companion+tier1) | ~3,500 org lines inside "org-free" core as a sanctioned exception; org-agenda widgets + `orgTodoRe` inside the `:jetpacs` library | The exception becomes the architecture | G1 gate, G2 home | **The exception never exists**: org lives in the glasspane repo from the first line; Kotlin ships only generic `customN` widget/tile slots + `header_action`; syntax vocabularies are server-pushed or app-side |
| D26 | Wire template DSL (rejected DECISION) | Drafted join/each/when/table, rejected | Re-encoding control flow as JSON reinvents elisp | G2 | **Stand by the rejection**: server rich, client thin; rich rendering in elisp builders; `:spec` stays minimal |
| D27 | Renderer honesty (companion "rot") | fillMaxWidth swallowed sibling weight; color/max_lines/hex/role tokens silently dropped; dead on_swipe + "Cycle TODO" string | Renderer silently ignoring wire keys is invisible until dogfood | G1 | Schema-coverage test: every key in node_schema is consulted by the renderer or absent from the schema; no silent layout defaults; on_swipe never enters the vocabulary |
| D28 | Golden corpus directionality (spec audit) | ZERO companion-direction goldens; no error/handshake vectors; key-presence-only validator | The entire inbound half of Emacs's parser untested | L1 onward | Both directions from rung 2 (the hand-crafted reply files ARE the corpus); handshake, error, and negative vectors first-class |
| D29 | Test seams (grocy lesson) | Tests let-bound private `--` internals → drove a late public reset seam | Untestable-without-internals IS the API bug report | L2 | Public `ebp-test-reset-state` analog + stubbed-send + render-to-JSON designed into module 1, never retrofitted |
| D30 | Measured costs (docs lesson) | Battery defect from ghost pushes hid until devtools existed (1.21) | Unmeasured full-snapshot costs | G1 (early, not late) | Devtools/metrics module lands WITH the shell, before dogfooding; spec names the counters so both sides report comparably |
| D31 | Naming footguns (amendments #22/#23) | Kept `trigger.fire` (builtin) one letter from `trigger.fired` (event), documented in prose | Prose warnings don't survive 3am greps | G3 | Keep `state.edge`≠`state.changed`; **rename the builtin** (e.g. `trigger.run`) — no legacy wire exists, so kill the one-letter hazard outright |
| D32 | Spec-file discipline (spec audit) | Two ~80%-identical sibling spec files with silent divergences | A superseded line as a sibling file WILL drift | L0 rule | One governing document per line; **superseded = git tag**, never a sibling file; no §14-style debt ledger allowed at any release tag |
| D33 | Brand-free spec (amendments #10/#11/#29) | De-branding took 3 amendments and still left residue | Brand scrub after publication is breaking | L0 | ebp names only from the first sentence (`companion.settings.open`, `emacs.*` examples); jetpacs appears nowhere in ebp artifacts |
| D34 | Prompt-bridge blast radius (elisp audit) | Advised ~20 blocking builtins gated on an in-action flag (734-line regression net) | Unbounded advice surface violates purely-additive (c) | G1 | Spec-level tested contract: an enumerated table of WHICH blocking primitives are bridged; advice provably inert outside action handlers; degrade-don't-hang (cancelled beats frozen) |

**Contested entries worth a full Socratic session each:** D3 (the kata makes it evidence), D4, D9, D11, D14, D22, D25, D31. For D14 the counter-argument to record: promoting edit.* before the editor exists violates "spec never more than one rung ahead" — resolution: the *decision* is recorded at L1 (methods table reserves them), the *sections* are written at G4.

---

## 3. Architecture Corrections Made BY DESIGN (not retrofit)

1. **jsonrpc.el rented; fail-closed hand-rolled.** The two dispatchers are the only transport code Caleb owns: pre-welcome, every request → 1200-class refusal, every notification → dropped, by explicit signal ("fail-closed is written in your dispatcher or it is not real"). Plus the outbound `:data` re-attach seam via the supported subclass hook. Landmines normative: never 32000, never -1, avoid -32899..-32800.
2. **`:key` reconciliation from the first list.** The `*` row carries `key` at L2; the first `lazy_column` spec sentence states the reconciliation-identity rule. The PoC paid three releases for retrofitting this.
3. **Versioned disk formats day one.** Room DB v1 clean (shape-preserving `kind`+`payload`+`dedupe_key`+`ttl_s` queue; no legacy columns, no migrations), versioned surface-cache and revision-file formats, all NAMED in the spec's durable-artifact clause (durability is the Companion's disk — so the disk formats are protocol-adjacent artifacts, not implementation trivia).
4. **Org-free core from the start.** glasspane owns everything jetpacs-org did; the core-load-test analog exists from G1 and blesses NO exceptions. Kotlin side: generic `customN` widget/tile slots + `header_action` replace the org-agenda widget family; no TODO-keyword regexes in `:jetpacs`.
5. **Brand-free spec.** ebp names only, from L0 — the FSF/standalone-standard endgame forbids a post-hoc scrub.
6. **Measured costs early.** Devtools/metrics land with the shell (G1), with spec-named counters — because the PoC's battery defect was invisible until instrumentation existed.
7. **One authoring surface.** SPEC.org org-tables → generated contract.json → golden witnesses → CI diff. Mirrors minimized and gated from day one.

---

## 4. Testing Strategy Per Layer

**Spec layer.** Hand-written stdlib-only validator (validate.py analog) checking goldens against the generated contract: node shapes, action discrimination, JSON-RPC class/id/direction, request-declares-result. **Corpus covers BOTH directions** — Emacs-emitted frames (netcat captures) AND companion-emitted frames (the rung-2 hand-crafted reply files), plus handshake vectors (good, bad-MAC, missing server_proof), error frames, and negative vectors (unknown method, batch array, oversized frame, wrong-direction method, ungated constraining key). CI runs the validator on every push from L1. Every MUST sentence eventually maps to exactly one conformance item (RFC-keyword discipline makes the checklist extractable).

**Elisp.** Headless ERT from the FIRST module: stubbed `send` capturing frames, render-to-JSON byte-compared against goldens (the glasspane pattern, applied to the core itself this time). Public `ebp-test-reset-state` seam designed in at L2. Standing gates: core-load-test analog (loads app-free, org-free), purely-additive gate (a) (no session → stock Emacs unchanged, checked by comparing advice/hook state), and later the advice-inertness gate (c).

**Kotlin.** Maximize the JVM (Caleb has no Android experience — keep the feedback loop off-device): FrameCodec/Envelope/Auth/queue-serialization/replay-ordering as plain JUnit against the **same golden files** (WireGoldenConformanceTest analog reads `ebp/goldens/` directly — one oracle, two implementations). Renderer: schema-coverage test + malformed-attr fuzz (Compose has no error boundary — safe clamps are conformance, not polish). Instrumented/on-device only where unavoidable: Room DAO tests, FGS lifecycle, BootReceiver, Compose interaction smoke tests.

**System.** The L4 kill matrix and L5 offline-replay scenario become permanent scripted checks run before every tag. On-device battery/acceptance checklist (TESTING-ON-DEVICE analog) run at G1, G3, G5 — everything headless cannot see (real radio, Doze, real battery). Backpressure burst test at G1 (conflation per traffic class; ordered editor legs never conflate — re-verified at G4).

---

## 5. Scope Honesty — the Minimal Conforming System

The target is a COMPLETE small system, not 20% of the PoC. Full-PoC parity is explicitly a non-goal for the first year.

**Node vocabulary (PoC: 39 types). Staged:**
- **v0 (L2, 3 types):** `column`, `text`, `button`.
- **v1 (G1, ~18 types):** + `row`, `lazy_column`, `spacer`, `divider`, `card`, `scaffold` (with top_bar/fab), `icon`, `icon_button`, `text_input`, `checkbox`, `switch`, `chip`, `progress`, `section_header`, `empty_state`.
- **v2 (G2/G5, glasspane-driven, ~10 more):** `collapsible`, `table`, `tabs`, `month_grid`, `date_button`, `time_button`, `menu`, `badge`, `date_stamp`, `reorderable_list`, `rich_text`.
- **Deferred until demanded:** `chart`, `canvas`, `flow_row`, `box`, `surface`, `slider`, `enum_list`, `assist_chip`, `image`; `editor` at G4. **Never:** `on_swipe` (dead v1 path).

**Tier-0.5 renderers (PoC: 8):** minimal target = **tablist** (204, the worked example) + **sections** (368). Defer hypertext (859), keymap (719), transient, comint, results to G5-and-beyond, each on demand.

**Satellite screens (PoC: 9, ~4,340 lines):** minimal target = **emacs-ui subset** (buffers + M-x) and **files subset** (browse + open). Everything else (package-browser, customize, tools, project, sql, hosts) is post-roadmap. Demo/onboarding minimal, last.

**Device half (PoC: 18 caps, 17 trigger types):** caps subset ≈ `vibrate`, `clipboard.read`, `flashlight`, `settings.open`, `app.launch`, `state.get`, `trigger.run`, `tts.speak`. Trigger types ≈ `time`, `power`, `battery.level`, `screen`, `boot`, `manual`. Defer calendar/sms/call/package/network family.

**glasspane MVP (G2):** org source + defsource, journal, saved views as **list + board** (calendar arrives with `month_grid` in v2 vocab), capture, one non-org skin. Ring 2 (G5): notes/vulpea, SRS, tables/babel/sparse-tree, gallery, ef-themes, demo tour.

**Definition of done for "minimal conforming system" (end of G2):** spec v1 with zero spec-ahead debt; both implementations green against the shared golden corpus; kill matrix + offline replay pass; glasspane journal+views dogfoodable daily on Caleb's phone. Everything beyond is additive growth under D21's law.

---

## 6. Risk Register — Hardest Correctness Surfaces

| Risk | Invariant to preserve | How it is tested |
|---|---|---|
| Editor shadow-sync (G4) | "Wrong state can only ever cost a missing feature, never a wrong edit" — no after-change hook; divergence caught by expected-length mismatch → exactly one resync | ERT: mismatch → stale + single resync → reseed; UTF-16↔code-point fixtures with astral chars; edit.apply refuses on stale seq / changed text / IME composition; race loses the result, never corrupts |
| Replay ordering (L5) | Replayed handlers read coherent state: welcome (incl. revision snapshot + input_state) absorbed → surfaces pushed → replay → re-push after drain | State-machine ERT scripting each early-exit; on-device offline scenario; shape-preservation test (a queued state.changed can never replay as an event.action) |
| Trigger safety inversion (G3) | A constraining field is never silently ignored: `when` rides only if every predicate type is announced; receiver never arms an ungated version | Negative golden (unknown predicate type); client-side skip-whole ERT; companion unit test proving no arming; CI grep tripwire |
| Revision monotonicity across death (L4) | Non-strictly-newer updates are no-ops; counter survives restarts; deleted revision file recovers via welcome floor — no silent stale-rejection loop | The kill matrix (kill -9, force-stop, reboot, delete-file), scripted and rerun at every tag |
| Overload/conflation (G1) | Latest-wins classes coalesce to at most one queued frame per key; ordered editor legs NEVER conflate; no unbounded buffering (bounded queue → 1401 + close) | Burst tests per traffic class; G4 re-verification that editor legs bypass conflation |
| Compose has no error boundary | One malformed attr must not kill the app | Fuzz malformed specs through the renderer; safe clamps (aspect/fraction/dp) as tested conformance |
| Beginner-shaped risks | Kotlin inexperience; motivation across a multi-month build; scope creep | JVM-first Kotlin testing; the rung ladder's banked demos; the staged vocabulary as the scope ratchet; G2‖G3 interleaving to alternate languages |
| Clean-room integrity | Caleb never reads the PoC; Claude never writes jetpacs code | Roadmap lives inside sealed `llm-poc/docs/`; tutor outputs are specs/challenges/pseudocode only; gradle/CI wiring is the sole archaeology exemption |

---

### Critical Files for Implementation

- /home/calebc42/pkb/projects/jetpacs/rewrite-notes.org — the lesson ledger the roadmap must resume (invariants, ladder, syllabus, Lesson 1 state)
- /home/calebc42/pkb/projects/jetpacs/ebp/SPEC.org — the fill-order skeleton the phase map's spec-entry criteria are keyed to
- /home/calebc42/pkb/projects/jetpacs/ebp/llm-poc/SPEC-2.md — the governing PoC line; §14 divergences and §16 reserved decisions feed the register
- /home/calebc42/pkb/projects/jetpacs/ebp/llm-poc/contract.json — the vocabulary/method/error inventory behind the staged-vocabulary and golden-corpus plans
- /home/calebc42/pkb/projects/jetpacs/jetpacs/llm-poc/docs/ — the sealed PoC docs directory where the hidden roadmap document should be written (proposed: REBUILD-ROADMAP.md)


---

# §4 Consolidated trap index

One line each; full context lives in §2 (per-unit traps), §3 (register +
risk register), and §1 (process risks). Quiz these as scenarios — never
recite them as facts from the PoC.

| # | Trap | Bites at |
|---|---|---|
| T1 | jsonrpc.el's default dispatchers are `#'ignore` — **fail-open**; unhandled requests return success-shaped null. Fail-closed must be hand-rolled by explicit signal in both dispatchers | L1 |
| T2 | Error-code landmines: 32000 is jsonrpc.el's "no error" sentinel (transmutes error→result); -1 is taken ("Server died"); -32899..-32800 reserved by LSP | L3/G1-B1 |
| T3 | jsonrpc.el strips `:data` on Emacs-outbound error signals — needs the documented subclass seam (`jsonrpc-convert-to-endpoint` override), not a fork | G1-B1 |
| T4 | Content-Length counts UTF-8 **bytes**, not characters — prove with a multibyte char | L1 |
| T5 | Offline queue storing *fields* instead of the whole frame → a replayed state-change comes back disguised as an action (shape-preserving kind+payload storage) | L5 |
| T6 | The safety inversion: a constraining key (`when` gate) leniently ignored turns "notify below 20%" into "notify always" — catalog negotiation + skip-the-WHOLE-thing, never strip | G3-E2 |
| T7 | `ttl_s` name collision (surface staleness vs queue expiry) — `ttl_s` means queue expiry ONLY; staleness field named fresh (`stale_after_s`, clock from disconnect, persisted) | L5/G1 |
| T8 | Position-keyed list reconciliation attaches cursor/focus/scroll to the wrong row after reorder — `:key` normative from the FIRST list spec | L2/G1-B2 |
| T9 | Renderer rot: wire keys silently ignored (the fillMaxWidth "one true bug" swallowed sibling weight) — schema-coverage test: every schema key consulted or absent | G1-B2 |
| T10 | Compose has **no error boundary** — one throwing composable kills the app; safe clamps (aspect/fraction/dp) are conformance, not polish; crash the K0 toy on purpose | K0 onward |
| T11 | Android platform: no silent app starts (wake = ask the human); notification trampolines banned targetSdk 31+; alarms don't survive reboot (re-arm on BOOT_COMPLETED); ICU regex ≠ JVM regex | L5/G3 |
| T12 | Unversioned disk formats accrete migration shims forever — every persisted artifact (surface cache, revision file, queue, trigger table, pairing token) gets a format-version field at birth, named in the spec's durable-artifact clause | L4 |
| T13 | Emacs restarts with a fresh revision counter → every push silently rejected as stale — persisted counter + welcome revision-floor absorption | L4 |
| T14 | Welcome bloat — everything eventually wants to ride the welcome; each key needs its own spec sentence | L1-R2 onward |
| T15 | One-letter cause/effect hazard: the PoC kept `trigger.fire` (builtin) beside `trigger.fired` (event) — rebuild renames the builtin (D31); `state.edge` ≠ `state.changed` stays | G3-E2 |
| T16 | Confirm-gate class of bug: policy fields (confirm, when_offline…) are companion-consumed and never echoed — a handler must resolve policy from its OWN state, never from the delivered frame | G3-E1 |
| T17 | Spec-ahead divergence ledgers (the PoC's §14 pattern) — banned at any release tag; prose that outruns contract+implementation is debt, not progress | every tag |
| T18 | Brand leakage costs breaking amendments post-publication — brand-free names from the first line (`ebp2:` tag, `companion.*`, `ebp-` elisp prefix); debrand-grep CI | L0/L1 |
| T19 | Tests that let-bind private `--` internals ARE the API bug report — public test-reset/flush seams designed in at module 1 | L2/G1-B7 |
| T20 | Two ~80%-identical sibling spec files WILL silently diverge — one governing doc per line; superseded = git **tag**, never a sibling file | L0 rule |
| T21 | Prompt-bridge blast radius: the PoC advised ~20 blocking builtins (734-line net) — rebuild specs an enumerated covered set (~4 primitives); everything else degrades; advice provably inert outside handlers | G1-B5 |
| T22 | Unmeasured full-snapshot costs: a battery defect from ghost pushes hid until devtools existed — the frame counter lands BEFORE the first renderer dogfood | G1/C1 |
| T23 | Overload behavior left undefined "resolves to an accidental buffer or drop at 3am" — assign behavior per traffic class in the spec; ordered editor legs NEVER conflate | G1-B7/G4 |
| T24 | jsonrpc.el expects LSP-style Content-Length framing — the Kotlin side must emit/parse it byte-accurately (the PoC's one surviving ebp/docs/TODO.org note) | L1/L2 |

---

# §5 Session log (append-only: date · mode · what advanced)

- 2026-07-21 · SCRIBE · Roadmap created from planning session (3 explorers +
  3 designers). No tutoring. Caleb's two Lesson-1 deliverables outstanding.

---

# §6 Consultation log (append-only: date · PoC file opened · fact class 1–5 · where it surfaced)

*(empty — goal state is that this stays nearly empty because this roadmap
suffices)*

---

# §7 Retained-phrasings ledger

PoC-origin maxims Caleb has already adopted as named invariants in his own
ledger (rewrite-notes.org). Cap: ten sentences total, one sentence each; must
be mirrored in `ebp/PROVENANCE.md` under "Retained phrasings" once that file
exists (a logged retention is defensible; an unacknowledged one is a landmine).

1. "The wire declares UI one way and named actions the other; never code."
   (already the epigraph of Caleb's SPEC.org Overview)
2. "Wrong state can only ever cost a feature, never a wrong edit."
   (in rewrite-notes.org syllabus item 7; to be re-derived by Caleb at ED1
   before it enters his spec)
