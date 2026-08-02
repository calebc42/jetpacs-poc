# DRAFT amendments #154–#155 — the `ebp.data` module

Drafted 2026-08-01 against `ebp/SPEC.md` @ `83d6e08` (amendments through
#152 ratified; #149 held; #153 drafted and routed, independent — if both
ratify, SPEC-CHANGES appends in ratification order as separate ebp
commits). Source plan: [PLAN-rf4a-data-spec.md](PLAN-rf4a-data-spec.md);
parent §RF-4 (PLAN-refound :779-881).

**These are drafts for ratification, not applied edits.** Each amendment
gives (a) the SPEC-CHANGES row ready to paste, (b) the precise normative
SPEC.md edits, (c) the artifact changes (contract, goldens, fixtures,
validate.py). The `Ratified` column is the maintainer's. **Held-#155
consequence, stated up front: the RF-4 gate names the wide-integer
round-trip golden, so a #155 hold blocks the RF-4a rung exit** — the
two-row split preserves per-row hold mechanics; it does not make the
encoding optional.

Suggested ratification order: **#154 then #155** (the encoding paragraph
lands inside #154's §27.1 type table; #155 is the severable decision that
the `wide_integer` type and its canonical grammar exist at all — a #155
hold strikes that type row and its dependents **and forces #154's own
goldens 40-42 and fixtures 23/24 to be re-authored off the type**, per
§27.1.1's hold note, leaving a four-type module).

**Artifacts are generated, not hand-copied.** Every golden line and wire
fixture below is emitted by
`tools/gen-rf4a-artifacts.py`;
running it with a target directory applies the package, running it bare
prints the exact bytes. Schema hashes are therefore **real** SHA-256/JCS
digests of the declarations — `c75e0524…ce494` (vault v1) and
`9f2b6e7d…16a70` (vault v2, the drift fixture) — not placeholders, and
the draft text cannot drift from the validated bytes. Dry-run result:
`OK: 43 frames, 75 widget lines, 7 hypertext nodes, 20 wire fixtures x3
chunkings`.

## Options for G1 (A = recommended; the draft below is written as A everywhere)

| # | Decision | A (recommended) | B |
|---|---|---|---|
| O1 | Welcome member name | `data_state` (avoids overloaded bare `data`: `error.data`, `${data.*}` trigger templates) | `data` |
| O2 | Snapshot capacity | ~~one frame~~ | **RESOLVED (Caleb, 2026-08-02): B — multi-part snapshot sequences, drafted in §27.3.** The measured vault is embryonic and NOT representative; the single-frame ceiling was a floor-case number promoted into a design envelope |
| O3 | JCS scope | identity/hash/dedupe-keys + §4.5 sizing only; wire bodies stay §24.5-semantic (elisp never ES6-formats floats) | wire-canonical changeset bodies (byte-exact data goldens become possible; every `real` needs ES6 shortest-round-trip in elisp) |
| O4 | Storage-exhaustion refusal | `1201` + `data.reason:"data-limit"` (no new code) | new 16xx code |
| O5 | `truncate` op | keep (4-op vocabulary) | trim (`replace` + snapshot cover most uses) |
| O6 | Provenance enum | `file \| db` (the orgseq words), default `db` | `source \| derived` |
| O7 | `data.desync` notification | omit in v1 — reconnect forces the welcome report | add Companion→Emacs desync notification |
| O8 | §24.6 module cases | draft now with explicit RF-4b/4c deferral markers (#141/#152 pattern) | defer the text too (leaves #154 claiming conformance scope with no named cases — the #150 defect) |
| O9 | #149 (held) interaction | #154's exhaustion language mirrors held-#149's drafted shape and cross-references it, so a later #149 ratification generalizes rather than forks | module-local wording, no cross-reference |
| O10 | `check_spec_sync` State/Capability columns | decline; the residue stays recorded as a wish (runbook enforcement paragraph) | extend the tool (ebp-side, d48075d class) |

---

## #154 — the data projection module (`ebp.data`, Section 27)

### SPEC-CHANGES row (ready to paste)

> | 154 | 2026-08-01 | §27 (new), §4.5, §7.4, §10.2, §11, §22.1, §22.2, §23.5, §24.3, §24.6 (cross-ref §3 item 3, §14, §15; contract) | **The data projection module — a durable, schema-declared, changeset-maintained mirror.** Consumers of Emacs-authored data (typed Android layers, widgets, queries) previously had two bad options: re-parse the authored source (rejected permanently — Emacs is the sole interpreter of source formats) or ride presentation nodes (measured: the node-aggregate caps bind at 4,096 cells, one real vault already needs 3 chunked updates — a `table` is a view with a view's limits). The module makes the Companion the holder of a durable materialized projection: Emacs declares a schema with identity `{id, version, hash}` (`data.schema`), then maintains the projection exclusively through revisioned inline changesets (`data.changeset`) — snapshots, deltas with parent chaining, row upserts/deletes and provenance-scoped `replace` (the producer's rewrite-all-rows-per-unit pattern as one atomic op). The projection is pairing-scoped and durable; the welcome's `data_state` member reports schema identity and last durably applied revision, so restart-resume is a welcome-read, not a probe. Single-writer v1: Emacs is authoritative; Companion-side mutations are ordinary §14 actions riding the §15 durable queue (descriptors delivered with the schema; no generic row-write verbs; no second sync machinery) and the projection changes only when the round-trip changeset lands. Drift is negotiated, not erred: `refused`/`dynamic` results let pinned typed consumers detect mismatch before any changeset flows. Ops are closed objects and the member names `site`/`clock`/`cols`/`merge` are reserved for a future negotiated merge-discipline capability — CRDT-ready vocabulary, not CRDT machinery. Exhaustion per the #151 duty: one totally ordered per-pairing revision stream, persisted, no wrap, reclaimed only by schema-epoch change; no retained tombstones (the floor is one durable number, unlike §13.1's raced per-surface streams). Seven welcome limits (`max_data_*`, one optional); the §4.5 reservation gains `data_state`'s bounded worst case. Additive per §25: a new OPTIONAL capability with positive discovery and safe fallback; no existing message, golden, or fixture is modified. Conformance cases for the loopback kill/restart scenario and offline write-back are drafted with explicit deferral to the implementation rungs (llm-poc-2 PLAN-refound RF-4b/4c), recorded here so the deferral is explicit, not silent. | contract `methods` += `data.schema`, `data.changeset`; `capabilities` += `ebp.data`; `limits.welcome` += 7 `max_data_*` members; goldens/frames.golden += 3 frames (coverage floor); goldens/wire += `23-data-schema-accept`, `24-data-schema-drift` (+ manifest); validate.py `check_params` data arm (enforcement named in-row) | |

### SPEC.md edits

**1. New top-level section, appended after §26** (a release-time reorder
note may move it beside §19–§21 at 2.0.0-final):

```markdown
## 27. Data projection module

The data module is OPTIONAL and is negotiated as `ebp.data`. It makes the
Companion the holder of a durable materialized projection of an
Emacs-authored dataset: a set of declared tables maintained exclusively by
applying changesets Emacs sends. The module is pairing-scoped and durable
— the deliberate opposite of Section 19's session-scoped editor: the
materialized projection, its schema identity, and the last durably applied
revision MUST survive connection loss and Companion process death, and
Emacs MUST durably persist its declared schema, its next revision, and its
last acknowledged revision.

Consumers MAY read the projection in every connection state, including
with no session at all. A projection is a snapshot of authority, not a
cache of it: a stale projection is correct, and a consumer MUST surface
the projection's revision rather than re-derive content from the authored
source. Projection authority is normative: a consumer reads only the
projection, MUST NOT interpret the underlying authored source format, and
MUST NOT write the projection except by the Companion applying received
changesets. Every Companion-originated mutation is a Section 14 action
riding the Section 15 durable queue; the projection reflects it only when
a subsequent changeset does.

Emacs MUST NOT send a `data.changeset` whose encoded params exceed
`max_data_changeset_bytes`, and MUST NOT declare a schema whose encoded
declaration exceeds `max_data_schema_bytes`; both bounds are chosen so an
allowed message also frames within `max_frame_bytes` (Section 4.5).

> Informative: the module's carrier is inline changesets only. The one
> measured vault (young and small — a floor case, not a design envelope)
> projects to 7.34% of a single frame, but the multi-part snapshot rules
> of Section 27.3 are the capacity story: a projection of any size crosses
> as a staged sequence of frame-sized parts. A bulk carrier is
> deliberately not specified; if measurement ever justifies one, the reserved shape is a
> hash-verified compressed changeset artifact at a negotiated URI whose
> integrity failure falls back to inline changesets, which remain the
> complete floor.

### 27.1 Data model

A schema declares tables; a table declares a primary key and columns.
Table and column names MUST match `[a-z][a-z0-9_]{0,63}` — a deliberately
narrower grammar than Section 4.4, chosen so declared names may be
embedded as SQL identifiers without quoting. Names beginning `sqlite_`
MUST be rejected; names beginning `ebp_` are reserved for consumer-side
bookkeeping and MUST be rejected in declarations.

A table definition is a closed object `{pk, columns, scope?}`: `pk` a
non-empty array of column names, each declared and non-nullable; `columns`
an object mapping column names to closed definitions
`{type, nullable?, provenance?}`. `type` is one of `integer`,
`wide_integer`, `real`, `text`, `blob`. `nullable` defaults to false; a
nullable column carries JSON `null` when absent of value. `provenance` is
`file` or `db` (default `db`): `file` marks the value as literal authored
source text; `db` marks it producer-formatted. A consumer MUST NOT treat a
`db`-provenance value as authored source text, and in particular MUST NOT
return one inside a mutation as though it were. `scope` optionally names
one declared column as the table's provenance-unit column; `replace` ops
are legal only on tables that declare it.

Column value encodings:

- `integer`: a JSON number, mathematically integral, within Section 4.2's
  safe range. A producer whose column domain cannot be proven within the
  safe range MUST declare the column `wide_integer` instead.
- `wide_integer`: always a JSON string in the canonical decimal encoding
  of Section 27.1.1.
- `real`: a JSON number under Section 4.2's rules. A producer holding a
  non-finite value MUST fail the changeset locally (Section 4.5's
  unrepresentable-value rule); it MUST NOT substitute.
- `text`: a sequence of Unicode scalar values. The Section 19 lossless
  rule applies to rows: no endpoint may substitute, drop, or replace
  characters to force representability; a row that cannot be represented
  losslessly is not eligible, and the producer resolves that above the
  protocol.
- `blob`: base64 [RFC4648] with required padding and no whitespace — the
  Section 17.2 image-data rule.

#### 27.1.1 Canonical wide-integer encoding

*(This subsection is amendment #155; see its row. A #155 hold strikes the
`wide_integer` entry from §27.1's type list, the `integer` bullet's
"MUST declare the column `wide_integer` instead" sentence, this
subsection, §24.6's boundary-value case, wire fixture 25, and — in
validate.py — the `wide_integer` member of `DATA_TYPES` plus the
canonical-form check. **It additionally REQUIRES re-authoring #154's own
artifacts, which bake the type in:** frames.golden 40-42 (`notes.pos`
re-typed `integer`, its values re-authored as JSON numbers) and wire
fixtures 23/24 with their manifest entries (same declaration; hashes and
Content-Length recomputed by the generator). The row split preserves
per-row hold mechanics in the ledger; it does not make the hold free in
the artifacts. Corrected 2026-08-01 after review: the earlier claim that
the hold was a clean four-item strike was false.)*

A `wide_integer` value is a JSON string: an optional leading `-` followed
by decimal digits with no leading zero (zero is exactly `"0"`), no `+`,
no `-0`, and a value within the inclusive range `-9223372036854775808`
through `9223372036854775807`. Every representable value has exactly one
spelling. A receiver MUST reject a non-canonical spelling with `1201` and
`data.reason: "data-value-invalid"`, and MUST NOT widen, round, or
re-format. The encoding exists because Section 4.2 hard-caps JSON numbers
at ±(2^53−1) with a Parse Error while 64-bit producers are real; a
`wide_integer` column carries the string form always — a receiver MUST
reject a JSON number in a `wide_integer` column, so no value has two
encodings.

### 27.2 Schema declaration and identity

`data.schema` (Emacs → Companion, request, `READY`) declares the
projection's schema and learns the Companion's resume point. Params:
`schema` — a closed object `{id, version, hash}` where `id` is a Section
4.4 identifier, `version` a positive safe integer monotonic per `id` that
MUST NOT wrap, and `hash` 64 lowercase hexadecimal characters; `tables` —
the table definitions of Section 27.1; `mutations` — an optional array of
mutation descriptors (Section 27.4). `hash` MUST equal SHA-256 over the
[RFC8785] serialization of the object `{"id", "version", "tables",
"mutations"}` — the hash member excluded from its own input, and the
optional `mutations` member **omitted from the input when absent**, never
substituted by `null` or `[]`. A Companion
SHOULD verify the hash against the declared content; a declaration whose
hash does not match is rejected with `1201` and
`data.reason: "data-value-invalid"` — an unverifiable identity is
malformed, not drift. Canonical JSON binds identity and key derivation;
wire bodies remain ordinary Section 4 JSON compared semantically per
Section 24.5.

The result is `{status, revision?, reason?}`, `status` one of:

- `accepted` — the Companion holds (or has just created) a projection
  under exactly this declaration. `revision` is present iff a materialized
  projection under this exact `hash` exists, and is the Companion's last
  durably applied revision; absent `revision`, Emacs MUST begin with a
  snapshot changeset.
- `refused` — a pinned typed consumer requires a schema this declaration
  mismatches, and the Companion is configured to refuse. `reason` states
  why. The previously materialized projection, if any, is retained
  untouched at its recorded identity and revision — honest-stale for its
  existing consumers.
- `dynamic` — the declaration mismatches a pin, and the Companion elects
  to materialize the declared schema generically instead; typed consumers
  requiring the pinned schema are disabled and MUST NOT observe this
  projection.

A Companion holds at most one projection per pairing. On a declaration
whose `{id, version, hash}` differs in any member from the held
projection's — a new `id` included — a schema-generic Companion MUST
atomically drop the held projection, including any tables the new
declaration does not declare, re-create the declared tables, and answer
`accepted` with no `revision` — a schema-epoch change. The projection is disposable
by construction; the authored source is not.

Emacs MUST send `data.schema` and receive `accepted` or `dynamic` on each
connection before its first `data.changeset`; a changeset without that is
rejected with `1201` and `data.reason: "data-undeclared"`. Re-declaration
mid-session is legal and follows the same rules.

The authenticated welcome carries the module's durable state: the
`data_state` member (Section 10.2), REQUIRED when `ebp.data` is granted
and omitted otherwise — `{}` when nothing is materialized, else
`{schema: {id, version, hash}, revision}`. Restart-resume is therefore a
welcome-read: after Companion process death, Emacs learns the surviving
floor before `READY`.

### 27.3 Changesets and the revision state machine

`data.changeset` (Emacs → Companion, request, `READY`) carries params
`{schema_hash, revision, ops, snapshot?, parent?}`. `schema_hash` MUST
equal the accepted declaration's hash — the guard against a changeset
racing a mid-session epoch change; mismatch is `1201` with
`data.reason: "data-schema-mismatch"`. `revision` is a non-negative safe
integer. `snapshot` defaults to false. `parent` is REQUIRED exactly when
`snapshot` is absent or false and MUST be absent when `snapshot` is true;
`part` and `final` are legal only when `snapshot` is true (Section
27.3's staging rules).

Emacs MUST assign data revisions from one durable, per-pairing counter
that is strictly increasing within a schema epoch; a schema-epoch change
begins a new revision stream, with which the counter MAY restart
(Section 27.6). Gaps in numbering are allowed. Revisions MUST NOT
wrap and MUST NOT be reused within a schema epoch.

A **delta** whose `revision` is greater than the floor MUST carry `parent`
equal to the Companion's last durably applied revision; the staleness
answer below takes precedence at or below the floor, so a redelivered
delta is answered `stale`, never `data-revision-gap`. On mismatch the
Companion MUST reject with `1201`,
`data.reason: "data-revision-gap"`, and `data.applied_revision` (its
actual floor; the member is absent when nothing is materialized), and
MUST NOT partially apply. Numbering gaps are legal; application gaps are
impossible — the parent chain, not the numbering, is the continuity.

A **snapshot** needs no parent: the Companion MUST atomically clear every
declared table, apply `ops`, and set its floor to `revision`. A snapshot
is required at first contact, after an epoch change, and is the universal
gap repair. An oversized full re-sync is instead expressed as a sequence
of `replace` deltas — honest at provenance-unit granularity, each
transactional.

`ops` is an ordered array. Each op is a **closed object** — Section 12's
ignore-unknown-member rule is expressly overridden for ops, because
ignoring merge metadata would change merge semantics: an op carrying an
unknown member MUST be rejected with `1201` and
`data.reason: "data-value-invalid"`. The member names `site`, `clock`,
and `cols` on every op, and `merge` on every table definition, are
reserved for a future negotiated merge-discipline capability and MUST NOT
be sent unnegotiated. The op vocabulary:

- `{"op": "put", "table": T, "row": {...}}` — upsert by primary key.
  `row` MUST contain every declared column of `T`.
- `{"op": "del", "table": T, "key": {...}}` — delete by primary key;
  `key` maps exactly the pk columns. A v1 consumer MAY physically delete.
- `{"op": "replace", "table": T, "scope_value": V, "rows": [...]}` —
  atomically delete every row whose declared `scope` column equals `V`
  and insert `rows`, each of whose scope column MUST equal `V`. This is
  the producer's rewrite-all-rows-per-unit pattern as one op: a whole
  provenance unit arrives as a unit-scoped snapshot, so the consumer
  never sees churn as loss.
- `{"op": "truncate", "table": T}` — clear one table.

Before answering `{status: "applied", revision}` the Companion MUST
durably commit every row effect of the changeset and the new revision in
one transaction; answering `applied` for a scheduled volatile write is
not conforming (the Section 14.4 accepted-commitment rule applied to
rows). A changeset whose `revision` is not greater than the floor MUST be
answered `{status: "stale", revision: <floor>}` without re-applying and
regardless of `parent` — the idempotent answer for a redelivered apply
whose result was lost. The floor is the revision `data_state` reports;
before anything is materialized there is no floor, every `revision` is
above it, and a snapshot is the only legal first changeset. Any
per-op failure — unknown table, undeclared column, missing pk, type or
encoding violation, limit breach — rejects the entire changeset with
`1201` and the applicable `data.reason`, leaving every table and the
floor unchanged.

Recovery paths, exhaustively: (1) Companion restart — the next welcome's
`data_state` reports the surviving floor; Emacs resumes with a delta from
it or re-baselines with a snapshot. (2) A lost or unacknowledged delta —
the next delta's `parent` fails, and `data.applied_revision` tells Emacs
where to resume from its journal. (3) Companion-side loss mid-session —
the Companion answers the next changeset with the gap error reflecting
reality; a Companion that cannot wait MAY close the connection, forcing
the welcome report. Emacs is the single writer and therefore the sole
repair initiator; the Companion's only verbs are refuse-and-report.

### 27.4 Write-back

Companion-originated mutations are ordinary Section 14 actions riding the
Section 15 durable queue — the module defines no mutation methods and no
second sync machinery. The optional `mutations` member of `data.schema`
delivers Emacs-authored action descriptors — the Section 14.1 descriptor
shape without `capture_fields` (surface-bound; forbidden here) — durable
alongside the schema, so mutations authored against the projection work
offline. Action names are application-namespaced; the module deliberately
mints no generic row-write verbs — a mutation names an intent the
producer authorized, and Section 14.5's meaningfulness rules apply to it
unchanged.

A descriptor's `dedupe` member here is the enum `row | none` (default
`none`). For `row`, the Companion MUST author the Section 15.2 queue key
as the action name, `:`, the table name, `:`, and the first 32 lowercase
hexadecimal characters of SHA-256 over the [RFC8785] serialization of the
row's primary-key object — deterministic and intent-encoding as Section
15.2 requires. The composed key MUST satisfy Section 4.4's 128-octet
bound; the available action-name budget is therefore reduced by the table
name's length plus 34 octets. Emacs MUST NOT declare a `dedupe: "row"`
descriptor whose action name exceeds that budget for any table the
descriptor may name, and a Companion receiving one MUST reject the
declaration with `1201` and `data.reason: "data-value-invalid"`. A
Companion MUST NOT truncate a composed key to fit — truncation would
discard the primary-key digest and collide distinct rows under Section
15.2's flat key space.

Every mutation occurrence is an `event.action` omitting `surface`,
`revision_seen`, and `dialog_id` (Section 14.4's context-exclusivity),
carrying its source identity in validated `args`; the module REQUIRES
`args.revision_seen` — the last applied data revision at the moment of
the occurrence — and Emacs applies Section 14.5's semantics to it. The
Companion MUST NOT write the materialized projection to reflect a pending
mutation; optimistic presentation is an overlay reconciled when the
round-trip changeset arrives (Section 13.6's draft discipline applied to
rows). Queued mutations replay under Section 15 regardless of the
delivering connection's grants or schema state — the queue is core.

### 27.5 Failure vocabulary

Module failures are `1201 content-invalid` with a `data.reason` string:
`data-undeclared`, `data-schema-mismatch`, `data-revision-gap` (with
`data.applied_revision`), `data-snapshot-invalid`, `data-value-invalid`,
`data-limit`. Wrong
session state is `1204` as everywhere. There is no `data.resync` method
and no Companion→Emacs data method: every recovery need is met by the
welcome report and the gap error (Section 27.3), and a Companion that
cannot wait reconnects.

### 27.6 Limits and exhaustion

The five welcome limits are `max_data_schema_bytes`,
`max_data_tables`, `max_data_columns`, `max_data_changeset_bytes`, and
`max_data_ops` (Section 4.5). Enforcement points: schema limits before
durable acceptance of a declaration; changeset limits before the apply
transaction. `max_data_ops` counts each `replace` row individually —
interpretation cost MUST be bounded independently of byte size (Section
23.5). A Companion advertising `max_data_storage_bytes` names the total
materialized bound its Section 23.5 refusal enforces; staged snapshot
parts count against the same bound. No pending-write-back limit exists: mutations are Section 15
events, already bounded by `max_queued_events` and `max_queued_bytes`; a
second bound would double-count.

The revision stream is a monotonically consumed resource: Emacs MUST
persist its next revision; the stream MUST NOT wrap; the only reclamation
is a schema-epoch change — after an accepted epoch change no changeset
bearing the old epoch's `schema_hash` can be applied, so the stream MAY
restart with the epoch. Within an epoch no reclamation exists and none is
needed: the space is Section 4.2's safe range, and a producer emitting
one revision per millisecond consumes it in roughly 285,000 years. No
tombstones or per-key floors are retained — the module has one totally
ordered stream whose floor is a single durable number reported in the
welcome, unlike Section 13.1's many independent surface streams raced by
cached reconnection. The Companion's retained monotonic record — the
`(schema, revision)` pair — is per-pairing, constant-size, and reclaimed
at pairing revocation. `schema.version` is producer-owned, monotonic per
`id`, MUST NOT wrap; the escape is a new `id`. EventIds and dedupe
windows for write-back are inherited unchanged from Sections 14.4 and
15.2, introducing nothing new.
```

**2. §4.5 — five rows appended to the welcome-limits table:**

```markdown
| `max_data_schema_bytes` | REQUIRED when `ebp.data` is granted; maximum encoded bytes (Section 4.5 JCS sizing) of one complete `data.schema` params object; at least `65536` and no
greater than `max_frame_bytes - 256` |
| `max_data_tables` | REQUIRED when `ebp.data` is granted; declared tables per schema; at least `16` |
| `max_data_columns` | REQUIRED when `ebp.data` is granted; declared columns per table; at least `64` |
| `max_data_changeset_bytes` | REQUIRED when `ebp.data` is granted; maximum bytes of the exact UTF-8 JSON encoding of one complete `data.changeset` params object chosen for transmission; at least `262144` and no greater than `max_frame_bytes - 256` |
| `max_data_snapshot_parts` | REQUIRED when `ebp.data` is granted; parts the Companion accepts in one staged snapshot sequence (Section 27.3); at least `16` |
| `max_data_storage_bytes` | OPTIONAL when `ebp.data` is granted; the total materialized-projection bytes the Companion sustains; a refusal past it is `1201` with `data.reason: "data-limit"` citing this bound |
| `max_data_ops` | REQUIRED when `ebp.data` is granted; ops per changeset, counting each `replace` row individually; at least `8192` |
```

plus, in the reservation paragraph (after the `device` budget sentence):
"When `ebp.data` is supported, the reservation MUST additionally count
the complete `data_state` member at its maximum encoded size (a 128-octet
`id`, maximum-magnitude `version` and `revision`, and the fixed member
names)."

**3. §7.4, first paragraph — the ordered-channel list gains data:**
"…surface mutations, `state.changed`, durable events, **data
changesets,** and editor-stream operations…"

**4. §10.2 — after "`device` MUST be omitted unless `capabilities` or
`triggers` was granted.":** "`data_state` MUST be omitted unless
`ebp.data` was granted." And the welcome-member schema table gains:

```markdown
| `data_state` | object: `{}` when no projection is materialized, else exactly `schema` (object of exactly `id`, `version`, `hash`) and `revision` (non-negative integer) — Section 27.2 |
```

**5. §11 — two rows inserted after `triggers.set`:**

```markdown
| `data.schema` | Emacs | request | R | `ebp.data` | 27 |
| `data.changeset` | Emacs | request | R | `ebp.data` | 27 |
```

**6. §21→§22 junction — one-line pointer (non-normative placement aid):**
"*A further optional module — the Section 27 data projection module — is
specified after the conformance sections; a 2.0.0-final editorial pass
may reorder it beside Sections 19–21.*"

**7. §22.1 — capability table row:** `| \`ebp.data\` | Section 27 data
methods |`

**8. §22.2 class 2 — first sentence gains:** "Editor opens, deltas,
applies, carets, closes, and resynchronizations, **and data schema
declarations and changesets,** MUST NOT be reordered or conflated. A gap
MUST make the stream stale and invoke its resync rule **— for data, the
Section 27.3 parent-chain rejection and its recovery paths**."

**9. §23.5 — appended paragraph:** the materialized projection is
per-pairing durable state the peer sizes: the Companion MUST bound its
total materialized storage and MUST refuse a changeset that would exceed
the bound atomically (`1201`, `data-limit`), never partially applying.
Declared table and column names are peer-supplied names under this
section's interning duty, bounded by `max_data_tables ×
max_data_columns`; the Section 27.1 name grammar is what makes embedding
them in consumer-side SQL identifiers safe, and the `sqlite_`/`ebp_`
refusals are part of that boundary. An accepted schema-epoch change
deletes the projection at the peer's instruction — acceptable because the
projection is rebuildable from the authoritative source, but a Companion
SHOULD rate-limit epoch churn as it would any destructive peer
instruction. The projection MUST be stored with protection no weaker than
the Companion's other per-pairing durable state.

**10. §24.3 — appended:** "An implementation MUST NOT advertise
`ebp.data` while implementing only changeset application without
single-transaction durability, only schema acceptance without drift
refusal, or only the read path without Section 15-queued mutation
admission; the schema declaration, revision state machine, atomic durable
apply, welcome `data_state` report, and write-back admission are one
negotiated unit."

**11. §24.6 — after the optional-module sentence:** "A data-module suite
MUST include at least: schema drift (a declaration a pinned consumer
rejects: `refused`/`dynamic`, no changeset accepted, prior projection
untouched); a delta whose `parent` mismatches the floor
(`data-revision-gap` with `data.applied_revision`, zero partial
application); the Section 27.1.1 boundary values round-tripping exactly
and non-canonical spellings rejected; Companion process death and restart
with the welcome reporting the surviving `(schema, revision)` and a
parent-chained delta applying; a mid-changeset failure leaving revision
and every table unchanged; a snapshot sequence interrupted by process
death or a mid-sequence gap leaving the floor and every table unchanged
with the staging discarded; a redelivered revision answering `stale`
without re-application; and an offline-queued mutation surviving replay
with the projection changing only on the round-trip changeset.
*(Reference implementation: the process-death, replay, and materialized
verification cases land with llm-poc-2 PLAN-refound RF-4b/4c — recorded
here so the deferral is explicit, not silent.)*"

### contract.json changes

`methods` (inserted after `triggers.set`):

```json
"data.schema": {
  "sender": "emacs", "class": "request", "states": ["READY"],
  "capability": "ebp.data",
  "params": {"required": ["schema", "tables"], "optional": ["mutations"]},
  "result": {"required": ["status"], "optional": ["revision", "reason"]},
  "result_status": ["accepted", "refused", "dynamic"],
  "errors": [1201, -32602]
},
"data.changeset": {
  "sender": "emacs", "class": "request", "states": ["READY"],
  "capability": "ebp.data",
  "params": {"required": ["schema_hash", "revision", "ops"],
             "optional": ["snapshot", "parent", "part", "final"]},
  "result": {"required": ["status", "revision"], "optional": []},
  "result_status": ["applied", "stale"],
  "errors": [1201, -32602]
}
```

`capabilities` += `"ebp.data"` (after `"offline.wake"`). `limits.welcome`
+= the five members:

```json
"max_data_schema_bytes": {"requirement": "when ebp.data granted", "min": 65536, "max_expr": "max_frame_bytes - 256"},
"max_data_tables": {"requirement": "when ebp.data granted", "min": 16},
"max_data_columns": {"requirement": "when ebp.data granted", "min": 64},
"max_data_changeset_bytes": {"requirement": "when ebp.data granted", "min": 262144, "max_expr": "max_frame_bytes - 256"},
"max_data_snapshot_parts": {"requirement": "when ebp.data granted", "min": 16},
"max_data_storage_bytes": {"requirement": "optional when ebp.data granted"},
"max_data_ops": {"requirement": "when ebp.data granted", "min": 8192}
```

### goldens/frames.golden (three appended lines, ordinals 40-42)

Emitted verbatim by `gen-rf4a-artifacts.py` (run it bare to print them);
key-sorted compact, real schema hashes, `notes.pos` typed `wide_integer`,
and the mutation descriptor's action named `example.note-retitle` — the
neutral spec repo takes no downstream product's vocabulary into its
goldens (review finding, 2026-08-01).

### goldens/wire — two fixtures + manifest entries (#154's; fixture 25 is #155's)

- `23-data-schema-accept.bin` — positive conversation: the `data.schema`
  request (golden 40's body) then `{"id":"ds1","jsonrpc":"2.0","result":{"revision":6,"status":"accepted"}}`.
  Rule: "27.2: an exact-identity re-declaration resumes — the result
  carries the Companion's durably applied floor."
- `24-data-schema-drift.bin` — positive conversation: a coherent v2
  declaration (`version: 2`, a new nullable `tags` text column on
  `notes`, hash `…cc2` — the content changed, so the hash changed; a
  hash-only change with identical content would be malformed, not drift,
  per 27.2's verification sentence) then
  `{"id":"ds2","jsonrpc":"2.0","result":{"reason":"pinned consumer requires vault@1","status":"refused"}}`.
  Rule: "27.2: drift is a negotiated result, not an error; the prior
  projection is retained untouched." **(The RF-4 gate's schema-drift
  golden.)**

### validate.py — the `check_params` data arm (the d48075d class; tested reference below)

The exact arm, dry-run-verified against the package (bites on `007`,
`+5`, AND `-0` — the first regex draft admitted `-0` and the second
missed `+5`; both corrected before this draft was routed). The
wide-integer check is a golden-scoped heuristic: any signed-digit-like
string value in a row is held to the §27.1.1 canonical grammar — exact
column-typing would need cross-golden schema resolution, and goldens are
authored, so digit-lookalike `text` values are simply not used in them.

```python
DATA_NAME_RE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
DATA_TYPES = {"integer", "wide_integer", "real", "text", "blob"}
DATA_WIDE_RE = re.compile(r"^(0|-?[1-9][0-9]*)$")
DATA_OP_SHAPES = {"put": {"op", "table", "row"}, "del": {"op", "table", "key"},
                  "replace": {"op", "table", "scope_value", "rows"},
                  "truncate": {"op", "table"}}

def check_data_params(method, params, path):
    # 27.1/27.2/27.3 (#154) + 27.1.1 canonical wide integers (#155): the
    # closed vocabulary the contract's key lists cannot express.
    if method == "data.schema":
        for tname, tdef in params.get("tables", {}).items():
            if not DATA_NAME_RE.match(tname) or tname.startswith(("sqlite_", "ebp_")):
                problem(f"{path}: table name `{tname}` violates 27.1")
            cols = tdef.get("columns", {})
            for cname, cdef in cols.items():
                if not DATA_NAME_RE.match(cname) or cname.startswith(("sqlite_", "ebp_")):
                    problem(f"{path}: column name `{cname}` violates 27.1")
                if cdef.get("type") not in DATA_TYPES:
                    problem(f"{path}: column `{cname}` has unknown type")
            pk = tdef.get("pk", [])
            if not pk or any(c not in cols for c in pk):
                problem(f"{path}: table `{tname}` pk invalid")
        return
    snapshot = params.get("snapshot", False)
    if not snapshot and ("part" in params or "final" in params):
        problem(f"{path}: part/final without snapshot")
    if snapshot and "parent" in params:
        problem(f"{path}: snapshot changeset carries parent")
    if not snapshot and "parent" not in params:
        problem(f"{path}: delta changeset lacks parent")
    for op in params.get("ops", []):
        kind = op.get("op")
        if kind not in DATA_OP_SHAPES:
            problem(f"{path}: unknown op `{kind}`")
            continue
        if set(op) != DATA_OP_SHAPES[kind]:
            problem(f"{path}: op `{kind}` is not the closed 27.3 shape")
        for row in ([op.get("row")] if "row" in op else []) \
                + op.get("rows", []) + ([op.get("key")] if "key" in op else []):
            for value in (row or {}).values():
                if isinstance(value, str) and re.fullmatch(r"[+-]?[0-9]+", value):
                    if not DATA_WIDE_RE.match(value) or not (
                            -(2**63) <= int(value) <= 2**63 - 1):
                        problem(f"{path}: non-canonical wide integer `{value}`")
```

hooked as the first statement of `check_params`:
`if method in ("data.schema", "data.changeset"): check_data_params(method, params, path)`.
Enforcement of the new vocabulary is thereby validate.py, named in the
SPEC-CHANGES row.

---

## Review status (2026-08-01) — verification COMPLETE

Four independent reviewers produced 35 findings; a triage pass merged them
to 24 unique; every one now carries at least one adversarial verdict from
a Sonnet and/or Opus refuter instructed to default to refutation. **Nine
findings were upheld and all nine are fixed in the text above; fifteen
were refuted with reasons.** Nothing is left unverified.

*Disposition correction (2026-08-02):* the "verification COMPLETE"
summary below itself dropped two pass-one-confirmed P2s — the §23.5
storage bound being enforceable but unreported, and the JCS-ceiling
clause on `max_data_changeset_bytes` outlawing ordinary elisp float
encodings. Both are now fixed (the optional `max_data_storage_bytes`
welcome member names the bound; the JCS-ceiling clause is struck — a
sender counts its actual transmitted bytes, and inflating its own
encoding only hurts itself). Recorded here because a review whose
summary loses findings is the same defect class as a harness that
mis-files them.

*Process note, recorded because it nearly shipped a false record:* the
first verification pass was cut short by a usage limit, and its harness
counted a finding with ZERO returned verdicts as "refuted" — silently
converting unverified into killed. Fourteen findings were mis-filed that
way. The harness now distinguishes UNVERIFIED from REFUTED, and every
mis-filed finding was re-verified rather than trusted.

**Upheld and fixed** — the redelivered-delta contradiction (stale now
takes precedence, the two rules scoped to disjoint inputs); the false
#155 hold-severability claim (now discloses the artifact re-authoring a
hold forces); decorative placeholder schema hashes in a package whose own
§27.2 computes them (now real digests from `tools/gen-rf4a-artifacts.py`,
which also removes the draft-vs-bytes drift class); the hash input's
treatment of an absent `mutations` member; the floor before first apply;
`jetpacs.` out of the goldens; **§27.4's dedupe key, which could exceed
§4.4's 128-octet bound — the worst legal composition is 226 octets, and
an implementer who clamped rather than rejected would truncate away the
primary-key digest and silently dedupe-collide distinct rows under
§15.2's flat key space** (the key is now bounded, over-long descriptors
are rejected, and truncation is forbidden outright); §27.3's
"strictly increasing" contradicting §27.6's epoch restart (monotonicity
is now epoch-scoped, the restart named); `max_data_schema_bytes` lacking
the frame-fitting ceiling §27's preamble promised; a declaration bearing
a **new** `id` having no defined transition though §27.6 names it the
version-exhaustion escape (now one projection per pairing, any identity
change is an epoch change); #155's row not recording the deferral its
prose claimed; and #154's row citing `I7`, which resolves nowhere inside
`ebp` (now §3 item 3, the in-repo statement of the same invariant).

**Refuted, with reasons on file** — §27.4's `args.revision_seen`
"shadowing" (the two members never coexist and the draft assigns them the
same §14.5 semantics; but see the residue below); the §12 closed-object
override's scope; §22.2's class-2 graft; §7.4-vs-§22.2 ordering and the
concurrent-request permission; surface-presented mutations colliding with
§14.4; "pinned typed consumer" being unconstructible; O2's headroom
figure; O9's #149 cross-reference; the #153 splice-point collision; and
five smaller items.

**Residue for G1, surfaced by a refuting verdict rather than a finding:**
§14.3's injection table is closed ("Hooks without an entry in this table
inject nothing") and lists no `revision_seen`, yet §27.4 requires the
Companion to inject one into `args`. That is an unamended cross-reference,
distinct from the shadowing claim that was refuted, and it needs either a
§14.3 edit or an express local override in §27.4. It is the one known
open item in this package.


## #155 — the canonical wide-integer string encoding

### SPEC-CHANGES row (ready to paste)

> | 155 | 2026-08-01 | §27.1.1 (within #154's new section; cross-ref §4.2; contract) | **64-bit integers cross the 2^53 wire as canonical strings, schema-typed.** §4.2 hard-caps JSON numbers at ±(2^53−1) with a Parse Error, and rightly — but SQLite `INTEGER` is 64-bit and a projection of real rows carries rowids and values beyond the safe range (audit P2-7 named this before the first changeset could flow). A `wide_integer` column therefore always carries a JSON string in one canonical decimal spelling (optional `-`, no leading zeros, no `+`, no `-0`, range exactly [−2^63, 2^63−1]); a JSON number in a `wide_integer` column is rejected, so no value has two encodings, and a receiver MUST NOT widen an out-of-range JSON number anywhere (§4.2 unchanged). Schema-typed strings, not a self-describing wrapper: changesets are never schema-less (§27.2's declaration gate), so a `{"$i64":…}` envelope would be redundant nesting; a flat typed string keeps rows flat and gives the canonical grammar one place to bind. Non-canonical spellings are `1201 data-value-invalid`. Engine-side rejection and its §24.6 non-canonical case are deferred to the implementation rungs (llm-poc-2 PLAN-refound RF-4b/4c) — in-repo enforcement is validate.py's canonical-grammar check over golden values only — recorded here so the deferral is explicit, not silent. Additive; the negative raw-number case is already pinned by wire fixture 22. | goldens/wire += `25-data-wide-int` (+ manifest); frames.golden line 41 carries the +2^63−1 boundary; validate.py `check_params` canonical-grammar check over `wide_integer` golden values | |

### Artifacts

- `25-data-wide-int.bin` — positive conversation: a `data.changeset`
  whose `put` rows carry `"9223372036854775807"` and
  `"-9223372036854775808"`, then `{"id":"dw1","jsonrpc":"2.0","result":{"revision":8,"status":"applied"}}`.
  Rule: "27.1.1: the boundary values survive both decoders at all
  chunkings as strings — the canonical encoding round-trips." **(The RF-4
  gate's wide-int golden.)** The raw-number negative is already
  `22-integer-out-of-range.bin`; canonical-form validity is checked by
  the validate.py arm (a manifest `expect_error` cannot express it — the
  vocabulary is decoder outcomes only), with engine-side `1201` rejection
  deferred to RF-4b/4c and recorded in the row.
