# Org renderer performance — 2026-08-21–22

## Question

Why did Org visibility cycling in POC3 feel dramatically worse than POC1/2,
how much do viewport virtualization and renderer stabilization recover, and
what changes when Emacs publishes a bounded set of authoritative presentation
variants that Android can select locally?

The victim is `com.calebc42.ebp.companion`; the matched workload is 15 taps of
the three-state visibility control on the 611-line `orgro-manual.org`, after six
warm-up taps, in a release APK compiled with `speed-profile`.

## Traces

- `/tmp/jetpacs-profiler/poc3-stable-org-keys.perfetto-trace`: stable row keys,
  before bounded lazy chunks.
- `/tmp/jetpacs-profiler/poc3-release-chunked-org.perfetto-trace`: bounded
  eight-row presentation chunks.
- `/tmp/jetpacs-profiler/poc3-release-rich-runs.perfetto-trace`: chunks plus
  bounded compatible rich-text runs (evaluated, then rolled back).
- `/tmp/jetpacs-profiler/poc3-release-stable-json.perfetto-trace`: rich runs
  plus structural Compose stability for the immutable JSON AST (experimental).
- `/tmp/jetpacs-profiler/poc3-release-final-safe.perfetto-trace`: the deployed
  release: bounded chunks and stable JSON, with every authored rich-text row
  retained as its own semantics node.
- `/tmp/jetpacs-profiler/poc3-release-retained-variants-final.perfetto-trace`:
  the final deployed retained-variant release, including the process-saveable
  keyed viewport-anchor fix. This is the authoritative 2026-08-22 comparison:
  15 taps after six warm-ups, with exactly 15 input, switch, and layout spans.

## Retained Emacs-authored variants — 2026-08-22

Evidence tags retain the meanings below.

- `[SOURCE]` Emacs now authors the canonical `overview`, `contents`, and `all`
  Org presentations together inside one bounded `variant_host`. Android's
  `variant.switch` selects an opaque authored value; it does not parse Org or
  implement folding semantics. Profiles that do not advertise both new names
  continue to receive the previous remote `surface.update` path.
- `[SQL]` The final trace contains exactly 15 input DOWN/UP pairs, 15
  `EBP variant.switch` spans, and 15 `EBP variant layout` spans, paired
  one-to-one before the following tap. There was no ftrace or TraceBuffer loss;
  three FrameTimeline parser errors were informational and every response token
  used here resolved to an Actual FrameTimeline row.
- `[SQL]` DOWN-to-response-frame start fell from 222.501 ms to 18.161 ms on
  average, a **91.8% reduction**. The median is 16.360 ms, nearest-rank p90 is
  24.446 ms, and the range is 10.052–41.897 ms. The named local switch itself
  averages 7.113 ms (median 6.998 ms, p90 8.157 ms).
- `[SQL]` A conservative DOWN-to-definitely-presented post-state buffer averages
  106.771 ms (median 107.065 ms, p90 125.561 ms). This rule uses the response
  buffer when presented and otherwise the first later non-dropped buffer whose
  main-thread frame starts after the response frame completes. `[INFERRED]`
  Pixel provenance is not directly captured, so this is deliberately
  conservative rather than an exact photon-latency claim.
- `[SQL]` The final response `doFrame` averages 76.650 ms, with a 77.959 ms
  median and 91.830 ms p90. The average is 7.7% above the earlier 71.152 ms
  full-snapshot baseline and the median is 17.1% above 66.587 ms, while p90
  remains 5.8% lower than 97.498 ms.
  FrameTimeline actual duration averages 84.426 ms; nine response buffers are
  dropped, six are late-presented, and all 15 finish late.
- `[SQL]` The remaining frame is selected-branch layout, not the removed wire
  round trip: `EBP variant layout` averages 49.502 ms and GC-clean
  measure/layout averages 56.740 ms. Only one response overlaps GC; the other
  14 still average 75.928 ms, so GC does not explain the aggregate.
- `[SQL]` Relative to the pre-retained GC-clean frames, Recomposer work falls
  from 24.746 to 11.878 ms (52.0%), while measure/layout rises from 40.299 to
  56.740 ms (40.8%). A later steady-state diagnostic still averages 72.514 ms
  per response frame and 45.759 ms in `EBP variant layout`, so warm-up recovers
  only part of the measured layout cost.
- `[SQL] [GAP]` Do not attribute the final-versus-prior-retained frame delta
  solely to the viewport-anchor patch: this fresh-process capture contains 287
  JIT compilations, every response overlaps JIT, and sampled running frequency
  is 8.5% lower. The trace has no thermal counters. The device lifecycle test
  establishes the anchor fix's correctness; a controlled profile/frequency A/B
  would be required to assign its isolated frame cost.
- `[SQL] [SOURCE]` Focused analysis found exactly four
  `AndroidOwner:measureAndLayout` passes per switch in both retained traces,
  with identical branch-dependent recomposition and text-measure counts. The
  hoisted registry performs bounded keyed lookup/recording, and no extra
  scroll/measure pass is visible; the trace therefore does not support blaming
  the anchor fix for the delta. The worst frame is 98.95% main-thread Running
  and spends 72.497 of 93.036 ms in measure/layout.
- `[SQL]` System vitals are nominal: aggregate CPU busy is 21.1%, LMK/OOM kills
  are zero, and SurfaceFlinger/GPU report no missed frames. Heap and RSS growth
  are also lower than in the preceding retained trace, so system pressure is
  not the primary explanation.
- `[SQL]` No Companion `ebp-conn` inbound surface-publication work occurs during
  the 15 switches. Emacs still receives the small `state.changed` notification
  and applies the real Org visibility command, but Android has already selected
  the branch and does not wait for that work. `[SOURCE] [INFERRED]`
- `[SOURCE]` Inactive branches are deactivated, not merely hidden: their effects,
  resources, callbacks, focus, and semantics are absent, while explicit
  saveable presentation state is restored by full presentation identity.
  Acceptance-time incarnation tracking also prevents a conflated
  remove/re-add from resurrecting stale state.
- `[SOURCE]` A device lifecycle test exposed and fixed a two-stage lazy-list
  hand-off that JVM tests had missed. After the fix, the all-visible branch was
  scrolled until line 52 was the first visible row, cycled through both other
  branches, and returned to the exact line-52 viewport. The keyed child/pixel
  anchor now lives above `ReusableContentHost`, remains process-saveable, and
  is retired with its presentation owner.

## Findings

Evidence tags: `[SQL]` is measured in Perfetto, `[SOURCE]` is established from
the implementation/compiler report, `[INFERRED]` is a conclusion from those
facts, and `[GAP]` names missing attribution.

- `[SQL]` In the pre-retained, semantics-preserving release, response `doFrame`
  fell from 129.292 ms after stable row keys to 71.152 ms: a 45.0% reduction.
  Bounded eight-row lazy chunks produced essentially all of that measured win;
  the chunk-only trace averaged 71.336 ms.
- `[SQL]` That pre-retained release has a 66.587 ms response-frame median and
  97.498 ms nearest-rank p90. FrameTimeline actual duration averages 81.018 ms;
  all 15 responses finish late and 13 are classified as dropped frames.
- `[SQL]` Excluding the two response frames that overlap GC leaves 13 samples
  averaging 71.062 ms (`Recomposer` 24.746 ms, measure/layout 40.299 ms).
  The remaining renderer cost is not a GC artifact.
- `[SQL]` A rich-row-collapse experiment plus stable JSON reached 53.356 ms,
  but the collapse supplied no reliable frame win on its own and replaced
  several authored `rich_text` semantics nodes with one `Text`. It was rolled
  back because TalkBack paragraph grouping, pauses, and row navigation could
  change without an explicit protocol guarantee.
- `[SOURCE]` The Compose release compiler report now marks `JsonObject` and
  `JsonArray` parameters stable for `RenderNode`, `RenderScaffold`,
  `RenderLazyColumn`, and the rest of the renderer. Before the stability file,
  those same parameters were unstable and strong skipping compared each fresh
  snapshot subtree by identity.
- `[SQL]` Stable JSON was effectively neutral in the final no-collapse A/B
  aggregate (71.152 versus 71.336 ms, within cross-run noise). It remains a
  safe foundation for structurally unchanged subtrees, but is not credited
  with an independent latency reduction here.
- `[INFERRED]` This is a software critical path, not unavoidable application
  size or device pressure. The fixed work is whole-snapshot invalidation; the
  remaining renderer floor is first layout of presentation that actually
  appears or changes.
- `[SQL]` Pre-retained input-to-response-frame latency averages 222.501 ms, but
  `[GAP]` cross-run latency varies with uninstrumented Emacs and receiver work,
  so it is not attributed to the renderer A/B. Add named spans before claiming
  a sender/receiver-stage percentage.

## Final validated SQL

For the retained trace, each ordinal input is paired one-to-one with its named
`EBP variant.switch` and `EBP variant layout`; the response is the unique
main-thread, depth-zero `Choreographer#doFrame` containing that layout span.
The historical pre-retained renderer A/B had no such span, so its validated
response selector pairs each Companion input `DOWN` with the first frame longer
than 20 ms before the next input:

```sql
INCLUDE PERFETTO MODULE slices.with_context;

WITH downs AS (
  SELECT
    s.ts AS input_ts,
    ROW_NUMBER() OVER (ORDER BY s.ts) AS rn,
    LEAD(s.ts, 1, trace_end()) OVER (ORDER BY s.ts) AS next_input_ts
  FROM thread_or_process_slice AS s
  WHERE s.name GLOB
    'publishMotionEvent(inputChannel=*com.calebc42.ebp.companion*action=DOWN)'
),
frames AS (
  SELECT s.id, s.ts, s.dur
  FROM thread_or_process_slice AS s
  WHERE s.process_name = 'com.calebc42.ebp.companion'
    AND s.depth = 0
    AND s.name GLOB 'Choreographer#doFrame*'
    AND s.dur > 20000000
),
pairs AS (
  SELECT
    d.rn,
    d.input_ts,
    (SELECT MIN(f.ts)
     FROM frames AS f
     WHERE f.ts > d.input_ts AND f.ts < d.next_input_ts) AS frame_ts
  FROM downs AS d
)
SELECT
  p.rn,
  ROUND((f.ts - p.input_ts) / 1e6, 3) AS latency_ms,
  ROUND(f.dur / 1e6, 3) AS doframe_ms
FROM pairs AS p
JOIN frames AS f ON f.ts = p.frame_ts
ORDER BY p.rn;
```

GC-clean frame comparison uses materialized, non-overlapping response and GC
intervals:

```sql
INCLUDE PERFETTO MODULE slices.with_context;

CREATE OR REPLACE PERFETTO TABLE input_downs AS
SELECT
  s.ts AS input_ts,
  ROW_NUMBER() OVER (ORDER BY s.ts) AS rn,
  LEAD(s.ts, 1, trace_end()) OVER (ORDER BY s.ts) AS next_input_ts
FROM thread_or_process_slice AS s
WHERE s.name GLOB
  'publishMotionEvent(inputChannel=*com.calebc42.ebp.companion*action=DOWN)';

CREATE OR REPLACE PERFETTO TABLE response_frames AS
WITH candidate_frames AS (
  SELECT s.id AS frame_id, s.ts, s.dur
  FROM thread_or_process_slice AS s
  WHERE s.process_name = 'com.calebc42.ebp.companion'
    AND s.depth = 0
    AND s.name GLOB 'Choreographer#doFrame*'
    AND s.dur > 20000000
)
SELECT d.rn, f.frame_id, f.ts, f.dur
FROM input_downs AS d
JOIN candidate_frames AS f ON f.ts = (
  SELECT MIN(f2.ts)
  FROM candidate_frames AS f2
  WHERE f2.ts > d.input_ts AND f2.ts < d.next_input_ts
);

CREATE OR REPLACE PERFETTO TABLE companion_gcs AS
SELECT s.id AS gc_id, s.ts, s.dur
FROM thread_or_process_slice AS s
WHERE s.process_name = 'com.calebc42.ebp.companion'
  AND s.thread_name = 'HeapTaskDaemon'
  AND s.name GLOB '*GC'
  AND s.depth = 0
  AND s.dur > 0;

DROP TABLE IF EXISTS response_frame_gc;
CREATE VIRTUAL TABLE response_frame_gc USING
  SPAN_JOIN(response_frames, companion_gcs);

SELECT
  COUNT(*) AS clean_n,
  ROUND(AVG(f.dur) / 1e6, 3) AS clean_frame_avg_ms
FROM response_frames AS f
WHERE f.rn NOT IN (
  SELECT DISTINCT fg.rn FROM response_frame_gc AS fg
);
```

## Remaining work

1. Reduce first layout of the newly selected retained branch without discarding
   viewport virtualization or reactivating hidden effects. The final trace is
   CPU-bound in selected-branch measure/layout; the Emacs/wire critical path is
   no longer the interaction gate.
2. Investigate the durable `variant.switch` commit only after layout. It
   averages 7.113 ms and is intentionally on the correctness path, but remains
   much smaller than the selected branch's frame.
3. Add a Compose instrumentation test for real gesture → deactivate → reactivate
   viewport retention, selected-only semantics/effects, and rapid accepted
   remove/re-add. JVM tests cover the pure identity, anchor, persistence, and
   retirement models, but cannot execute the actual lazy-layout lifecycle.
4. Use a dedicated allocation/method capture to distinguish collectible
   per-switch churn and JIT warm-up from retained heap growth. Do not infer that
   split from the system trace alone.
