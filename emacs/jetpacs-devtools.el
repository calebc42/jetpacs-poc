;;; jetpacs-devtools.el --- Push-loop profiler + failure flight recorder -*- lexical-binding: t; -*-

;; SPDX-License-Identifier: GPL-3.0-or-later
;; Package-Requires: ((emacs "30.1"))

;;; Commentary:

;; The measurement layer, ported from poc-v1 `jetpacs-devtools.el' and
;; grown a flight recorder.  Two halves, separately switched:
;;
;; PROFILER (`jetpacs-devtools-profile', OFF by default) — per-surface
;; builder wall clock, outbound push counts and serialized sizes, and
;; the push-storm tripwire for the builder-that-retriggers-itself
;; class of bug.  Timings, counts, and sizes are metadata, so SPEC
;; 23.3 does not constrain them — but sizing is not free: each push is
;; serialized once more to measure it (the same compact representation
;; as GATE 5).  It is therefore an explicit diagnostic, not daily-driver work.
;;
;; FLIGHT RECORDER (`jetpacs-devtools-recording', OFF by default) —
;; the home the scrubbed error path never had.  SPEC 23.3 makes every
;; loud channel print only the error SYMBOL (`jetpacs-error-label'):
;; the app card, *Messages*, the wire.  The morning this module was
;; born, that policy reduced a device-only home failure to the string
;; "error" with nowhere to look.  The recorder listens on the shell's
;; `jetpacs-shell-builder-error-functions' seam, which fires from
;; HANDLER-BIND context — the stack is still standing — so it can keep
;; what the label drops: the full condition, a real backtrace, and the
;; failing screen's identity, as inspectable Lisp data.  The last spec
;; each surface built rides the same setting: a spec EMBEDS payload
;; (the clip view renders the kill ring, buffer drills render buffer
;; text), so retaining one is payload capture, not metadata — it is
;; kept only while the recorder is on, and dropped with the records
;; when it turns off.
;;
;; The recorder is §23.3's "explicit developer setting" made literal:
;; detailed payload capture is legal ONLY behind such a setting, and
;; what is kept "MUST be bounded in size and lifetime" — hence off by
;; default, a hard entry cap (`jetpacs-devtools-record-limit'), and a
;; TTL (`jetpacs-devtools-record-ttl').  Nothing recorded here ever
;; crosses the wire or reaches a log; the report renders into a local
;; buffer on demand.  Enabling the recorder while a privacy-sensitive
;; trigger source (SPEC 21.4) is live is the developer's own call —
;; the setting exists so that call is explicit.
;;
;; INSPECTOR (`jetpacs-devtools-inspect', no setting at all) — the
;; third half, and the only one that answers "what is this screen
;; MADE of".  A Jetpacs screen is a Lisp datum: the builder returns a
;; spec, the spec is a plist of plists and vectors, and the device is
;; a rendering of it.  The inspector closes that loop for a human —
;; build the spec fresh, pretty-print it into a buffer, and show the
;; buffer through the Tier-0 renderer so the data is readable ON THE
;; PHONE, then hand it straight back with `jetpacs-shell-push'
;; `:spec'.  Inspect, edit the sexp, push it: the whole round trip and
;; not one line of new machinery on the return leg.
;;
;; It is CAPTURE ON DEMAND, and that is a deliberate refusal of the
;; retention above.  `jetpacs-devtools--specs' already holds a spec
;; per surface — but only while the recorder is on, which is a
;; developer setting that is OFF by default and may be off right now,
;; and what it holds is the spec of some PAST build.  Reading it would
;; make the inspector answer "nothing" on a healthy session and answer
;; STALE on a recording one.  So the inspector builds; the retention
;; is the flight recorder's business and stays that way.
;;
;; EVENTS (always on, metadata only; payload under the recorder) — the
;; fourth half, json-render's devtools panel brought to the Emacs side
;; of the wire (W8 keeps devtools off the wire itself).  One bounded
;; ring of typed entries: every push, `surface.data' patch and remove
;; with its result; every dispatched action and `state.changed'; and,
;; under `jetpacs-devtools-stream-log', every frame with its method and
;; byte count — the measurement the #194–#198 amendments took with an
;; ad-hoc `process-send-string' advice, made a toggle.  The inspector
;; grew the other half of the data path: the document a surface last
;; sent and the materialized tree the Companion presents, and a buffer
;; whose edited document goes back as a `surface.data' patch.
;;
;; Zero wire cost throughout: this module observes seams and advised
;; functions and never sends anything itself.  The inspector sends
;; exactly what any drill-in sends — a buffer — and the data patch is
;; the ordinary `jetpacs-shell-data-patch'.

;;; Code:

(require 'cl-lib)
(require 'backtrace)
(require 'pp)
(require 'jetpacs-shell)
(require 'jetpacs-data)
(require 'jetpacs-navigate)

(defgroup jetpacs-devtools nil
  "Instrumentation for the Jetpacs push loop."
  :group 'jetpacs)

(defcustom jetpacs-devtools-profile nil
  "When non-nil, record builder timings, push counts, and push sizes.
Metadata only — no payload, so SPEC 23.3 leaves it unconstrained.
This diagnostic is deliberately off by default: measuring the push size
serializes the complete SurfaceSpec an additional time and can materially
increase allocation and GC work on large Org or Files views."
  :type 'boolean :group 'jetpacs-devtools)

(defcustom jetpacs-devtools-recording nil
  "When non-nil, keep full detail for builder and gate failures.
This is the SPEC 23.3 \"explicit developer setting\": while enabled,
the flight recorder retains each failure's condition object,
`error-message-string' (which may embed the offending datum), a
backtrace, the failing surface/screen, and each surface's last built
spec — locally, in bounded storage, never on the wire and never in a
log.  OFF by default, as 23.3 requires.  Turning it off — by any
path: `jetpacs-devtools-toggle-recording', the device Settings
screen, or `setopt' — clears everything it retained; the :set below
is what makes the Settings path keep that promise."
  :type 'boolean :group 'jetpacs-devtools
  :set (lambda (sym val)
         (set-default sym val)
         (unless val
           (when (boundp 'jetpacs-devtools--records)
             (setq jetpacs-devtools--records nil))
           (when (boundp 'jetpacs-devtools--specs)
             (clrhash jetpacs-devtools--specs))
           (when (and (boundp 'jetpacs-devtools--events)
                      (fboundp 'jetpacs-devtools--event-without-detail))
             (setq jetpacs-devtools--events
                   (mapcar #'jetpacs-devtools--event-without-detail
                           jetpacs-devtools--events))))))

(defcustom jetpacs-devtools-event-limit 500
  "Most events the ring keeps; older entries fall off (SPEC 23.3 size bound).
Events are metadata — kind, surface, method, revision, byte count, status,
timing — so the ring is always on; payload members (`:detail') ride
`jetpacs-devtools-recording' and are dropped when it turns off."
  :type 'natnum :group 'jetpacs-devtools)

(defcustom jetpacs-devtools-stream-log nil
  "When non-nil, record every wire frame's method and byte count.
A one-variable test on `jsonrpc-event-hook' while off.  Outbound frames
measure their own JSON; inbound frames take the body octets
`ebp--receive-strict' decoded, paired in order.  Metadata only: no body is
kept, so SPEC 23.3 leaves it unconstrained — the cost is a hook call per
frame, which is why it is a toggle."
  :type 'boolean :group 'jetpacs-devtools)

(defcustom jetpacs-devtools-record-limit 32
  "Most failure records kept; older entries fall off (SPEC 23.3 size bound)."
  :type 'natnum :group 'jetpacs-devtools)

(defcustom jetpacs-devtools-record-ttl 3600
  "Seconds a failure record survives (SPEC 23.3 lifetime bound).
Old entries are dropped whenever a record lands or the report renders."
  :type 'natnum :group 'jetpacs-devtools)

(defcustom jetpacs-devtools-storm-threshold 8
  "`surface.update' pushes within `jetpacs-devtools-storm-window' seconds
that trigger the push-storm warning.  A settled app pushes on user
action and data change; a builder that re-triggers every build pushes
continuously — the storm warning is the tripwire for that class of
bug.  The push history sizes itself to this threshold, so any value
can trip."
  :type 'natnum :group 'jetpacs-devtools)

(defcustom jetpacs-devtools-storm-window 10
  "Seconds of history the push-storm check considers."
  :type 'natnum :group 'jetpacs-devtools)

(defconst jetpacs-devtools--backtrace-max 12000
  "Octets of backtrace kept per record — part of the 23.3 size bound.")

(defconst jetpacs-devtools-inspect-max 65536
  "Octets of pretty-printed spec `jetpacs-devtools-inspect-buffer' shows.
A DEFENSIVE cap, not a wire bound — nothing here crosses the wire; the
Tier-0 renderer pages the buffer onto the device like any other, and
budgets it there.  What this bounds is the local buffer: a spec is
whatever a builder returned, a builder is user code, and an unbounded
`pp' of a pathological one is a wedged Emacs on a phone.  A cut is
ANNOUNCED in the buffer, never silent, and the full datum is always one
`jetpacs-devtools-capture-spec' call away in the REPL.")

;; --- State ------------------------------------------------------------------

(defvar jetpacs-devtools--builds (make-hash-table :test 'equal)
  "Surface -> plist (:last-ms N :max-ms N :count N :at TIME).")

(defvar jetpacs-devtools--specs (make-hash-table :test 'equal)
  "Surface -> the spec its builder last produced (a reference, not a copy).
Payload, not metadata: filled only while `jetpacs-devtools-recording'
is on, cleared when it turns off.")

(defvar jetpacs-devtools--pushes (make-hash-table :test 'equal)
  "Surface -> plist (:last-bytes N-or-nil :count N :at TIME).")

(defvar jetpacs-devtools--push-times nil
  "Recent push times (floats), newest first.
Capped at (max 64 `jetpacs-devtools-storm-threshold') entries, so the
storm check always has enough history to reach its threshold.")

(defvar jetpacs-devtools--storm-warned-at 0
  "Last storm warning time, rate-limiting to one per window.")

(defvar jetpacs-devtools--taps (make-hash-table :test 'equal)
  "Surface -> plist (:last-ms N :max-ms N :count N :prep-ms N :at TIME).
The tap→push tally: how long the device waited between its action's
dispatch and the push or patch that answered it, and (`:prep-ms') how
much of that was `jetpacs-shell-push' / `jetpacs-shell-data-patch'
itself — builders, gates and the size measurement — before the send.
Metadata; rides `jetpacs-devtools-profile' like the push tally.")

(defvar jetpacs-devtools--push-entered-at nil
  "Float time at which the outermost push or patch entry began, or nil.
Bound by `jetpacs-devtools--time-push' around `jetpacs-shell-push',
`jetpacs-shell-data-patch' and `jetpacs-shell-data-sync'; the send
observers read it to stamp the event's `:prep-ms'.")

(defun jetpacs-devtools--time-push (orig &rest args)
  "Around a push or patch entry: remember when the outermost one began.
ORIG runs with ARGS unchanged; a nested entry (sync → patch) keeps the
outer time, so `:prep-ms' always measures from the first entry."
  (let ((jetpacs-devtools--push-entered-at
         (or jetpacs-devtools--push-entered-at (float-time))))
    (apply orig args)))

(defvar jetpacs-devtools--events)       ; the ring, defined below

(defun jetpacs-devtools--tap-ms (surface now)
  "Milliseconds from the device action this send answers to NOW, or nil.
The action is the newest `action' event for SURFACE (a surfaceless
event — a reminder, shortcut or dialog — counts for any surface) that
no push or patch of SURFACE has answered yet.  A send with no such
action — a timer refresh, a reconnect drain — carries no tap time."
  (let ((answered nil) (found nil))
    (cl-loop for e in jetpacs-devtools--events
             for kind = (plist-get e :kind)
             for s = (plist-get e :surface)
             until found
             do (cond
                 ((and (memq kind '(push patch)) (equal s surface))
                  (setq answered t))
                 ((and (eq kind 'action) (not answered)
                       (or (null s) (equal s surface)))
                  (setq found (* 1000 (- now (plist-get e :at)))))))
    found))

(defun jetpacs-devtools--note-tap (surface tap-ms prep-ms)
  "Tally TAP-MS and PREP-MS for SURFACE when the profiler is on."
  (when (and jetpacs-devtools-profile (or tap-ms prep-ms))
    (ignore-errors
      (let ((rec (gethash surface jetpacs-devtools--taps)))
        (puthash surface
                 (list :last-ms tap-ms
                       :max-ms (max (or tap-ms 0.0)
                                    (or (plist-get rec :max-ms) 0.0))
                       :count (1+ (or (plist-get rec :count) 0))
                       :prep-ms prep-ms
                       :at (current-time))
                 jetpacs-devtools--taps)))))

(defun jetpacs-devtools--send-timing (surface)
  "The (TAP-MS . PREP-MS) a send to SURFACE carries, tallied; either may be nil."
  (let* ((now (float-time))
         (tap (ignore-errors (jetpacs-devtools--tap-ms surface now)))
         (prep (and jetpacs-devtools--push-entered-at
                    (* 1000 (- now jetpacs-devtools--push-entered-at)))))
    (jetpacs-devtools--note-tap surface tap prep)
    (cons tap prep)))

(defvar jetpacs-devtools--records nil
  "Failure records, newest first, bounded by limit and TTL.
Each is a plist (:at FLOAT :surface S :screen ID-or-nil :phase SYM
:symbol SYM :message STR :backtrace STR).")

;; --- The flight recorder (the `jetpacs-shell-builder-error-functions' seam) --

(defun jetpacs-devtools--prune-records (&optional now)
  "Enforce the 23.3 bounds: entry cap and TTL, as of NOW."
  (let ((cutoff (- (or now (float-time)) jetpacs-devtools-record-ttl)))
    (setq jetpacs-devtools--records
          (seq-take (seq-filter (lambda (r) (> (plist-get r :at) cutoff))
                                jetpacs-devtools--records)
                    jetpacs-devtools-record-limit))))

(defun jetpacs-devtools--record-failure (context err)
  "Keep ERR's full story for CONTEXT while the stack still stands.
Installed on `jetpacs-shell-builder-error-functions', so this runs
from HANDLER-BIND context — `backtrace-get-frames' here sees the
signal path, not the catch site.  A nil `jetpacs-devtools-recording'
makes this one variable test (the seam is always installed, the
developer setting gates the retention)."
  (when jetpacs-devtools-recording
    (push (list :at (float-time)
                :surface (plist-get context :surface)
                :screen (plist-get context :screen)
                :phase (or (plist-get context :phase) 'build)
                :symbol (if (consp err) (car err) err)
                :message (if (consp err) (error-message-string err)
                           (format "%s" err))
                :backtrace
                ;; A bare-symbol ERR is a synthesized failure (chrome's
                ;; non-node check): no signal happened, so the frames
                ;; here would be the CATCH SITE's loop, not the builder
                ;; — a misleading trace is worse than none.
                (if (not (consp err)) ""
                  (let ((bt (ignore-errors
                              (backtrace-to-string
                               (backtrace-get-frames
                                'jetpacs-devtools--record-failure)))))
                    (if (and bt (> (length bt)
                                   jetpacs-devtools--backtrace-max))
                        (substring bt 0 jetpacs-devtools--backtrace-max)
                      (or bt "")))))
          jetpacs-devtools--records)
    (jetpacs-devtools--prune-records)))

(defun jetpacs-devtools-toggle-recording ()
  "Flip the failure recorder; dropping to off clears everything it kept.
The clear rides the defcustom's :set — the same path the device
Settings toggle takes — so disabling the developer setting ends the
retention it authorized no matter which door it goes through."
  (interactive)
  (customize-set-variable 'jetpacs-devtools-recording
                          (not jetpacs-devtools-recording))
  (message "jetpacs-devtools: failure recording %s"
           (if jetpacs-devtools-recording "ON" "off")))

;; --- The profiler (advice, zero footprint elsewhere) ------------------------

(defun jetpacs-devtools--time-build (orig surface plist)
  "Around `jetpacs-shell--build': wall clock, keyed by SURFACE.
The timing is metadata and rides `jetpacs-devtools-profile'; the
built spec is PAYLOAD, so its retention (for
`jetpacs-devtools-last-spec' + `pp') rides the recorder's developer
setting instead.  Measurement never alters the build: it is
condition-cased away from the value path."
  (let ((t0 (float-time))
        (spec (funcall orig surface plist)))
    (ignore-errors
      (when jetpacs-devtools-profile
        (let ((ms (* 1000 (- (float-time) t0)))
              (rec (gethash surface jetpacs-devtools--builds)))
          (puthash surface (list :last-ms ms
                                 :max-ms (max ms (or (plist-get rec :max-ms)
                                                     0.0))
                                 :count (1+ (or (plist-get rec :count) 0))
                                 :at (current-time))
                   jetpacs-devtools--builds)))
      (when jetpacs-devtools-recording
        (puthash surface spec jetpacs-devtools--specs)))
    spec))

(defun jetpacs-devtools--storm-p (times now threshold window)
  "Non-nil when THRESHOLD of TIMES fall within WINDOW seconds before NOW.
Pure, for tests."
  (>= (cl-count-if (lambda (tm) (<= (- now tm) window)) times)
      threshold))

(defun jetpacs-devtools--note-push (surface spec &optional bytes)
  "Tally one outbound push of SPEC to SURFACE; watch for storms.
Size is the compact live-wire measure used by GATE 5, computed here
unless the caller measured BYTES already.  Key ordering does not change
byte length, and recursively sorting a large hidden stack view solely
for profiler metadata can cost more than the visible view build."
  (when jetpacs-devtools-profile
    (ignore-errors
      (let ((rec (gethash surface jetpacs-devtools--pushes))
            (bytes (or bytes (ignore-errors (jetpacs-node-wire-bytes spec))))
            (now (float-time)))
        (puthash surface (list :last-bytes bytes
                               :count (1+ (or (plist-get rec :count) 0))
                               :at (current-time))
                 jetpacs-devtools--pushes)
        (push now jetpacs-devtools--push-times)
        (let ((tail (nthcdr (max 63 (1- jetpacs-devtools-storm-threshold))
                            jetpacs-devtools--push-times)))
          (when tail (setcdr tail nil)))
        (when (and (jetpacs-devtools--storm-p
                    jetpacs-devtools--push-times now
                    jetpacs-devtools-storm-threshold
                    jetpacs-devtools-storm-window)
                   (> (- now jetpacs-devtools--storm-warned-at)
                      jetpacs-devtools-storm-window))
          (setq jetpacs-devtools--storm-warned-at now)
          (display-warning
           'jetpacs
           (format (concat "push storm: %d surface updates in %ds — a "
                           "builder may re-trigger every push; see "
                           "M-x jetpacs-devtools-report")
                   jetpacs-devtools-storm-threshold
                   jetpacs-devtools-storm-window)
           :warning))))))

(defun jetpacs-devtools--observe-push (orig client surface spec &rest keys)
  "Around ORIG, `ebp-client-surface-update': note the push, then send unchanged.
Records a `push' event for CLIENT's SURFACE and SPEC and wraps the
`:callback' in KEYS so the result lands as a `result' event; the value
path is untouched."
  (let* ((bytes (and jetpacs-devtools-profile
                     (ignore-errors (jetpacs-node-wire-bytes spec))))
         (timing (ignore-errors (jetpacs-devtools--send-timing surface)))
         (seq (ignore-errors
                (jetpacs-devtools--push-event
                 'push :surface surface :method "surface.update" :bytes bytes
                 :count (and jetpacs-devtools-profile
                             (ignore-errors
                               (jetpacs-devtools--node-count spec)))
                 :tap-ms (car timing) :prep-ms (cdr timing)))))
    (jetpacs-devtools--note-push surface spec bytes)
    (apply orig client surface spec
           (jetpacs-devtools--wrap-callback keys seq "surface.update"))))

;; --- The event ring (the data path, actions, frames) ------------------------

(defvar jetpacs-devtools--events nil
  "Events, newest first, bounded by `jetpacs-devtools-event-limit' and the TTL.
Each is a plist (:seq N :at FLOAT :kind KIND …) where KIND is `push',
`patch', `remove', `result', `action', `state' or `frame', with the
metadata members that kind carries: :surface :method :origin :revision
:bytes :count :ms :status :error :for-seq, a push's or patch's :tap-ms
\(from the answered action's dispatch to the send) and :prep-ms (inside
the push or patch entry), and :detail — the one payload member, present
only while `jetpacs-devtools-recording' is on.")

(defvar jetpacs-devtools--event-seq 0
  "The sequence number the next event takes.")

(defvar jetpacs-devtools--inbound-octets nil
  "Body octet counts of decoded inbound frames awaiting their jsonrpc event.
`ebp--receive-strict' decodes a body and schedules its dispatch from a
0-delay timer, in order, so the counts pair FIFO with the `server'
events `jsonrpc-event-hook' raises.  Bounded: a parse fault schedules no
event, so the oldest entries are dropped past 64 to resynchronize.")

(defun jetpacs-devtools--prune-events (&optional now)
  "Enforce the ring's bounds: entry cap and TTL, as of NOW."
  (let ((cutoff (- (or now (float-time)) jetpacs-devtools-record-ttl)))
    (setq jetpacs-devtools--events
          (seq-take (seq-filter (lambda (e) (> (plist-get e :at) cutoff))
                                jetpacs-devtools--events)
                    jetpacs-devtools-event-limit))))

(defun jetpacs-devtools--push-event (kind &rest fields)
  "Record one event of KIND with FIELDS; return its sequence number.
A nil-valued field is dropped, so a plist member means a value."
  (let ((seq (cl-incf jetpacs-devtools--event-seq))
        (clean nil))
    (while fields
      (let ((k (pop fields)) (v (pop fields)))
        (when v (push k clean) (push v clean))))
    (push (append (list :seq seq :at (float-time) :kind kind) (nreverse clean))
          jetpacs-devtools--events)
    (jetpacs-devtools--prune-events)
    seq))

(defun jetpacs-devtools--event-without-detail (event)
  "EVENT without its payload member."
  (let ((p event) out)
    (while p
      (let ((k (pop p)) (v (pop p)))
        (unless (eq k :detail) (push k out) (push v out))))
    (nreverse out)))

(defun jetpacs-devtools-events (&optional kind surface)
  "The recorded events, newest first, narrowed to KIND and SURFACE when given."
  (seq-filter (lambda (e)
                (and (or (null kind) (eq (plist-get e :kind) kind))
                     (or (null surface)
                         (equal (plist-get e :surface) surface))))
              jetpacs-devtools--events))

(defun jetpacs-devtools-events-clear ()
  "Drop every recorded event."
  (interactive)
  (setq jetpacs-devtools--events nil
        jetpacs-devtools--inbound-octets nil))

(defun jetpacs-devtools--node-count (spec)
  "How many nodes SPEC holds, by the data walk."
  (let ((n 0))
    (jetpacs-data--walk-nodes spec (lambda (_node _path) (cl-incf n)))
    n))

(defun jetpacs-devtools--wrap-callback (keys seq method)
  "KEYS with its `:callback' wrapped to record the result of event SEQ.
The caller's callback still runs with the same (STATUS ERROR); METHOD
names the request in the `result' event.  Nothing is wrapped when no
event was recorded."
  (if (null seq)
      keys
    (let ((callback (plist-get keys :callback))
          (t0 (float-time)))
      (plist-put (copy-sequence keys) :callback
                 (lambda (status error)
                   (ignore-errors
                     (jetpacs-devtools--push-event
                      'result :for-seq seq :method method
                      :status (and status (format "%s" status))
                      :error (and error
                                  (if (consp error)
                                      (list :code (plist-get error :code)
                                            :kind (plist-get (plist-get error :data) :kind))
                                    (format "%s" error)))
                      :ms (* 1000 (- (float-time) t0))))
                   (when callback (funcall callback status error)))))))

(defun jetpacs-devtools--observe-patch (orig client surface ops &rest keys)
  "Around ORIG, `ebp-client-surface-data': record the patch, then send unchanged.
CLIENT, SURFACE, OPS and KEYS pass through.  The ops count is metadata;
the ops themselves are payload and ride the recorder; their byte size
rides the profiler like a push's."
  (let* ((timing (ignore-errors (jetpacs-devtools--send-timing surface)))
         (seq (ignore-errors
                (jetpacs-devtools--push-event
                 'patch :surface surface :method "surface.data"
                 :count (length ops)
                 :bytes (and jetpacs-devtools-profile
                             (ignore-errors
                               (jetpacs-node-wire-bytes
                                (if (listp ops) (vconcat ops) ops))))
                 :tap-ms (car timing) :prep-ms (cdr timing)
                 :detail (and jetpacs-devtools-recording ops)))))
    (apply orig client surface ops
           (jetpacs-devtools--wrap-callback keys seq "surface.data"))))

(defun jetpacs-devtools--observe-remove (orig client surface &rest keys)
  "Around ORIG, `ebp-client-surface-remove': record CLIENT's removal of SURFACE.
KEYS pass through with the callback wrapped."
  (let ((seq (ignore-errors
               (jetpacs-devtools--push-event
                'remove :surface surface :method "surface.remove"))))
    (apply orig client surface
           (jetpacs-devtools--wrap-callback keys seq "surface.remove"))))

(defun jetpacs-devtools--observe-dispatch (orig client params fn)
  "Around ORIG, `jetpacs--dispatch': record the action and its status.
CLIENT, PARAMS and FN pass through.  The event id and the args are
payload (SPEC 23.3) and ride the recorder; a dispatch that signals out
is recorded with status `signalled'."
  (let ((t0 (float-time)) (status 'signalled))
    (unwind-protect
        (setq status (funcall orig client params fn))
      (ignore-errors
        (jetpacs-devtools--push-event
         'action :surface (plist-get params :surface)
         :method (plist-get params :action)
         :revision (plist-get params :revision_seen)
         :status (format "%s" status)
         :ms (* 1000 (- (float-time) t0))
         :detail (and jetpacs-devtools-recording
                      (list :event_id (plist-get params :event_id)
                            :args (plist-get params :args))))))
    status))

(defun jetpacs-devtools--observe-state (_client surface revision id value)
  "Before `jetpacs--on-state-changed': record SURFACE's report of ID.
REVISION is metadata; VALUE is payload and rides the recorder."
  (ignore-errors
    (jetpacs-devtools--push-event
     'state :surface surface :method id :revision revision
     :detail (and jetpacs-devtools-recording value))))

(defun jetpacs-devtools--observe-inbound-octets (_conn bytes)
  "Before `ebp--receive-strict' on CONN: remember BYTES' size for its event."
  (when jetpacs-devtools-stream-log
    (setq jetpacs-devtools--inbound-octets
          (nconc jetpacs-devtools--inbound-octets (list (length bytes))))
    (while (> (length jetpacs-devtools--inbound-octets) 64)
      (pop jetpacs-devtools--inbound-octets))))

(defun jetpacs-devtools--observe-frame (conn origin &rest keys)
  "On `jsonrpc-event-hook': record one frame of the ebp connection CONN.
ORIGIN is `client' or `server'; KEYS carry the hook's :json, :kind and
:message.  A one-variable test while `jetpacs-devtools-stream-log' is
off; other jsonrpc connections are ignored.  The byte count is the
outbound JSON's own size, or the inbound body size paired from
`jetpacs-devtools--inbound-octets'."
  (when (and jetpacs-devtools-stream-log
             (fboundp 'ebp--connection-client)
             (ignore-errors (ebp--connection-client conn)))
    (ignore-errors
      (let* ((json (plist-get keys :json))
             (kind (plist-get keys :kind))
             (message (plist-get keys :message))
             (params (plist-get message :params))
             (bytes (cond ((stringp json) (string-bytes json))
                          ((eq origin 'server) (pop jetpacs-devtools--inbound-octets)))))
        (jetpacs-devtools--push-event
         'frame :origin origin
         :method (or (plist-get message :method)
                     (and kind (format "%s" kind)))
         :bytes bytes
         :surface (and (consp params) (plist-get params :surface))
         :revision (and (consp params)
                        (or (plist-get params :revision)
                            (plist-get params :revision_seen))))))))

(defun jetpacs-devtools-toggle-stream-log ()
  "Flip the wire frame log."
  (interactive)
  (customize-set-variable 'jetpacs-devtools-stream-log
                          (not jetpacs-devtools-stream-log))
  (message "jetpacs-devtools: wire frame log %s"
           (if jetpacs-devtools-stream-log "ON" "off")))

;; --- The log views ----------------------------------------------------------

(defun jetpacs-devtools--event-time (event)
  "EVENT's time of day with milliseconds."
  (let ((at (plist-get event :at)))
    (format "%s.%03d" (format-time-string "%T" at)
            (floor (* 1000 (- at (floor at)))))))

(defun jetpacs-devtools--insert-inert (&rest strings)
  "Insert STRINGS as they are — never through a format control (SPEC 23.2)."
  (dolist (s strings) (insert (if (stringp s) s (format "%s" s)))))

(defun jetpacs-devtools-stream-log-buffer ()
  "Render the wire frame log into *jetpacs-stream* and return the buffer.
Oldest first, one line per frame with its direction, kind, method, byte
count and surface, then the totals per method."
  (jetpacs-devtools--prune-events)
  (let ((buf (get-buffer-create "*jetpacs-stream*"))
        (frames (reverse (jetpacs-devtools-events 'frame)))
        (totals (make-hash-table :test 'equal)))
    (with-current-buffer buf
      (let ((inhibit-read-only t))
        (erase-buffer)
        (insert (format "Jetpacs wire frames — %s   log: %s

"
                        (format-time-string "%F %T")
                        (if jetpacs-devtools-stream-log "ON"
                          "OFF (M-x jetpacs-devtools-toggle-stream-log)")))
        (if (null frames)
            (insert "  (no frames recorded)
")
          (dolist (e frames)
            (let ((method (or (plist-get e :method) "?"))
                  (bytes (plist-get e :bytes)))
              (jetpacs-devtools--insert-inert
               (jetpacs-devtools--event-time e)
               (if (eq (plist-get e :origin) 'client) " → " " ← ")
               (format "%-24s" method)
               (if bytes (format "%8d B" bytes) "       ? B")
               (if-let* ((s (plist-get e :surface))) (concat "  " s) "")
               (if-let* ((r (plist-get e :revision))) (format " r%s" r) "")
               "
")
              (let ((cell (gethash method totals '(0 . 0))))
                (puthash method (cons (1+ (car cell)) (+ (cdr cell) (or bytes 0)))
                         totals))))
          (insert "
Totals per method:
")
          (let (rows)
            (maphash (lambda (m cell) (push (list m (car cell) (cdr cell)) rows)) totals)
            (dolist (row (cl-sort rows #'> :key #'caddr))
              (jetpacs-devtools--insert-inert
               (format "  %-24s x%-5d %10d B
" (nth 0 row) (nth 1 row) (nth 2 row)))))))
      (special-mode))
    buf))

(defun jetpacs-devtools-action-log-buffer ()
  "Render the action log into *jetpacs-actions* and return the buffer.
Oldest first: every dispatched action with its status and time, every
`state.changed', and the pushes, patches, removes and results around
them.  Payload members appear only while the recorder is on."
  (jetpacs-devtools--prune-events)
  (let ((buf (get-buffer-create "*jetpacs-actions*"))
        (events (reverse (seq-remove (lambda (e) (eq (plist-get e :kind) 'frame))
                                     jetpacs-devtools--events))))
    (with-current-buffer buf
      (let ((inhibit-read-only t))
        (erase-buffer)
        (insert (format "Jetpacs actions and data — %s   recording: %s

"
                        (format-time-string "%F %T")
                        (if jetpacs-devtools-recording "ON" "off")))
        (if (null events)
            (insert "  (nothing recorded)
")
          (dolist (e events)
            (jetpacs-devtools--insert-inert
             (jetpacs-devtools--event-time e)
             (format " #%-4d %-7s" (plist-get e :seq) (plist-get e :kind))
             (if-let* ((m (plist-get e :method))) (format " %-28s" m) (make-string 29 ?\s))
             (if-let* ((s (plist-get e :surface))) (concat " " s) "")
             (if-let* ((r (plist-get e :revision))) (format " r%s" r) "")
             (if-let* ((n (plist-get e :count))) (format " ops=%s" n) "")
             (if-let* ((b (plist-get e :bytes))) (format " %dB" b) "")
             (if-let* ((st (plist-get e :status))) (concat " → " st) "")
             (if-let* ((f (plist-get e :for-seq))) (format " (for #%d)" f) "")
             (if-let* ((ms (plist-get e :ms))) (format " %.1fms" ms) "")
             (if-let* ((tap (plist-get e :tap-ms))) (format " tap→send %.1fms" tap) "")
             (if-let* ((prep (plist-get e :prep-ms))) (format " prep %.1fms" prep) "")
             (if-let* ((err (plist-get e :error))) (format " error=%S" err) "")
             (if-let* ((d (plist-get e :detail))) (format "
      %S" d) "")
             "
"))))
      (special-mode))
    buf))

;; --- The data inspector: the document is data too ------------------------------

(defvar-local jetpacs-devtools--inspected-surface nil
  "The surface whose data document a *jetpacs-data:* buffer shows.")

(defun jetpacs-devtools--pp-bounded (datum)
  "Pretty-print DATUM within `jetpacs-devtools-inspect-max', announcing a cut."
  (let* ((text (pp-to-string datum))
         (full (string-bytes text)))
    (if (<= full jetpacs-devtools-inspect-max)
        text
      (concat (substring text 0 jetpacs-devtools-inspect-max)
              (format "
;; TRUNCATED at %d of %d octets (jetpacs-devtools-inspect-max).
"
                      jetpacs-devtools-inspect-max full)))))

(defun jetpacs-devtools-capture-data (surface)
  "The data document of SURFACE: the one last sent, else one built now.
Returns (SOURCE . DOCUMENT) where SOURCE is `sent', `built' or nil when
the root registers no data builder.  A build leaves the instrumentation
alone, as `jetpacs-devtools-capture-spec' does."
  (when-let* ((id (jetpacs-devtools--resolve-root surface)))
    (if-let* ((sent (gethash id jetpacs-shell--sent-data)))
        (cons 'sent sent)
      (let* ((entry (alist-get id jetpacs-shell--roots nil nil #'equal))
             (jetpacs-devtools-profile nil)
             (jetpacs-devtools-recording nil)
             (built (and (plist-get entry :data-builder)
                         (ignore-errors (jetpacs-shell--build-data id entry)))))
        (and built (cons 'built built))))))

(defun jetpacs-devtools-inspect-data-buffer (surface)
  "Pretty-print SURFACE's data document into a buffer; return it.
Nil when nothing registers SURFACE.  The buffer is \"*jetpacs-data:
SURFACE*\", opens with the datum, is left writable in `lisp-data-mode',
and remembers its surface so `jetpacs-devtools-patch-from-buffer' can
send the edited document back as a `surface.data' patch — json-render's
state editor, over EBP's own patch."
  (when-let* ((id (jetpacs-devtools--resolve-root surface)))
    (let* ((capture (jetpacs-devtools-capture-data id))
           (buf (get-buffer-create (format "*jetpacs-data: %s*" id))))
      (with-current-buffer buf
        (let ((inhibit-read-only t))
          (erase-buffer)
          (insert (jetpacs-devtools--pp-bounded (cdr capture)))
          (unless (bolp) (insert "
"))
          (insert (format "
;; %s — the data document %s.
" id
                          (pcase (car capture)
                            ('sent "Emacs last SENT (what the Companion holds)")
                            ('built "built now, never sent (no document on the Companion yet)")
                            (_ "is absent: the root registers no :data-builder")))
                  ";; Edit the form above, then M-x jetpacs-devtools-patch-from-buffer:
"
                  ";; the difference goes out as surface.data ops through
"
                  ";; jetpacs-shell-data-patch (a whole push when the Companion holds
"
                  ";; no document).  The materialized tree is in the spec inspector.
"))
        (lisp-data-mode)
        (setq jetpacs-devtools--inspected-surface id)
        (goto-char (point-min)))
      buf)))

(defun jetpacs-devtools--document-ops (old new &optional path)
  "DataOps turning document OLD into NEW below pointer PATH (SPEC 13.8.4).
Objects diff member by member (members NEW adds or changes are `set',
members it drops are `remove'); arrays whose elements all carry an `id'
diff by key through `jetpacs-data-array-ops', other arrays and scalars
are `set' whole; a nil OLD sets NEW at PATH.  Nil when nothing differs."
  (let ((path (or path "")))
    (cond
     ((and (jetpacs-data--object-p old) (jetpacs-data--object-p new))
      (let ((old-names (jetpacs-data--member-names old))
            (new-names (jetpacs-data--member-names new))
            ops)
        (dolist (name new-names)
          (let ((child (concat path "/" (jetpacs-data-pointer-escape name)))
                (value (cdr (jetpacs-data--object-member new name))))
            (if (member name old-names)
                (setq ops (nconc ops (jetpacs-devtools--document-ops
                                      (cdr (jetpacs-data--object-member old name))
                                      value child)))
              (setq ops (nconc ops (list (jetpacs-data-set child value)))))))
        (dolist (name old-names)
          (unless (member name new-names)
            (setq ops (nconc ops (list (jetpacs-data-remove
                                        (concat path "/" (jetpacs-data-pointer-escape name))))))))
        ops))
     ((and (vectorp old) (vectorp new))
      (cond
       ((jetpacs-data--same-p old new) nil)
       ((and (> (length new) 0)
             (seq-every-p (lambda (e) (and (jetpacs-data--object-p e)
                                           (car (jetpacs-data--object-member e "id"))))
                          new))
        (let ((diff (jetpacs-data-array-ops path old new :key "id")))
          (if (eq diff :whole) (list (jetpacs-data-set path new)) diff)))
       (t (list (jetpacs-data-set path new)))))
     ((and old (jetpacs-data--same-p old new)) nil)
     ((and (null old) (null new)) nil)
     (t (list (jetpacs-data-set path new))))))

(defun jetpacs-devtools-patch-from-buffer (&optional buffer)
  "Patch the Companion with BUFFER's edited data document; return the ops.
BUFFER defaults to the current one and must be a *jetpacs-data:* buffer.
The patch is one `surface.data' request.  The first form is read (the
Lisp reader evaluates nothing) and must be an object; the ops are the
difference from the document the Companion holds (else a whole `set'),
sent through `jetpacs-shell-data-patch', which re-pushes when the
surface was never pushed with a document.  Nil, with a message, when
nothing changed."
  (interactive)
  (with-current-buffer (or buffer (current-buffer))
    (let ((surface jetpacs-devtools--inspected-surface))
      (unless surface
        (error "jetpacs-devtools: not a data inspection buffer"))
      (let* ((new (save-excursion
                    (goto-char (point-min))
                    (read (current-buffer))))
             (old (cdr (jetpacs-devtools-capture-data surface))))
        (unless (jetpacs-data--object-p new)
          (error "jetpacs-devtools: the document must be an object (a plist or hash table)"))
        (let ((ops (jetpacs-devtools--document-ops old new)))
          (if (null ops)
              (progn (message "jetpacs-devtools: %s — no change to patch" surface) nil)
            (jetpacs-shell-data-patch surface ops)
            (message "jetpacs-devtools: %s — %d op%s sent" surface (length ops)
                     (if (= 1 (length ops)) "" "s"))
            ops))))))

(defun jetpacs-devtools-inspect-data (surface &optional target)
  "Show SURFACE's data document as Lisp, on the device and here.
Like `jetpacs-devtools-inspect' for the document: TARGET is the surface
to drill onto, nil for the device flow's own.  Returns the buffer, or
nil when nothing registers SURFACE.  Never signals."
  (interactive
   (list (completing-read "Inspect data of surface: "
                          (sort (mapcar #'car (jetpacs-shell-roots)) #'string<)
                          nil t)))
  (let ((buf (jetpacs-devtools-inspect-data-buffer surface)))
    (cond
     (buf (jetpacs-navigate-buffer buf target) buf)
     (t (jetpacs-shell-notify (format "No live surface named %s"
                                      (jetpacs-scalar-text (format "%s" surface)))
                              target)
        nil))))

(defun jetpacs-devtools--action-inspect-data (args params)
  "Handler for `jetpacs.devtools.inspect-data': ARGS `:surface', shown now.
PARAMS names the devtools surface that asked, so the buffer opens there."
  (let ((surface (plist-get args :surface))
        (target (plist-get params :surface)))
    (if (not (jetpacs-devtools--resolve-root surface))
        'rejected
      (jetpacs-flow-continue
       (lambda ()
         (condition-case err
             (jetpacs-devtools-inspect-data surface target)
           (error (message "jetpacs-devtools: inspect-data failed: %s"
                           (jetpacs-error-label err))))))
      'accepted)))

(defun jetpacs-devtools--action-inspect-data-pick (_args params)
  "Handler for `jetpacs.devtools.inspect-data-pick': choose, then inspect.
PARAMS names the devtools surface that asked, so the buffer opens there."
  (let ((target (plist-get params :surface)))
    (jetpacs-flow-continue
     (lambda ()
       (let ((names (sort (mapcar #'car (jetpacs-shell-roots)) #'string<)))
         (if (null names)
             (jetpacs-shell-notify "No live surfaces to inspect" target)
           (let ((choice (condition-case nil
                             (completing-read "Inspect data of surface: " names nil t)
                           (quit ""))))
             (unless (string-empty-p choice)
               (condition-case err
                   (jetpacs-devtools-inspect-data choice target)
                 (error (message "jetpacs-devtools: inspect-data failed: %s"
                                 (jetpacs-error-label err))))))))))
    'accepted))

;; --- Public surface ---------------------------------------------------------

(defun jetpacs-devtools-last-spec (surface)
  "The spec SURFACE's builder last produced, or nil.
The raw material for \"what did the Companion actually receive\"
questions; pretty-print it with `pp'.  Retained only while
`jetpacs-devtools-recording' is on — a spec embeds payload, so it
lives under the same developer setting as the failure records."
  (gethash surface jetpacs-devtools--specs))

(defun jetpacs-devtools-reset ()
  "Drop all instrumentation, records included."
  (interactive)
  (clrhash jetpacs-devtools--builds)
  (clrhash jetpacs-devtools--specs)
  (clrhash jetpacs-devtools--pushes)
  (clrhash jetpacs-devtools--taps)
  (setq jetpacs-devtools--push-times nil
        jetpacs-devtools--storm-warned-at 0
        jetpacs-devtools--records nil
        jetpacs-devtools--events nil
        jetpacs-devtools--inbound-octets nil))

(defun jetpacs-devtools-report-buffer ()
  "Render the report into *jetpacs-devtools* and return the buffer.
Recorded strings are inserted inert — never through a format control —
per SPEC 23.2."
  (jetpacs-devtools--prune-records)
  (let ((buf (get-buffer-create "*jetpacs-devtools*"))
        (now (float-time)))
    (with-current-buffer buf
      (let ((inhibit-read-only t))
        (erase-buffer)
        (insert (format "Jetpacs devtools — %s\n" (format-time-string "%F %T"))
                (format "profile: %s   failure recording: %s\n\n"
                        (if jetpacs-devtools-profile "on" "OFF")
                        (if jetpacs-devtools-recording "ON" "off")))
        (insert (format "Pushes in last %ds: %d\n\n"
                        jetpacs-devtools-storm-window
                        (cl-count-if (lambda (tm)
                                       (<= (- now tm)
                                           jetpacs-devtools-storm-window))
                                     jetpacs-devtools--push-times)))
        (jetpacs-devtools--prune-events)
        (insert (format "Events kept: %d (%s)   wire log: %s\n\n"
                        (length jetpacs-devtools--events)
                        (mapconcat (lambda (kind)
                                     (format "%s %d" kind
                                             (length (jetpacs-devtools-events kind))))
                                   '(push patch remove result action state frame)
                                   ", ")
                        (if jetpacs-devtools-stream-log "ON" "off")))
        (insert (format "Failures recorded: %d%s\n"
                        (length jetpacs-devtools--records)
                        (if jetpacs-devtools-recording ""
                          "  (recording is off — M-x \
jetpacs-devtools-toggle-recording)")))
        (dolist (r jetpacs-devtools--records)
          (insert (format "\n— %s  %s%s  [%s]\n"
                          (format-time-string "%T" (plist-get r :at))
                          (plist-get r :surface)
                          (if (plist-get r :screen)
                              (concat " / " (plist-get r :screen)) "")
                          (plist-get r :phase))
                  "  ")
          (insert (plist-get r :message))
          (insert "\n")
          (let ((bt (plist-get r :backtrace)))
            (unless (string-empty-p bt)
              (insert "  backtrace:\n")
              (dolist (line (split-string bt "\n" t))
                (insert "    ") (insert line) (insert "\n")))))
        (insert "\nSurfaces (last push):\n")
        (let (rows)
          (maphash (lambda (s rec) (push (cons s rec) rows))
                   jetpacs-devtools--pushes)
          (if (null rows)
              (insert "  (none observed)\n")
            (dolist (row (cl-sort rows #'> :key (lambda (r)
                                                  (or (plist-get (cdr r)
                                                                 :last-bytes)
                                                      0))))
              (insert (format "  %-28s %8s bytes  x%-4d %s\n"
                              (car row)
                              (or (plist-get (cdr row) :last-bytes) "?")
                              (plist-get (cdr row) :count)
                              (format-time-string
                               "%T" (plist-get (cdr row) :at)))))))
        (insert "\nBuilders (wall clock):\n")
        (let (rows)
          (maphash (lambda (s rec) (push (cons s rec) rows))
                   jetpacs-devtools--builds)
          (if (null rows)
              (insert "  (none observed)\n")
            (dolist (row (cl-sort rows #'> :key (lambda (r)
                                                  (plist-get (cdr r)
                                                             :last-ms))))
              (insert (format "  %-28s last %7.1f ms  max %7.1f ms  x%d\n"
                              (car row)
                              (plist-get (cdr row) :last-ms)
                              (plist-get (cdr row) :max-ms)
                              (plist-get (cdr row) :count))))))
        (insert "\nTap → send (ms; the device's wait from its action's dispatch \
to the answering push or patch; prep = time inside the push/patch entry):\n")
        (let (rows)
          (maphash (lambda (s rec) (push (cons s rec) rows))
                   jetpacs-devtools--taps)
          (if (null rows)
              (insert "  (none observed)\n")
            (dolist (row (cl-sort rows #'> :key (lambda (r)
                                                  (or (plist-get (cdr r)
                                                                 :last-ms)
                                                      0.0))))
              (insert (format "  %-28s last %7s ms  max %7.1f ms  prep %7s ms  x%d\n"
                              (car row)
                              (if-let* ((ms (plist-get (cdr row) :last-ms)))
                                  (format "%.1f" ms) "—")
                              (plist-get (cdr row) :max-ms)
                              (if-let* ((ms (plist-get (cdr row) :prep-ms)))
                                  (format "%.1f" ms) "—")
                              (plist-get (cdr row) :count))))))
        (insert "\nLast spec per surface (retained while recording is on): \
(pp (jetpacs-devtools-last-spec SURFACE))\n"
                "Views: *jetpacs-stream* (jetpacs-devtools-stream-log-buffer), \
*jetpacs-actions* (jetpacs-devtools-action-log-buffer), \
*jetpacs-data: SURFACE* (jetpacs-devtools-inspect-data).\n"))
      (special-mode))
    buf))

(defun jetpacs-devtools-report ()
  "Render and display the devtools report."
  (interactive)
  (display-buffer (jetpacs-devtools-report-buffer)))

;; --- The inspector: the spec is data, and the data is yours ------------------

(defun jetpacs-devtools--resolve-root (surface)
  "The live root id SURFACE names, or nil when nothing registers one.
`jetpacs-shell-roots' is the PUBLIC read of the registry — the same one
the launcher builds its app list from, so \"a surface you can inspect\"
and \"a surface you can switch to\" are the same set by construction.
An exact id wins; a bare owner falls back to its D1 `app:<owner>'
spelling, so a REPL caller may type `\"hub\"'."
  (and (stringp surface)
       (let ((roots (jetpacs-shell-roots)))
         (cond ((assoc surface roots) surface)
               ((assoc (concat "app:" surface) roots) (concat "app:" surface))))))

(defun jetpacs-devtools-capture-spec (surface)
  "Build SURFACE's registered root FRESH, right now, and return the spec.
Nil when nothing registers SURFACE.

CAPTURE ON DEMAND.  This deliberately does NOT read
`jetpacs-devtools--specs' (nor its accessor `jetpacs-devtools-last-spec'):
that retention is RECORDING-GATED and the recorder is off by default —
so it may hold nothing at all, and when it holds something it holds the
spec of a PAST build.  \"What does this screen look like as data, now\"
is a question a build answers and a cache does not.

The capture route is `jetpacs-shell--build' called with the registry's
own entry plist — literally the two lines `jetpacs-shell-push' runs
before it reaches the gates.  That is the LEAST new seam available:
zero new code in the shell, and the spec you read here is the spec the
device would be sent, degrade included (a crashing builder yields its
error view rather than signalling out of an inspection).  Calling the
root's `:builder' directly would have to re-create by hand the three
things that build wraps it in — the registered owner binding, the ONE
per-document id-claim table, and the degrade — which is more new code
for a spec that is no longer the one the phone gets.  Devtools is
already the module that reaches into this function; it advises it.

The measurement is left ALONE: the profiler and the recorder are bound
off across the build, so an inspection does not land in the wall-clock
tally, does not count toward the push-storm history, and — the part
that matters under SPEC 23.3 — creates no payload retention of its own.
Inspecting is reading, not recording."
  (when-let* ((id (jetpacs-devtools--resolve-root surface))
              (entry (alist-get id jetpacs-shell--roots nil nil #'equal)))
    (let ((jetpacs-devtools-profile nil)
          (jetpacs-devtools-recording nil))
      (jetpacs-shell--build id entry))))

(defun jetpacs-devtools-inspect-buffer (surface)
  "Pretty-print SURFACE's freshly built spec into a buffer; return it.
Nil when nothing registers SURFACE.  The buffer is
\"*jetpacs-inspect: SURFACE*\" and it OPENS WITH THE DATUM: point-min is
the spec's own open paren, so `C-M-f', `C-x C-e', and a plain kill of
the whole form all work without stepping over a preamble.  The prose —
provenance, the push-back recipe, any truncation notice — trails below
as Lisp comments.

The loop the prose describes needs no code on its return leg:

    ;; on the phone, or here
    (jetpacs-devtools-inspect \"app:hub\")
    ;; edit the sexp — retitle the top bar, drop a node, add a button
    (jetpacs-shell-push \"app:hub\" :spec EDITED)

`jetpacs-shell-push' has taken a `:spec' override since the first cut,
so the edited datum goes back exactly the way the builder's would have.
Homoiconic all the way down: the screen is a value, and a value can be
read, changed, and returned."
  (when-let* ((id (jetpacs-devtools--resolve-root surface)))
    (let* ((spec (jetpacs-devtools-capture-spec id))
           (text (pp-to-string spec))
           (full (string-bytes text))
           (cut (> full jetpacs-devtools-inspect-max))
           (buf (get-buffer-create (format "*jetpacs-inspect: %s*" id))))
      (with-current-buffer buf
        (let ((inhibit-read-only t))
          (erase-buffer)
          ;; Chars never outnumber octets, so a char-count substring is
          ;; always inside the octet cap.
          (insert (if cut (substring text 0 jetpacs-devtools-inspect-max) text))
          (unless (bolp) (insert "\n"))
          (when cut
            (insert (format "\n;; TRUNCATED at %d of %d octets \
(jetpacs-devtools-inspect-max).\n\
;; The form above is INCOMPLETE — read the whole datum with\n\
;;   (pp (jetpacs-devtools-capture-spec %S))\n"
                            jetpacs-devtools-inspect-max full id)))
          (insert (format "\n;; %s — built %s, fresh (never a cached spec).\n\
;; The spec is DATA and the data is yours: edit the form above, then\n\
;;   (jetpacs-shell-push %S :spec EDITED)\n\
;; and the phone renders what you wrote.\n"
                          id (format-time-string "%F %T") id))
          ;; SPEC 13.8: the other half of what the Companion holds — the
          ;; data document and the tree it materializes from both.
          (when-let* ((capture (jetpacs-devtools-capture-data id)))
            (insert (format "\n;; --- data document (%s) — edit it with \
(jetpacs-devtools-inspect-data %S) ---\n"
                            (if (eq (car capture) 'sent) "last sent" "built now, never sent")
                            id))
            (insert (jetpacs-devtools--pp-bounded (cdr capture)))
            (unless (bolp) (insert "\n"))
            (insert ";; --- materialized (:strip t): bindings resolved, templates \
expanded, hidden nodes dropped — what the Companion presents ---\n")
            (insert (condition-case err
                        (jetpacs-devtools--pp-bounded
                         (jetpacs-data-materialize spec (cdr capture) :strip t))
                      (error (format ";; materialization failed: %s\n"
                                     (jetpacs-error-label err)))))
            (unless (bolp) (insert "\n"))))
        ;; Data, not a program: `lisp-data-mode' fontifies and indents it
        ;; without pretending the plist is a call form.  Left WRITABLE on
        ;; purpose — editing this buffer is half the point.
        (lisp-data-mode)
        (goto-char (point-min)))
      buf)))

(defun jetpacs-devtools-inspect (surface &optional target)
  "Show SURFACE's freshly built spec as Lisp, on the device and here.
Builds through `jetpacs-devtools-capture-spec', renders through
`jetpacs-devtools-inspect-buffer', and presents the result with
`jetpacs-navigate-buffer' — so the Tier-0 buffer renderer makes the
data phone-visible for free, exactly the way the Tools drawer's
*Messages* row already reads the Emacs log.  TARGET is the surface to
drill onto; nil takes the device flow's own (the surface the user
tapped from).

Returns the buffer, or nil when nothing registers SURFACE.  Never
signals: this is reachable from an action handler's continuation.

Interactively, completion is over the live root registry — on the
device that prompt is the Companion's own picker, because the dialog
bridge answers `completing-read' there."
  (interactive
   (list (completing-read "Inspect surface: "
                          (sort (mapcar #'car (jetpacs-shell-roots)) #'string<)
                          nil t)))
  (let ((buf (jetpacs-devtools-inspect-buffer surface)))
    (cond
     (buf (jetpacs-navigate-buffer buf target) buf)
     (t (jetpacs-shell-notify (format "No live surface named %s"
                                      (jetpacs-scalar-text (format "%s" surface)))
                              target)
        nil))))

(defun jetpacs-devtools--action-inspect (args params)
  "Handler for `jetpacs.devtools.inspect': ARGS `:surface', built now.
Resolution happens INSIDE the extent so an unknown name answers a
clean `rejected' — SPEC 14.4's permanent no — rather than deferring
work that has nothing to do.  The build and the drill are D2
continuation work: a builder is user code and may take as long as it
likes."
  (let ((surface (plist-get args :surface))
        (target (plist-get params :surface)))
    (if (not (jetpacs-devtools--resolve-root surface))
        'rejected
      (jetpacs-flow-continue
       (lambda ()
         (condition-case err
             (jetpacs-devtools-inspect surface target)
           (error (message "jetpacs-devtools: inspect failed: %s"
                           (jetpacs-error-label err))))))
      'accepted)))

(defun jetpacs-devtools--action-inspect-pick (_args params)
  "Handler for `jetpacs.devtools.inspect-pick': choose, then inspect.
The affordance's half of the pair — the Tools drawer row carries no
surface, because the interesting surface is whichever one is live when
you tap it.  The picker is a BRIDGED `completing-read' over the root
registry, raised from a `jetpacs-flow-continue' continuation for the
reason D2 exists: inside the dispatch extent prompting is banned, and
outside it the flow identity the continuation carries is what tells the
dialog floor to render the picker on the phone instead of a minibuffer
nobody is looking at."
  (let ((target (plist-get params :surface)))
    (jetpacs-flow-continue
     (lambda ()
       (let ((names (sort (mapcar #'car (jetpacs-shell-roots)) #'string<)))
         (if (null names)
             (jetpacs-shell-notify "No live surfaces to inspect" target)
           (let ((choice (condition-case nil
                             (completing-read "Inspect surface: " names nil t)
                           (quit ""))))
             (unless (string-empty-p choice)
               (condition-case err
                   (jetpacs-devtools-inspect choice target)
                 (error (message "jetpacs-devtools: inspect failed: %s"
                                 (jetpacs-error-label err))))))))))
    'accepted))

;; GLOBAL VERBS.  Devtools owns the actions and ZERO surfaces — the
;; affordance is a row in the hub's drawer, and an inspection is
;; legitimate from any screen — so the D1 scope exemption is declared
;; explicitly on `jetpacs.devtools.inspect' itself.  The
;; :args/:doc schema is dogfooded here rather than described: the
;; inspector is the self-hosting program's own tool, and an action
;; editor reading `jetpacs-action-schema' can lay out this form.
(with-jetpacs-owner "jetpacs.devtools"
  (jetpacs-defaction "jetpacs.devtools.inspect"
                     #'jetpacs-devtools--action-inspect
                     :any-surface t
                     :args '((:name surface :type "text" :required t))
                     :doc "Show a live surface's spec, built fresh, as Lisp.")
  (jetpacs-defaction "jetpacs.devtools.inspect-pick"
                     #'jetpacs-devtools--action-inspect-pick
                     :any-surface t
                     :doc "Pick a live surface, then inspect its spec.")
  (jetpacs-defaction "jetpacs.devtools.inspect-data"
                     #'jetpacs-devtools--action-inspect-data
                     :any-surface t
                     :args '((:name surface :type "text" :required t))
                     :doc "Show a live surface's data document as Lisp, editable into a patch.")
  (jetpacs-defaction "jetpacs.devtools.inspect-data-pick"
                     #'jetpacs-devtools--action-inspect-data-pick
                     :any-surface t
                     :doc "Pick a live surface, then inspect its data document."))

;; The seam member is always installed; `jetpacs-devtools-recording'
;; gates the retention, so a live session pays one nil test per failure
;; until the developer setting turns the recorder on.
(add-hook 'jetpacs-shell-builder-error-functions
          #'jetpacs-devtools--record-failure)

;; The floor's reset seam: instrumentation is per-session state like any
;; other, so a fixture that resets the floor drops the records too.
(add-hook 'jetpacs-reset-functions #'jetpacs-devtools-reset)

(unless (advice-member-p #'jetpacs-devtools--time-build 'jetpacs-shell--build)
  (advice-add 'jetpacs-shell--build :around #'jetpacs-devtools--time-build))
(unless (advice-member-p #'jetpacs-devtools--observe-push
                         'ebp-client-surface-update)
  (advice-add 'ebp-client-surface-update :around
              #'jetpacs-devtools--observe-push))
;; The push and patch entries: where the send's `:prep-ms' starts. The
;; shell is already loaded (required above), so the advice binds now.
(dolist (entry '(jetpacs-shell-push jetpacs-shell-data-patch
                 jetpacs-shell-data-sync))
  (unless (advice-member-p #'jetpacs-devtools--time-push entry)
    (advice-add entry :around #'jetpacs-devtools--time-push)))
;; The rest of the data path and the two device-event entry points. Every
;; observer is value-transparent and `ignore-errors'-isolated.
(unless (advice-member-p #'jetpacs-devtools--observe-patch 'ebp-client-surface-data)
  (advice-add 'ebp-client-surface-data :around #'jetpacs-devtools--observe-patch))
(unless (advice-member-p #'jetpacs-devtools--observe-remove 'ebp-client-surface-remove)
  (advice-add 'ebp-client-surface-remove :around #'jetpacs-devtools--observe-remove))
(unless (advice-member-p #'jetpacs-devtools--observe-dispatch 'jetpacs--dispatch)
  (advice-add 'jetpacs--dispatch :around #'jetpacs-devtools--observe-dispatch))
(unless (advice-member-p #'jetpacs-devtools--observe-state 'jetpacs--on-state-changed)
  (advice-add 'jetpacs--on-state-changed :before #'jetpacs-devtools--observe-state))
;; The wire frame log: jsonrpc.el 1.0.25 (Emacs 30.1) raises an event per
;; frame; the inbound body size rides the strict receiver's advice.
(when (boundp 'jsonrpc-event-hook)
  (add-hook 'jsonrpc-event-hook #'jetpacs-devtools--observe-frame))
(unless (advice-member-p #'jetpacs-devtools--observe-inbound-octets 'ebp--receive-strict)
  (advice-add 'ebp--receive-strict :before #'jetpacs-devtools--observe-inbound-octets))

(provide 'jetpacs-devtools)
;;; jetpacs-devtools.el ends here
