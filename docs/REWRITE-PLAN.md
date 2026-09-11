# Rewrite milestones and current owners

Status: historical milestone map. W0-W10 describe the order used to establish
the EBP/Jetpacs foundation; they are not an active backlog or an authority for
current protocol behavior. Current work is routed through
[`PLAN-poc3-rebuild.md`](PLAN-poc3-rebuild.md).

## W0-W10 map

| Milestone | Guarantee it introduced | Current owner and evidence |
|---|---|---|
| W0 | Contract-derived vocabulary and drift checks | EBP `contract.json`, generators in `ebp-kmp`/`ebp.el`, and owner projection checks |
| W1 | Emacs framing, envelope, authentication, and client state | `../../../ebp.el` plus ERT and wire goldens |
| W2 | Kotlin framing, envelope, authentication, and strict receiver | `../../ebp-kmp/wire` plus JVM conformance suites |
| W3 | Session state machine, welcome profiles, barriers, and reconnect | EBP implementations and handshake/adversarial tests |
| W4 | Revisioned surfaces, tombstones, drafts, and core rendering | `:ebp-kmp`, `:wire`, Room store, Elisp builders, and renderer suites |
| W5 | Typed actions/events, state-before-action, receipts, and outcomes | `wire`, `ebp.el`, Jetpacs action dispatch, and receipt/work contracts |
| W6 | Durable outbox, replay, expiry, dedupe, and capacity | EBP reducers, Jetpacs Room outbox, kill/replay tests |
| W7 | Dialogs, notifications, reminders, themes, capabilities, triggers, and editing | Focused EBP modules plus Android/Elisp adapters and their owning suites |
| W8 | Offline reminder/trigger durability and post-commit effects | Room store, trigger firing service, reminder/trigger contracts, device gates |
| W9 | Renderer/chrome feature parity under current conformance rules | `ebp-compose`, design renderers, Jetpacs chrome, screenshots and semantics |
| W10 | Bounded overload behavior | EBP limits, bounded command actor, Elisp work budgets, saturation tests |

The current source contains implementations for every milestone topic. A green
historical exit gate is not proof for a later working tree; rerun the current
owning suite and state exactly which device/manual gates were not run.

## The `ebp.el` boundary

The durable naming and dependency rule is:

> `ebp-` code is wire-and-Emacs only. `jetpacs-` code owns product behavior at
> the Kotlin/Android/Compose application boundary.

An `ebp-` file must load in bare `emacs -Q --batch` with only its repository on
`load-path`. Its require closure may contain vanilla Emacs and other `ebp-`
files, but no Jetpacs feature, function, variable, face, error, custom group,
or downstream app reference. The aggregate runner derives coverage from the
actual upstream file set instead of maintaining a manual list.

The canonical endpoint and reusable Org engine now live in
`../../../ebp.el` and `../../../ebp-org`. Jetpacs keeps product
surface, renderer, reader/editor, files, app-host, and device integrations in
`emacs/jetpacs-*.el`.

SPEC 14.4 durable EventId receipts now live in `ebp-sqlite.el`'s versioned,
pairing-partitioned schema behind the `ebp-store.el` contract — `ebp.el` keeps
only the dispatch ordering — and a legacy flat `receipts.sqlite` migrates in
place, atomically, on the first client that opens it.

## Reuse and lineage

The immutable `poc/v1`, `poc/v2`, and `poc/v3-pre-split` tags are reusable
implementation evidence. They are neither conformance authority nor code-use
prohibitions. Copy, merge, or adapt any useful implementation, then verify it
against the current EBP spec, contract projection, goldens, architecture, and
tests.

When desired semantics are absent from EBP, amend the normative spec and align
its projection, witnesses, and both endpoint tests before relying on them. A
Jetpacs plan or old implementation cannot silently fill a protocol gap.

## Elisp conventions retained from the rewrite

- Minimum supported Emacs is 30.1; use public built-ins such as `jsonrpc.el`,
  `sqlite.el`, and `track-changes.el` before adding compatibility machinery.
- Keep lexical binding enabled and public docstrings accurate.
- Use `defcustom` only for user-settable policy and `setopt` for package-owned
  customization. Internal state remains `defvar` or lexical state.
- A wire-visible action has literal documentation metadata and a handler
  docstring.
- Normalize and validate through the shared contract-backed builders; do not
  hand-author a second vocabulary.
- Sort unordered inputs before bounding or serializing them.
- Canonicalize paths before containment checks; lexical prefixes are not a
  security boundary.
- Bound work where the resource is owned and isolate a failed app/screen/seam
  rather than poisoning the process.

## Standing product decisions

- The rebuilt line targets the current EBP major; it does not provide an alias
  period for old wire names.
- The first platform remains Android, but EBP and its reusable implementations
  stay platform-neutral.
- Room is the Companion store; SQLite is the independent Emacs receipt/work
  store. Neither proves the other's commit.
- Org synchronization uses built-in `track-changes.el`; a custom diff engine
  is not a default architecture.
- Shared helpers require a real ownership boundary and demonstrated consumers,
  not merely repeated-looking code.
- Platform and library primitives are selected through
  [`PLATFORM-RENTAL-REGISTER.md`](PLATFORM-RENTAL-REGISTER.md).

## Historical detail

The full original rung table, audits, and port manifests are preserved at the
snapshot documented in [`HISTORY.md`](HISTORY.md). Source comments that cite
this file refer primarily to the `ebp.el` boundary above.
