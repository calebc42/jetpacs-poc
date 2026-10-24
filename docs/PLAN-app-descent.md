# App descent: simplify Glasspane and Bloom, move shared behavior into Jetpacs

Status: plan, 2026-10-23. Section 1 in progress.

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

## Section 2 — small shared helpers (new public Jetpacs API)

- `jetpacs-dialog-with-prompting (fn surface &optional refusal)`: replaces
  `glasspane-detail--with-prompting` and the private copies in
  jetpacs-org-dialogs, jetpacs-emacs-ui, jetpacs-org-table.
- `jetpacs-org-path-p` (covers `.gpg`, `_archive`): replaces three checks.
- `jetpacs-org-with-file` macro (check file, clamped IO, find-file, widen):
  five copies across Glasspane and Jetpacs.
- `jetpacs-org-priority-levels`, `jetpacs-org-done-p`.
- `jetpacs-files-segment-name (name &optional ext)`: Bloom and Orgzly.
- `jetpacs-org-file-preface` → `(:text :keywords :intro)`: Bloom and Orgzly.
- `jetpacs-stable-id (prefix identity)` and `jetpacs-heading-identity (ref)`:
  Bloom, Orgzly, Harp. Output must stay byte-identical (ids are persisted).
- `jetpacs-org-changed-hook`, batched on idle: Bloom's watcher, Glasspane's
  save refresh, `orgzly-data-invalidate`.
- Design: public `jetpacs-design-token-color`, an alpha-flattening helper
  (today private `jetpacs-theme--blend`), `jetpacs-design-profile-declares-p`.

## Section 3 — shared heading verbs (~330 lines in Glasspane)

Ownerless `jetpacs.org.heading.*` verbs (todo-set, todo-cycle, schedule,
priority, tags, refile, add-note, delete, duplicate, prop-set, prop-add,
clock-in) resolved in the `jetpacs-org-dialogs-owner` token scope and saved
through `jetpacs-org-save-policy`, with a SAVE-function hook for app
refreshes (Glasspane's vulpea refresh). Glasspane keeps its old verb names
registered as thin aliases for the offline-queue window (`:ttl-s`). Bloom's
TODO handler and Orgzly's `orgzly-data-set-*` follow.

## Section 4 — revision-checked subtree writes (~100 lines in Bloom)

Teach `jetpacs-editor-org-fresh-splice` (and `jetpacs-org-heading-at-ref`) an
optional `:revision` check via `ebp-org-subtree-revision`, answering `stale`.
Replace `bloom-document--live-subtree`, `--replace-subtree`,
`write-subtree`, and the repeated checks in the save/planning handlers and
`bloom-structure--context`. First unify Bloom's two hash computations
(string vs buffer region; they can differ on non-ASCII). Harp's
`harp-write--replace-subtree` gains a check it lacks today.

## Section 5 — link presenter

Public `jetpacs-org-render-register-presenter`, plus an optional `:tick`
freshness check on `jetpacs.org.follow`. Replace
`glasspane-org-reader--on-link-open` / `--present-link-destination` and
Bloom's `bloom-document--on-read-link`. Fix `org.link.open`
(`glasspane-detail--on-link-open`), which pushes a screen directly and
bypasses `glasspane-navigation-open-document`; add its NAVIGATION.org row.
Keep `glasspane.link.open` as an alias verb for the cache window.

## Section 6 — dates and timestamps

- `ebp-org-ts-parse` → `:date :time :end :repeater :warning :range-end
  :active`; `ebp-org-body-active-timestamps`; `:scheduled` and `:revision`
  on `ebp-org--outline-record`.
- `jetpacs-dates-valid-p`, `-delta`, `-weekday`, `-month-name`.
- `jetpacs-org-ts-describe` for repeater/span wording.
- Consumers: `glasspane-ui-human-date` family, `bloom-agenda-parse-timestamp`,
  `bloom-org` date helpers, `orgzly-data--parse-ts`. Day labels ("Today")
  stay in each app.

## Section 7 — reminder projections

`jetpacs-reminders-define-projection (owner projector &key enabled-p)` owns
batching, the last-confirmed cache, and the push hook; the foundation's own
sync becomes one caller. Bloom keeps only its projector; Orgzly gains sync.

## Section 8 — `jetpacs-org-blocks` (~650 lines in Glasspane)

Org body → nodes (prose, quote/verse, checkboxes, drawers, logbook, source and
Babel cards, typed property editor) plus `jetpacs-org-inline-spans` with
style and link-title hooks (vulpea titles stay in Glasspane). Highest payoff,
highest risk (~30 tests pin Glasspane private names). Last.

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
- Move `glasspane-demo.el`'s editor tour next to `jetpacs-org-mode-seed` and
  load the PARA corpus on demand?
