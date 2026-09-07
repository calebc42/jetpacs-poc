# Platform-tools implementation guide

This file applies to `jetpacs-platform-tools/` and supplements the workspace
root `AGENTS.md`. Follow it when changing the platform CLI, repository
workbench, or platform MCP server.

## Product identity

`jetpacs-platform-tools` is a read-only, source-backed workbench for people and
agents changing Jetpacs or EBP. It is not the Jetpacs runtime, public applet
API, or a distributable SDK. The installed executable has two frontends:

- explicit human-facing commands such as `doctor`, `overview`, `summarize`,
  and `contract`; and
- an MCP stdio server selected by `serve` or by omitting the command.

Both frontends must call the same domain operations. Do not implement a fact or
query separately in the CLI and MCP layers.

Applet source reading, semantic manifests, validators, scaffolds, and trusted
render checks belong to `../jetpacs-applet-mcp/`. Do not move them back into
this product merely because they are developer tools.

## File map

| File | Owns |
|---|---|
| `src/main/kotlin/com/jetpacs/platform/tools/JetpacsPlatformTools.kt` | Process identity, CLI argument parsing, command selection, usage text, version |
| `Workspace.kt` | Canonical workspace/Org roots, containment, bounded reads, deterministic file discovery |
| `SourceOutlines.kt` | Token-efficient, non-evaluating Kotlin and Elisp outlines |
| `JetpacsWorkbench.kt` | Repository-backed domain operations, MCP tool definitions/dispatch, resources, prompts |
| `McpServer.kt` | MCP and JSON-RPC lifecycle and stdio adaptation only |
| `src/test/.../WorkspaceTest.kt` | Root, traversal, symlink, Org, and determinism boundaries |
| `src/test/.../SourceOutlinesTest.kt` | Language masking, top-level detection, and outline behavior |
| `src/test/.../McpServerTest.kt` | Tool documentation/schema contract and protocol lifecycle |
| `README.md` | User-facing commands, MCP configuration, and product boundary |

If a change does not fit one owner, split the responsibility rather than
turning `McpServer` or `main` into a second workbench.

## Non-negotiable invariants

### Read-only means read-only

The exposed product may inspect allowlisted source. It must not expose a tool
that writes files, invokes builds, starts arbitrary processes, evaluates
Elisp, performs network requests, or mutates the inspected workspace.

A developer may run Gradle to build this product; that does not authorize an
MCP caller to run Gradle through it.

### Repository-backed, not lore-backed

- Discover modules from current settings files.
- Read EBP vocabulary from the active `contract.json`.
- Cite architecture explanations to canonical local documents and source.
- Return real implementation excerpts when examples are requested.
- Do not maintain a parallel handwritten list of methods, nodes, limits,
  modules, or API signatures when a source artifact already owns it.

If source artifacts disagree, report the mismatch. Never choose one silently
and present it as reconciled truth.

### Every path has one explicit boundary

`Workspace` owns all filesystem authority:

- canonicalize configured roots with real paths;
- reject traversal and symlink escape after resolution;
- require regular files and enforce byte limits;
- expose the external Org checkout only as `org/...`;
- reject overlapping workspace and Org roots; and
- never let the Org allowlist broaden ordinary workspace reads.

Do not open a caller-provided path directly in a workbench method. Resolve it
through `Workspace` first.

### Determinism precedes result limits

Filesystem iteration order is not stable. Sort canonical relative paths before
applying a count limit. Sort unordered records before rendering. Cap returned
text through the shared bound so the CLI and MCP see the same result.

Do not truncate input before sorting; doing so makes the selected result depend
on filesystem-provider order.

### Outlines never execute source

`SourceOutlines` is an intentionally shallow static view. It masks comments and
string bodies while preserving positions/newlines, then finds declarations at
the appropriate structural scope. It does not compile Kotlin, call Emacs, load
Elisp, expand macros, or claim to be a complete parser.

Add a fixture for every syntax case that changes masking or scope. A regex-only
change without decoy tests is incomplete.

### MCP is an adapter, not the domain

`McpServer` owns protocol mechanics:

- negotiate the supported MCP version;
- require `initialize` followed by `notifications/initialized`;
- preserve JSON-RPC string and numeric request IDs;
- never answer notifications;
- distinguish parse, request, method, parameter, and internal failures;
- return recoverable domain/tool failures as visible `isError` tool results;
  and
- keep protocol traffic as the only stdout output.

Repository discovery and architecture prose belong in `JetpacsWorkbench`, not
in JSON-RPC branches.

## Change recipes

### Add or change a domain operation

1. Identify the source-of-truth artifact and cite it in KDoc or returned text.
2. Implement one bounded operation in `JetpacsWorkbench` using `Workspace`.
3. Add direct unit coverage for normal, empty, malformed, and boundary cases.
4. If exposed through the CLI, update command parsing, dispatch, and usage.
5. If exposed through MCP, follow the MCP recipe below.
6. Update `README.md` when the user-visible command or result contract changes.

### Add or change an MCP tool

Update all of these in one change:

1. the domain method in `JetpacsWorkbench`;
2. `toolDefinitions()` with a stable `jetpacs_...` name, concise title,
   behaviorally complete description, closed object schema, explicit required
   fields, and accurate annotations;
3. `callTool()` argument validation and dispatch;
4. direct domain tests and an MCP-level discovery/call/error test; and
5. the README tool list and any relevant resource or workflow prompt.

Do not add an MCP tool that only wraps a shell command or returns an unbounded
repository dump. Prefer a narrow query that lets the caller ask the next
question.

### Add or change a resource or prompt

- Resources are compact stable context, not a replacement for focused tools.
- Resource text must be repository-backed or clearly identified as curated
  architecture guidance with local citations.
- Prompts describe repeatable workflows and must name the evidence/tools the
  caller should use.
- Add list and read/get protocol coverage.

### Change source discovery

1. Resolve EBP sources only from their canonical sibling namespaces `ebp/`,
   `ebp.el/`, `ebp-kmp/`, `ebp-compose/`, and `ebp-org/`, with the fixed
   applet/authoring siblings `glasspane/` and `jetpacs-authoring/`
   also explicitly allowlisted.
   Components, automations and the component catalog are workspace modules, never sibling fallbacks.
2. Do not add fallback copies or scan `.claude` worktrees and `archives`.
3. Add a test that proves stale or archived candidates remain excluded.
4. Update `doctor`, overview output, and README discovery documentation.

### Change a product name, package, executable, or version

Keep these synchronized:

- `rootProject.name` in `settings.gradle.kts`;
- `application.mainClass` in `build.gradle.kts`;
- Kotlin package directories and declarations;
- process diagnostics, usage text, MCP `serverInfo`, and tests;
- installed-distribution paths in `README.md` and `.agents/mcp_config.json`;
  and
- the version constant and protocol identity assertions.

Run a clean build, then search the entire workspace and generated distribution
for the stale identity.

## Documentation standard

- Boundary classes and public/internal operations receive KDoc that states
  responsibility, side effects, and important ordering/security rules.
- Tool descriptions explain when to use the tool and what its evidence means.
- Error text tells the caller how to correct input without exposing a stack
  trace through normal CLI failures.
- Do not say an artifact is authoritative unless the governing spec says so.
- Examples use current paths and names and are exercised by a smoke command.

## Verification

Run from this directory:

```sh
./gradlew clean test installDist
```

Then exercise the installed command against the real workspace and Org root:

```sh
build/install/jetpacs-platform-tools/bin/jetpacs-platform-tools \
  --root /home/calebc42/workspace/jetpacs-poc \
  --org-source /home/calebc42/workspace/emacs/emacs/lisp/org \
  doctor
```

For MCP changes, also perform one real stdio initialization against the
installed distribution. Unit tests are necessary, but this smoke catches a
wrong generated script, main class, package, path, or server identity.

Before completion, confirm:

- every Kotlin test passes;
- the installed binary exists under the documented name;
- `doctor` finds the EBP spec/contract, Emacs endpoint, surface API, Companion
  settings, and exact Org checkout;
- no old package/path identity remains in source or `build/install`;
- no unbounded or mutating MCP capability was introduced; and
- the root and this README still describe the same product boundary.
