;;; ebp-host-test.el --- live loopback against the RF-2.6 JVM host -*- lexical-binding: t; -*-
;; SPDX-License-Identifier: GPL-3.0-or-later

;;; Commentary:

;; RF-2.6(E2): the conformant-path half of the live-loopback coverage,
;; dialed against a REAL Companion — the :host module's CompanionEngine
;; over Memory stores.  Real validation, real revision floors, real
;; queue semantics, real proofs; nothing scripted.  Until this suite
;; existed, every "live" ERT test spoke to a fake whose replies came
;; from ebp.el's own encoder, so the two conformance layers only ever
;; checked each other (ebp-wire-test.el:336-339).  This is the first
;; elisp<->Kotlin gate in the tree.
;;
;; The scripted fake is NOT replaced.  It keeps the paths a conformant
;; host cannot produce — wire-order readback, a forged server_proof, an
;; incomplete welcome, withheld replies, Companion-originated pushes —
;; which is why RF-2.6 demotes it to a unit fixture rather than deleting
;; it (docs/PLAN-rf26-host.md, E1's disposition census).
;;
;; Opt-in, per ratified decision 5: the suite runs only when
;; EBP_HOST_LAUNCH holds a command that starts the host, e.g.
;;
;;   EBP_HOST_LAUNCH="java -jar companion/host/build/libs/host-all.jar \
;;                    --port 0 --kat"
;;
;; whose stdout carries the launcher contract line "EBP-HOST PORT=<n>".
;; Unset, every test skips and `test/run-tests.sh' behaves exactly as it
;; did before this rung.  One JVM starts per suite run; each dial gets a
;; fresh engine and fresh Memory stores from the host's SPEC 5.2
;; newest-wins accept loop, so tests stay independent of each other.

;;; Code:

(require 'ert)
(require 'cl-lib)
(require 'ebp)

(defconst ebp-host-test--launch (getenv "EBP_HOST_LAUNCH"))

(defconst ebp-host-test--kat-token
  (ebp-decode-pairing-token "AAECAwQFBgcICQoLDA0ODw"))
(defconst ebp-host-test--kat-pid "101112131415161718191a1b1c1d1e1f")
(defconst ebp-host-test--kat-cn "202122232425262728292a2b2c2d2e2f")

(defvar ebp-host-test--host nil
  "The shared host plist (:port :process :stop), one per suite run.")

(defun ebp-host-test--start-host (&optional command)
  "Launch COMMAND (default `EBP_HOST_LAUNCH'); return (:port P :process PROC :stop FN).
Blocks until the \"EBP-HOST PORT=<n>\" launcher line, 30 s deadline —
generous because a cold JVM is seconds, not milliseconds.  COMMAND lets
a suite launch a differently-flagged host (RF-3's seam suite appends
--echo) without duplicating this plumbing."
  (let* ((buf (generate-new-buffer " *ebp-host*"))
         (proc (make-process
                :name "ebp-host"
                :command (split-string-shell-command
                          (or command ebp-host-test--launch))
                :buffer buf :noquery t))
         (port nil)
         (deadline (+ (float-time) 30)))
    (while (and (not port) (< (float-time) deadline))
      (unless (process-live-p proc)
        (error "EBP_HOST_LAUNCH died before printing its port: %s"
               (with-current-buffer buf (buffer-string))))
      (accept-process-output proc 0.1)
      (with-current-buffer buf
        (goto-char (point-min))
        (when (re-search-forward "EBP-HOST PORT=\\([0-9]+\\)" nil t)
          (setq port (string-to-number (match-string 1))))))
    (unless port
      (ignore-errors (kill-process proc))
      (error "EBP_HOST_LAUNCH never printed EBP-HOST PORT=<n>"))
    (list :port port :process proc
          :stop (lambda ()
                  (when (process-live-p proc) (kill-process proc))
                  (ignore-errors (kill-buffer buf))))))

(defun ebp-host-test--host ()
  "The shared host, started on first use and stopped at `kill-emacs'."
  (or ebp-host-test--host
      (progn
        (setq ebp-host-test--host (ebp-host-test--start-host))
        (add-hook 'kill-emacs-hook #'ebp-host-test--stop-host)
        ebp-host-test--host)))

(defun ebp-host-test--stop-host ()
  (when ebp-host-test--host
    (funcall (plist-get ebp-host-test--host :stop))
    (setq ebp-host-test--host nil)))

(defun ebp-host-test--connect (&rest extra)
  "Dial the shared host with the KAT identity.
EXTRA comes FIRST so a caller can override any default, `:wants'
included — duplicate keys resolve to the earliest, and against a real
Companion `:wants' is load-bearing: `granted' is the engine's
wants-intersect-supported, not something a script can hand us."
  (apply #'ebp-connect "127.0.0.1" (plist-get (ebp-host-test--host) :port)
         (append extra
                 (list :client-name "host-test" :client-version "0.0.1"
                       :pairing-id ebp-host-test--kat-pid
                       :token ebp-host-test--kat-token
                       :wants '("theme")
                       :client-nonce ebp-host-test--kat-cn
                       :receipt-file (make-temp-file "ebp-host-receipts")))))

(defun ebp-host-test--wait (pred &optional timeout)
  "Pump the event loop until PRED or TIMEOUT (default 10 s); return PRED.
Double the fake suite's 5 s: a real engine does real work between one
reply and the next, and the first dial of a run also absorbs JVM
class-loading."
  (let ((deadline (+ (float-time) (or timeout 10))))
    (while (and (not (funcall pred)) (< (float-time) deadline))
      (accept-process-output nil 0.05))
    (funcall pred)))

(defmacro ebp-host-test--with-client (spec &rest body)
  "Bind SPEC = (CLIENT-VAR &rest CONNECT-ARGS) over BODY, closing after.
Skips when `EBP_HOST_LAUNCH' is unset."
  (declare (indent 1))
  (pcase-let ((`(,client-var . ,connect-args) spec))
    `(progn
       (skip-unless ebp-host-test--launch)
       (let ((,client-var (ebp-host-test--connect ,@connect-args)))
         (unwind-protect (progn ,@body)
           (ignore-errors (ebp-client-close ,client-var 'test-done)))))))

(defmacro ebp-host-test--with-ready-client (spec &rest body)
  "Like `ebp-host-test--with-client', with the handshake already done."
  (declare (indent 1))
  (pcase-let ((`(,client-var . ,connect-args) spec))
    `(ebp-host-test--with-client (,client-var ,@connect-args)
       (should (ebp-host-test--wait
                (lambda () (eq (ebp-client-state ,client-var) 'ready))))
       ,@body)))

;;;; The handshake against a real engine

(ert-deftest ebp-host-test-handshake-to-ready ()
  "SPEC 10.3 over a real Companion: the barrier completes and the
welcome the client absorbs is the engine's own.

Reaching READY is itself the KAT proof assertion, and a stronger one
than the fake's: `CompanionEngine' verifies `client_proof'
constant-time against the Kotlin HMAC and answers 1203 auth-failed
plus close on any mismatch, so a welcome arriving at all proves the
elisp proof byte-matched the Kotlin one.  The fake compared our proof
against `ebp-client-proof' — our own implementation.  The on-wire
method ORDER stays with the fake: this host has no readback channel
\(ratified decision 3)."
  (let ((ready nil))
    (ebp-host-test--with-client
        (client :ready-function (lambda (_c) (setq ready t)))
      (should (ebp-host-test--wait (lambda () ready)))
      (should (eq (ebp-client-state client) 'ready))
      ;; `granted' is the engine's wants-intersect-supported, not a
      ;; scripted literal.
      (should (equal (ebp-client-granted client) ["theme"]))
      (should (= (plist-get (ebp-client-limits client) :max_frame_bytes)
                 4194304))
      ;; The host supports more than we asked for (it also offers
      ;; surfaces.dialog), so this also proves `granted' is a real
      ;; wants-intersect-supported and not an echo.  A fresh Memory
      ;; store retains no surfaces, so the welcome's map is empty.
      (should-not (ebp-client-surfaces client)))))

;;;; Surfaces against real floors (SPEC 13)

(ert-deftest ebp-host-test-surface-revisions-climb-real-floors ()
  "SPEC 13.1 against a real SurfaceStore: claims climb, the engine
applies each one, and its accepted floor is what the next claim
follows.  The fake echoed whatever revision we sent; here the floor
lives in the Companion and a stale claim would be told so."
  (let ((statuses '()))
    (ebp-host-test--with-ready-client (client)
      (let ((record (lambda (status _err) (push status statuses))))
        ;; A fresh store reports no surfaces, so the client's floor is
        ;; -1 and the first claim is 0 (ebp.el:1759/1782).
        (should (= (ebp-client-surface-update client "app:main"
                                              '(:t "text" :text "hi")
                                              :callback record)
                   0))
        ;; A second, independent surface claims from its own floor.
        (should (= (ebp-client-surface-update client "app:other"
                                              '(:t "text" :text "two")
                                              :callback record)
                   0))
        ;; The same surface again climbs past its in-flight claim.
        (should (= (ebp-client-surface-update client "app:main"
                                              '(:t "text" :text "again")
                                              :callback record)
                   1))
        (should (ebp-host-test--wait (lambda () (= (length statuses) 3))))
        ;; Every claim was above the real floor, so all three applied.
        (should (equal statuses '("applied" "applied" "applied")))))))

(ert-deftest ebp-host-test-surface-remove-is-revisioned-and-tombstoned ()
  "SPEC 13.3 against a real store: removal claims a revision like any
mutation, and the tombstone it leaves is a real floor — the re-create
has to climb past it, which the fake could only pretend."
  (let ((status nil) (recreate nil))
    (ebp-host-test--with-ready-client (client)
      (should (= (ebp-client-surface-update
                  client "app:main" '(:t "text" :text "hi"))
                 0))
      (should (= (ebp-client-surface-remove
                  client "app:main"
                  :callback (lambda (s _e) (setq status s)))
                 1))
      (should (ebp-host-test--wait (lambda () status)))
      (should (equal status "applied"))
      ;; Recreating climbs past the tombstone and the engine accepts it.
      (should (= (ebp-client-surface-update
                  client "app:main" '(:t "text" :text "reborn")
                  :callback (lambda (s _e) (setq recreate s)))
                 2))
      (should (ebp-host-test--wait (lambda () recreate)))
      (should (equal recreate "applied")))))

;;;; Draft reset history (SPEC 14.6)

(ert-deftest ebp-host-test-state-changed-reset-history ()
  "SPEC 14.6 + P1 #2 with the reset revision taken from a real
Companion: a report against a pre-reset revision is discarded, one at
or above the reset revision is adopted.  The fake pushed the initial
`state.changed' from its after-ready script; here the client's own
dispatcher supplies the reports, which is all the assertions ever
read."
  (ebp-host-test--with-ready-client (client)
    (let (claim)
      ;; Establish the input and its value before any reset.
      (setq claim (ebp-client-surface-update
                   client "app:main" '(:t "text_input" :id "title")))
      (ebp-client--handle-state-changed
       client (list :surface "app:main" :revision_seen claim
                    :id "title" :value "first"))
      (should (equal (ebp-client-input-value client "app:main" "title")
                     "first"))
      ;; Reset the draft at the NEXT revision the client claims.
      (let ((reset (ebp-client-surface-update
                    client "app:main" '(:t "text_input" :id "title")
                    :reset-input-ids '("title"))))
        (should (= reset (1+ claim)))
        ;; A report racing in against the pre-reset revision loses...
        (ebp-client--handle-state-changed
         client (list :surface "app:main" :revision_seen claim
                      :id "title" :value "raced-and-lost"))
        (should (equal (ebp-client-input-value client "app:main" "title")
                       "first"))
        ;; ...and one at the reset revision is adopted.
        (ebp-client--handle-state-changed
         client (list :surface "app:main" :revision_seen reset
                      :id "title" :value "reinstated"))
        (should (equal (ebp-client-input-value client "app:main" "title")
                       "reinstated"))))))

;;;; SPEC 22.3 the sender ceiling and the close path

(ert-deftest ebp-host-test-close-fails-outstanding-locally ()
  "SPEC 22.3: on close, outstanding requests fail LOCALLY — jsonrpc's
sentinel errors every continuation, our callbacks conclude, the
counter returns to zero.

The fake had to be scripted to swallow a method so requests could
accumulate.  A conformant Companion supplies the same condition
honestly: SPEC 18.1 says `dialog.show' is held until a user acts, and
a headless host has no user, so three dialogs stay genuinely
outstanding for as long as we like.  That also pins the holding
behavior itself, which no fake test could."
  (let ((concluded '()))
    ;; dialog.show is gated by the surfaces.dialog capability
    ;; (`ebp--method-capabilities'), so this dial asks for it.
    (ebp-host-test--with-ready-client
        (client :wants '("theme" "surfaces.dialog"))
      (should (equal (ebp-client-granted client) ["theme" "surfaces.dialog"]))
      (dotimes (i 3)
        (ebp-client-dialog-show
         client (format "d%d" i) '(:t "text" :text "hold me")
         :callback (lambda (_status _result error) (push error concluded))))
      ;; Really outstanding: pump, and they are still unanswered.
      (ebp-host-test--wait (lambda () nil) 0.5)
      (should (= (ebp-client-outstanding client) 3))
      (should (null concluded))
      ;; Kill OUR transport; the sentinel must conclude all three.
      (delete-process (ebp-client-process client))
      (should (ebp-host-test--wait (lambda () (= (length concluded) 3))))
      (should (= (ebp-client-outstanding client) 0))
      (should (cl-every (lambda (e) (plist-get e :code)) concluded)))))

(ert-deftest ebp-host-test-inbound-pause-resume-and-in-send-guard ()
  "The backpressure lever against a real connection: high-water pauses
the reader (stop-process), the drain side resumes it at the low-water
mark, and the A8 section 1.5 invariant holds — no pause is ever taken
inside a send, and any send resumes a paused reader first.

Migrated verbatim: every assertion is client-internal, and the one
frame it emits (a 1400 `log.error') the real engine drops without a
reply, so a quiescent host cannot perturb the sequence."
  (ebp-host-test--with-ready-client (client)
    (let ((proc (ebp-client-process client)))
      (cl-letf (((symbol-function 'ebp-client--backlog)
                 (lambda (_c) ebp-overload-hold)))
        (ebp-client--inbound-check client))
      (should (ebp-client-inbound-paused client))
      (should (eq (process-status proc) 'stop))
      (cl-letf (((symbol-function 'ebp-client--backlog)
                 (lambda (_c) (1+ ebp-overload-resume))))
        (ebp--with-dispatch client nil))
      (should (ebp-client-inbound-paused client))
      (cl-letf (((symbol-function 'ebp-client--backlog)
                 (lambda (_c) ebp-overload-resume)))
        (ebp--with-dispatch client nil))
      (should-not (ebp-client-inbound-paused client))
      (should (eq (process-status proc) 'open))
      (let ((ebp--in-send t))
        (cl-letf (((symbol-function 'ebp-client--backlog)
                   (lambda (_c) (* 2 ebp-overload-hold))))
          (ebp-client--inbound-check client)))
      (should-not (ebp-client-inbound-paused client))
      (setf (ebp-client-inbound-paused client) t)
      (stop-process proc)
      (ebp-client-notify client 'log.error '(:code 1400 :message "x"))
      (should-not (ebp-client-inbound-paused client))
      (should (eq (process-status proc) 'open)))))

(ert-deftest ebp-host-test-dispatch-depth-exhaustion-closes ()
  "SPEC 22.3 exhaustion via the #124 dispatch-depth bound: the client
closes itself with the terminal reason.  The backlog-driven half of
the fake's version asserts the 1401 `log.error' arrives exactly once
on the wire, which needs readback and stays there; this half is
entirely client-side."
  (ebp-host-test--with-ready-client (client)
    (let ((ebp--dispatch-depth ebp-max-dispatch-depth))
      (should-error
       (ebp-client--notification-dispatcher client nil 'state.changed nil)))
    (should (eq (ebp-client-state client) 'closed))
    (should (equal (ebp-client-close-reason client)
                   '(overloaded dispatch-depth)))))

;;;; SPEC 23 decode hygiene, over the real path

(defconst ebp-host-test--secret "SUPERSECRET-PASSWORD-VALUE-9d41"
  "A distinctive volatile value planted in an undecodable frame body.")

(defun ebp-host-test--sink-text ()
  "The concatenated text of both SPEC 23.3 log sinks."
  (concat (with-current-buffer (get-buffer-create "*Messages*") (buffer-string))
          (if-let* ((w (get-buffer "*Warnings*")))
              (with-current-buffer w (buffer-string))
            "")))

(ert-deftest ebp-host-test-jsonrpc-warn-redacts-frame-bodies ()
  "SPEC 23.3/24.6-12: an undecodable frame body never reaches a log
sink.  The sinks are cleared BEFORE the dial, so handshake chatter
cannot make the assertion pass on staleness."
  (skip-unless ebp-host-test--launch)
  (let ((inhibit-message t))
    (when-let* ((w (get-buffer "*Warnings*"))) (kill-buffer w))
    (with-current-buffer (get-buffer-create "*Messages*")
      (let ((inhibit-read-only t)) (erase-buffer)))
    (ebp-host-test--with-ready-client (client)
      ;; Drive the decode failure through the client's real filter — the
      ;; wrapper, and therefore the redaction, is installed on it.  The
      ;; secret never crosses the wire.
      (let ((body (format "{\"jsonrpc\":\"2.0\",\"params\":{\"password\":\"%s\"}"
                          ebp-host-test--secret)))
        (funcall (process-filter (ebp-client-process client))
                 (ebp-client-process client)
                 (ebp-encode-frame body)))
      (let ((sinks (ebp-host-test--sink-text)))
        (should-not (string-search ebp-host-test--secret sinks))
        (should (string-search "jsonrpc diagnostic redacted" sinks))))))

(ert-deftest ebp-host-test-decode-does-not-grow-the-global-obarray ()
  "SPEC 23.5: peer-supplied member and method names must not grow a
process-global pool.  `before' is sampled after READY so the
handshake's own interning stays outside the measured window."
  (let ((inhibit-message t))
    (ebp-host-test--with-ready-client (client)
      (let* ((count-atoms (lambda ()
                            (let ((n 0)) (mapatoms (lambda (_) (setq n (1+ n)))) n)))
             (before (funcall count-atoms))
             (members (mapconcat
                       (lambda (i) (format "\"ebp-peer-invented-%d\":%d" i i))
                       (number-sequence 1 400) ","))
             (frame (ebp-encode-frame
                     (format "{\"jsonrpc\":\"2.0\",\"method\":\"ebp.peer.invented.method\",\"params\":{%s}}"
                             members))))
        (funcall (process-filter (ebp-client-process client))
                 (ebp-client-process client) frame)
        (garbage-collect)
        (let ((grew (- (funcall count-atoms) before)))
          (should (< grew 50))
          ;; Non-vacuity: the frame really was decoded.
          (should (string-search "ebp-peer-invented-1"
                                 (format "%S" (ebp-decoder-feed
                                               (ebp-make-decoder) frame)))))))))

(ert-deftest ebp-host-test-unknown-method-still-dispatches-correctly ()
  "SPEC 24.6 item 4: the obarray substitution must not change behavior
— an unknown REQUEST still answers -32601 and an unknown NOTIFICATION
is still ignored, with the sentinel in place over a real connection."
  (let ((inhibit-message t))
    (ebp-host-test--with-ready-client (client)
      (let ((conn (ebp-client-connection client)))
        ;; An unknown REQUEST: the dispatcher must answer -32601, not
        ;; signal something else and not hang.
        (should (eq :method-not-found
                    (condition-case err
                        (progn (ebp-client--request-dispatcher
                                client conn 'totally.unknown.method nil)
                               :no-error)
                      (jsonrpc-error
                       (if (eq (alist-get 'jsonrpc-error-code (cdr err))
                               -32601)
                           :method-not-found
                         :wrong-code)))))
        ;; An unknown NOTIFICATION is ignored: no signal, no reply. (It
        ;; returns `message's string, so assert the absence of a signal
        ;; rather than a nil value.)
        (should (eq :ignored
                    (condition-case nil
                        (progn (ebp-client--notification-dispatcher
                                client conn 'totally.unknown.notification nil)
                               :ignored)
                      (error :signalled))))))))

(provide 'ebp-host-test)
;;; ebp-host-test.el ends here
