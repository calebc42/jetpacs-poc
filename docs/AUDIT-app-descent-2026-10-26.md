# Audit: the app-descent and pixel-parity change set (2026-10-26)

Scope: the work landed 2026-10-21 to 2026-10-24 in `jetpacs-poc` (0fa3db8..de4eae3),
`ebp.el` (4295221..6b93d3d), `ebp-org` (fadf782..e3a24d9), `ebp-compose`
(146dda1..174b63b) and the spec (amendment #199), made so that Bloom Native,
Orgzly Native and Harp Native could match their Kotlin counterparts pixel for
pixel. The question asked of every change: is it the right seam, or a
workaround left where a seam belonged? The three applets' own parity work was
not re-judged; it was kept green while the foundation under it changed.

## How it was verified

Emacs 30.2 (the PPA build the cloud setup script installs), Org built in. The
whole ERT corpus was run before and after: `ebp.el`, `ebp-org`, every local
`jetpacs-poc` stanza, and the Bloom, Orgzly, Harp and Glasspane suites. Three
things about the baseline are worth knowing before reading any red run:

- Running as root turns five tests red that are green as an ordinary user:
  `ebp-org-save-path-never-prompts`, `ebp-org-status-split-and-dispositions`
  (write-protection and `chmod 0` do not bind root), and the three
  `ebp.el` "no-store" tests (root can create `/nonexistent-ebp-dir`). Run
  the suites as a non-root user.
- `tools/check-jetpacs-registry.sh` fails on any Emacs other than the one
  that built the committed registry: the applet manifest hash it compares
  covers `:generator :emacs-version`. The committed `hello.json` carries the
  author's Emacs; a cloud session carries 30.2. Not drift, and not fixed
  here (see "Left as found").
- Orgzly's and Harp's runners exit 1 on Emacs 30.2 from Checkdoc's newer
  imperative-mood check on existing docstrings; every ERT test passes.
  Bloom's runner reports the same warnings without failing.

## Fixed

### Portability

- `emacs/jetpacs-hypertext.el` called `dom-inner-text`, which exists only in
  Emacs 31, so the warning-as-error compile stanza failed on the declared
  30.1 floor. Now `(dom-texts node "")`; the hypertext goldens are unchanged.

### One build target instead of three answers

2e0aa18 fixed "a widget row carried a member only the app profile advertises"
for one caller with a dynamic variable (`jetpacs-shell-build-target`, read by
exactly one function) and de4eae3 fixed the same class for list rows by
threading a `:target` keyword two layers down into Bloom. The forty other
foundation helpers that gate on the profile still defaulted to `:app`.

- `jetpacs-build-target` (in `jetpacs-surfaces.el`) replaces both that
  variable and `jetpacs--in-builder`: nil outside every builder, the
  profile key of the surface being built inside one. The shell, the data
  builder, the stale builder and the chrome kit bind it; dispatch and flow
  continuations rebind it to nil as they did the old marker. The builder
  guard reads the same binding.
- `jetpacs-effective-target (&optional target)` is the default every
  `*-advertised-p` helper now resolves its TARGET through, so a helper
  reached from any depth of a widget builder asks the widget profile
  without being told. `jetpacs-components-list-item` takes the same default;
  Bloom's explicit `:widget` still works and is now redundant.
- `jetpacs-components.el` requires `jetpacs-surfaces` instead of probing it
  with `fboundp`.

### The target gate covers what Emacs can measure

`jetpacs-shell--gate-target-nodes` checked `max_nodes` alone.
`jetpacs-shell--gate-target-limits` checks `max_nodes`, `max_node_depth` and
`max_size_variants` against the target profile's `limits` (SPEC 10.2); a
widget one level too deep is now refused with the cause named instead of by
the receiver with the whole surface. `max_lazy_items` and
`max_remote_views_bytes` remain the receiver's measures.

### Seams that were hidden parameters

- `jetpacs-chrome-stage-screen` replaces the `jetpacs-chrome--stage-only`
  flag that changed `jetpacs-chrome-push-screen`'s contract from a distance.
  Push is now stage-then-present; Files stages its browser below a document
  through the public function.
- `jetpacs--claim-site` replaces the rebinding of `load-file-name` in
  `jetpacs-apps-call-register`. The ledger records the same claim sites; no
  Emacs primitive is spoofed to get them.
- `jetpacs-shell-push-caught` is the one caught repush for timers and flow
  continuations (log the label, requeue, return nil). The seven
  `(ignore-errors (jetpacs-shell-push ...))` in the heading verbs, the table
  verbs and `jetpacs-chrome-leave` use it; a failing repush is no longer
  silent.

### One save path in the foundation

Foundation code saved Org through two spellings: `jetpacs-org-save-policy`
in some handlers and `(funcall ebp-org-file-save-function ...)` in others.
The two coincide only while the Org editor is registered; otherwise the
variable is the engine's bare `save-buffer`, without the Files pre-write
chain (Org Crypt re-encryption among it). The render encrypt verb, the
file-properties writer, the timestamp writer, the add-heading flow, the
refile save and the clock saves now call the policy. `ebp-org-file-save-function`
stays what the engine documents it as: the engine's own write path, which
the Org editor installs the policy into. Apps may keep calling the variable.

### Behavior fixes in the lifted Org verbs

- `jetpacs-org-heading-verbs`: a `prop-set` with no value wrote the string
  `"nil"`; it now deletes the property (a JSON `false` still writes `"nil"`,
  paired with `t` for switch rows). `schedule` computed "today" on the Emacs
  host; it now uses the device's day (`jetpacs-dates-today` with the
  Companion's zone, SPEC 20.1.2) and refuses a `:when` or `:value` that is
  neither the relative form nor a real ISO date, instead of letting
  `org-read-date-analyze` normalize junk.
- `jetpacs-org-clock--save` caught every error and still answered
  `accepted`. It now defers only on `ebp-org-refused` (a prompt nobody can
  answer), lets other failures reach the handler, and does not try to save
  a buffer that visits no file.
- `jetpacs-reader-org` requires `ob-lob` at top level instead of inside a
  handler, the one lazy require the module's own tripwire was written to
  forbid; its one-line save wrapper is gone.
- `jetpacs-org-render-unload-function` unregisters the skin through
  `jetpacs-render-buffer-unregister`, releasing the ledger claim it used to
  leave behind.
- Two `(error nil)` around `ebp-org-with-file` (settings keywords, tag
  groups) catch only the engine's three statuses; a bug in the visit
  signals.

### Boundary hygiene

- `test/jetpacs-org-reminders-test.el` required and drove Glasspane
  (`glasspane-agenda-register`); the plan said that had to go first. It is
  gone, and the foundation suite runs without `../glasspane`.
- `jetpacs-org-agenda-item-meta` had no consumer anywhere (Glasspane builds
  its own caption); deleted with its test. `jetpacs-org-agenda-type-icon`
  and `-type-label` stay because Glasspane calls them; their wording is
  product opinion by the ratified rule and should move into Glasspane when
  that repository is next touched.
- `jetpacs-editor-org-save-policy` was an alias "kept for app callers" with
  no caller in any app; deleted. `jetpacs-org-reminders-item-hm` is a
  function, not an alias of a private; `jetpacs-org-reminders-enabled` is a
  defcustom.
- `jetpacs-reminders-schedule-sync` no longer takes ignored arguments; the
  coalescing delay is a named constant.
- The widget fixtures are `jetpacs-demo-*`. Their action names stay
  `grove.capture` and `grove.agenda.open` because they reproduce the SPEC's
  `widget-surfaces.golden` witnesses byte for byte; the layering guards now
  include `grove` and no longer carve the fixture out.
- `jetpacs-files-directory-return-screen` ("files-resources", a Glasspane
  PARA term) had no caller; deleted.
- `jetpacs-applet-tooling-check-lifecycle-symmetry` re-registers an applet
  that was registered on entry under `unwind-protect`, so a signalling probe
  cannot strand the live runtime.
- `jetpacs-dialog-slot` logs a failed abandon instead of swallowing it.

### Engine (`ebp-org`)

`ebp-org-with-clamped-io` and `ebp-org-check-file` are public; the `--`
spellings remain for the engine's own use. The foundation reached the
private names in thirteen places; it now uses the public ones.

### Design runtime

`jetpacs-design-scope` takes `:inherit`, the enclosing scope's declarations.
The reference check resolved token and motion names against the local maps
only, while the receiver resolves a nested scope over its parent's merged
maps (`DesignModel.compileScope`), so Bloom copied every `color.*` token into
each screen scope to get past the Elisp check. Bloom's
`bloom-design-screen-scope` now inherits from the active profile and sends
no copied tokens. Nothing from `:inherit` rides the wire.

### Companion (Kotlin, not compiled here)

The design-tier keyboard fix (c6b7192) was applied to one of two editor
renderers. The Material tier built a new `AnnotationOutputTransformation`
per annotation epoch in `Renderer.kt`, the exact pattern the commit names
as the cause of the keyboard restarting after every keystroke. It now keeps
one `LiveAnnotationOutputTransformation` for the field's life, mirroring the
Jetpacs tier. This change and the comment edits in `ebp-compose` were made
without an Android SDK; run `companion/TESTING.md`'s commands before
trusting them.

## Left as found, with the reason

- `jetpacs-org-ts-time-range` is a second timestamp parser beside
  `ebp-org-ts-parse`, kept because it returns Org's unpadded clock ("9:05")
  and Glasspane displays that text; converging would change Glasspane's
  rendering.
- `jetpacs-org-inline-link-tally`, a dynamically bound counter a builder
  increments, has one consumer (Glasspane's reader). Returning the count
  from `jetpacs-org-inline-spans` is the honest shape; it needs the Glasspane
  change in the same step.
- `jetpacs-apps-open-home-route` dispatches a verb by hand (no owner
  binding, no `:strict` gate, status discarded). A floor-level
  `jetpacs-call-action` that runs the dispatch path without a wire receipt
  is the fix; Files' view-change observer is its only caller.
- `jetpacs-files.el` reads `jetpacs-apps--current`, `--registry`,
  `--current-route` and `jetpacs-chrome--stacks` directly. Public accessors
  belong in apps and chrome; the "active app's route targets Files" branch
  belongs in `jetpacs-apps--on-view-change`.
- The registry build's manifest hash covers the generating Emacs version
  (`jetpacs-applet-tooling`, `:generator :emacs-version`), so
  `check-jetpacs-registry.sh` is red on every machine but the author's.
  Hashing the applet's files and the contract, not the generator, would make
  the committed build reproducible.
- `ebp-org-cache-invalidate` accepts and ignores a namespace, and
  `ebp-org-with-mutation` still carries one through every public mutation's
  signature. Whole-table invalidation is right; the dead parameter is API
  churn to remove and was left.
- Companion: `ChromeBack.primaryDockHomeDescriptor` invents a Back action
  from a dock's visual state (an application decision the receiver makes,
  justified by a Glasspane plan invariant); `DeviceBridge` opens an
  `open_surface` target before the admission checks SPEC 14.1 orders;
  `CoreTextInputComponent` re-seeds on node identity where the Material tier
  keys on the accepted input epoch; `SystemBarsMatch` runs from every
  composed scaffold and never restores. Each changes device behavior and
  needs the device to judge; none was changed.

## The other side of the ledger

Most of the descent is sound and should not be revisited: the save-policy
leaf and its tripwires, the ledgered teardown with a disposer per
registration kind, the builder guard, `jetpacs-reminders` (failure is not
emptiness; a late confirm for a replaced projection is ignored), the heading
verb builder with the app keeping names, scope, save and leave, the
revision-checked splice with its restore outside `save-restriction`,
`jetpacs-dates`, the follow and id-title hooks, `:strict`, the register-at-
load refusal attributed by load file, and amendment #199 end to end.
