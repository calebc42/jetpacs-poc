;;; jetpacs-devtools-test.el --- Devtools exit gate -*- lexical-binding: t; -*-

;;; Commentary:

;; The devtools gate: the flight recorder keeps the full failure story
;; (condition, backtrace, screen) that SPEC 23.3 scrubs off the wire —
;; and the wire stays scrubbed, asserted here against the pushed spec
;; itself.  The profiler half is v1 parity: build wall clock, last
;; spec, push sizes, the storm predicate.  Recording defaults OFF — the
;; §23.3 "explicit developer setting" floor is itself under test.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)
(require 'jetpacs-widgets)
(require 'jetpacs-async)
(require 'jetpacs-surfaces)
(require 'jetpacs-shell)
(require 'jetpacs-buffer)
(require 'jetpacs-chrome)
(require 'jetpacs-devtools)

(defconst jetpacs-devtools-test--types
  ["text" "row" "column" "box" "spacer" "divider" "button" "text_input"
   "card" "icon" "icon_button" "lazy_column" "scaffold"])

(defun jetpacs-devtools-test--client ()
  (let ((client (ebp-client-create
                 :receipt-file (make-temp-file "jetpacs-devtools-receipts"))))
    (setf (ebp-client-state client) 'ready
          (ebp-client-profiles client)
          `(:app (:node_types ,jetpacs-devtools-test--types
                  :builtins ["view.switch"] :features []))
          (ebp-client-limits client) '(:max_frame_bytes 4194304))
    client))

(defmacro jetpacs-devtools-test--with (client-form &rest body)
  (declare (indent 1))
  `(let ((client ,client-form))
     (unwind-protect
         (progn (jetpacs-attach client) ,@body)
       (jetpacs-detach)
       (jetpacs-test-reset-state)
       (clrhash jetpacs-chrome--stacks))))

(defmacro jetpacs-devtools-test--recording (records &rest body)
  (declare (indent 1))
  `(let ((,records nil))
     (cl-letf (((symbol-function 'ebp-client-surface-update)
                (cl-function
                 (lambda (_c surface spec &rest keys)
                   (push (list surface spec keys) ,records)
                   42))))
       ,@body)))

(defun jetpacs-devtools-test--define-root (owner)
  (with-jetpacs-owner owner
    (jetpacs-chrome-define-root
     owner "home"
     (lambda (_back) (jetpacs-chrome-screen "Hub" (jetpacs-text "h"))))))

;;;; The §23.3 floor

(ert-deftest jetpacs-devtools-recording-defaults-off ()
  "The recorder is the SPEC 23.3 explicit developer setting: off unless asked."
  (should-not (default-value 'jetpacs-devtools-recording)))

;;;; The flight recorder

(ert-deftest jetpacs-devtools-screen-failure-recorded-and-wire-scrubbed ()
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording recs
      (let ((jetpacs-devtools-recording t))
        (jetpacs-devtools-test--define-root "devt")
        (jetpacs-chrome-push-screen
         "devt" "boom" (lambda (_back) (error "boom: %s" "the-datum")))
        ;; The push survived — the screen degraded, the surface shipped.
        (should recs)
        ;; The record kept the whole story.
        (let ((rec (car jetpacs-devtools--records)))
          (should rec)
          (should (equal (plist-get rec :surface) "app:devt"))
          (should (equal (plist-get rec :screen) "boom"))
          (should (eq (plist-get rec :symbol) 'error))
          (should (string-match-p "the-datum" (plist-get rec :message)))
          (should (> (length (plist-get rec :backtrace)) 0)))
        ;; The wire kept nothing: the datum never reaches the spec, the
        ;; scrubbed card does.
        (let ((json (jetpacs-node->canonical-json (nth 1 (car recs)))))
          (should (string-match-p "failed to build" json))
          (should-not (string-match-p "the-datum" json)))))))

(ert-deftest jetpacs-devtools-shell-degrade-ships-the-label-only ()
  "The whole-surface degrade spec is wire-bound and Companion-persisted:
it carries the error SYMBOL, never the datum (SPEC 23.3)."
  (let* ((spec (jetpacs-shell--build
                "app:x"
                (list :builder (lambda () (error "leak: %s" "sms-body")))))
         (json (jetpacs-node->canonical-json spec)))
    (should (string-match-p "Error building" json))
    (should (string-match-p "error" json))
    (should-not (string-match-p "sms-body" json))))

(ert-deftest jetpacs-devtools-tile-degrade-stays-in-the-tile-schema ()
  "A failed tile builder produces a valid node-less SPEC 13.4 tile."
  (let* ((spec (jetpacs-shell--build
                "tile:custom1"
                (list :builder (lambda () (error "leak: %s" "sms-body")))))
         (json (jetpacs-node->canonical-json spec)))
    (should (equal (plist-get spec :label) "Error"))
    (should (eq (plist-get spec :active) :json-false))
    (should-not (plist-member spec :t))
    (should-not (string-match-p "sms-body" json))))

(ert-deftest jetpacs-devtools-recorder-off-keeps-nothing ()
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording recs
      ;; Default-off: same crash, zero retention.
      (jetpacs-devtools-test--define-root "devt")
      (jetpacs-chrome-push-screen
       "devt" "boom" (lambda (_back) (error "boom: %s" "the-datum")))
      (should recs)
      (should-not jetpacs-devtools--records))))

(ert-deftest jetpacs-devtools-gate-failure-recorded ()
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording recs
      (let ((jetpacs-devtools-recording t))
        ;; An unadvertised node type: the gate signals out of the push
        ;; (the sender MUSTs stay loud), and the recorder saw it first.
        (should-error (jetpacs-shell-push "app:devt"
                                          :spec (list :t "video")))
        (let ((rec (car jetpacs-devtools--records)))
          (should rec)
          (should (equal (plist-get rec :surface) "app:devt"))
          (should (eq (plist-get rec :phase) 'gate)))))))

(ert-deftest jetpacs-devtools-record-limit-and-ttl-bound ()
  (let ((jetpacs-devtools-recording t)
        (jetpacs-devtools-record-limit 5)
        (jetpacs-devtools--records nil))
    (dotimes (i 8)
      (jetpacs-devtools--record-failure
       (list :surface (format "s%d" i)) '(error "x")))
    ;; Size bound: only the newest five survive.
    (should (= 5 (length jetpacs-devtools--records)))
    (should (equal "s7" (plist-get (car jetpacs-devtools--records) :surface)))
    ;; Lifetime bound: an entry past the TTL is dropped on the next record.
    (let ((jetpacs-devtools-record-ttl 60))
      (push (list :at (- (float-time) 61) :surface "stale")
            jetpacs-devtools--records)
      (jetpacs-devtools--record-failure '(:surface "fresh") '(error "y"))
      (should-not (seq-find (lambda (r) (equal (plist-get r :surface) "stale"))
                            jetpacs-devtools--records)))))

(ert-deftest jetpacs-devtools-toggle-off-clears ()
  (let ((jetpacs-devtools-recording nil)
        (jetpacs-devtools--records nil))
    (jetpacs-devtools-toggle-recording)
    (should jetpacs-devtools-recording)
    (jetpacs-devtools--record-failure '(:surface "s") '(error "x"))
    (should jetpacs-devtools--records)
    ;; Off ends the retention the setting authorized.
    (jetpacs-devtools-toggle-recording)
    (should-not jetpacs-devtools-recording)
    (should-not jetpacs-devtools--records)))

;;;; The profiler

(ert-deftest jetpacs-devtools-profiler-times-and-keeps-spec ()
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording recs
      (let ((jetpacs-devtools-profile t)
            (jetpacs-devtools-recording t))
        (jetpacs-devtools-test--define-root "devt")
        ;; Defining only registers; the build the profiler times happens
        ;; on a push.
        (jetpacs-chrome-push-screen
         "devt" "detail"
         (lambda (back) (jetpacs-chrome-screen "Detail" (jetpacs-text "d")
                                               :back back)))
        (should recs)
        (let ((rec (gethash "app:devt" jetpacs-devtools--builds)))
          (should rec)
          (should (>= (plist-get rec :count) 1))
          (should (numberp (plist-get rec :last-ms))))
        ;; The spec is payload: retained because recording is ON here.
        (should (jetpacs-devtools-last-spec "app:devt"))))))

(ert-deftest jetpacs-devtools-spec-retention-rides-the-developer-setting ()
  "Payload retention is recorder-gated: with recording off, timings land
but the built spec is NOT kept (SPEC 23.3 — a spec embeds payload)."
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording recs
      (let ((jetpacs-devtools-profile t))
        (jetpacs-devtools-test--define-root "devt")
        (jetpacs-chrome-push-screen
         "devt" "detail"
         (lambda (back) (jetpacs-chrome-screen "Detail" (jetpacs-text "d")
                                               :back back)))
        (should recs)
        (should (gethash "app:devt" jetpacs-devtools--builds))
        (should-not (jetpacs-devtools-last-spec "app:devt"))))))

(ert-deftest jetpacs-devtools-profiler-defaults-off ()
  "Daily-driver pushes do not pay for diagnostic whole-spec serialization."
  (should-not (default-value 'jetpacs-devtools-profile)))

(ert-deftest jetpacs-devtools-shell-build-failure-recorded ()
  "The surface build catch fires the seam: a plain define-root builder
crash (notification/widget/non-chrome surfaces reach ONLY this seam)
records with :phase build."
  (let ((jetpacs-devtools-recording t)
        (jetpacs-devtools--records nil)
        (jetpacs-devtools--specs (make-hash-table :test 'equal)))
    (jetpacs-shell--build
     "app:plain" (list :builder (lambda () (error "boom: %s" "shell-datum"))))
    (let ((rec (car jetpacs-devtools--records)))
      (should rec)
      (should (equal (plist-get rec :surface) "app:plain"))
      (should (eq (plist-get rec :phase) 'build))
      (should (string-match-p "shell-datum" (plist-get rec :message)))
      (should (> (length (plist-get rec :backtrace)) 0)))))

(ert-deftest jetpacs-devtools-non-node-screen-recorded ()
  "A builder that RETURNS a non-node never signals; the chrome catch
synthesizes the failure and must still tell the seam."
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording recs
      (let ((jetpacs-devtools-recording t))
        (jetpacs-devtools-test--define-root "devt")
        (jetpacs-chrome-push-screen "devt" "nilscreen" (lambda (_back) nil))
        (should recs)
        (let ((rec (car jetpacs-devtools--records)))
          (should rec)
          (should (equal (plist-get rec :screen) "nilscreen"))
          (should (eq (plist-get rec :symbol) 'wrong-type-argument))
          ;; No signal happened — a backtrace here would show the catch
          ;; site, not the builder, so none is kept.
          (should (string-empty-p (plist-get rec :backtrace))))))))

(ert-deftest jetpacs-devtools-gate2-failure-recorded ()
  "GATE 2 is a push gate like the others: a current_view naming no view
signals out AND records with :phase gate."
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording recs
      (let ((jetpacs-devtools-recording t))
        (should-error
         (jetpacs-shell-push
          "app:devt"
          :spec (jetpacs-multi-view (list (cons "home" (jetpacs-text "h")))
                                    "home")
          :current-view "gone"))
        (let ((rec (car jetpacs-devtools--records)))
          (should rec)
          (should (eq (plist-get rec :phase) 'gate)))))))

(ert-deftest jetpacs-devtools-settings-path-off-clears ()
  "The Companion Settings toggle goes through the defcustom's :set —
turning recording off by THAT door must clear retention too."
  (let ((jetpacs-devtools--records nil)
        (jetpacs-devtools--specs (make-hash-table :test 'equal)))
    (unwind-protect
        (progn
          (funcall (get 'jetpacs-devtools-recording 'custom-set)
                   'jetpacs-devtools-recording t)
          (jetpacs-devtools--record-failure '(:surface "s") '(error "x"))
          (puthash "app:s" (jetpacs-text "payload") jetpacs-devtools--specs)
          (should jetpacs-devtools--records)
          (funcall (get 'jetpacs-devtools-recording 'custom-set)
                   'jetpacs-devtools-recording nil)
          (should-not jetpacs-devtools--records)
          (should (zerop (hash-table-count jetpacs-devtools--specs))))
      (set-default 'jetpacs-devtools-recording nil)
      (jetpacs-devtools-reset))))

(ert-deftest jetpacs-devtools-storm-history-scales-to-threshold ()
  "A threshold above the old 64-entry cap can still trip: the history
sizes itself to the threshold."
  (let ((jetpacs-devtools--pushes (make-hash-table :test 'equal))
        (jetpacs-devtools--push-times nil)
        (jetpacs-devtools-profile t)
        (jetpacs-devtools-storm-threshold 100)
        (jetpacs-devtools--storm-warned-at 0)
        (warned nil))
    (cl-letf (((symbol-function 'display-warning)
               (lambda (&rest _) (setq warned t))))
      (dotimes (_ 120) (jetpacs-devtools--note-push "app:x" (jetpacs-text "h")))
      (should (>= (length jetpacs-devtools--push-times) 100))
      (should warned))))

(ert-deftest jetpacs-devtools-note-push-tallies-and-sizes ()
  (let ((jetpacs-devtools--pushes (make-hash-table :test 'equal))
        (jetpacs-devtools--push-times nil)
        (jetpacs-devtools-profile t))
    (cl-letf (((symbol-function 'jetpacs-node->canonical-json)
               (lambda (_spec)
                 (error "golden serializer reached from live profiler"))))
      (jetpacs-devtools--note-push "app:x" (jetpacs-text "hello"))
      (jetpacs-devtools--note-push "app:x" (jetpacs-text "hello again")))
    (let ((rec (gethash "app:x" jetpacs-devtools--pushes)))
      (should (= 2 (plist-get rec :count)))
      (should (natnump (plist-get rec :last-bytes)))
      (should (> (plist-get rec :last-bytes) 0)))
    (should (= 2 (length jetpacs-devtools--push-times)))))

(ert-deftest jetpacs-devtools-storm-p ()
  (let ((now 100.0))
    ;; Eight inside the window trips it…
    (should (jetpacs-devtools--storm-p
             (mapcar (lambda (i) (- now i)) '(0 1 2 3 4 5 6 7)) now 8 10))
    ;; …seven inside plus one outside does not.
    (should-not (jetpacs-devtools--storm-p
                 (mapcar (lambda (i) (- now i)) '(0 1 2 3 4 5 6 20)) now 8 10))))

;;;; The report

(ert-deftest jetpacs-devtools-report-renders ()
  (let ((jetpacs-devtools-recording t)
        (jetpacs-devtools--records nil))
    ;; A message that LOOKS like a format control must render inert
    ;; (SPEC 23.2: peer/error text is an argument, never a template).
    (jetpacs-devtools--record-failure '(:surface "s")
                                      '(error "100%s of the time"))
    (with-current-buffer (jetpacs-devtools-report-buffer)
      (should (string-match-p "100%s of the time"
                              (buffer-substring-no-properties
                               (point-min) (point-max)))))))

;;;; The inspector (capture on demand)

(ert-deftest jetpacs-devtools-inspect-verb-declares-its-schema ()
  "The verb dogfoods the `:args'/`:doc' registry it asks other apps to
use: one required `surface' of type text, and a line saying what it
does.  A global verb, too — devtools owns zero surfaces and the
affordance sits in the hub's drawer."
  (let ((schema (jetpacs-action-schema "jetpacs.devtools.inspect")))
    (should schema)
    (should (equal (plist-get schema :args)
                   '((:name surface :type "text" :required t))))
    (should (stringp (plist-get schema :doc)))
    (should (plist-get schema :any-surface)))
  ;; The picker half declares itself too (no args: the surface is the
  ;; thing it asks for).
  (let ((schema (jetpacs-action-schema "jetpacs.devtools.inspect-pick")))
    (should schema)
    (should-not (plist-get schema :args))
    (should (stringp (plist-get schema :doc)))
    (should (plist-get schema :any-surface))))

(ert-deftest jetpacs-devtools-inspect-builds-fresh-and-renders-the-datum ()
  "The handler produces a live buffer that OPENS with the datum: point-min
is the spec's own paren, and a node member of the registered root is in
it.  Built fresh — the recorder is OFF here, so a cache-reading
inspector would have had nothing to show."
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording _recs
      (jetpacs-devtools-test--define-root "devt")
      (let ((navigated nil))
        (cl-letf (((symbol-function 'jetpacs-navigate-buffer)
                   (lambda (target &rest _) (setq navigated target)))
                  ((symbol-function 'jetpacs-flow-continue)
                   (lambda (fn) (funcall fn))))
          (should (eq (jetpacs-devtools--action-inspect
                       '(:surface "app:devt") '(:surface "app:devt"))
                      'accepted))
          (let ((buf (get-buffer "*jetpacs-inspect: app:devt*")))
            (should (buffer-live-p buf))
            (should (eq navigated buf))
            (with-current-buffer buf
              (let ((text (buffer-substring-no-properties
                           (point-min) (point-max))))
                ;; The datum first: no preamble to step over.
                (should (string-prefix-p "(" text))
                ;; A known node member of the built root.
                (should (string-match-p ":t \"scaffold\"" text))
                (should (string-match-p "Hub" text))
                ;; The push-back recipe rides along as a comment.
                (should (string-match-p "jetpacs-shell-push" text))))
            (kill-buffer buf)))))))

(ert-deftest jetpacs-devtools-inspect-does-not-read-the-recorder-cache ()
  "CAPTURE ON DEMAND: the retention is recording-gated and may be off, so
the inspector builds instead of reading.  A poisoned cache entry proves
which one it did — and the inspection leaves the instrumentation
untouched, creating no retention of its own."
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording _recs
      (jetpacs-devtools-test--define-root "devt")
      (puthash "app:devt" (jetpacs-text "STALE-CACHED-SPEC")
               jetpacs-devtools--specs)
      (let ((spec (jetpacs-devtools-capture-spec "app:devt")))
        (should spec)
        (let ((json (jetpacs-node->canonical-json spec)))
          (should (string-match-p "Hub" json))
          (should-not (string-match-p "STALE-CACHED-SPEC" json))))
      ;; Measurement-neutral: nothing recorded, nothing counted.
      (should (equal (gethash "app:devt" jetpacs-devtools--specs)
                     (jetpacs-text "STALE-CACHED-SPEC")))
      (should-not (gethash "app:devt" jetpacs-devtools--builds))
      ;; A bare owner spells the same root (the REPL convenience).
      (should (jetpacs-devtools-capture-spec "devt")))))

(ert-deftest jetpacs-devtools-inspect-cap-truncates-and-says-so ()
  "An oversized spec is CUT, never silently: the announcement names the
cap and points at the REPL for the whole datum."
  ;; The registry bound directly: no client, no wire, no teardown — the
  ;; inspector reads `jetpacs-shell-roots' and builds, and that is all
  ;; this needs to be true of.
  (let ((jetpacs-shell--roots
         (list (cons "app:big"
                     (list :builder (lambda ()
                                      (jetpacs-text (make-string 4000 ?x)))
                           :owner "big"))))
        (jetpacs-devtools-inspect-max 512))
    (let ((buf (jetpacs-devtools-inspect-buffer "app:big")))
      (should (buffer-live-p buf))
      (with-current-buffer buf
        (let ((text (buffer-substring-no-properties (point-min) (point-max))))
          (should (string-prefix-p "(" text))
          (should (string-match-p "TRUNCATED at 512 of" text))
          (should (string-match-p "jetpacs-devtools-capture-spec" text))))
      (kill-buffer buf))))

(ert-deftest jetpacs-devtools-inspect-unknown-surface-rejects-quietly ()
  "A surface nothing registers is a PERMANENT no (SPEC 14.4 `rejected'),
answered inside the extent — never a signal escaping the dispatch, and
never a deferred continuation with nothing to build."
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (let ((deferred 0))
      (cl-letf (((symbol-function 'jetpacs-flow-continue)
                 (lambda (_fn) (cl-incf deferred))))
        (should (eq (jetpacs-devtools--action-inspect
                     '(:surface "app:nope") '(:surface "app:hub"))
                    'rejected))
        (should (eq (jetpacs-devtools--action-inspect
                     '(:surface 42) '(:surface "app:hub"))
                    'rejected))
        (should (zerop deferred))))
    ;; And through the real dispatch, which is where "does not signal
    ;; out of the extent" actually means something.
    (should (eq (jetpacs--dispatch
                 (jetpacs-client)
                 '(:action "jetpacs.devtools.inspect"
                   :surface "app:hub" :args (:surface "app:nope"))
                 (gethash "jetpacs.devtools.inspect" jetpacs-action-handlers))
                'rejected))
    (should-not (get-buffer "*jetpacs-inspect: app:nope*"))
    ;; The renderer half degrades the same way: nil, no buffer, no signal.
    (should-not (jetpacs-devtools-inspect-buffer "app:nope"))
    (should-not (jetpacs-devtools-capture-spec nil))))

(ert-deftest jetpacs-devtools-tap-to-send-stamps-the-answering-push ()
  "A push or patch that answers a device action carries the device's wait.
`:tap-ms' spans the action's dispatch to the send; `:prep-ms' the push
entry to the send; a send with no unanswered action carries neither."
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0)
        (jetpacs-devtools-profile t))
    (clrhash jetpacs-devtools--taps)
    (jetpacs-devtools--observe-dispatch
     (lambda (&rest _) 'accepted) 'client
     '(:action "x.toggle" :surface "app:x" :revision_seen 1 :event_id "e1")
     #'ignore)
    ;; The deferred push, entered through the shell's timed entry.
    (jetpacs-devtools--time-push
     (lambda ()
       (jetpacs-devtools--observe-push
        (lambda (_c _s _spec &rest _keys) 7)
        'client "app:x" (jetpacs-text "hello") :callback #'ignore)))
    (let ((push (car (jetpacs-devtools-events 'push))))
      (should (numberp (plist-get push :tap-ms)))
      (should (>= (plist-get push :tap-ms) 0))
      (should (numberp (plist-get push :prep-ms)))
      (should (>= (plist-get push :tap-ms) (plist-get push :prep-ms))))
    (let ((rec (gethash "app:x" jetpacs-devtools--taps)))
      (should (= 1 (plist-get rec :count)))
      (should (numberp (plist-get rec :last-ms)))
      (should (numberp (plist-get rec :prep-ms))))
    ;; The action is answered: a second push of the same surface, with no
    ;; new action and outside any push entry, carries no tap or prep time.
    (jetpacs-devtools--observe-push
     (lambda (_c _s _spec &rest _keys) 8)
     'client "app:x" (jetpacs-text "again") :callback #'ignore)
    (let ((push (car (jetpacs-devtools-events 'push))))
      (should-not (plist-member push :tap-ms))
      (should-not (plist-member push :prep-ms)))
    (should (= 1 (plist-get (gethash "app:x" jetpacs-devtools--taps) :count)))
    ;; Another surface's action does not answer this one; a surfaceless
    ;; action (a shortcut, a reminder) answers any surface.
    (jetpacs-devtools--observe-dispatch
     (lambda (&rest _) 'accepted) 'client
     '(:action "y.open" :surface "app:y" :event_id "e2") #'ignore)
    (jetpacs-devtools--observe-patch
     (lambda (_c _s _ops &rest _keys) 9)
     'client "app:x" (list (jetpacs-data-set "/n" 1)) :callback #'ignore)
    (should-not (plist-member (car (jetpacs-devtools-events 'patch)) :tap-ms))
    (jetpacs-devtools--observe-dispatch
     (lambda (&rest _) 'accepted) 'client
     '(:action "shortcut.fire" :event_id "e3") #'ignore)
    (jetpacs-devtools--observe-patch
     (lambda (_c _s _ops &rest _keys) 10)
     'client "app:x" (list (jetpacs-data-set "/n" 2)) :callback #'ignore)
    (should (numberp (plist-get (car (jetpacs-devtools-events 'patch)) :tap-ms)))
    (should (= 2 (plist-get (gethash "app:x" jetpacs-devtools--taps) :count)))
    ;; The report and the action log show the figures.
    (with-current-buffer (jetpacs-devtools-report-buffer)
      (should (string-match-p "Tap → send"
                              (buffer-substring-no-properties (point-min) (point-max))))
      (should (string-match-p "app:x +last +[0-9.]+ ms"
                              (buffer-substring-no-properties (point-min) (point-max)))))
    (with-current-buffer (jetpacs-devtools-action-log-buffer)
      (should (string-match-p "tap→send [0-9.]+ms"
                              (buffer-substring-no-properties (point-min) (point-max)))))))

(ert-deftest jetpacs-devtools-tap-tally-rides-the-profiler ()
  "Events always carry the stamps; the per-surface tally rides the profiler."
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0)
        (jetpacs-devtools-profile nil))
    (clrhash jetpacs-devtools--taps)
    (jetpacs-devtools--observe-dispatch
     (lambda (&rest _) 'accepted) 'client
     '(:action "x.toggle" :surface "app:x" :event_id "e1") #'ignore)
    (jetpacs-devtools--observe-push
     (lambda (_c _s _spec &rest _keys) 7)
     'client "app:x" (jetpacs-text "hello") :callback #'ignore)
    (should (numberp (plist-get (car (jetpacs-devtools-events 'push)) :tap-ms)))
    (should-not (gethash "app:x" jetpacs-devtools--taps))))

(provide 'jetpacs-devtools-test)
;;; jetpacs-devtools-test.el ends here

;;;; The event ring (the data path, actions and frames)

(ert-deftest jetpacs-devtools-events-ring-is-bounded-and-typed ()
  "Events are metadata, kept newest first, capped and aged out."
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0)
        (jetpacs-devtools-event-limit 5))
    (dotimes (i 8) (jetpacs-devtools--push-event 'push :surface (format "app:%d" i)))
    (should (= 5 (length jetpacs-devtools--events)))
    (should (equal (plist-get (car jetpacs-devtools--events) :surface) "app:7"))
    (should (= 5 (length (jetpacs-devtools-events 'push))))
    (should (= 1 (length (jetpacs-devtools-events 'push "app:7"))))
    (should-not (jetpacs-devtools-events 'patch))
    ;; A nil field is dropped, so a member always means a value.
    (jetpacs-devtools--push-event 'patch :surface "app:p" :bytes nil :count 2)
    (should-not (plist-member (car jetpacs-devtools--events) :bytes))
    (should (= 2 (plist-get (car jetpacs-devtools--events) :count)))
    ;; TTL: an old entry falls off at the next prune.
    (push (list :seq 0 :at 0.0 :kind 'push) jetpacs-devtools--events)
    (jetpacs-devtools--prune-events)
    (should-not (seq-find (lambda (e) (= 0 (plist-get e :seq))) jetpacs-devtools--events))
    (jetpacs-devtools-events-clear)
    (should-not jetpacs-devtools--events)))

(ert-deftest jetpacs-devtools-patch-and-result-events-record-metadata ()
  "A `surface.data' send records a patch event; its wrapped callback records
the result and still reaches the caller's callback.  The ops are payload."
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0)
        (jetpacs-devtools-profile t) (jetpacs-devtools-recording nil)
        captured)
    (should (eq 'sent
                (jetpacs-devtools--observe-patch
                 (lambda (_c _s _ops &rest keys) (setq captured keys) 'sent)
                 'client "app:x" (list (jetpacs-data-set "/a" 1))
                 :callback (lambda (status error) (list status error)))))
    (let ((patch (car (jetpacs-devtools-events 'patch))))
      (should (equal (plist-get patch :surface) "app:x"))
      (should (equal (plist-get patch :method) "surface.data"))
      (should (= 1 (plist-get patch :count)))
      (should (natnump (plist-get patch :bytes)))
      (should-not (plist-member patch :detail))
      (should (equal (funcall (plist-get captured :callback) "applied" nil)
                     '("applied" nil)))
      (let ((result (car (jetpacs-devtools-events 'result))))
        (should (equal (plist-get result :status) "applied"))
        (should (equal (plist-get result :method) "surface.data"))
        (should (= (plist-get result :for-seq) (plist-get patch :seq)))
        (should (numberp (plist-get result :ms)))))
    ;; An error reply keeps its code and kind, never its message text.
    (funcall (plist-get captured :callback) nil
             '(:code 1201 :message "Invalid content" :data (:kind "content-invalid")))
    (should (equal (plist-get (car (jetpacs-devtools-events 'result)) :error)
                   '(:code 1201 :kind "content-invalid")))
    ;; Recording keeps the ops; turning it off drops them from every event.
    (let ((jetpacs-devtools-recording t))
      (jetpacs-devtools--observe-patch (lambda (&rest _) 'sent)
                                       'client "app:x" (list (jetpacs-data-set "/b" 2)))
      (should (plist-get (car (jetpacs-devtools-events 'patch)) :detail)))
    (customize-set-variable 'jetpacs-devtools-recording nil)
    (should-not (plist-member (car (jetpacs-devtools-events 'patch)) :detail))
    ;; A remove records too, with its own result.
    (jetpacs-devtools--observe-remove (lambda (&rest _) 'sent) 'client "app:x")
    (should (equal (plist-get (car (jetpacs-devtools-events 'remove)) :method)
                   "surface.remove"))))

(ert-deftest jetpacs-devtools-push-event-wraps-the-callback ()
  "The push observer records the push and routes the result."
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0)
        (jetpacs-devtools-profile t) captured)
    (jetpacs-devtools--observe-push
     (lambda (_c _s _spec &rest keys) (setq captured keys) 7)
     'client "app:x" (jetpacs-text "hello") :callback #'ignore)
    (let ((push (car (jetpacs-devtools-events 'push))))
      (should (equal (plist-get push :method) "surface.update"))
      (should (natnump (plist-get push :bytes)))
      (should (= 1 (plist-get push :count))))
    (funcall (plist-get captured :callback) "stale" nil)
    (should (equal (plist-get (car (jetpacs-devtools-events 'result)) :status) "stale"))))

(ert-deftest jetpacs-devtools-dispatch-and-state-events ()
  "Actions and state reports land as events; args and values ride the recorder."
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0)
        (jetpacs-devtools-recording nil))
    (should (eq 'accepted
                (jetpacs-devtools--observe-dispatch
                 (lambda (&rest _) 'accepted) 'client
                 '(:action "x.y" :surface "app:x" :revision_seen 4
                   :event_id "e1" :args (:secret 1))
                 #'ignore)))
    (let ((ev (car (jetpacs-devtools-events 'action))))
      (should (equal (plist-get ev :method) "x.y"))
      (should (equal (plist-get ev :status) "accepted"))
      (should (= 4 (plist-get ev :revision)))
      (should (numberp (plist-get ev :ms)))
      (should-not (plist-member ev :detail)))
    ;; A dispatch that signals is recorded as such and still signals out.
    (should-error (jetpacs-devtools--observe-dispatch
                   (lambda (&rest _) (error "boom")) 'client
                   '(:action "x.z" :surface "app:x") #'ignore))
    (should (equal (plist-get (car (jetpacs-devtools-events 'action)) :status) "signalled"))
    (jetpacs-devtools--observe-state 'client "app:x" 5 "title" "typed")
    (let ((ev (car (jetpacs-devtools-events 'state))))
      (should (equal (plist-get ev :method) "title"))
      (should (= 5 (plist-get ev :revision)))
      (should-not (plist-member ev :detail)))
    (let ((jetpacs-devtools-recording t))
      (jetpacs-devtools--observe-dispatch
       (lambda (&rest _) 'accepted) 'client
       '(:action "x.y" :surface "app:x" :event_id "e2" :args (:k 1)) #'ignore)
      (should (equal (plist-get (plist-get (car (jetpacs-devtools-events 'action)) :detail)
                                :event_id)
                     "e2"))
      (jetpacs-devtools--observe-state 'client "app:x" 6 "title" "typed more")
      (should (equal (plist-get (car (jetpacs-devtools-events 'state)) :detail)
                     "typed more")))
    (with-current-buffer (jetpacs-devtools-action-log-buffer)
      (let ((text (buffer-substring-no-properties (point-min) (point-max))))
        (should (string-match-p "x\\.y" text))
        (should (string-match-p "→ accepted" text))
        (should (string-match-p "typed more" text))))))

(ert-deftest jetpacs-devtools-frame-events-ride-the-stream-log-toggle ()
  "Frames are recorded only while the wire log is on, with their byte counts."
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0)
        (jetpacs-devtools--inbound-octets nil))
    (cl-letf (((symbol-function 'ebp--connection-client) (lambda (_c) 'an-ebp-client)))
      (let ((jetpacs-devtools-stream-log nil))
        (jetpacs-devtools--observe-frame
         'conn 'client :json "{\"a\":1}" :kind 'request
         :message '(:method "surface.update" :params (:surface "app:x" :revision 3)))
        (jetpacs-devtools--observe-inbound-octets 'conn "0123456789")
        (should-not jetpacs-devtools--events)
        (should-not jetpacs-devtools--inbound-octets))
      (let ((jetpacs-devtools-stream-log t))
        (jetpacs-devtools--observe-frame
         'conn 'client :json "{\"a\":\"é\"}" :kind 'request
         :message '(:method "surface.update" :params (:surface "app:x" :revision 3)))
        (let ((ev (car (jetpacs-devtools-events 'frame))))
          (should (eq (plist-get ev :origin) 'client))
          (should (equal (plist-get ev :method) "surface.update"))
          (should (= (plist-get ev :bytes) (string-bytes "{\"a\":\"é\"}")))
          (should (equal (plist-get ev :surface) "app:x"))
          (should (= 3 (plist-get ev :revision))))
        ;; Inbound octets pair FIFO with the next server event.
        (jetpacs-devtools--observe-inbound-octets 'conn "0123456789")
        (jetpacs-devtools--observe-frame
         'conn 'server :json nil :kind 'notification
         :message '(:method "state.changed" :params (:surface "app:x" :id "t")))
        (let ((ev (car (jetpacs-devtools-events 'frame))))
          (should (eq (plist-get ev :origin) 'server))
          (should (= 10 (plist-get ev :bytes)))
          (should (equal (plist-get ev :method) "state.changed")))
        (should-not jetpacs-devtools--inbound-octets)
        ;; A reply has no method: its kind names it.
        (jetpacs-devtools--observe-frame 'conn 'server :json nil :kind 'reply
                                         :message '(:id 1 :result (:status "applied")))
        (should (equal (plist-get (car (jetpacs-devtools-events 'frame)) :method) "reply"))
        (with-current-buffer (jetpacs-devtools-stream-log-buffer)
          (let ((text (buffer-substring-no-properties (point-min) (point-max))))
            (should (string-match-p "→ surface.update" text))
            (should (string-match-p "← state.changed" text))
            (should (string-match-p "Totals per method" text))))))
    ;; A non-ebp jsonrpc connection is ignored.
    (cl-letf (((symbol-function 'ebp--connection-client) (lambda (_c) nil)))
      (let ((jetpacs-devtools-stream-log t) (jetpacs-devtools--events nil))
        (jetpacs-devtools--observe-frame 'conn 'client :json "{}" :kind 'request
                                         :message '(:method "textDocument/hover"))
        (should-not jetpacs-devtools--events)))))

(ert-deftest jetpacs-devtools-report-counts-events ()
  (let ((jetpacs-devtools--events nil) (jetpacs-devtools--event-seq 0))
    (jetpacs-devtools--push-event 'patch :surface "app:x" :count 1)
    (with-current-buffer (jetpacs-devtools-report-buffer)
      (should (string-match-p "Events kept: 1 (push 0, patch 1,"
                              (buffer-substring-no-properties (point-min) (point-max)))))))

;;;; The data inspector

(ert-deftest jetpacs-devtools-document-ops-diff-members-and-keyed-arrays ()
  "The edited document becomes the smallest DataOps the shell can send."
  (should-not (jetpacs-devtools--document-ops '(:a 1) '(:a 1)))
  (let ((ops (jetpacs-devtools--document-ops '(:a 1 :b "x") '(:a 2 :c t))))
    (should (equal (mapcar (lambda (o) (plist-get o :path)) ops) '("/a" "/c" "/b")))
    (should (equal (mapcar (lambda (o) (plist-get o :op)) ops) '("set" "set" "remove"))))
  ;; Nested objects diff member by member; keyed arrays diff by id.
  (let* ((old (list :p (list :title "A") :rows (vector (list :id "a" :n 1) (list :id "b" :n 2))))
         (new (list :p (list :title "B") :rows (vector (list :id "a" :n 1) (list :id "b" :n 3))))
         (ops (jetpacs-devtools--document-ops old new)))
    (should (equal (mapcar (lambda (o) (plist-get o :path)) ops) '("/p/title" "/rows/1/n"))))
  ;; Unkeyed arrays are set whole; a changed scalar is set; nothing sends nothing.
  (should (equal (plist-get (car (jetpacs-devtools--document-ops '(:xs [1 2]) '(:xs [2 1]))) :path)
                 "/xs"))
  (should-not (jetpacs-devtools--document-ops '(:xs [1 2]) '(:xs [1 2])))
  ;; No document on the Companion yet: one whole set at the root.
  (let ((ops (jetpacs-devtools--document-ops nil '(:a 1))))
    (should (= 1 (length ops)))
    (should (equal (plist-get (car ops) :path) ""))
    (should (equal (plist-get (car ops) :op) "set")))
  ;; Pointer tokens are escaped (Elisp has no bar quoting; intern the key).
  (let ((key (intern ":a/b")))
    (should (equal (plist-get (car (jetpacs-devtools--document-ops (list key 1) (list key 2))) :path)
                   "/a~1b"))))

(ert-deftest jetpacs-devtools-inspect-data-and-patch-from-buffer ()
  "The document opens as a datum, the edited datum goes back as a patch, and
the spec inspector shows the document and the materialized tree."
  (jetpacs-devtools-test--with (jetpacs-devtools-test--client)
    (jetpacs-devtools-test--recording _recs
      (with-jetpacs-owner "devt"
        (jetpacs-chrome-define-root
         "devt" "home"
         (lambda (_back) (jetpacs-chrome-screen "Hub" (jetpacs-text (jetpacs-ref "/title"))))
         :data-builder (lambda () (list :title "Hello" :rows (vector (list :id "a" :n 1))))))
      (let ((buf (jetpacs-devtools-inspect-data-buffer "app:devt")))
        (should (buffer-live-p buf))
        (with-current-buffer buf
          (let ((text (buffer-substring-no-properties (point-min) (point-max))))
            (should (string-prefix-p "(" text))
            (should (string-match-p ":title \"Hello\"" text))
            (should (string-match-p "built now, never sent" text))
            (should (string-match-p "jetpacs-devtools-patch-from-buffer" text)))
          (should (equal jetpacs-devtools--inspected-surface "app:devt"))
          ;; Nothing changed: no patch.
          (should-not (jetpacs-devtools-patch-from-buffer))
          ;; Edit the datum: retitle and bump the row.
          (goto-char (point-min))
          (forward-sexp)
          (delete-region (point-min) (point))
          (goto-char (point-min))
          (insert "(:title \"Edited\" :rows [(:id \"a\" :n 2)])")
          (let (sent)
            (cl-letf (((symbol-function 'jetpacs-shell-data-patch)
                       (cl-function (lambda (surface ops &rest _) (setq sent (list surface ops)) 'ok))))
              (let ((ops (jetpacs-devtools-patch-from-buffer)))
                (should (equal (car sent) "app:devt"))
                (should (= 2 (length ops)))
                (should (equal (sort (mapcar (lambda (o) (plist-get o :path)) ops) #'string<)
                               '("/rows/0/n" "/title")))))))
        (kill-buffer buf))
      ;; The spec inspector carries the document and the materialized tree.
      (let ((buf (jetpacs-devtools-inspect-buffer "app:devt")))
        (with-current-buffer buf
          (let ((text (buffer-substring-no-properties (point-min) (point-max))))
            (should (string-prefix-p "(" text))
            (should (string-match-p ";; --- data document (built now, never sent)" text))
            (should (string-match-p ";; --- materialized" text))
            ;; Authored: a binding. Materialized: the resolved text.
            (should (string-match-p ":bind (:text \"/title\")" text))
            (should (string-match-p ":text \"Hello\"" text))))
        (kill-buffer buf)))))

(ert-deftest jetpacs-devtools-inspect-data-verbs-declare-their-schema ()
  (let ((schema (jetpacs-action-schema "jetpacs.devtools.inspect-data")))
    (should schema)
    (should (equal (plist-get schema :args) '((:name surface :type "text" :required t))))
    (should (stringp (plist-get schema :doc)))
    (should (plist-get schema :any-surface)))
  (let ((schema (jetpacs-action-schema "jetpacs.devtools.inspect-data-pick")))
    (should schema)
    (should-not (plist-get schema :args))
    (should (plist-get schema :any-surface))))

(ert-deftest jetpacs-devtools-stream-log-defaults-off ()
  (should-not (default-value 'jetpacs-devtools-stream-log)))

