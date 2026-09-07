# Jetpacs implementation guide for coding agents

This file is the operational contract for agents working anywhere below this
directory. It is deliberately explicit so that a smaller model can complete a
bounded change without inventing architecture, treating generated files as
authority, or claiming success without evidence.

## How these instructions compose

- Read this file before editing anything in the workspace.
- Then read the nearest nested `AGENTS.md`; nested instructions add
  directory-specific requirements.
- A direct user request overrides this guide. When a request conflicts with an
  architectural or trust boundary, explain the conflict before changing it.
- Do not guess past missing authority. If the governing spec, source, and tests
  disagree, report the disagreement with exact paths and stop treating any one
  derived artifact as truth.

## Understand the workspace before changing it

This directory is the canonical Jetpacs POC repository. Its independent EBP,
and applet dependencies are sibling repositories under
`/home/calebc42/workspace`; a root `git status` does not describe their changes.
`jetpacs-platform-tools/` and `jetpacs-applet-mcp/` are tracked in this repository.

| Path | Role | Default editing rule |
|---|---|---|
| `README.org` | Goal of the spec-driven branch | Preserve unless the task explicitly changes that goal |
| `./` | Canonical standalone Jetpacs POC repository and the source-backed target used by the developer tools | Obey its lineage and architecture documents |
| `poc/v1`, `poc/v2`, `poc/v3-pre-split` tags in this repository | Immutable older POC snapshots and reusable implementation evidence | Reuse or adapt their code as useful; do not move or modify the snapshot tags |
| `../ebp/`, `../ebp.el/`, `../ebp-kmp/`, `../ebp-compose/`, `../ebp-org/` | Independent EBP repositories | Use `../ebp/` as the sole POC specification authority |
| `../glasspane/` | Sibling applet source | Follow the applet authoring contract below |
| `../grove-native/` | Grove Jetpacs applet and tests | Read its local `AGENTS.md`; the Android reference app lives in `../grove/` |
| `../jetpacs-authoring/` | Shared Elisp authoring and source-inspection repository | Read its local `AGENTS.md` before changing it |
| `jetpacs-automations/` | Optional automation runtime and applet module | Read its local `AGENTS.md` before changing it |
| `jetpacs-components/` | Foundation components and styles module | Read its local `AGENTS.md` before changing it |
| `jetpacs-component-catalog/` | Optional component catalog and design-lab applet module | Read its local `AGENTS.md` before changing it |
| `jetpacs-platform-tools/` | Read-only platform contributor workbench, CLI, and MCP server | Read its nested `AGENTS.md` |
| `jetpacs-applet-mcp/` | Applet analysis engine, static MCP server, and explicit trusted launcher | Read its nested `AGENTS.md` |
| `/home/calebc42/workspace/emacs/emacs/lisp/org` | Exact Emacs Org source checkout | Inspect as a separate read-only source root; never silently add it to runtime `load-path` |

Before editing a nested tree, identify its owner and local dirtiness:

```sh
git -C PATH rev-parse --show-toplevel
git -C PATH status --short
```

Preserve changes you did not create. Do not clean, reset, overwrite, compile
over, or otherwise normalize unrelated files. Existing `.elc`, backup,
generated, rejected-patch, and build files are not source merely because they
are nearby.

## Route the task before reading broadly

| Task | Read first | Primary developer interface |
|---|---|---|
| Change EBP wire/session/durability behavior | `../ebp/SPEC.md`, especially §2.2, then the focused contract slice and goldens | `jetpacs-platform` MCP or `jetpacs-platform-tools ... contract` |
| Change Jetpacs Kotlin or foundational Elisp | `docs/ARCHITECTURE-POC3.md`, `docs/REWRITE-PLAN.md`, then the owning source/tests | `jetpacs-platform` MCP |
| Reuse or port prior POC code | `README.org`, `../ebp/SPEC.md`, then the owning architecture, source, and tests | Prior POC code may be copied, merged, or adapted; verify it against the current normative EBP rules |
| Create or change an applet | `jetpacs-applet-mcp/APPLET-GUIDE.md`, then `DETERMINISM.md` | Static `jetpacs-applets` MCP first; trusted runtime only for explicitly trusted code |
| Skin an existing Emacs package | `jetpacs-applet-mcp/PACKAGE-SKINS.md`, then the exact package source under its configured read-only namespace | `skin_emacs_package` prompt plus static `jetpacs-applets` tools |
| Look up Org behavior | The exact Org definition under the external `org/` namespace | `jetpacs_describe_symbol` or an explicit `org/FILE.el` summary |
| Change platform developer tooling | `jetpacs-platform-tools/AGENTS.md` | Its CLI and MCP tests |
| Change applet developer tooling | `jetpacs-applet-mcp/AGENTS.md` | Direct Elisp tests plus MCP protocol tests |

Do not read every plan and every snapshot. Start with the smallest governing
document and the exact source seam named by the task.

## Source-of-truth order

Use this order when artifacts disagree:

1. `../ebp/SPEC.md` is the normative EBP POC authority. Its §2.2 explicitly
   defines `contract.json` as a machine-readable projection and goldens as
   conformance witnesses.
2. `contract.json` supplies structured vocabulary for implementations and
   tooling. It does not overrule conflicting normative prose.
3. Goldens pin the cases they identify, including byte-exact behavior only
   where the spec says bytes are normative.
4. Architecture documents define local dependency and ownership boundaries.
5. Runtime source and tests show current implementation behavior. They may
   reveal drift; they do not silently amend the protocol.
6. Generated mirrors such as `Vocabulary.kt` and `jetpacs-vocabulary.el` are
   outputs. Regenerate them from the owning contract; never hand-author drift.
7. For Org behavior, inspect the exact external checkout. Do not substitute
   memory, examples, or a handwritten API table for source.

When a README or old plan contradicts this order, cite the contradiction and
follow the governing artifact rather than choosing the most convenient text.

Implementation lineage never determines conformance. Code from `poc/v1`,
`poc/v2`, `poc/v3-pre-split`, `slop-fork/main`, or any other prior work may be
reused, copied, merged, or adapted. Treat that code as implementation evidence,
not protocol authority: build and verify it against `../ebp/SPEC.md`. If
the desired behavior needs protocol semantics that the specification does not
yet define, amend the normative specification and keep its contract projection,
goldens, and affected conformance tests aligned before relying on that behavior.

## Required implementation workflow

### 1. Establish scope and evidence

- Restate the requested outcome in one sentence.
- Name the owning repository, runtime layer, and trust mode.
- Inspect local status and relevant nearby tests.
- Use `rg` for source discovery. Prefer the platform/app MCP tools for bounded
  repository-backed questions.
- For protocol work, query the exact JSON Pointer before editing.
- For public Elisp or Org work, inspect the actual defining form and docstring.

### 2. Write down the invariant before the code

Identify what must remain true after the change. Typical invariants include:

- EBP remains implementation- and Jetpacs-neutral.
- Emacs owns application state and application decisions.
- The Companion owns native presentation, negotiated platform integration,
  accepted presentation state, and durable delivery records.
- EBP carries declarative data and named semantic actions, never executable
  Elisp or another host language.
- Dependency direction is EBP → Jetpacs platform → applets. Foundation code
  must not name a downstream applet.
- A builder returns the actual plist/vector screen IR. Do not introduce a
  parallel UI AST or undocumented generation representation.
- Builders are deterministic readers of explicit state; effects belong in
  actions or background state producers.
- Action acceptance is a durability conclusion, not permission to perform the
  durable effect later on a best-effort timer.
- Every emitted feature, node, builtin, identifier, aggregate, and size remains
  within the negotiated profile and fixed limits.

If a change requires weakening one of these, it is an architecture decision,
not a local refactor.

### 3. Implement through the owning seam

- Put domain behavior in the layer that owns it. Keep protocol adapters,
  storage adapters, UI rendering, and application policy separate.
- Reuse the real contract, reader, registry, canonicalizer, reducer, or sender
  gate. Do not create a second handwritten model of an existing authority.
- Sort unordered inputs before bounding or serializing them.
- Canonicalize paths before checking containment. A lexical prefix is not a
  security boundary.
- Bound file reads, message sizes, tree walks, retained diagnostics, and result
  text at the point that owns the resource.
- Prefer a small focused patch. Do not repair unrelated POC residue unless the
  task asks for it or it blocks verification.

### 4. Make the change explain itself

Code and metadata are part of the documentation surface:

- Every public Elisp function has an accurate docstring and argument names
  referenced in the docstring where Checkdoc expects them.
- Every wire-visible action has literal `:doc` metadata; handlers also have
  implementation docstrings.
- Every public or boundary-bearing Kotlin declaration has KDoc explaining its
  responsibility and invariant, not merely restating syntax.
- Every MCP tool has a stable name, title, behaviorally complete description,
  closed input schema, explicit required fields, and accurate annotations.
- Security and trust transitions are stated next to the entrypoint that
  performs them.
- Comments explain ownership, reason, source, or proof. Delete comments that
  only paraphrase the following expression.
- Update the nearest README/guide and tests in the same change when a public
  workflow, name, path, schema, trust boundary, or command changes.

Do not hide a semantic contract only in chat, a commit message, or a generated
artifact.

### 5. Verify from narrow to broad

Run the smallest test that witnesses the changed contract, then the owning
suite, then cross-layer or device checks proportional to risk.

| Scope | Minimum local verification |
|---|---|
| EBP spec/contract/goldens | `cd ../ebp && python3 validate.py` plus every affected implementation conformance test |
| Jetpacs Elisp foundation | Targeted ERT, warning-as-error byte compilation, then `test/run-tests.sh` for broad changes |
| Companion Kotlin/Android | Owning module unit tests; for broad changes run `cd companion && ./gradlew testDebugUnitTest assembleDebug` |
| Platform tools | `cd jetpacs-platform-tools && ./gradlew clean test installDist`, then `doctor` smoke |
| Applet tooling | Static ERT suite, trusted-runtime ERT suite, Checkdoc, and warning-as-error byte compilation described in its nested guide |
| Applet | Static inspect/manifest/validate, offline ERT, trusted determinism check for trusted code, then a narrow real-device flow |

Android compilation is not evidence that native rendering, persistence,
reconnect, offline delivery, input-state reconciliation, or an applet gesture
works. Use a device test when the behavior crosses that boundary.

### 6. Audit and hand off

Before declaring completion:

- Search for stale names, paths, generated vocabulary, and duplicate constants.
- Confirm no source-directory `.elc` or unrelated build artifact was created.
- Confirm generated files came from their documented generator.
- State exactly what tests ran and what did not run.
- State the determinism envelope or runtime context for any render claim.
- Link the primary implementation and documentation files.

## Using the installed developer interfaces

The workspace MCP configuration is `.agents/mcp_config.json`:

- `jetpacs-platform` answers cross-language platform, architecture, source, and
  EBP-contract questions.
- `jetpacs-applets` exposes static Jetpacs and allowlisted package-source
  discovery, action vocabulary/reconciliation, applet/skin workflows,
  scaffolding, manifests, and validation.

The platform CLI is also usable without an MCP host:

```sh
jetpacs-platform-tools/build/install/jetpacs-platform-tools/bin/jetpacs-platform-tools \
  --root /home/calebc42/workspace/jetpacs-poc \
  --repositories-root /home/calebc42/workspace \
  --org-source /home/calebc42/workspace/emacs/emacs/lisp/org \
  doctor
```

If it is absent or stale, rebuild it in `jetpacs-platform-tools` rather than
inventing repository facts by hand.

## Stop instead of guessing when

- the requested change crosses from static inspection into executing
  untrusted Elisp;
- a spec amendment, contract projection, golden, and implementation disagree;
- the owner of a nested Git tree is unclear;
- completion would require modifying the external Org checkout;
- a capability, durability rule, or security boundary lacks an explicit
  governing contract; or
- a device/external-state action requires authority the user did not grant.

Report the exact blocker, evidence already gathered, and the smallest decision
needed from the user.
