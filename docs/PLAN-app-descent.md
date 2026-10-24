# App descent: simplify Glasspane and Bloom, move shared behavior into Jetpacs

Status: plan, 2026-10-23. Sections 1–8 landed on branch `app-descent`.

Boundary rule (ratified 2026-10-22): Jetpacs owns built-in Emacs behavior
(Org, dired, project, Modus), plain and unopinionated, exposed as ownerless
verbs, ref-addressed primitives, or seams. Apps own their product opinion:
Glasspane the PARA model and third-party integrations (vulpea, ef-themes,
org-srs, packages); Bloom the Grove/Bloom Android app's information
architecture and pixel parity. This plan continues the first descent
(glasspane steps 1–3, hardening rungs 1–8).

Repositories: `jetpacs-poc`, `../glasspane`, `../bloom-native`; the shared
pieces also serve `../orgzly-native`, `../harp-native`, and `../grove`.
Commit per step per repository. Every step must keep `test/run-tests.sh`
(jetpacs-poc, all siblings), `glasspane/test/run-tests.sh`, and
`bloom-native/test-elisp/run-tests.sh` green, plus the layering and
delineation checks.

Line counts below are survey estimates.

## Section 1 — no new Jetpacs code

### 1a. Glasspane dead code (~190 lines)

- `glasspane-org--file-list` (no callers).
- `glasspane-ui--top-actions` alias (no callers).
- `glasspane-detail-clock-body` (no callers; Journal was retired) and the two
  helpers only it calls, `glasspane-org-clock-status` and
  `glasspane-org-recent-clocks`. `jetpacs-org-clock.el` owns clocking.
- Test-only helpers (`glasspane-org--heading-at`, `--file-heading-items`,
  `--filter-items`, `glasspane-agenda-widget-icon`): delete with their tests
  when the test covers nothing that the production path does not.

### 1b. Glasspane aliases left from the first descent (~60 lines)

Switch callers to the Jetpacs names, then delete the aliases:
`glasspane-detail--relative-date`, `--ref-location`,
`glasspane-ui-ts-time-range`, `glasspane-org-mtime-stamp`,
`glasspane-org-tag-strings`, `-tag-group`, `-tag-settings-stamp`,
`-workflow-keywords`, `-heading-item-at`, `glasspane-agenda-type-icon`,
`-type-label`, `-widget-item-meta`, `glasspane-agenda-tokenize`,
`glasspane-resources--archive-row`, and the agenda-scope/items aliases in
`glasspane-org.el`.

Prerequisite: `jetpacs-poc/test/jetpacs-org-reminders-test.el` asserts that
Glasspane alias symbols exist. A Jetpacs test must not name Glasspane; drop
those assertions first (move any still-useful check into Glasspane's suite).

Wrappers that add a vulpea arm or the PARA inheritance rule are not aliases
and stay (`glasspane-org-todo-items`, `-all-tags`, `-file-tag-group-index`).

### 1c. Bloom switches to existing Jetpacs functions

- `bloom-agenda--with-heading` → `jetpacs-org-heading-at-ref` (gains stale
  classification and the save policy instead of a bare `save-buffer`).
- `bloom-agenda--move-to-today` regex rewrite →
  `jetpacs-org-heading-set-planning` (keeps time, repeater, delay). Note:
  may add reschedule log notes when `org-log-reschedule` is set.
- Direct `customize-save-variable` calls → `jetpacs-settings-save-variable`.
- Not switched: `bloom-settings-on-change` cannot use
  `jetpacs-settings-apply-wire` as is. Bloom's screens send choices and
  strings as printed sexps (`read-from-string`), Jetpacs' decoder expects
  choice labels and raw strings. Converging needs Bloom's settings screens
  to emit Jetpacs' wire shape, which touches pixel-parity builders.

## Section 2 — small shared helpers (landed)

Pure Org helpers went to ebp-org, not Jetpacs: by the naming rule in
`jetpacs-org.el`, `ebp-` names what touches only Emacs and the wire.

- `jetpacs-dialog-with-prompting (fn surface &optional refusal failure)`
  in jetpacs-dialog.el replaces the private copies in jetpacs-org-dialogs,
  jetpacs-emacs-ui, jetpacs-org-table, glasspane-detail and the inline
  guard in glasspane-srs. Bloom's two-line guards were left alone: they
  carry their own error handling, and wrapping them adds nesting for
  nothing.
- `ebp-org-path-p (path &optional encrypted)` replaces
  `jetpacs-reader-org-path-p` and the dialogs' private predicate;
  Glasspane's editor adapter keeps a one-line wrapper that opts into
  whole-file gpg.
- `ebp-org-with-file ((var file) body...)` (root check, IO clamp, Org mode,
  widened) replaces four hand-rolled visits. Habits was not converted: it
  has no root check today, so converting would change behavior.
- `ebp-org-priority-levels`; `ebp-org-done-keywords` /
  `ebp-org-done-keyword-p` (buffer, then global default, then what
  `org-todo-keywords` declares) replace Glasspane's hardcoded
  DONE/CANCELLED fallbacks.
- `jetpacs-opaque-id (prefix identity &optional length)` in
  jetpacs-widgets.el: Bloom (4 sites), Orgzly, Harp and the custom-view
  list; pinned byte-identical to the persisted formula.
- `jetpacs-files-segment-p` in jetpacs-files.el: Jetpacs Files' rename and
  new-entry flows, Bloom's notebook names, Orgzly's book names.
- Dropped after a closer look:
  - `jetpacs-org-file-preface`: Bloom and Orgzly return different shapes,
    and Bloom's is pixel-parity code.
  - `jetpacs-org-changed-hook`: Bloom is the only watcher. Glasspane's
    "save refresh" was a flag nobody read, now deleted.
  - Design token helpers: only Bloom reads design tokens.
  - `jetpacs-heading-identity`: Bloom's two heading identities spell the
    headline key differently (`headline:` vs `heading:`), so unifying them
    would change ids.

## Section 3 — shared heading verb handlers (landed)

`jetpacs-org-heading-verbs.el` builds the handlers; the app keeps the wire
names. `(jetpacs-org-heading-verb VERB :token-owner O :save S :leave L)`
returns an (ARGS PARAMS) handler for todo-set, todo-cycle, schedule,
priority, tags, refile, add-note, delete, duplicate, prop-set, prop-add
or clock-in. The app registers it with its own `jetpacs-defaction` and
name.

Why not fixed `jetpacs.org.heading.*` verbs, as first planned:
- Glasspane's `heading.*` names stay byte-identical, so taps already queued
  offline still resolve and no alias period is needed.
- The static applet validator still sees a literal provider for every name
  Glasspane emits; it records the handler as dynamic.
- Each app keeps its own token scope and save funnel; an ownerless verb
  could know neither.

Glasspane dropped its twelve handlers and the refile flow (~290 lines);
its tests still dispatch every verb by name. Not adopted elsewhere yet:
Bloom addresses headings by notebook/heading ids rather than tokens (and
its toggle carries its own repeater rule), and Orgzly does not load
ebp-org, which the heading cores require.

## Section 4 — revision-checked subtree writes (landed)

- `jetpacs-editor-org-splice (beg end value)` is the shared write core:
  replace the region, guard the trailing newline and size limit, save
  through `jetpacs-org-save-policy`, and restore text, narrowing, point and
  modified flag on a failure before durability. The restore runs outside
  `save-restriction`, since putting the whole text back collapses the
  markers a narrowed restriction is saved as.
- `jetpacs-editor-org-fresh-splice` keeps its stamp/bounds/tick and
  synced-editor checks and calls the core.
- `jetpacs-editor-org-revision-splice (ref value revision)` writes only
  while `ebp-org-subtree-revision` still answers REVISION, else `stale`.
- Bloom: `bloom-document--live-subtree` and `--replace-subtree` are gone;
  the document write, the planning save and the edit autosave all use
  `ebp-org-subtree-revision`. The document revision was a hash of the
  subtree string while the outline's was a hash of the buffer region;
  they differed on CRLF files. Both are now the region hash, and a test
  pins it on a CRLF file.
- Harp: `harp-write--replace-subtree` had callers only under `legacy/`;
  deleted. The live Harp writes edit in place and were never the
  unchecked whole-subtree replace the survey worried about.

## Section 5 — link following (landed)

- Jetpacs: `jetpacs.org.follow` takes an optional `:tick` (an edited
  buffer answers `stale`) and checks the link in the widened buffer. A new
  abnormal hook, `jetpacs-org-render-follow-functions`, receives
  (SOURCE DESTINATION POSITION SURFACE LINK-POS) and runs after a presenter
  registered for the buffer on that surface and before the global
  presenter. Apps add to it with `jetpacs-add-hook`, so teardown removes
  them. A hook fit Glasspane better than the planned per-buffer
  `register-presenter`: its tree reader draws every Org document while
  Glasspane is registered.
- Glasspane: the reader emits `jetpacs.org.follow`; `glasspane.link.open`
  and its handler are gone. Its presenter claims links followed on
  Glasspane's surface, from a dialog, or in the document Files shows, and
  routes file destinations through `glasspane-navigation-open-document`.
  Link exposure is per session, so a tap rendered before the change could
  not have resolved anyway.
- Glasspane `org.link.open` is gone too. It opened a device-supplied link
  string with `org-link-open-from-string` (any link type, `shell:` and
  `elisp:` included) and pushed the detail screen past NAVIGATION.org.
  Properties link buttons now emit `jetpacs.org.follow` on the link's
  exposed drawer position.
- Bloom: read mode still names links by target text (its parity parser has
  no buffer positions), but `bloom.read.link` now accepts only a target
  written in the current note. Bloom's other read mode already used
  `jetpacs-org-render-in-context` with its own presenter.

## Section 6 — dates and timestamps (landed)

- ebp-org: `ebp-org-ts-parse` reads the stamp that starts a string into
  `:date :time :end :repeater :warning :range-end :active`. Clocks are
  zero-padded, and a date or range end that is not a real calendar day is
  refused. It replaces `bloom-agenda-parse-timestamp`; the existing
  `ebp-org-ts-date`/`-time`/`-repeater` extractors stay for callers that
  scan unanchored text.
- jetpacs-dates: `jetpacs-dates-parts`, `-valid-p`, `-delta` (calendar
  days, so DST cannot shift it), `-weekday` (optionally short) and
  `-month-name`, all locale-free English like the existing month
  abbreviations. Bloom dropped its own parts/validity/delta helpers and
  its weekday and month tables; `bloom-org-date-shift` keeps its
  nil-on-invalid contract over `jetpacs-dates-shift`. Glasspane dropped
  its weekday table and its `time-to-days` deltas; a stamp naming a day
  that does not exist now yields no pill instead of a normalized date.
- Bloom's outline scan reads SCHEDULED once per heading instead of twice.
- Dropped after a closer look:
  - `jetpacs-org-ts-describe`: only Glasspane words repeaters ("weekly",
    "every 2 weeks"); Bloom shows the cookie as written. It stays in
    Glasspane as product wording.
  - `ebp-org-body-active-timestamps`: Bloom is the only consumer; Orgzly
    scans body stamps too but does not load ebp-org.
  - `:scheduled`/`:revision` on `ebp-org--outline-record`: every other
    outline consumer would pay a property lookup and a subtree hash per
    heading for fields only Bloom reads.
  - Orgzly: `orgzly-data--parse-ts` returns epoch floats for its own
    model and cannot use ebp-org; nothing in it duplicates jetpacs-dates.
- Unrelated fix found on the way: Bloom's quick-card test stubbed
  `current-time`, but Org's matcher reads today through a bare
  `decode-time`, so the test failed on any day but 2026-10-23.

## Section 7 — reminder projections (landed)

New `jetpacs-reminders.el`, free of Org and ebp-org:
`(jetpacs-reminders-define-projection OWNER PROJECTOR &key enabled-p)`.
The app supplies only the projector; the module owns
- coalescing (`jetpacs-reminders-schedule-sync OWNER`: one idle timer per
  owner, or the handler's continuation inside a dispatch);
- the confirmed-set cache, moved only by the Companion's acceptance, and
  ignored for a projection replaced while its send was in flight;
- failure versus emptiness: a signalling projector sends nothing, a
  disabled one (`:enabled-p` answers nil) sends the empty set;
- the push trigger (one `jetpacs-shell-after-push-hook` member for every
  projection) and teardown: a projection is a `reminder-projection`
  ledger claim, so `jetpacs-teardown-owner` removes it. Removal keeps the
  device's set, as both callers already did.

Callers:
- The canonical Org set (`jetpacs-org-reminders`) is one projection; its
  hand-rolled sync, cache and hook are gone. Its sync moved from inside
  the push hook to the coalesced timer, the timing Bloom already used.
- Bloom keeps its projector, actions and the Org-refresh trigger; its
  timer, cache, sync and push hook are gone.

Not done: Orgzly. Its PARITY.md says platform reminder delivery is
deliberately not claimed by the current UI, and its specs use
path-bearing ids that are not SPEC identifiers. Wiring it is now a
projector of about twenty lines, but turning on notifications for
Orgzly users is the owner's call (see open decisions).

## Section 8 — inline Org spans (landed, narrowed)

Planned as `jetpacs-org-blocks`, the whole Glasspane body renderer.
Narrowed after reading it: by the boundary rule Jetpacs renders Org
bodies through `jetpacs-org-render` (built-in fontification), and the
Glasspane block builders are product design — quote rules, centered
verse, titled export and special-block cards, Glasspane ids and colors —
with no second consumer. Bloom keeps its parity parser. Moving them would
have put a second, Glasspane-styled body renderer into Jetpacs.

What two callers did share moved:
- `jetpacs-org-inline.el`: `jetpacs-org-inline-spans` and
  `jetpacs-org-inline-prose` render Org inline objects (emphasis, code
  and verbatim, links, footnote references) as spans, tapping and
  exposing real buffer positions, charged to the shared budget as one
  unit. Bare links get readable labels; id links ask the abnormal hook
  `jetpacs-org-inline-id-title-functions` before Org's id index, and
  Glasspane adds its vulpea lookup there with `jetpacs-add-hook`.
  `jetpacs-org-inline-link-tally` counts rendered links for a caller
  that binds it.
- The stock footnote dialog now renders definitions with
  `jetpacs-org-inline-prose`, so it shows emphasis without Glasspane.
  The `jetpacs-reader-org-footnote-prose-function` seam, whose only user
  was Glasspane, is gone.
- `jetpacs-buffer-face-spans` (prepared faces to monospace spans, faces
  only) serves Jetpacs source blocks and Glasspane export blocks;
  `jetpacs-buffer-charge` (spend spans and bytes all or nothing) serves
  the inline spans and Glasspane's block and call cards. The source-block
  renderer keeps its own accounting: it checks spans before
  `jetpacs-org-render--emit-native-p` spends bytes, an order the helper
  cannot express.

Glasspane lost about 270 lines; its two pure emphasis tests moved to
`test/jetpacs-org-inline-test.el`, and its reader tests still cover links
and footnotes end to end.

## Stays in the apps

Bloom's Grove-parity parser and inline spans, agenda sections and wording,
palettes, planning canvas UI, the keywordless-repeater rule; Glasspane's
vulpea index, PARA tag inheritance, navigation boundary, Areas/Projects/
Resources, SRS, EF, packages; saved views (each app has its own query
language); Bloom's org-ql mode until a security review of device-supplied
queries reaching `read-from-string`/`org-ql-select`.

## Open decisions for the owner

- Retire Glasspane's compatibility verbs (`files.properties.*`,
  `detail.open-file`, `journal.capture` / `glasspane-journal.el`)?
- Should Orgzly deliver reminders? Its calculation exists
  (`orgzly-reminders-upcoming`); delivery would be a projection with
  opaque ids, likely behind an off-by-default setting.
- Move `glasspane-demo.el`'s editor tour next to `jetpacs-org-mode-seed` and
  load the PARA corpus on demand?
