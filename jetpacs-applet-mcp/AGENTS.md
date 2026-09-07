# Applet-tooling implementation guide

This file applies to `jetpacs-applet-mcp/` and supplements the workspace root
`AGENTS.md`. Read it both when maintaining the applet developer tooling and
when using that tooling to implement an applet elsewhere in this workspace.

## Classify the work first

There are two different jobs in this directory:

1. **Tooling maintenance** changes the reader, index, manifest, validator,
   scaffold, runtime checker, MCP protocol adapter, resources, or prompts.
2. **Applet authoring** uses those operations to build an Elisp application
   over the public Jetpacs API.

Do not mix them. An applet feature should not require weakening the static MCP
server, and an MCP feature should not become a hidden applet runtime dependency.

## Implementation map

| File | Owns |
|---|---|
| `jetpacs-applet-tooling.el` | Path boundaries, native-reader indexing, API/node discovery, applet scans, canonical manifests, validation, deterministic scaffolding, bounded trusted-runtime checks |
| `jetpacs-applet-mcp.el` | MCP tool catalog, arguments, resources, prompts, JSON-RPC lifecycle, stdio |
| `jetpacs-applet-mcp-runtime.el` | The explicit trust transition that loads exactly one workspace applet and the real Jetpacs runtime |
| `APPLET-GUIDE.md` | Normative local workflow for applet lifecycle, builders, actions, state, packaging, Org use, and tests |
| `PACKAGE-SKINS.md` | Package-agnostic architecture and workflow for adapting an existing Emacs package into a native Jetpacs skin |
| `DETERMINISM.md` | One-IR model, determinism envelope, real sender gates, debugging, manifest provenance, Org/runtime separation |
| `examples/hello/hello.el` | Minimal lifecycle-complete reference applet; must remain form-for-form equal to the generated hello scaffold |
| `test/jetpacs-applet-tooling-test.el` | Reader, path, Org, manifest, validator, scaffold, and documentation contract |
| `test/jetpacs-applet-mcp-test.el` | MCP lifecycle, schemas, resources, prompts, error behavior, and static safety |
| `test/jetpacs-applet-mcp-runtime-test.el` | Live registry provenance, runtime tool annotations, and real render determinism/gates |

The domain engine must remain usable without MCP. The MCP server calls its
public functions; it does not reimplement their semantics.

## Trust modes are a hard boundary

### Static mode is the default

Loading `jetpacs-applet-mcp.el` may read bounded source forms but must not:

- load or evaluate an applet;
- macroexpand arbitrary applet forms;
- write files;
- start a shell or arbitrary process;
- use the network; or
- advertise a general evaluator disguised as a debugging tool.

Static inspection is not a sandbox and is not a trust verdict. It is a way to
extract bounded evidence without executing the inspected applet.

### Trusted runtime mode is explicit

Only `jetpacs-applet-mcp-runtime.el` may cross into applet execution. It must:

- require a non-empty `JETPACS_TRUSTED_APPLET`;
- require an `.el` file in the workspace or an explicitly allowlisted sibling
  checkout;
- reject every external read-only reference namespace and path outside those
  canonical checkout roots;
- prefer newer source over stale neighboring bytecode;
- load the real Jetpacs runtime before advertising live operations; and
- expose bounded domain checks, never general-purpose evaluation.

Loading an applet executes arbitrary Elisp. Never start trusted mode merely to
answer a static source question.

## Elisp introspection rules

### Read code as code-shaped data

Use the native Emacs reader for exact Elisp structure. On Emacs 30,
`read-positioning-symbols` attaches source positions while reading. The tooling
records executable registration cons identity before stripping positional
wrappers with `byte-run-strip-symbol-positions`.

This ordering is intentional: an identical quoted example must not steal the
source line of the executable registration. Preserve the identity-based test
whenever reader or scan behavior changes.

Do not replace exact form parsing with regex. A lightweight syntax-aware index
is acceptable only for lazily narrowing a large external package tree; hydrate
the selected definition with the native reader before presenting an exact
signature, docstring, form, or location.

### Preserve homoiconicity

A Jetpacs builder returns ordinary plist/vector/scalar Elisp data. That exact
datum is:

```text
built → inspected/edited → structurally analyzed → sender-gated
      → canonical JSON → EBP → Companion rendering
```

Do not add a second UI AST, a code-generated shadow representation, or a
debug-only serializer. Compose the existing builder, analyzer,
`jetpacs-node->canonical-json`, sender gates, and push path.

### Keep package source references separate from runtime dependencies

`JETPACS_REFERENCE_ROOTS` maps explicit namespaces to arbitrary package source
trees. Every root is inspection-only and may target a different package or
Emacs revision from the running process.

- Validate namespace grammar and reject roots overlapping the workspace or
  one another.
- Never merge a reference root into the workspace boundary or trusted
  launcher's `load-path`.
- Index definitions lazily and hydrate exact evidence with the native reader.
- Discover configured namespaces through MCP schemas/output; do not add one
  hard-coded tool branch per package.

Org is the compatibility/default instance of this generic rule.

`JETPACS_ORG_SOURCE` points at the exact external source checkout and is
exposed only under `org/...` for inspection. It may target a different Emacs
revision from the running process.

- Never merge this root into the workspace boundary.
- Never add it to the trusted launcher's `load-path`.
- At runtime, use Org compatible with the running Emacs unless the trusted
  applet's own dependency graph explicitly supplies another version.
- Test exact lookup for symbols that do not begin with `org-`; prefix guesses
  are not an index.

## Deterministic and bounded output

- Hash literal file bytes with SHA-256.
- Canonicalize paths and forms before hashing semantic records.
- Own printer settings locally; caller `print-length`, `print-level`, or pretty
  printer configuration must not change tool output.
- Sort every semantically unordered collection by a documented stable key.
- Reject cyclic values instead of printing an unstable representation.
- Bound file bytes, result characters, message bytes, walks, and retained
  runtime diagnostics.
- Never cut a manifest form or docstring to fit a response.

A large manifest returns a digest-bearing section index. A page repeats the
same digest, exact range, total, and next offset and contains only whole
records. If source changes and the digest changes during paging, callers must
restart from the new index.

When the manifest's semantic shape changes, review the schema identifier,
generator version, canonical digest inputs, index/page formats, documentation,
and both small/large-manifest tests as one contract.

## Runtime determinism means one explicit envelope

The runtime checker invokes a builder twice and compares the real canonical
JSON bytes. Its result is evidence only for the reported context:

- loaded source and registrations;
- Emacs and Org/application state;
- resolved surface and reconciled device state;
- negotiated profile, capabilities, grants, and limits; and
- window class and any other deliberately observed option.

Do not claim universal purity from two equal calls. Hidden time, randomness,
I/O, prompting, mutation, or process state invalidates that inference.

Read fixed limits from the loaded runtime. Invoke the real non-sending gates.
Do not duplicate limit constants or implement a friendly parallel validator
that can disagree with the normal sender.

## MCP contract

Every tool must have:

- a stable `jetpacs_...` name;
- a short title;
- a description that explains when to call it, what evidence it returns, and
  any trust or execution consequence;
- a closed object input schema with explicit required fields;
- `readOnlyHint`, `destructiveHint`, `idempotentHint`, and `openWorldHint`
  matching actual behavior; and
- bounded text output, with `isError` for recoverable tool-input/domain errors.

The server must preserve string and numeric JSON-RPC IDs, require the complete
initialize lifecycle, ignore notifications without responding, and keep stdout
protocol-only. Static mode must not list runtime tools. The render checker is
not read-only because it invokes a trusted live builder, even though its sender
gates do not transmit.

Resources hold stable guidance. Tools answer focused questions. Prompts encode
repeatable create/review workflows. Keep those roles distinct.

## Tooling change recipes

### Add or change a static analysis operation

1. Implement a documented, bounded function in `jetpacs-applet-tooling.el`.
2. Route every file through the canonical workspace/Org resolver.
3. Use reader-backed structure for exact claims.
4. Add direct ERT coverage, including a decoy/malformed/boundary case.
5. Add or update the MCP tool definition, closed schema, dispatch, and tool
   error behavior.
6. Add MCP discovery/call coverage and update the nearest guide/resource.

### Change registration scanning or provenance

Test all of these explicitly:

- executable top-level/owner-scoped registrations;
- identical quoted forms;
- strings and comments containing definition-shaped text;
- nested decoys;
- exact source line and canonical normalized form; and
- malformed source that must produce a visible parse finding rather than
  silently disappearing.

For action emission/provider changes, additionally test literal applet
providers, literal platform providers, unresolved names, dynamic expressions,
quoted/comment/string decoys, registered-but-unemitted names, and current
contract-derived builtin constructors. Never pin a reference applet's
incidental action count.

### Add a validator rule

- Give the finding a stable severity and code.
- Explain the violated contract and include path/line when known.
- Avoid claiming semantic proof from a static walk.
- Add a failing fixture and the corresponding passing/corrected fixture.
- Keep flow continuations distinct from prompts executed directly inside
  action dispatch.
- Update `APPLET-GUIDE.md` when authors must change behavior.

### Change the scaffold

- Construct literal Elisp forms first.
- Serialize them with tooling-owned settings.
- Read the emitted source back.
- Require form-for-form equality before returning it.
- Preserve lexical binding, package requirements, public docstrings, one
  stable owner, app/root/action registration, idempotent register, symmetric
  unregister, and `provide`.
- Keep `examples/hello/hello.el` form-for-form equal to the hello scaffold.
- Require generated output to pass the current validator.

### Change trusted runtime checking

- Keep runtime seams optional in static mode.
- Source inventory from live registries and claim sites.
- Build through the real registered root.
- Compare real canonical bytes and include the context digest.
- Analyze through `jetpacs-shell--analyze-spec` and invoke real non-sending
  gates.
- Clearly distinguish offline fixed/identity checks from connected negotiated
  checks and normal device acceptance.
- Add a runtime ERT case and perform an actual trusted-launcher handshake.

## Applet-authoring workflow

When the task is to implement an applet, follow this sequence without skipping
directly to trusted execution:

1. Read `APPLET-GUIDE.md` and `DETERMINISM.md`; also read
   `PACKAGE-SKINS.md` when adapting an existing Emacs package.
2. Generate the lifecycle scaffold with `jetpacs_applet_template`.
3. Query `jetpacs_describe_actions`, `jetpacs_describe_node` for each
   unfamiliar EBP node, and `jetpacs_describe_symbol` for each unfamiliar
   Jetpacs or allowlisted package API.
4. Implement one stable owner, pure builders, owner-scoped semantic actions,
   app/root registration, idempotent register, and symmetric unregister.
5. Give every public function a docstring and every action literal `:doc`
   metadata plus `:args` when it accepts named arguments.
6. Keep direct prompts and blocking work out of action dispatch. Continue
   interaction with `jetpacs-flow-continue`; defer ordinary presentation with
   `jetpacs-app-defer-refresh`.
7. Run `jetpacs_inspect_applet`, require every literal emitted action to have a
   provider, review every dynamic site, capture the canonical manifest/digest,
   and run `jetpacs_validate_applet` until it passes or every warning is
   justified.
8. Add offline ERT for builder states, action conclusions/durability,
   registration symmetry, capability fallback, limits, and prompt deferral.
9. Only for code explicitly trusted by the developer, launch runtime mode and
   run inventory plus `jetpacs_check_render_determinism`.
10. Finish with a narrow device smoke for the actual native render, gesture,
    state round trip, reconnect, and offline behavior affected by the change.

The static validator is a gate, not proof. A stable double build is scoped
evidence, not proof. Android compilation is not a device-flow test.

## Verification for tooling changes

Run from this directory.

Static reader and MCP protocol suites:

```sh
emacs -Q --batch \
  -l test/jetpacs-applet-tooling-test.el \
  -l test/jetpacs-applet-mcp-test.el \
  -l test/jetpacs-applet-source-discovery-test.el \
  -f ert-run-tests-batch-and-exit
```

Trusted runtime suite in a fresh process:

```sh
emacs -Q --batch \
  -L ../../ebp.el/lisp \
  -l test/jetpacs-applet-mcp-runtime-test.el \
  -f ert-run-tests-batch-and-exit
```

Check documentation for every shipped Elisp entrypoint and the reference
applet:

```sh
emacs -Q --batch --eval \
  "(progn (require 'checkdoc)
     (dolist (file '(\"jetpacs-applet-tooling.el\"
                     \"jetpacs-applet-mcp.el\"
                     \"jetpacs-applet-mcp-runtime.el\"
                     \"examples/hello/hello.el\"))
       (checkdoc-file file)))"
```

Byte-compile with warnings as errors into a temporary directory, not beside
source:

```sh
compile_output="$(mktemp -d /tmp/jetpacs-applet-compile.XXXXXX)"
JETPACS_APPLET_COMPILE_OUTPUT="$compile_output" \
emacs -Q --batch -L . -L ../../ebp.el/lisp -L ../emacs \
  --eval "(setq byte-compile-error-on-warn t
                byte-compile-dest-file-function
                (lambda (file)
                  (expand-file-name
                   (concat (file-name-nondirectory file) \"c\")
                   (getenv \"JETPACS_APPLET_COMPILE_OUTPUT\"))))" \
  -f batch-byte-compile \
  jetpacs-applet-tooling.el \
  jetpacs-applet-mcp.el \
  jetpacs-applet-mcp-runtime.el \
  examples/hello/hello.el
```

Afterward, confirm the expected four `.elc` files exist only in that temporary
directory. Do not leave compiled files in this source tree.

For entrypoint or trust-boundary changes, perform real static and trusted MCP
initialization handshakes using the commands in `README.md`. Confirm the static
server advertises only static tools and the trusted server advertises exactly
the two bounded runtime tools in addition.

## Completion checklist

- Static mode still executes no applet code.
- Every configured package source (including Org) is inspectable but absent
  from runtime `load-path`.
- Canonical output is independent of caller printer variables and filesystem
  order.
- Manifests are lossless, digest-bearing, and page only on whole records.
- Tool descriptions, resources, prompts, README, and implementation agree.
- Public forms, actions, handlers, and scaffold output carry their required
  documentation.
- Static, protocol, and runtime tests pass in separate Emacs processes.
- Checkdoc and warning-as-error byte compilation are clean.
- No source-directory `.elc` or stale product name/path remains.
