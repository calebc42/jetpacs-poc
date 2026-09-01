# Skinning an Emacs package with Jetpacs

Jetpacs does not replace an Emacs package. It gives that package another
presentation and input surface. Keep three responsibilities distinct:

```text
existing package        applet skin                    Jetpacs / EBP / Companion
state + invariants  ->  snapshot + node builders  ->  native declarative rendering
domain operations  <-  semantic action adapters  <-  generic action dispatch
```

The package remains the domain engine. The skin is ordinary Elisp that reads
explicit state, returns ordinary Jetpacs plist/vector data, and registers
named actions that call the package's programmatic API. The Companion does not
load the package or evaluate Elisp.

## What “arbitrary package” means

The applet model is package-agnostic, but the usefulness of a skin depends on
two concrete interfaces:

1. The package must expose, or permit a narrow adapter to expose, observable
   state and noninteractive domain operations.
2. The desired native experience must fit the current EBP node, action,
   method, capability, and platform-integration vocabulary.

A package with structured state, hooks, and callable functions is directly
skinnable. A package whose interactive commands wrap useful lower-level
functions usually needs a small adapter. A package whose essential behavior
exists only as minibuffer control flow, terminal escape sequences, arbitrary
buffer redisplay, or private side effects needs a stronger adapter and may
need package changes. A genuinely missing native concept requires an EBP and
Companion feature; inventing more remote verb names cannot create a missing
renderer primitive.

## 1. Make the exact package source inspectable

Configure read-only source namespaces when starting the static MCP:

```sh
JETPACS_REFERENCE_ROOTS='{"magit":"/src/magit/lisp","denote":"/src/denote"}'
```

Then:

- call `jetpacs_list_api` with the package namespace and a narrow query;
- call `jetpacs_describe_symbol` for the exact state readers, operations,
  hooks, data structures, and customization variables you intend to use; and
- call `jetpacs_summarize_elisp` on `NAMESPACE/file.el` when you need a file's
  public outline.

The source namespace is inspection evidence only. It is never merged with the
workspace and never enters trusted runtime `load-path`. The deployed Emacs
must load the intended package version through its real package configuration.

Do not create a package-specific MCP tool. Adding a source namespace is data;
the reader, index, symbol description, manifest, validator, and runtime
workflow remain the same for every package.

## 2. Write an explicit package adapter

Keep package coupling in one documented module. Its public contract should
make four things obvious:

- how current package state becomes a deterministic snapshot;
- which stable domain value supplies each node ID and sibling key;
- which package operation each semantic action performs; and
- which hook, sentinel, callback, or explicit refresh invalidates the screen.

A small shape looks like this:

```elisp
(defun notes-skin-snapshot ()
  "Return notes as stable, presentation-independent records."
  (sort (mapcar #'notes-skin--record (notes-all))
        (lambda (left right)
          (string< (plist-get left :id) (plist-get right :id)))))

(defun notes-skin--view ()
  "Build the native notes screen from one explicit package snapshot."
  (apply #'jetpacs-lazy-column
         (mapcar #'notes-skin--row (notes-skin-snapshot))))

(defun notes-skin--on-archive (args params)
  "Archive ARGS' note durably and defer the resulting presentation."
  (if-let* ((id (plist-get args :id)))
      (progn
        (notes-archive id)
        (jetpacs-app-defer-refresh params)
        'accepted)
    'rejected))
```

The snapshot is not a second UI model. It is optional domain normalization;
the builder's returned Jetpacs data remains the only UI intermediate
representation. If the package already exposes stable records, use them
directly.

Never call an interactive command merely because it is public. Inspect what it
does. If it prompts, continue presentation with `jetpacs-flow-continue` after
dispatch or call the underlying noninteractive function with explicit data.
Return `accepted` only after the package effect is complete or durably
committed.

## 3. Design the native skin around workflows

Map user workflows, not Emacs windows, one-for-one. A package buffer is often
several native screens or one adaptive list/detail screen. Preserve domain
identity and navigation context across every entry point:

- one canonical presenter per domain destination;
- thin adapters from search, project, notification, deep-link, and resource
  entry points;
- one return/navigation policy owned by the destination; and
- one semantic action per domain effect, reused wherever it appears.

This prevents the multiple-entry-point bug where the same note, TODO, commit,
or message acquires different chrome and behavior depending on where it was
opened.

## 4. Let source-derived inventories enforce the wiring

Run `jetpacs_describe_actions` before choosing action constructors. Then run
`jetpacs_inspect_applet` over the completed directory. Its action inventory is
not a hand-maintained list:

- registrations come from executable `jetpacs-defaction` forms;
- emissions come from executable remote-action constructor forms;
- providers reconcile against the applet and current Jetpacs platform source;
- dynamic expressions retain their exact form and location; and
- builtin vocabulary comes from `contract.json`, joined to conservative
  source-derived reachability through current Elisp constructors and wrappers.

Require zero unresolved literal remote names. Give every dynamic site a
finite-value ERT test. Compare static provider evidence with
`jetpacs_runtime_inventory` when trusted code is loaded.

## 5. Test the real package boundary

Offline ERT should cover:

- representative empty, loading, populated, stale, and error package states;
- deterministic ordering and stable node identity;
- every action's argument validation, durable effect, conclusion, and refresh;
- package hook/callback invalidation without duplicate effects;
- all finite values behind dynamic action constructors;
- lifecycle registration and teardown; and
- capability fallbacks and negotiated limits.

The trusted render checker then proves repeatability and sender-gate acceptance
for one explicit live state. A narrow device smoke proves native rendering,
gesture dispatch, state round trip, reconnect, and relevant offline behavior.

## When an architectural change is actually needed

| Need | Change |
|---|---|
| New package-specific operation | Applet action + handler only |
| Inspect another package version | Add a read-only source namespace |
| Reuse package adaptation across skins | Shared documented Elisp adapter |
| New layout or input concept | EBP contract + Jetpacs constructor + Companion renderer |
| New receiver-local behavior | EBP builtin, method, capability, or feature + both endpoints |
| New cross-package static invariant | Generic applet-tooling analysis + MCP exposure |

Do not rearchitect the MCP for each package. Extend its generic source and
analysis vocabulary only when the same structural question applies across
packages. Extend EBP when the native representation itself is missing.
