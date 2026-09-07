# Local repository layout

The 2026-09-05 migration flattened the canonical POC and moved its independent
repositories into `~/workspace/`. The separate `~/workspace/jetpacs/` checkout
remains the research and hand-rewrite repository.

| Local checkout | Intended GitHub repository | Role |
|---|---|---|
| `~/workspace/jetpacs-poc/` | `calebc42/jetpacs-poc` | POC platform, Foundation components, optional Material 3, automations and catalog, developer tools |
| `~/workspace/ebp/` | `calebc42/ebp-poc` | Normative protocol specification and conformance data |
| `~/workspace/ebp.el/` | `calebc42/ebp.el` | Emacs endpoint |
| `~/workspace/ebp-kmp/` | `calebc42/ebp-kmp` | Kotlin protocol implementation |
| `~/workspace/ebp-compose/` | `calebc42/ebp-compose` | Neutral model and Compose Foundation renderer |
| `~/workspace/ebp-org/` | `calebc42/ebp-org` | Reusable Org integration |
| `~/workspace/glasspane/` | `calebc42/glasspane` | Glasspane applet |
| `~/workspace/jetpacs-authoring/` | `calebc42/jetpacs-authoring` | Shared Elisp authoring and inspection |
| `~/workspace/grove-native/` | `calebc42/grove-native` | Grove Elisp applet |
| `~/workspace/orgzly-native/` | `calebc42/orgzly-native` | Orgzly Elisp applet |
| `~/workspace/harp-native/` | `calebc42/harp-native` | Harp Elisp applet |
| `~/workspace/jetpacs/` | `calebc42/jetpacs` | Separate hand rewrite |

The initial migration assembled eleven POC repositories: the three existing GitHub projects plus
eight additional repositories. Grove's Elisp implementation was subsequently extracted into the additional
`~/workspace/grove-native/` repository; the original `~/workspace/grove/` keeps
the Android app. See `../grove-native/README.md` relative to the POC root.
The future hand rewrite uses `calebc42/jetpacs`.
The developer tools and Material 3 remain inside `jetpacs-poc`; they do not need
separate remotes.

## Main integration (2026-09-06)

All twelve local repositories now use `main`. The EBP checkout keeps its local
name `ebp/` and source namespace `ebp/`; its fetch and push remote is now
`git@github.com:calebc42/ebp-poc.git` after the GitHub rename.

POC main joins the consolidated implementation, the previous main, the former
umbrella head, and all three consolidated module heads. EBP main joins its
published POC specification with its previous main. Glasspane main joins the
published consolidated applet with the old bundled/submodule history. These
merge commits deliberately retain the verified consolidated source trees;
predecessor implementations remain available in their parent histories. The
separate Jetpacs rewrite's existing master tip also becomes main. The other
eight repositories were already on main.

Every pre-integration source tree was compared with its resulting main tree
and matched exactly before this documentation update. No runtime code or
protocol semantics changed. Existing historical branches and tags remain
preserved. The records below describe earlier migration stages; references
to former branches and remote names in those records are historical.

## Components and automations consolidation (2026-09-06)

The current target is **12 GitHub repositories**, listed above.
`jetpacs-components/` and `jetpacs-automations/` now live inside `jetpacs-poc/`
as ordinary modules, without nested Git repositories or separate remotes.
Foundation has no Material dependency; automations remains an optional applet
using public host APIs. Build, staging, test and source-discovery paths use the
local modules, while feature names, payload paths and extension IDs are stable.

Original branches and tags are preserved under
`refs/archive/jetpacs-components/{heads,tags}/*` and
`refs/archive/jetpacs-automations/heads/*`. Full Git databases (including indexes),
portable `history.bundle` files, source snapshots, patches and baseline hashes
are retained in `/home/calebc42/workspace/.jetpacs-consolidation-backup-20260906-122927`.
These archive refs are not merged ancestry and are not sent by a normal branch
push. When publishing, explicitly push the archive refs too, or retain/upload
the bundles. No accumulated working changes were committed; HEAD and existing
branches/tags remain unchanged. The following older migration record describes
the prior layout and its verification.

Current consolidation checks are recorded in
[COMPONENTS-AUTOMATIONS-CONSOLIDATION.md](COMPONENTS-AUTOMATIONS-CONSOLIDATION.md).

## Component catalog consolidation (2026-09-06)

`jetpacs-component-catalog/` is now an optional module inside `jetpacs-poc/`.
The catalog and Design Lab retain their public app/feature names and depend
on the public components and platform APIs. They need no separate GitHub repo.
The target is now **12 repositories**, listed above.

Its original Git database/index, source snapshot, patches, hashes and verified
`history.bundle` are preserved at `/home/calebc42/workspace/.jetpacs-catalog-consolidation-backup-20260906-143802`.
Original history is also available at
`refs/archive/jetpacs-component-catalog/heads/main`. This is preserved history,
not merged ancestry; normal branch pushes do not send archive refs. Explicitly
push the archive refs or retain the bundles when publishing. No commits,
merges, remote changes or pushes were made.

Catalog consolidation verification: all 115 catalog/Design Lab ERT tests,
16 platform-tools tests, 3 trusted-tooling tests and 3 platform-entry tests
pass. Companion application unit tests and APK assembly pass. Onboarding and
clean package-install fixtures pass; all five packaged catalog Elisp files
match the relocated sources. Static/MCP tests pass 44/45, retaining only the
previously documented Glasspane documentation fixture failure. Checkdoc,
temporary warning-as-error tooling compilation, doctor and real static,
trusted and platform MCP handshakes pass. The synthetic trusted launcher
confirms the catalog is not eagerly loaded and loads Catalog/Design Lab
without Material 3. No device deployment was performed for this path change.
All 16 original catalog source files are preserved; runtime source is
unchanged. No source bytecode was created. Verification logs and a task-only
patch are retained in the catalog consolidation backup.

## Checkout discovery

Build and test scripts discover sibling checkouts through
`tools/repositories-root.sh`, or accept `JETPACS_REPOSITORIES_ROOT` explicitly.
The Companion Gradle settings use the same collection directory. Existing
per-dependency environment overrides in test scripts are retained.

The MCP configuration in `.agents/mcp_config.json` points to this flattened
checkout and explicitly selects `/home/calebc42/workspace` as the repository
collection. Tool source access remains restricted to named, canonical repository
namespaces. The collection directory itself is not a general source namespace.
The external Org checkout remains read-only and separate from runtime loading.

## Preserved history and recovery evidence

The canonical Jetpacs Git database, index, working changes, branches and tags
were promoted intact. At that stage the branch remained `slop-fork/main`.
The former umbrella history is also retained in the active repository under
`refs/archive/umbrella/heads/*` and `refs/archive/umbrella/tags/*`.
No branches were merged and no commits or pushes were made by this migration.

The local backup is:

```text
/home/calebc42/workspace/.jetpacs-layout-backup-20260905-211439/
  baseline.json       original repository heads and source-file hashes
  moves.json          completed source/destination journal
  canonical-git/      original canonical Git database
  umbrella/.git/      original umbrella Git database and index
  umbrella/           preserved colliding files, archives, old Material checkout
```

The former umbrella documentation is retained in `docs/workspace/`. All ten
independent repositories were moved as complete directories, including ignored
files and Git metadata. No nested active Git repository remains inside the POC.
The baseline audit found no missing tracked or untracked source files and
confirmed all repository HEADs and 59 original canonical refs were preserved.

Remote URLs were not changed by this migration. In particular, the POC origin
still names `calebc42/jetpacs`; after the GitHub rename, update both its fetch URL
and its separately configured push URL to `calebc42/jetpacs-poc`. Connect the
remaining checkouts to their intended remotes after those repositories exist.
Committing the accumulated working changes and merging onto `main` are separate
steps from the local filesystem migration.

## Verification

- Companion `testDebugUnitTest assembleDebug`: passed.
- Platform tools `clean test installDist`, real doctor, CLI source lookup and
  MCP initialization/tool listing: passed.
- Onboarding and hermetic package installation fixtures: passed.
- Core and Material 3 generated-vocabulary checks: passed without regenerating
  outputs.
- Material catalog and REPL: 59 ERT tests passed.
- Focused contract-authoring and widget suite: 84 ERT tests passed, including
  default sibling contract lookup.
- Independent authoring, automation, component and catalog suites: passed.
- Glasspane reader/navigation/PARA suites: passed. Its final suite retains the
  seven failures reproduced before this migration: agenda formatters, detail
  builders, hub verb inventory, private reads across modules, reader adapter
  gate, reader trees and board views.
- Aggregate Elisp verification passed the sibling suites (including Grove),
  projection checks, package/onboarding fixtures and delineation guards, then
  stopped at the existing warning-as-error failure in
  `emacs/jetpacs-org-render.el:1420`: `jetpacs-editor-org-save-policy` is not known
  to be defined. This failure was also observed before relocation.
- Applet static/MCP suite: 42/43 passed. The remaining Glasspane documentation
  contract test reports the previously observed five unresolved fixture action
  providers and two package-header warnings.
- Bounded source discovery and overlapping checkout regressions: 2/2 passed.
- Trusted runtime ERT: 3/3 passed. Checkdoc and temporary warning-as-error
  compilation passed for all four shipped applet-tooling entrypoints.
- Actual trusted Glasspane MCP initialization/tool listing and offline render
  check: passed. Both builds produced SHA-256
  `1e97976620a0951e45238def713deff03b393fa707ee71435a56f02ed1c51737`,
  matching the pre-migration output: 8,455 bytes, 109 nodes, depth 7, compact/medium
  fallback window and no negotiated device profile. Offline structural and
  identity/variant gates passed; this is not a connected device-flow claim.
- No source-directory `.elc` files were created or modified by verification.

No device deployment or remote operation is part of this layout verification.
