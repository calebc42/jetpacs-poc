# POC 3 rebuild status and completion gates

Status: the architectural rebuild is implemented in the current workspace.
This document is now the execution map for changing or validating it; it is not
a queue of the original construction phases.

The outcome is a Room-first Android Companion, independent Emacs durability,
Navigation 3 receiver state, extracted EBP implementations, composable renderer
extensions, and an Elisp-owned application layer. The boundaries are defined in
[`ARCHITECTURE-POC3.md`](ARCHITECTURE-POC3.md).

## Implemented baseline

| Area | Current implementation evidence |
|---|---|
| Protocol authority | `../../ebp/SPEC.md`, its contract projection, goldens, and validator |
| Emacs endpoint | Independent `../../../ebp.el` and `../../../ebp-org` repositories |
| Kotlin endpoint | Independent `../../ebp-kmp` repository, consumed as `:ebp-kmp` and `:wire` |
| Android durability | One Room 3 `jetpacs.db`, schema version 2, `RoomEbpDurableStore`, and a bounded command actor |
| Android presentation | Navigation 3 keys plus registry-derived app, dialog, notification, widget, and tile profiles |
| Core rendering | Independent `ebp-compose` model and Foundation renderer |
| Design rendering | Jetpacs-owned Material 3 and sibling Jetpacs Components implementations composed only by `:app` |
| Home-screen widgets | Generic `:renderer:glance`, Room bindings/tokens, and durable click admission |
| Text editing | Generic `ebp-sync.el` plus shared state-based Compose controllers and Jetpacs product adapters |
| Platform breadth | Closed capabilities, 17 trigger types, 10 state types, and five fixed Quick Settings tile hosts |
| Applets | Owner-scoped Elisp apps registered through Jetpacs APIs; selected packages remain independently owned |

A row means the source seam exists. It does not claim that every device or
manual gate was rerun for the current working tree. Verification evidence must
always name the exact command and runtime used.

## Change order

For any behavior change:

1. Identify the governing EBP paragraph or confirm that the behavior is
   Jetpacs-only.
2. Query or inspect the matching `contract.json` projection when wire
   vocabulary is involved.
3. Change the repository that owns the behavior. Do not add a Jetpacs adapter
   to avoid changing an upstream EBP or renderer owner.
4. Add the narrow contract witness before or with the implementation.
5. Run the owning suite, then Jetpacs integration, then device checks if the
   behavior crosses a native or lifecycle boundary.
6. Update the nearest current guide when a public workflow, name, path,
   schema, command, or trust boundary changes.

## Routing table

| Change | Owning seam | Minimum evidence |
|---|---|---|
| Wire/session/durability semantics | EBP spec, contract, goldens, `ebp-kmp`/`wire`, or `ebp.el` | `validate.py` plus affected implementation conformance suites |
| Jetpacs Room persistence | `:core:database`, `:core:ebp-store`, and app composition | Memory/Room contract, reopen/migration/fault test, app unit test; device for Android-only boundaries |
| Receiver navigation | `:core:navigation` and `JetpacsNavigation.kt` | Navigation policy/unit tests and process-restoration/device flow |
| Core Compose behavior | `ebp-compose` | Model/Foundation unit tests and relevant device semantics |
| Material or Jetpacs design behavior | Owning renderer repository | Manifest/projection check, renderer tests, reviewed screenshots, semantics where affected |
| Jetpacs Elisp foundation | `emacs/jetpacs-*.el` | Focused ERT, warning-as-error byte compilation, then `test/run-tests.sh` for broad changes |
| Applet behavior | Applet repository | Static validation, focused ERT, determinism/trusted runtime as applicable, then narrow device flow |
| Android component or platform effect | `:app` | Unit/manifest/lint/assembly plus real-device evidence for the affected OS behavior |

## Completion gates

The rebuild remains conforming only while all applicable gates hold:

- the exact EBP specification, contract projection, and named goldens agree;
- generated Kotlin and Elisp vocabulary matches its owning source;
- `:ebp-kmp`, `:wire`, and `ebp-` Elisp remain free of Jetpacs, Room,
  Navigation, Android, Compose, and downstream app policy as applicable;
- every accepted Companion mutation and external effect is ordered after its
  required Room commit;
- every accepted Emacs action has receiver-owned receipt/work durability that
  survives independently of the Companion and session;
- all Emacs-derived durable Android state is pairing-partitioned;
- Room is the only production source for accepted cached state and the outbox;
- legacy file/prototype stores are neither imported nor used as fallback;
- renderer profiles are derived from installed handlers and each extension
  node is dual-gated by node type and owner;
- no app-specific behavior or second UI AST enters Kotlin;
- Navigation 3 keys contain identifiers only and never compete with EBP
  `current_view`;
- all file, message, tree, diagnostic, and result bounds are enforced by the
  layer that owns the resource;
- revocation fences authentication and resumes external cleanup after a crash;
- Android component exposure and Intent handling match [`SECURITY.md`](SECURITY.md);
- JVM/ERT, schema, lint, assembly, screenshot, accessibility, offline,
  reconnect, process-death, and device gates pass in proportion to the change.

The current Android command matrix is maintained in
[`../companion/TESTING.md`](../companion/TESTING.md). The aggregate Elisp and
cross-repository gate is `test/run-tests.sh`.

## Rebuild triggers

Most changes should be focused adaptations. Revisit the architecture before
coding when a proposal changes any of these:

- source of truth or protocol authority;
- transaction, lifecycle, or failure-domain owner;
- public renderer model or extension ownership;
- KMP/common-code boundary;
- trust mode or exported Android surface;
- direction between EBP, Jetpacs, and applets; or
- the single SurfaceSpec IR used by builders and renderers.

A new helper, library, or compatibility layer that changes none of those is not
a new architecture. Check [`PLATFORM-RENTAL-REGISTER.md`](PLATFORM-RENTAL-REGISTER.md)
before creating it.

## Historical phase names

The original POC construction plans used W, RF, JC, JA, and numbered phase
labels. Those labels remain in some source comments because they identify the
test that introduced a seam. [`REWRITE-PLAN.md`](REWRITE-PLAN.md) maps W0-W10
to current owners. The short compatibility pages map the JC/JA citations. Full
plans and audits are recoverable through [`HISTORY.md`](HISTORY.md); they do
not determine current doneness.
